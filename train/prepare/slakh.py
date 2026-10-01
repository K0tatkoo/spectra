"""Slakh2100 -> the splitter's corpus (synthsplit/corpus.py).

Slakh renders Lakh MIDI files with real sample libraries and synth patches,
one stem per instrument, each labelled with its General MIDI class. "Synth
Lead" and "Synth Pad" are the synth stem; "Bass" (synth bass included) and
drums go to bassdrums; synth and sound *effects* are left out of the mix
altogether, since effects are not what this lane is for. Nobody sings in
Slakh, so a share of the songs get a MUSDB18-HQ vocal mixed in
(prepare/musdb_vocals.py) — the stem model then has to deal with a voice and
a lead at once, as it does in real music.

The Zenodo archive is shuffled — a song's files are spread over all 104 GB —
so it is fetched whole by ~/synthsplit-data/fetch-slakh.sh, which keeps only
metadata and stems. With --watch this runs alongside that download and takes
each song the moment all its stems are on disk:

    python prepare/slakh.py --slakh ~/synthsplit-data/slakh --vocals ~/synthsplit-data/musdb-vocals \\
        --out ~/synthsplit-data/corpus --workers 6 --watch

Slakh's validation and test songs are held out; each split has a quota, as
does the share of songs with no synth at all (kept as negatives).
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import zlib
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import soundfile as sf
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from synthsplit import corpus, stemgen  # noqa: E402

_sg = None
SPLITS = {"train": "train", "validation": "valid", "test": "valid"}
VOCAL_ACTIVE_DB = -40.0


def classify(inst_class: str, is_drum: bool) -> str | None:
    c = (inst_class or "").lower()
    if is_drum or c == "bass":
        return "bassdrums"
    if "effects" in c:
        return None
    if c in ("synth lead", "synth pad"):
        return "synth"
    return "nonsynth"


def _init(model_path: str):
    global _sg
    _sg = stemgen.StemgenRT(Path(model_path))


def load_stereo(path: Path) -> np.ndarray:
    audio, rate = sf.read(str(path), dtype="float32", always_2d=True)
    if rate != stemgen.SAMPLE_RATE:
        raise ValueError(f"{path}: {rate} Hz, expected {stemgen.SAMPLE_RATE}")
    audio = audio.T
    return np.repeat(audio, 2, axis=0) if audio.shape[0] == 1 else audio[:2]


def pick_vocal(rng, vocals_dir: Path, split: str, length: int):
    """A MUSDB vocal crop of `length` samples where somebody is singing, or (None, None)."""
    index = json.loads((vocals_dir / "index.json").read_text())
    pool = [v for v in index if v["split"] == ("train" if split == "train" else "test")]
    if not pool:
        return None, None
    v = pool[rng.integers(len(pool))]
    audio = np.load(vocals_dir / v["file"]).astype(np.float32)
    if audio.shape[1] < length:
        audio = np.tile(audio, (1, -(-length // audio.shape[1])))
    active = [i for i, db in enumerate(v["activity_db"]) if db > VOCAL_ACTIVE_DB]
    centre = (active[rng.integers(len(active))] * 44100 + 22050) if active else audio.shape[1] // 2
    start = int(np.clip(centre - length // 2, 0, audio.shape[1] - length))
    return audio[:, start:start + length], v["song"]


def prepare(track_dir: str, split: str, out_root: str, keep: float, vocals_dir: str | None, vocals_share: float) -> str:
    track = Path(track_dir)
    out = Path(out_root) / "slakh" / track.name
    if (out / "meta.json").exists():
        return f"skip {track.name}"
    meta = yaml.safe_load((track / "metadata.yaml").read_text()) or {}
    arrays: dict[str, list[np.ndarray]] = {g: [] for g in corpus.GROUPS}
    classes: dict[str, list[str]] = {g: [] for g in corpus.GROUPS}
    for sid, info in (meta.get("stems") or {}).items():
        path = track / "stems" / f"{sid}.flac"
        if not info.get("audio_rendered", True) or not path.exists():
            continue
        g = classify(info.get("inst_class", ""), bool(info.get("is_drum", False)))
        if g is None:
            continue
        arrays[g].append(load_stereo(path))
        classes[g].append(f"{info.get('inst_class')}/{info.get('midi_program_name', '')}")
    lengths = [a.shape[1] for v in arrays.values() for a in v]
    if not lengths:
        return f"empty {track.name}"
    total = max(lengths)

    def summed(arrs):
        acc = np.zeros((2, total), dtype=np.float32)
        for a in arrs:
            acc[:, :a.shape[1]] += a
        return acc

    groups = {g: summed(v) for g, v in arrays.items()}
    start, end = corpus.choose_window(groups, keep)
    extra = {"sources": classes, "slakh_split": track.parent.name}
    rng = np.random.default_rng(zlib.crc32(track.name.encode()))
    if vocals_dir and rng.random() < vocals_share:
        pre = min(start, corpus.PREROLL)
        vocal, song = pick_vocal(rng, Path(vocals_dir), split, end - start + pre)
        if vocal is not None:
            mix = sum(groups.values())[:, start:end]
            sung = vocal[:, pre:]
            voiced = np.abs(sung).max(axis=0) > 1e-3
            loud = float(np.sqrt(np.mean(np.square(sung[:, voiced])))) if voiced.any() else 0.0
            if loud > 0:
                target = float(np.sqrt(np.mean(np.square(mix)))) * 10 ** (rng.uniform(-4.0, 2.0) / 20)
                groups["vocals"][:, start - pre:end] += vocal * (target / loud)
                extra["vocals_from"] = f"musdb18hq/{song}"
    result = corpus.write_song(out, "slakh", track.name, split, groups, _sg, extra=extra, window=(start, end))
    return f"done {track.name} ({split}, synth {result['rms_db']['synth']} dB{', + vocals' if 'vocals_from' in extra else ''})"


def track_state(track: Path, settle: float):
    """None while incomplete; else whether it has synth, once every rendered stem is on disk and settled."""
    meta_path = track / "metadata.yaml"
    if not meta_path.exists() or time.time() - meta_path.stat().st_mtime < settle:
        return None
    meta = yaml.safe_load(meta_path.read_text()) or {}
    has_synth = False
    for sid, info in (meta.get("stems") or {}).items():
        if not info.get("audio_rendered", True):
            continue
        g = classify(info.get("inst_class", ""), bool(info.get("is_drum", False)))
        if g is None:
            continue  # effects: not used, so not waited for
        path = track / "stems" / f"{sid}.flac"
        if not path.exists() or time.time() - path.stat().st_mtime < settle:
            return None
        has_synth = has_synth or g == "synth"
    return has_synth


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--slakh", required=True, type=Path, help="folder holding slakh2100_flac_redux/")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--vocals", type=Path, help="prepare/musdb_vocals.py output")
    ap.add_argument("--vocals-share", type=float, default=0.6)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--max-train", type=int, default=750)
    ap.add_argument("--max-valid", type=int, default=60)
    ap.add_argument("--no-synth-share", type=float, default=0.2,
                    help="share of songs allowed to have no synth at all (negatives)")
    ap.add_argument("--max-seconds", type=float, default=90,
                    help="keep only this much of each song, where its synth is (0 = all)")
    ap.add_argument("--watch", action="store_true", help="keep taking songs until the download is done")
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    args = ap.parse_args()

    model = stemgen.fetch_model(args.model_cache)
    root = args.slakh / "slakh2100_flac_redux"
    done_marker = args.slakh / "DOWNLOAD_DONE"
    decided: set[str] = set()
    taken = {"train": 0, "valid": 0}
    with_synth = {"train": 0, "valid": 0}
    without = {"train": 0, "valid": 0}
    vocals = str(args.vocals) if args.vocals else None
    with ProcessPoolExecutor(args.workers, initializer=_init, initargs=(str(model),)) as pool:
        pending = []
        while True:
            finished = done_marker.exists()
            for split_dir in sorted(root.glob("*")) if root.exists() else []:
                split = SPLITS.get(split_dir.name)
                if split is None:
                    continue
                for track in sorted(split_dir.glob("Track*")):
                    if track.name in decided:
                        continue
                    if (args.out / "slakh" / track.name / "meta.json").exists():
                        decided.add(track.name)
                        taken[split] += 1
                        continue
                    has_synth = track_state(track, settle=0 if finished else 30)
                    if has_synth is None:
                        continue
                    decided.add(track.name)
                    quota = args.max_train if split == "train" else args.max_valid
                    if taken[split] >= quota:
                        continue
                    if not has_synth:
                        if without[split] + 1 > args.no_synth_share * (with_synth[split] + without[split] + 1):
                            continue
                        without[split] += 1
                    else:
                        with_synth[split] += 1
                    taken[split] += 1
                    pending.append(pool.submit(prepare, str(track), split, str(args.out), args.max_seconds,
                                               vocals, args.vocals_share))
            still = []
            for f in pending:
                if f.done():
                    print(f.result(), flush=True)
                else:
                    still.append(f)
            pending = still
            full = taken["train"] >= args.max_train and taken["valid"] >= args.max_valid
            if not args.watch or finished or full:
                break
            time.sleep(30)
        for f in pending:
            print(f.result(), flush=True)
    print(f"taken {taken} · with synth {with_synth} · negatives {without}", flush=True)


if __name__ == "__main__":
    main()

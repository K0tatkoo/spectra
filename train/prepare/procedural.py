"""Songs with made-up synths -> the splitter's corpus (synthsplit/corpus.py).

Each song is a stretch of a real corpus song with its own synth taken out —
Slakh, or MoisesDB once it is downloaded — sometimes a MUSDB18-HQ vocal on
top, and one to three synth parts from synthsplit/procsynth.py, new patches
every time. The whole mix then goes through the app's stem model like every
other song, so the splitter learns how the stem model routes synths it has
never heard, not only Slakh's few hundred.

    python prepare/procedural.py --source slakh --slakh D:/synthsplit/slakh \\
        --vocals D:/synthsplit/musdb-vocals --out D:/synthsplit/corpus --train 1600 --valid 80 --workers 16

Backing from a held-out song makes a held-out song (so validation never hears
a backing track the network trained on). Resumable: a song whose meta.json
exists is skipped, and song i is the same on every run.
"""

from __future__ import annotations

import argparse
import json
import sys
import zlib
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import soundfile as sf
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare import moisesdb as moises  # noqa: E402
from prepare.slakh import SPLITS, classify, pick_vocal  # noqa: E402
from synthsplit import corpus, procsynth, stemgen  # noqa: E402

RATE = stemgen.SAMPLE_RATE
_sg = None


def _init(model_path: str):
    global _sg
    _sg = stemgen.StemgenRT(Path(model_path))


def _read(path: Path, start: int, length: int) -> np.ndarray:
    a, rate = sf.read(str(path), start=start, stop=start + length, dtype="float32", always_2d=True)
    if rate != RATE:
        raise ValueError(f"{path}: {rate} Hz")
    a = a.T
    return np.repeat(a, 2, axis=0) if a.shape[0] == 1 else a[:2]


# -- backing tracks: (path, split, frames) and what is in them, synth removed ---------------


def slakh_tracks(root: Path) -> list[tuple[str, str]]:
    out = []
    for split_dir in sorted((root / "slakh2100_flac_redux").glob("*")):
        split = SPLITS.get(split_dir.name)
        if split is None:
            continue
        for track in sorted(split_dir.glob("Track*")):
            meta = track / "metadata.yaml"
            if meta.exists() and all((track / "stems" / f"{sid}.flac").exists()
                                     for sid, info in (yaml.safe_load(meta.read_text()).get("stems") or {}).items()
                                     if info.get("audio_rendered", True)):
                out.append((str(track), split))
    return out


def slakh_stems(track: Path):
    meta = yaml.safe_load((track / "metadata.yaml").read_text()) or {}
    for sid, info in (meta.get("stems") or {}).items():
        path = track / "stems" / f"{sid}.flac"
        if info.get("audio_rendered", True) and path.exists():
            yield path, classify(info.get("inst_class", ""), bool(info.get("is_drum", False)), info.get("program_num"))


def moisesdb_tracks(root: Path) -> list[tuple[str, str]]:
    out = []
    for data in sorted(root.glob("*/*/data.json")):
        artist = json.loads(data.read_text()).get("artist", "")
        out.append((str(data.parent), moises.split_for(artist)))
    return out


def moisesdb_stems(track: Path):
    data = json.loads((track / "data.json").read_text())
    for stem in data.get("stems", []):
        name = stem.get("stemName", "")
        for t in stem.get("tracks", []):
            path = track / name / f"{t['id']}.{t.get('extension', 'wav')}"
            if path.exists():
                yield path, moises.classify(name, t.get("trackType", ""))


SOURCES = {"slakh": (slakh_tracks, slakh_stems), "moisesdb": (moisesdb_tracks, moisesdb_stems)}


def backing(source: str, track: Path, rng, length: int) -> tuple[dict[str, np.ndarray], int] | None:
    """The song's groups over a random `length`-sample stretch, with no synth in them."""
    stems = [(p, g) for p, g in SOURCES[source][1](track) if g is not None and g != "synth"]
    if not stems:
        return None
    frames = min(sf.info(str(p)).frames for p, _ in stems)
    if frames < length:
        return None
    start = int(rng.integers(0, frames - length + 1))
    groups = {g: np.zeros((2, length), dtype=np.float32) for g in corpus.GROUPS}
    for path, g in stems:
        a = _read(path, start, length)
        groups[g][:, :a.shape[1]] += a
    return groups, start


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x, dtype=np.float64))))


def make(i: int, split: str, source: str, track: str, out_root: str, seconds: float, vocals_dir: str | None,
         seed: int) -> str:
    song_id = f"{source}-{split}-{i:05d}"
    out = Path(out_root) / "procedural" / song_id
    if (out / "meta.json").exists():
        return f"skip {song_id}"
    rng = np.random.default_rng((seed, zlib.crc32(source.encode()), 0 if split == "train" else 1, i))
    pre = corpus.PREROLL
    length = int(seconds * RATE) + pre
    got = backing(source, Path(track), rng, length)
    if got is None:
        return f"short {song_id} ({Path(track).name})"
    groups, start = got
    arrangement = []
    if rng.random() < 0.05:
        groups = {g: np.zeros_like(v) for g, v in groups.items()}  # the synths alone
        arrangement.append("solo")
    else:
        for g, p in (("nonsynth", 0.15), ("bassdrums", 0.1), ("vocals", 0.2)):
            if rng.random() < p and rms(groups[g]) > 0:
                groups[g][:] = 0
                arrangement.append(f"no {g}")
        if vocals_dir and rms(groups["vocals"]) < 1e-3 and rng.random() < 0.5:
            vocal, song = pick_vocal(rng, Path(vocals_dir), split, length)
            if vocal is not None:
                groups["vocals"] += vocal * (max(rms(sum(groups.values())), 0.05) / max(rms(vocal), 1e-4)
                                             * 10 ** (rng.uniform(-4, 2) / 20))
                arrangement.append(f"vocals musdb18hq/{song}")
    synth, info = procsynth.render_synths(rng, length / RATE)
    bed = rms(sum(groups.values()))
    level = (bed if bed > 1e-3 else 0.1) * 10 ** (rng.uniform(-12, 3) / 20)
    playing = np.abs(synth).max(axis=0) > 1e-4
    groups["synth"] = synth * (level / max(rms(synth[:, playing]) if playing.any() else 1.0, 1e-6))
    meta = corpus.write_song(out, "procedural", song_id, split, groups, _sg, window=(pre, length),
                             extra={"backing": f"{source}/{Path(track).name}", "backing_from_s": round(start / RATE, 2),
                                    "arrangement": arrangement, "synths": info})
    return f"done {song_id} ({split}, synth {meta['rms_db']['synth']} dB, {' + '.join(info['parts'])})"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", choices=sorted(SOURCES), required=True)
    ap.add_argument("--slakh", type=Path, help="folder holding slakh2100_flac_redux/")
    ap.add_argument("--moisesdb", type=Path, help="folder holding the provider folders")
    ap.add_argument("--vocals", type=Path, help="prepare/musdb_vocals.py output")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--train", type=int, default=1600)
    ap.add_argument("--valid", type=int, default=80)
    ap.add_argument("--seconds", type=float, default=40)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--seed", type=int, default=20261006)
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    args = ap.parse_args()

    root = args.slakh if args.source == "slakh" else args.moisesdb
    if root is None:
        ap.error(f"--{args.source} is needed")
    tracks = SOURCES[args.source][0](root)
    by_split = {s: [t for t, sp in tracks if sp == s] for s in ("train", "valid")}
    print(f"{args.source}: {len(by_split['train'])} training and {len(by_split['valid'])} held-out backing tracks", flush=True)
    model = stemgen.fetch_model(args.model_cache)
    jobs = []
    for split, count in (("train", args.train), ("valid", args.valid)):
        pool = by_split[split]
        if not pool:
            continue
        pick = np.random.default_rng((args.seed, 0 if split == "train" else 1))
        for i in range(count):
            jobs.append((i, split, pool[int(pick.integers(len(pool)))]))
    vocals = str(args.vocals) if args.vocals else None
    with ProcessPoolExecutor(args.workers, initializer=_init, initargs=(str(model),)) as ex:
        futures = [ex.submit(make, i, split, args.source, track, str(args.out), args.seconds, vocals, args.seed)
                   for i, split, track in jobs]
        for n, f in enumerate(as_completed(futures), 1):
            print(f"[{n}/{len(futures)}] {f.result()}", flush=True)


if __name__ == "__main__":
    main()

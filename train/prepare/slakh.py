"""Slakh2100 -> the splitter's corpus (synthsplit/corpus.py).

Slakh renders Lakh MIDI files with real sample libraries and synth patches,
one stem per instrument, each labelled with its General MIDI class. "Synth
Lead" and "Synth Pad" are the synth stem; "Bass" (synth bass included) and
drums go to bassdrums; synth and sound *effects* are left out of the mix
altogether, since effects are not what this lane is for. There are no vocals.

It reads the 104 GB archive as a stream — straight from Zenodo or from a file —
and never unpacks it: each track's stems are held in memory just long enough
to be mixed, separated and written, so the disk only ever holds the result.

    python prepare/slakh.py --tar https://zenodo.org/records/4599666/files/slakh2100_flac_redux.tar.gz?download=1 \\
        --out D:/data/synthsplit --workers 8 --max-tracks 800

A gzip stream cannot be resumed part way, so a restart reads from the top
again; tracks already written are skipped rather than redone.
"""

from __future__ import annotations

import argparse
import io
import sys
import tarfile
import urllib.request
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait
from pathlib import Path

import numpy as np
import soundfile as sf
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from synthsplit import corpus, stemgen  # noqa: E402

_sg = None
SPLITS = {"train": "train", "validation": "valid", "test": "valid"}


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


def decode(data: bytes) -> np.ndarray:
    audio, rate = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
    if rate != stemgen.SAMPLE_RATE:
        raise ValueError(f"{rate} Hz, expected {stemgen.SAMPLE_RATE}")
    audio = audio.T
    return np.repeat(audio, 2, axis=0) if audio.shape[0] == 1 else audio[:2]


def prepare(track: str, split: str, metadata: bytes, stems: dict[str, bytes], out_root: str, keep: float) -> str:
    out = Path(out_root) / "slakh" / track
    if (out / "meta.json").exists():
        return f"skip {track}"
    meta = yaml.safe_load(metadata)
    arrays: dict[str, list[np.ndarray]] = {g: [] for g in corpus.GROUPS}
    classes: dict[str, list[str]] = {g: [] for g in corpus.GROUPS}
    for sid, info in (meta.get("stems") or {}).items():
        if not info.get("audio_rendered", True) or sid not in stems:
            continue
        g = classify(info.get("inst_class", ""), bool(info.get("is_drum", False)))
        if g is None:
            continue
        arrays[g].append(decode(stems[sid]))
        classes[g].append(f"{info.get('inst_class')}/{info.get('midi_program_name', '')}")
    lengths = [a.shape[1] for v in arrays.values() for a in v]
    if not lengths:
        return f"empty {track}"
    total = max(lengths)

    def summed(arrs):
        acc = np.zeros((2, total), dtype=np.float32)
        for a in arrs:
            acc[:, :a.shape[1]] += a
        return acc

    result = corpus.write_song(out, "slakh", track, split, {g: summed(v) for g, v in arrays.items()}, _sg,
                               extra={"sources": classes}, keep_seconds=keep)
    return f"done {track} ({split}, synth {result['rms_db']['synth']} dB)"


def tracks_in(stream):
    """Yields (split, track, metadata bytes, {stem id: flac bytes}) as the archive goes by."""
    current = None
    metadata = None
    stems: dict[str, bytes] = {}
    with tarfile.open(fileobj=stream, mode="r|gz") as tar:
        for member in tar:
            parts = Path(member.name).parts
            # [slakh2100_flac_redux/]<split>/<TrackXXXXX>/...
            at = next((i for i, p in enumerate(parts) if p in SPLITS or p == "omitted"), None)
            if at is None or len(parts) < at + 3 or not member.isfile():
                continue
            key = (parts[at], parts[at + 1])
            if key != current:
                if current is not None and metadata is not None:
                    yield current[0], current[1], metadata, stems
                current, metadata, stems = key, None, {}
            rest = parts[at + 2:]
            if rest == ("metadata.yaml",):
                metadata = tar.extractfile(member).read()
            elif len(rest) == 2 and rest[0] == "stems" and rest[1].endswith(".flac"):
                stems[rest[1][:-5]] = tar.extractfile(member).read()
    if current is not None and metadata is not None:
        yield current[0], current[1], metadata, stems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tar", required=True, help="path or URL of slakh2100_flac_redux.tar.gz")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--max-tracks", type=int, default=0, help="stop after this many written tracks (0 = all)")
    ap.add_argument("--no-synth-share", type=float, default=0.2,
                    help="share of written tracks allowed to have no synth at all (negatives)")
    ap.add_argument("--max-seconds", type=float, default=120,
                    help="keep only this much of each track, where its synth is (0 = all)")
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    args = ap.parse_args()

    model = stemgen.fetch_model(args.model_cache)
    stream = urllib.request.urlopen(args.tar) if args.tar.startswith("http") else open(args.tar, "rb")
    written = without = 0
    with ProcessPoolExecutor(args.workers, initializer=_init, initargs=(str(model),)) as pool:
        pending = set()
        for split_dir, track, metadata, stems in tracks_in(stream):
            split = SPLITS.get(split_dir)
            if split is None:
                continue
            meta = yaml.safe_load(metadata) or {}
            has_synth = any(classify(i.get("inst_class", ""), bool(i.get("is_drum", False))) == "synth"
                            for i in (meta.get("stems") or {}).values())
            if not has_synth:
                if without + 1 > args.no_synth_share * max(written + 1, 1):
                    continue
                without += 1
            # Bounded, so a slow pool cannot pile the archive up in memory.
            while len(pending) >= 2 * args.workers:
                done, pending = wait(pending, return_when=FIRST_COMPLETED)
                for f in done:
                    print(f.result(), flush=True)
            pending.add(pool.submit(prepare, track, split, metadata, stems, str(args.out), args.max_seconds))
            written += 1
            if args.max_tracks and written >= args.max_tracks:
                break
        for f in pending:
            print(f.result(), flush=True)
    print(f"{written} tracks queued, {without} without synth")


if __name__ == "__main__":
    main()

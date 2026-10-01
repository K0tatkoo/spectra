"""MUSDB18-HQ's vocal stems, for mixing real singing into Slakh songs.

Slakh has synths but nobody sings, and a splitter that never hears a voice
in its vocals input learns that anything tonal there is synth. MUSDB18-HQ
(Zenodo 3338373, non-commercial licence) has 150 songs' vocals on their own.
Only the vocals are used: its "other" stems hold synths nobody labelled.

    python prepare/musdb_vocals.py --zip ~/synthsplit-data/downloads/musdb18hq.zip --out ~/synthsplit-data/musdb-vocals

Read straight out of the zip; writes <split>/<n>.npy (stereo float16) and an
index with each file's length and per-second level. MUSDB's own train/test
split is kept: train vocals go into training songs, test vocals into the
held-out ones.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import zipfile
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from synthsplit import corpus  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--zip", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    index = []
    with zipfile.ZipFile(args.zip) as z:
        names = sorted(n for n in z.namelist() if n.endswith("/vocals.wav"))
        for i, name in enumerate(names):
            parts = Path(name).parts
            split = "test" if "test" in parts else "train"
            audio, rate = sf.read(io.BytesIO(z.read(name)), dtype="float32", always_2d=True)
            assert rate == 44100, f"{name}: {rate} Hz"
            audio = audio.T[:2]
            if audio.shape[0] == 1:
                audio = np.repeat(audio, 2, axis=0)
            out = args.out / split / f"{i:03d}.npy"
            out.parent.mkdir(parents=True, exist_ok=True)
            np.save(out, audio.astype(np.float16))
            index.append({"file": str(out.relative_to(args.out)), "song": parts[-2], "split": split,
                          "samples": int(audio.shape[1]), "activity_db": corpus.activity(audio.mean(axis=0))})
            print(f"[{i + 1}/{len(names)}] {split} {parts[-2]}", flush=True)
    (args.out / "index.json").write_text(json.dumps(index) + "\n")
    print(f"{len(index)} vocal stems written to {args.out}")


if __name__ == "__main__":
    main()

"""Separates songs into the five stems with a trained file model, to listen to.

    python tools/separate.py --ckpt ~/stems-clean/runs/v0/best.pt song.flac other.wav --out listen-v0

Writes <out>/<song>/{mix,vocals,synth,other,bass,drums}.wav. Any format
libsndfile reads (WAV, FLAC, OGG, MP3); resampled to 44.1 kHz, mono made
stereo. The songs are only listened to: nothing here feeds training.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ownstems import RATE, STEMS  # noqa: E402
from ownstems.audio import resample  # noqa: E402
from ownstems.model import net_from_checkpoint, separate  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("songs", nargs="+", type=Path)
    ap.add_argument("--ckpt", required=True, type=Path, help="best.pt or checkpoint.pt (its averaged weights)")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--seconds", type=float, help="seconds of audio from the start (default: all)")
    ap.add_argument("--device", default="cpu", help="cpu, or cuda for a GPU")
    args = ap.parse_args()
    torch.set_num_threads(args.threads)
    torch.set_flush_denormal(True)
    ckpt = torch.load(args.ckpt, map_location="cpu", weights_only=False)
    net = net_from_checkpoint(ckpt).eval().to(args.device)
    # Chunks as long as the training examples: rotary positions beyond them were never trained.
    chunk = float(ckpt.get("seconds") or ckpt.get("args", {}).get("seconds") or 4.0)
    print(f"{args.ckpt}: step {ckpt['step']}, chunks of {chunk:g} s", flush=True)
    for song in args.songs:
        x, rate = sf.read(song, dtype="float32", always_2d=True, frames=-1 if args.seconds is None
                          else int(args.seconds * sf.info(song).samplerate))
        x = resample(np.ascontiguousarray(x.T), rate)
        if x.shape[0] == 1:
            x = np.repeat(x, 2, 0)
        x = x[:2]
        t0 = time.time()
        stems = separate(net, torch.from_numpy(x), chunk_s=chunk).numpy()
        d = args.out / song.stem
        d.mkdir(parents=True, exist_ok=True)
        sf.write(d / "mix.wav", x.T, RATE, subtype="FLOAT")
        for name, s in zip(STEMS, stems):
            sf.write(d / f"{name}.wav", s.T, RATE, subtype="FLOAT")
        secs = x.shape[1] / RATE
        print(f"{song.name}: {secs:.0f} s of music in {time.time() - t0:.0f} s -> {d}", flush=True)


if __name__ == "__main__":
    main()

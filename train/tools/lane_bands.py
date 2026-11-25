"""Where a model's lanes put each band of real songs. Reads separate.py's output
(<dir>/<song>/{mix,vocals,synth,other,bass,drums}.wav) and prints, per band, each lane's share of
the energy, then each lane's median level against the mix. Use the WAVs: the listening page's MP3s
have had their top end cut by the encoder.

    python tools/lane_bands.py D:/stems-clean/listen-stems-v2
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ownstems import STEMS  # noqa: E402

BANDS = [(0, 120), (120, 500), (500, 2000), (2000, 6000), (6000, 10000), (10000, 14000), (14000, 16500),
         (16500, 24000)]


def band_energy(x: np.ndarray, rate: int) -> np.ndarray:
    f, _, z = signal.stft(x, rate, nperseg=4096, noverlap=2048)
    p = (np.abs(z) ** 2).sum(1)
    return np.array([p[(f >= a) & (f < b)].sum() for a, b in BANDS])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir", type=Path)
    ap.add_argument("--json", type=Path, help="also write the numbers here")
    args = ap.parse_args()
    acc = np.zeros((len(BANDS), len(STEMS)))
    levels: dict[str, list[float]] = {s: [] for s in STEMS}
    songs = sorted(p for p in args.dir.iterdir() if p.is_dir() and (p / "mix.wav").exists())
    for d in songs:
        mix, rate = sf.read(d / "mix.wav", dtype="float32", always_2d=True)
        e = float(np.mean(mix.mean(1) ** 2)) + 1e-12
        for j, s in enumerate(STEMS):
            x = sf.read(d / f"{s}.wav", dtype="float32", always_2d=True)[0].mean(1)
            acc[:, j] += band_energy(x, rate)
            levels[s].append(10 * np.log10(float(np.mean(x ** 2)) / e + 1e-12))
    share = acc / (acc.sum(1, keepdims=True) + 1e-30) * 100
    print(f"{len(songs)} songs in {args.dir}: % of each band's energy per lane")
    print(f"  {'band':14}" + "".join(f"{s:>8}" for s in STEMS))
    for i, (a, b) in enumerate(BANDS):
        name = f"{a / 1000:g}-{b / 1000:g} kHz" if a else f"< {b} Hz"
        print(f"  {name:14}" + "".join(f"{v:8.0f}" for v in share[i]))
    med = {s: round(float(np.median(v)), 1) for s, v in levels.items()}
    print("  median level vs the mix (dB): " + ", ".join(f"{s} {v}" for s, v in med.items()))
    if args.json:
        args.json.write_text(json.dumps({"bands": [list(b) for b in BANDS], "share": share.round(1).tolist(),
                                         "lanes": list(STEMS), "level_db": med}, indent=1))


if __name__ == "__main__":
    main()

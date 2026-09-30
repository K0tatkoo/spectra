"""Writes the fixture the app's SpectralSplitTest checks itself against.

The reference (synthsplit/reference.py) is run on two made-up input stems with
a stand-in "network" whose masks are a fixed pattern over band, input and
frame — no weights involved, so the test pins the framing, windows, band
powers, mask expansion, overlap-add and the rest subtraction, and nothing else.

    python3 tools/make_dsp_fixture.py        # from train/, numpy only
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from synthsplit.layout import Layout  # noqa: E402
from synthsplit.reference import run_stream  # noqa: E402

OUT = HERE.parent.parent / "app/src/test/resources/synthsplit"

# The stand-in network. SpectralSplitTest computes exactly this.
A_BAND, A_INPUT, A_FRAME = 0.37, 1.3, 0.05


def pattern_mask(power: np.ndarray, frame: int) -> np.ndarray:
    k, b = power.shape
    band = np.arange(b)[None, :]
    inp = np.arange(k)[:, None]
    return 0.5 + 0.5 * np.sin(A_BAND * band + A_INPUT * inp + A_FRAME * frame)


def signals(total: int, rate: int) -> np.ndarray:
    rng = np.random.default_rng(20260930)
    t = np.arange(total) / rate
    voice = 0.3 * np.sin(2 * np.pi * 330 * t - 1.2 * np.cos(2 * np.pi * 5 * t))
    saws = sum(2.0 * ((t * f) % 1.0) - 1.0 for f in (220.0, 277.18, 329.63)) / 3
    other = 0.2 * saws
    noise = 0.05 * rng.standard_normal((2, total))
    return np.stack([voice, other]) + noise


def main():
    lay = Layout()
    total = 16384
    x = signals(total, lay.sample_rate).astype(np.float32).astype(np.float64)
    synth, rests, powers = run_stream(lay, pattern_mask, x)
    OUT.mkdir(parents=True, exist_ok=True)
    meta = {
        "about": "written by train/tools/make_dsp_fixture.py; see there",
        "sample_rate": lay.sample_rate,
        "n_fft": lay.n_fft,
        "hop": lay.hop,
        "band_edges": list(lay.edges),
        "inputs": list(lay.inputs),
        "samples": total,
        "frames": int(powers.shape[0]),
        "mask": {"band": A_BAND, "input": A_INPUT, "frame": A_FRAME},
        "layout": "float32 LE: inputs[inputs][samples], powers[frames][inputs][bands], "
                  "synth[samples - hop], rests[inputs][samples - hop]",
    }
    (OUT / "dsp-fixture.json").write_text(json.dumps(meta, indent=1) + "\n")
    blob = np.concatenate([x.ravel(), powers.ravel(), synth.ravel(), rests.ravel()]).astype("<f4")
    (OUT / "dsp-fixture.bin").write_bytes(blob.tobytes())
    print(f"wrote {OUT}: {blob.nbytes / 1e3:.0f} kB, {lay.bands} bands, {powers.shape[0]} frames")


if __name__ == "__main__":
    main()

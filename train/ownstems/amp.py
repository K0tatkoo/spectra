"""A guitar amplifier and cabinet, for clean guitars the renderer plays in rock and metal parts (and
Guitar-TECHS's dry DI takes): a pre-EQ, a drive into an asymmetric clipper at four times the rate (so
its overtones don't fold back), a second gentler stage, a tone stack, and a cabinet as a miked 4x12
sounds: high-passed, a resonance near 100 Hz, a dip in the low mids, a presence bump and a steep top
end. "crunch" for rock, "high" for metal (tight low end, mid push, a noise gate)."""

from __future__ import annotations

import math

import numpy as np
from scipy import signal

from . import RATE


def _biquad(kind: str, fc: float, gain_db: float = 0.0, q: float = 0.707) -> np.ndarray:
    a = 10 ** (gain_db / 40)
    w = 2 * math.pi * min(fc, 0.45 * RATE) / RATE
    cw, sw = math.cos(w), math.sin(w)
    alpha = sw / (2 * q)
    if kind == "peak":
        b = [1 + alpha * a, -2 * cw, 1 - alpha * a]
        d = [1 + alpha / a, -2 * cw, 1 - alpha / a]
    elif kind == "low":
        s = 2 * math.sqrt(a) * alpha
        b = [a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s)]
        d = [(a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s]
    else:  # high shelf
        s = 2 * math.sqrt(a) * alpha
        b = [a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s)]
        d = [(a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s]
    return np.array([b[0] / d[0], b[1] / d[0], b[2] / d[0], 1.0, d[1] / d[0], d[2] / d[0]])


def amp(x: np.ndarray, rng, gain: str = "crunch") -> np.ndarray:
    """x mono (n,) → the amped, miked guitar (n,), peak 0.9."""
    x = np.asarray(x, np.float64)
    peak = float(np.max(np.abs(x))) + 1e-9
    x = x / peak * 0.5
    high = gain == "high"
    pre = [signal.butter(2, rng.uniform(110, 200) if high else rng.uniform(60, 100), "hp", fs=RATE, output="sos")[0],
           _biquad("peak", rng.uniform(600, 900), rng.uniform(4, 9) if high else rng.uniform(0, 5), 0.8)]
    x = signal.sosfilt(np.stack(pre), x)
    drive = rng.uniform(25, 90) if high else rng.uniform(4, 16)
    bias = rng.uniform(0.05, 0.25)
    up = signal.resample_poly(x, 4, 1)
    up = np.tanh(drive * up + bias) - math.tanh(bias)
    up = np.tanh(rng.uniform(1.2, 2.5) * up)
    y = signal.resample_poly(up, 1, 4)[: len(x)]
    stack = [_biquad("low", 120, rng.uniform(-3, 5)), _biquad("peak", rng.uniform(450, 800), rng.uniform(-8, 2), 0.7),
             _biquad("high", 3000, rng.uniform(-4, 4))]
    cab = [signal.butter(2, rng.uniform(70, 95), "hp", fs=RATE, output="sos")[0],
           _biquad("peak", rng.uniform(95, 130), rng.uniform(3, 6), 1.4),
           _biquad("peak", rng.uniform(380, 600), -rng.uniform(2, 6), 1.0),
           _biquad("peak", rng.uniform(1800, 3500), rng.uniform(2, 6), 1.2)]
    lp = signal.butter(4, rng.uniform(4300, 6500), "lp", fs=RATE, output="sos")
    y = signal.sosfilt(np.vstack([np.stack(stack), np.stack(cab), lp, lp]), y)
    if high:  # a noise gate between chugs
        env = signal.lfilter([0.02], [1, -0.98], np.abs(x))
        thr = 0.02 * float(np.max(env)) + 1e-9
        g = np.clip((env - thr) / thr, 0, 1)
        y *= signal.lfilter([0.05], [1, -0.95], g)
    return (y / (np.max(np.abs(y)) + 1e-9) * 0.9).astype(np.float32)

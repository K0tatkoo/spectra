"""Perceived loudness in LUFS, as ITU-R BS.1770-4 defines it (what EBU R128 meters read).

The ear is far less sensitive to deep bass than to the midrange, so two stems
at the same RMS can sound 10 dB apart: a bright guitar against a sub-heavy kick.
The mixer levels stems by this measure, not by RMS.

- K-weighting: a +4 dB high shelf from about 1.5 kHz (the head's effect), then a
  high-pass at 38 Hz. The two biquads are worked out for the sample rate from the
  standard's shelf and high-pass parameters, as pyloudnorm does, so 44.1 kHz needs
  no resampling.
- Gating: 400 ms blocks every 100 ms; blocks under -70 LUFS are dropped, then
  those more than 10 LU under the mean of the rest. A part that rests half the
  time is measured on what it plays, not on its silences.
"""

from __future__ import annotations

import math

import numpy as np
from scipy import signal

SILENT = -200.0


def _k_sos(rate: int) -> np.ndarray:
    # Stage 1: high shelf, +4 dB, fc 1500 Hz, Q 1/sqrt(2) (RBJ cookbook form).
    a = 10 ** (4.0 / 40)
    w0 = 2 * math.pi * 1500.0 / rate
    alpha = math.sin(w0) / (2 / math.sqrt(2))
    cw, sa = math.cos(w0), 2 * math.sqrt(a) * alpha
    shelf = [a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
             (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa]
    # Stage 2: high-pass, fc 38 Hz, Q 0.5.
    w0 = 2 * math.pi * 38.0 / rate
    alpha = math.sin(w0) / (2 * 0.5)
    cw = math.cos(w0)
    hp = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha]
    sos = np.array([shelf, hp], dtype=np.float64)
    sos[:, :3] /= sos[:, 3:4]
    sos[:, 3:] /= sos[:, 3:4]
    return sos


_SOS: dict[int, np.ndarray] = {}


def lufs(x: np.ndarray, rate: int = 44100) -> float:
    """Integrated loudness of x, (channels, samples) or (samples,); SILENT if nothing passes the gates."""
    x = np.atleast_2d(np.asarray(x, dtype=np.float64))
    if x.shape[-1] == 0:
        return SILENT
    sos = _SOS.get(rate)
    if sos is None:
        sos = _SOS[rate] = _k_sos(rate)
    sq = np.square(signal.sosfilt(sos, x, axis=-1)).sum(axis=0)  # channel weights are 1 for L and R
    block, step = int(0.4 * rate), int(0.1 * rate)
    if len(sq) <= block:
        z = np.array([sq.mean()])
    else:
        c = np.concatenate([[0.0], np.cumsum(sq)])
        starts = np.arange(0, len(sq) - block + 1, step)
        z = (c[starts + block] - c[starts]) / block
    with np.errstate(divide="ignore"):
        loud = -0.691 + 10 * np.log10(z)
    z = z[loud > -70.0]
    if not z.size:
        return SILENT
    rel = -0.691 + 10 * math.log10(z.mean()) - 10.0
    with np.errstate(divide="ignore"):
        z = z[-0.691 + 10 * np.log10(z) > rel]
    return -0.691 + 10 * math.log10(z.mean()) if z.size else SILENT

"""The splitter's signal path, frame by frame, in plain numpy: the specification.

This is what app/src/main/java/com/n3d/spectra/stems/SpectralSplit.kt does,
written for clarity rather than speed. The batched torch version the trainer
uses (synthsplit/dsp.py) is tested against it, and the app is tested against a
fixture it wrote (tools/make_dsp_fixture.py), so all three agree on every sample.

Framing: frames are n_fft long and end at every multiple of the hop, counting
from the start of the stream (samples before it are zero). The frame that ends
at `pos` completes the output hop [pos − 2·hop, pos − hop).
"""

from __future__ import annotations

from typing import Callable

import numpy as np

from .layout import Layout

# (band powers, frame number) -> masks; both shaped (inputs, bands).
MaskFn = Callable[[np.ndarray, int], np.ndarray]


class StreamSplit:
    def __init__(self, layout: Layout, mask_fn: MaskFn):
        self.layout = layout
        self.mask_fn = mask_fn
        self.wa = layout.analysis_window()
        self.ws = layout.synthesis_window()
        self.band_of = layout.band_of_bin()
        self.widths = layout.band_widths()
        k = len(layout.inputs)
        self.pending = np.zeros((k, layout.hop))
        self.count = 0

    def frame(self, frames: np.ndarray):
        """frames: (inputs, n_fft), oldest first. Returns (synth (hop,), rest (inputs, hop), power (inputs, bands))."""
        lay = self.layout
        n, h = lay.n_fft, lay.hop
        spec = np.fft.rfft(frames * self.wa, axis=-1)
        p = np.abs(spec) ** 2
        power = np.zeros((frames.shape[0], lay.bands))
        np.add.at(power, (slice(None), self.band_of), p)
        power /= self.widths
        mask = self.mask_fn(power, self.count)
        self.count += 1
        y = np.fft.irfft(spec * mask[:, self.band_of], n=n, axis=-1) * self.ws
        start = n - 2 * h
        part = self.pending + y[:, start:start + h]
        self.pending = y[:, start + h:].copy()
        synth = part.sum(axis=0)
        rest = frames[:, start:start + h] - part
        return synth, rest, power

    def reset_overlap(self):
        self.pending[:] = 0


def run_stream(layout: Layout, mask_fn: MaskFn, inputs: np.ndarray):
    """Runs a whole signal through StreamSplit from its first sample.

    inputs: (inputs, T), T a multiple of the hop. Returns synth (T − hop,),
    rests (inputs, T − hop), powers (frames, inputs, bands), aligned with the input.
    """
    lay = layout
    n, h = lay.n_fft, lay.hop
    k, total = inputs.shape
    assert total % h == 0
    padded = np.concatenate([np.zeros((k, n)), inputs], axis=1)
    split = StreamSplit(lay, mask_fn)
    synth, rests, powers = [], [], []
    for pos in range(h, total + 1, h):
        s, r, p = split.frame(padded[:, pos:pos + n])  # padded index pos + n ↔ stream index pos
        powers.append(p)
        if pos >= 2 * h:
            synth.append(s)
            rests.append(r)
    return np.concatenate(synth), np.concatenate(rests, axis=1), np.stack(powers)

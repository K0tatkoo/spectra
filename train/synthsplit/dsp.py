"""The splitter's signal path in batched torch, for training.

Numerically the same as synthsplit/reference.py (tests/test_dsp.py holds it
to that), so a network trained through it sees exactly the features, and
produces exactly the output, that the app's SpectralSplit does frame by frame.
"""

from __future__ import annotations

import torch
from torch import nn

from .layout import Layout


class SplitDSP(nn.Module):
    """analyze(): stems -> spectra + band powers. synthesize(): spectra + masks -> each input's synth part."""

    def __init__(self, layout: Layout):
        super().__init__()
        self.layout = layout
        self.register_buffer("wa", torch.tensor(layout.analysis_window(), dtype=torch.float32), persistent=False)
        self.register_buffer("ws", torch.tensor(layout.synthesis_window(), dtype=torch.float32), persistent=False)
        self.register_buffer("band_of", torch.tensor(layout.band_of_bin(), dtype=torch.long), persistent=False)
        self.register_buffer("widths", torch.tensor(layout.band_widths(), dtype=torch.float32), persistent=False)

    def frames(self, x: torch.Tensor) -> torch.Tensor:
        """x: (B, K, T), T a multiple of the hop -> (B, K, T / hop, n_fft).

        Frame f ends at stream sample (f + 1)·hop; samples before 0 are zero."""
        n, h = self.layout.n_fft, self.layout.hop
        assert x.shape[-1] % h == 0, "length must be a multiple of the hop"
        padded = torch.nn.functional.pad(x, (n - h, 0))
        return padded.unfold(-1, n, h)

    def analyze(self, x: torch.Tensor):
        """x: (B, K, T) -> spec (B, K, F, bins) complex, power (B, K, F, bands)."""
        spec = torch.fft.rfft(self.frames(x) * self.wa, dim=-1)
        p = spec.real.square() + spec.imag.square()
        power = torch.zeros(*p.shape[:-1], self.layout.bands, device=p.device, dtype=p.dtype)
        power.index_add_(-1, self.band_of, p)
        return spec, power / self.widths

    def synthesize(self, spec: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """spec (B, K, F, bins), mask (B, K, F, bands) -> synth part of each input, (B, K, (F − 1)·hop).

        Output sample t of the stream, for t in [0, (F − 1)·hop)."""
        n, h = self.layout.n_fft, self.layout.hop
        y = torch.fft.irfft(spec * mask[..., self.band_of], n=n, dim=-1)
        region = y[..., n - 2 * h:] * self.ws[n - 2 * h:]
        first, second = region[..., :h], region[..., h:]
        # Frame f covers [(f − 1)·hop, (f + 1)·hop): hop-block b is frame b's
        # second half plus frame b + 1's first half.
        blocks = second[..., :-1, :] + first[..., 1:, :]
        return blocks.flatten(-2)

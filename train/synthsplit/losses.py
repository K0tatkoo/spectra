"""The objective: signal-to-distortion of the synth lane, with a floor.

Plain SDR is undefined on a crop with no synth in it, and those crops matter:
they teach the network not to invent a synth. With the floor — a thousandth of
the inputs' energy — a silent target scores 0 dB for a silent answer and worse
for anything else, and a loud one scores its SDR, capped at +30 dB.
"""

from __future__ import annotations

import torch

TAU = 1e-3


def floored_sdr_loss(est: torch.Tensor, ref: torch.Tensor, inputs_energy: torch.Tensor, tau: float = TAU) -> torch.Tensor:
    """est, ref: (B, T); inputs_energy: (B,). Returns (B,) losses, lower is better (−SDR in dB)."""
    floor = tau * inputs_energy + 1e-10
    err = (est - ref).square().sum(-1)
    sig = ref.square().sum(-1)
    return 10 * torch.log10(err + floor) - 10 * torch.log10(sig + floor)

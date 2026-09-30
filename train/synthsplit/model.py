"""The splitter's network: band powers of each input in, a mask per band out.

Small on purpose. On the phone it runs 86 times a second next to a stem model
that already fills the fastest core, so it has to cost a few percent of a
slower one: about a million multiply-adds a frame at the defaults, most of it
in two GRU layers, exported as ONNX Runtime's fused GRU.
"""

from __future__ import annotations

import torch
from torch import nn

from .layout import Layout

LOG_EPS = 1e-8


class SynthSplitNet(nn.Module):
    def __init__(self, layout: Layout, hidden: int = 256, layers: int = 2):
        super().__init__()
        self.layout = layout
        self.hidden = hidden
        self.layers = layers
        f = layout.features
        # Per-feature normalisation of the log powers, measured on the training
        # data before training starts (train.py) and frozen into the export.
        self.register_buffer("mean", torch.zeros(f))
        self.register_buffer("std", torch.ones(f))
        self.inp = nn.Linear(f, hidden)
        self.gru = nn.GRU(hidden, hidden, num_layers=layers, batch_first=True)
        self.out = nn.Linear(hidden, f)
        # Start by calling a little of everything synth, not half of it.
        nn.init.constant_(self.out.bias, -2.0)

    def forward(self, power: torch.Tensor, state: torch.Tensor | None = None):
        """power (B, F, features) -> mask (B, F, features), state (layers, B, hidden)."""
        x = (torch.log(power + LOG_EPS) - self.mean) / self.std
        x = torch.relu(self.inp(x))
        y, state = self.gru(x, state)
        mask = torch.sigmoid(self.out(y + x))
        return mask, state

    def initial_state(self, batch: int, device=None) -> torch.Tensor:
        return torch.zeros(self.layers, batch, self.hidden, device=device)


class SplitStep(nn.Module):
    """One frame, the way the app calls it: the ONNX export's graph.

    Inputs `power` (1, 1, features) and `state`; outputs `mask` and `next_state`
    — the names OnnxMaskNet looks for."""

    def __init__(self, net: SynthSplitNet):
        super().__init__()
        self.net = net

    def forward(self, power: torch.Tensor, state: torch.Tensor):
        mask, next_state = self.net(power, state)
        return mask, next_state


def split_batch(dsp, net: SynthSplitNet, inputs: torch.Tensor):
    """Runs a batch of crops end to end.

    inputs: (B, K, T) stems, T a multiple of the hop. Returns the synth
    (B, T − hop), each input's synth part (B, K, T − hop) and the masks."""
    spec, power = dsp.analyze(inputs)                      # (B, K, F, bins), (B, K, F, bands)
    b, k, f, bands = power.shape
    feats = power.permute(0, 2, 1, 3).reshape(b, f, k * bands)
    mask, _ = net(feats)
    mask = mask.reshape(b, f, k, bands).permute(0, 2, 1, 3)
    parts = dsp.synthesize(spec, mask)                     # (B, K, T − hop)
    return parts.sum(dim=1), parts, mask

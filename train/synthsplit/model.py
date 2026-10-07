"""The splitter's network: band powers of each input in, a mask per band out.

Small on purpose. On the phone it runs 86 times a second next to a stem model
that already fills the fastest core, so it has to cost a few percent of a
slower one: about a million multiply-adds a frame at the defaults, most of it
in two GRU layers, exported as ONNX Runtime's fused GRU.

front="conv" puts two 1-D convolutions across the bands before the GRU: the
same small filters slide over every band, so a spectral shape (how wide a
partial is, how a saw's harmonics fall off) is recognised wherever it sits,
instead of being learned once per band by a dense layer. Run 3 memorised its
training songs (MoisesDB +1.6 dB on them, -0.2 on new ones); sharing weights
across frequency is the usual cure. About 3 M multiply-adds a frame at
hidden 256, 32 channels.
"""

from __future__ import annotations

import torch
from torch import nn

from .layout import Layout

LOG_EPS = 1e-8


class ConvFront(nn.Module):
    """(B, F, inputs * bands) normalised log powers -> (B, F, hidden): convolutions across bands, per frame."""

    def __init__(self, inputs: int, bands: int, hidden: int, channels: int):
        super().__init__()
        self.inputs, self.bands = inputs, bands
        self.conv1 = nn.Conv1d(inputs, channels, 9, padding=4)
        self.conv2 = nn.Conv1d(channels, channels, 9, stride=2, padding=4)
        self.proj = nn.Linear(channels * ((bands + 1) // 2), hidden)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        b, f, _ = x.shape
        y = x.reshape(b * f, self.inputs, self.bands)
        y = torch.relu(self.conv1(y))
        y = torch.relu(self.conv2(y))
        return self.proj(y.reshape(b, f, -1))


class SynthSplitNet(nn.Module):
    def __init__(self, layout: Layout, hidden: int = 256, layers: int = 2, dropout: float = 0.0,
                 front: str = "linear", channels: int = 32):
        super().__init__()
        self.layout = layout
        self.hidden = hidden
        self.layers = layers
        self.front_kind = front
        self.channels = channels
        f = layout.features
        # Per-feature normalisation of the log powers, measured on the training
        # data before training starts (train.py) and frozen into the export.
        self.register_buffer("mean", torch.zeros(f))
        self.register_buffer("std", torch.ones(f))
        if front == "conv":
            self.front = ConvFront(len(layout.inputs), layout.bands, hidden, channels)
        else:  # the first runs' dense layer (and its parameter name, so their checkpoints load)
            self.inp = nn.Linear(f, hidden)
        # Dropout only while training (the first run memorised its songs);
        # it adds no weights, so checkpoints load either way.
        self.drop = nn.Dropout(dropout)
        self.gru = nn.GRU(hidden, hidden, num_layers=layers, batch_first=True, dropout=dropout if layers > 1 else 0.0)
        self.out = nn.Linear(hidden, f)
        # Start by calling a little of everything synth, not half of it.
        nn.init.constant_(self.out.bias, -2.0)

    def forward(self, power: torch.Tensor, state: torch.Tensor | None = None):
        """power (B, F, features) -> mask (B, F, features), state (layers, B, hidden)."""
        x = (torch.log(power + LOG_EPS) - self.mean) / self.std
        x = self.drop(torch.relu(self.front(x) if self.front_kind == "conv" else self.inp(x)))
        y, state = self.gru(x, state)
        mask = torch.sigmoid(self.out(y + x))
        return mask, state

    def initial_state(self, batch: int, device=None) -> torch.Tensor:
        return torch.zeros(self.layers, batch, self.hidden, device=device)


def net_from_checkpoint(ckpt: dict, layout: Layout) -> SynthSplitNet:
    """The network a checkpoint was trained as (runs before the conv front have no "front")."""
    return SynthSplitNet(layout, int(ckpt["hidden"]), int(ckpt["layers"]), front=ckpt.get("front", "linear"),
                         channels=int(ckpt.get("channels", 32)))


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

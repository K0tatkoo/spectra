"""The network the app runs one frame at a time must answer as it did in training."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
torch = pytest.importorskip("torch")
from synthsplit.dsp import SplitDSP  # noqa: E402
from synthsplit.layout import Layout  # noqa: E402
from synthsplit.losses import floored_sdr_loss  # noqa: E402
from synthsplit.model import SynthSplitNet, split_batch  # noqa: E402


def test_one_frame_at_a_time_equals_the_whole_sequence():
    torch.manual_seed(0)
    net = SynthSplitNet(Layout(), hidden=32).eval()
    power = torch.rand(1, 50, net.layout.features) * 10
    with torch.no_grad():
        whole, _ = net(power)
        state = None
        steps = []
        for f in range(power.shape[1]):
            m, state = net(power[:, f:f + 1], state)
            steps.append(m)
    assert (torch.cat(steps, 1) - whole).abs().max() < 1e-6


def test_a_training_step_runs_and_learns():
    torch.manual_seed(0)
    lay = Layout()
    net = SynthSplitNet(lay, hidden=32)
    dsp = SplitDSP(lay)
    opt = torch.optim.Adam(net.parameters(), 3e-3)
    t = torch.arange(24 * lay.hop) / 44100.0
    synth = 0.3 * torch.sign(torch.sin(2 * np.pi * 440 * t))          # a square lead…
    voice = 0.3 * torch.sin(2 * np.pi * 220 * t + 2 * torch.sin(2 * np.pi * 5 * t))  # …over a sung-ish tone
    x = torch.stack([voice + synth, 0.05 * torch.randn_like(t)])[None]
    losses = []
    for _ in range(60):
        est, _, _ = split_batch(dsp, net, x)
        end = est.shape[-1]
        loss = floored_sdr_loss(est, synth[None, :end], x[:, :, :end].sum(1).square().sum(-1)).mean()
        opt.zero_grad()
        loss.backward()
        opt.step()
        losses.append(loss.item())
    assert losses[-1] < losses[0] - 3, f"did not learn: {losses[0]:.1f} -> {losses[-1]:.1f} dB"


def test_export_round_trip(tmp_path):
    pytest.importorskip("onnx")
    pytest.importorskip("onnxruntime")
    from export import export, verify

    torch.manual_seed(1)
    net = SynthSplitNet(Layout(), hidden=16).eval()
    path = tmp_path / "split.onnx"
    export(net, path, {"spectra.note": "test"})
    _, _, err = verify(net, path, frames=40)
    assert err < 1e-5

    import onnx

    meta = {p.key: p.value for p in onnx.load(str(path)).metadata_props}
    assert meta["spectra.format"] == "synth-split/1"
    assert meta["inputs"] == "vocals,other"

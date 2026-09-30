"""The trainer's batched signal path must be the app's, sample for sample.

The app is held to synthsplit/reference.py by its own fixture test; this holds
synthsplit/dsp.py (what the network is trained through) to the same reference.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from synthsplit.layout import Layout  # noqa: E402
from synthsplit.reference import run_stream  # noqa: E402

torch = pytest.importorskip("torch")
from synthsplit.dsp import SplitDSP  # noqa: E402


def test_batched_matches_reference():
    lay = Layout()
    rng = np.random.default_rng(3)
    x = rng.standard_normal((2, 40 * lay.hop)) * 0.3
    frames = x.shape[1] // lay.hop
    masks = rng.uniform(0, 1, (frames, 2, lay.bands))
    synth_ref, rests_ref, powers_ref = run_stream(lay, lambda p, f: masks[f], x)

    dsp = SplitDSP(lay).double()
    spec, power = dsp.analyze(torch.from_numpy(x)[None])
    np.testing.assert_allclose(power[0].permute(1, 0, 2).numpy(), powers_ref, rtol=1e-9, atol=1e-12)
    parts = dsp.synthesize(spec, torch.from_numpy(masks.transpose(1, 0, 2))[None])
    np.testing.assert_allclose(parts[0].sum(0).numpy(), synth_ref, atol=1e-10)
    np.testing.assert_allclose((torch.from_numpy(x)[None, :, :parts.shape[-1]] - parts)[0].numpy(), rests_ref, atol=1e-10)


def test_all_pass_is_exact_in_float32():
    lay = Layout()
    x = torch.randn(3, 2, 20 * lay.hop) * 0.3
    dsp = SplitDSP(lay)
    spec, power = dsp.analyze(x)
    parts = dsp.synthesize(spec, torch.ones(*power.shape))
    assert (parts.sum(1) - x[:, :, :parts.shape[-1]].sum(1)).abs().max() < 1e-5

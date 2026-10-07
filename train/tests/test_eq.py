"""The batch EQ on the GPU is the numpy EQ the loader used to apply example by example."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
torch = pytest.importorskip("torch")
from synthsplit.data import EQ_PARAMS, apply_eq, random_eq_params  # noqa: E402


def numpy_eq(p: np.ndarray, n: int, rate: int = 44100) -> np.ndarray:
    """The loader's original per-example response, written out from the same parameters."""
    lf = np.log2(np.maximum(np.fft.rfftfreq(n, 1 / rate), 20.0) / 1000.0)
    db = p[1] * lf
    for k in (2, 5):
        db = db + p[k] * np.exp(-0.5 * ((lf - p[k + 1]) / p[k + 2]) ** 2)
    return 10 ** (np.clip(db, -12, 12) / 20)


def test_batch_eq_matches_the_per_example_numpy_eq():
    rng = np.random.default_rng(3)
    b, n = 5, 4410
    x = rng.standard_normal((b, 2, n)).astype(np.float32)
    s = rng.standard_normal((b, n)).astype(np.float32)
    params = np.stack([random_eq_params(rng) for _ in range(b)])
    params[2] = 0  # an example without EQ stays exactly as it was
    got_x, got_s = apply_eq(torch.from_numpy(x), torch.from_numpy(s), torch.from_numpy(params))
    for i in range(b):
        both = np.concatenate([x[i], s[i][None]])
        want = both if params[i, 0] == 0 else np.fft.irfft(np.fft.rfft(both, axis=-1) * numpy_eq(params[i], n), n=n, axis=-1)
        assert np.abs(got_x[i].numpy() - want[:2]).max() < 1e-4
        assert np.abs(got_s[i].numpy() - want[2]).max() < 1e-4


def test_all_off_is_a_no_op():
    x, s = torch.randn(2, 2, 100), torch.randn(2, 100)
    gx, gs = apply_eq(x, s, torch.zeros(2, EQ_PARAMS))
    assert gx is x and gs is s

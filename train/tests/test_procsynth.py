"""The made-up synths: reproducible, finite, in range, and never synth bass."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from synthsplit import procsynth as ps  # noqa: E402


def test_a_seed_makes_the_same_synths_every_time():
    a, ia = ps.render_synths(np.random.default_rng(7), 6.0)
    b, ib = ps.render_synths(np.random.default_rng(7), 6.0)
    assert ia == ib
    assert np.array_equal(a, b)


def test_every_kind_renders_finite_audio_at_a_sane_level():
    for i, kind in enumerate(ps.KINDS * 3):
        rng = np.random.default_rng(100 + i)
        mu = ps.Music(rng, 8.0)
        x, desc = ps.render_part(rng, mu, kind, 8 * ps.SR)
        assert x.shape == (2, 8 * ps.SR), desc
        assert np.isfinite(x).all(), desc
        assert np.abs(x).max() < 50, desc  # RMS 1 where it plays: peaks stay within a sane crest factor


def test_no_note_is_low_enough_to_be_synth_bass():
    for i in range(40):
        rng = np.random.default_rng(i)
        mu = ps.Music(rng, 20.0)
        for kind, notes in ps.NOTES.items():
            for n in notes(rng, mu, mu.sections(rng), int(rng.integers(55, 82))):
                assert n.midi >= ps.LOWEST, (kind, n)


def test_band_limited_saw_has_little_energy_above_nyquist_folding():
    f = np.full(ps.SR, 3520.0)  # A7: a naive saw would alias loudly here
    x = ps.osc_saw(f, 0.0)
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    freqs = np.fft.rfftfreq(len(x), 1 / ps.SR)
    harmonic = np.zeros_like(spec, dtype=bool)
    for k in range(1, int(ps.NYQ // 3520) + 1):
        harmonic |= np.abs(freqs - k * 3520) < 20
    assert spec[~harmonic].sum() / spec[harmonic].sum() < 0.01

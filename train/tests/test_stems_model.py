"""The file model (ownstems/model.py) and its training pieces, on random audio: no data needed."""

import math

import pytest

torch = pytest.importorskip("torch")

from ownstems import RATE, STEMS  # noqa: E402
from ownstems.model import ModelConfig, StemsNet, band_edges, separate  # noqa: E402
from ownstems.training import floored_sdr, stems_loss  # noqa: E402

TINY = ModelConfig(dim=16, blocks=1, heads=2, bands=24, head_hidden=16)


def test_bands_cover_every_bin_once():
    for bands in (24, 40, 48, 56):
        edges = band_edges(2048, bands)
        assert edges[0][0] == 0 and edges[-1][1] == 1025
        assert all(a2 == b1 for (_, b1), (a2, _) in zip(edges, edges[1:]))
        assert all(b - a >= 3 for a, b in edges)
        assert len(edges) <= bands
        if bands >= 40:  # the presets: narrow in the bass, the first band under 100 Hz
            assert edges[0][1] * RATE / 2048 < 100


def test_stems_sum_to_the_mix():
    torch.manual_seed(0)
    net = StemsNet(TINY)
    mix = torch.randn(2, 2, RATE) * 0.3
    est = net(mix)
    assert est.shape == (2, len(STEMS), 2, RATE)
    err = (est.sum(1) - mix).abs().max() / mix.abs().max()
    assert err < 1e-4


def test_scale_in_is_scale_out():
    torch.manual_seed(0)
    net = StemsNet(TINY).eval()
    mix = torch.randn(1, 2, RATE // 2)
    a, b = net(mix), net(mix * 0.01)
    assert torch.allclose(a * 0.01, b, atol=1e-5)


def test_score_floor():
    mix = torch.randn(1, 2, 1000)
    stems = torch.zeros(1, 5, 2, 1000)
    stems[:, 0] = mix
    perfect = floored_sdr(stems, stems, mix)
    assert perfect[0, 0] == pytest.approx(10 * math.log10(1 / 1e-3 + 1), abs=0.01)  # a playing stem caps near 30 dB
    assert perfect[0, 1:].abs().max() < 1e-4  # silent stem, silent answer: 0 dB
    noisy = stems.clone()
    noisy[:, 1] = mix * 0.1
    assert floored_sdr(noisy, stems, mix)[0, 1] < -5


def test_a_few_steps_learn_one_batch():
    torch.manual_seed(0)
    net = StemsNet(TINY)
    opt = torch.optim.Adam(net.parameters(), lr=3e-3)
    t = torch.arange(RATE // 2) / RATE
    stems = torch.zeros(2, 5, 2, len(t))
    stems[:, 3] = torch.sin(2 * math.pi * 55 * t)          # bass: a low sine
    stems[:, 4] = (torch.rand(len(t)) - 0.5) * (t % 0.25 < 0.03)  # drums: noise bursts
    mix = stems.sum(1)
    first = None
    for _ in range(40):
        loss, _ = stems_loss(net(mix), stems)
        first = first if first is not None else float(loss)
        opt.zero_grad()
        loss.backward()
        opt.step()
    assert float(loss) < 0.6 * first


def test_separate_long_song_in_chunks():
    torch.manual_seed(0)
    net = StemsNet(TINY).eval()
    mix = torch.randn(2, int(2.7 * RATE)) * 0.2
    out = separate(net, mix, chunk_s=1.0, overlap=0.25)
    assert out.shape == (5, 2, mix.shape[1])
    assert (out.sum(0) - mix).abs().max() < 1e-4

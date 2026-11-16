"""What the file model's trainer needs besides the model: the example stream, the loss, the score.

The stream builds a fresh made-up song for every example (ownstems/mixer.py),
in DataLoader worker processes, each with its own Library and seed. Nothing
is pre-rendered: every step hears songs nobody has heard before.
"""

from __future__ import annotations

import os

import numpy as np
import torch
from torch.utils.data import IterableDataset, get_worker_info

from . import STEMS
from .mixer import MixConfig, Mixer

# Floor of the per-stem score: a thousandth of the mix's energy (as the synth splitter's).
TAU = 1e-3


class MixStream(IterableDataset):
    """An endless stream of (mix (C, N), stems (S, C, N)) float32 tensors."""

    def __init__(self, root, cfg: MixConfig, seed: int):
        self.root, self.cfg, self.seed = root, cfg, seed

    def __iter__(self):
        from .audio import Library

        info = get_worker_info()
        wid = info.id if info else 0
        torch.set_num_threads(1)
        mixer = Mixer(Library(self.root), self.cfg)
        rng = np.random.default_rng([self.seed, wid])
        while True:
            ex = mixer.example(rng)
            yield torch.from_numpy(ex.mix), torch.from_numpy(ex.stems)


def worker_init(_):
    os.environ["OMP_NUM_THREADS"] = "1"
    torch.set_num_threads(1)


def fixed_set(root, cfg: MixConfig, count: int, seed: int) -> dict:
    """A held-out set built once (split=valid) and kept, so every validation scores the same songs."""
    from .audio import Library

    mixer = Mixer(Library(root), cfg)
    rng = np.random.default_rng(seed)
    mixes, stems, hardstyle = [], [], []
    for _ in range(count):
        ex = mixer.example(rng)
        mixes.append(torch.from_numpy(ex.mix))
        stems.append(torch.from_numpy(ex.stems))
        hardstyle.append(bool(ex.info.get("generated")))
    return {"mix": torch.stack(mixes), "stems": torch.stack(stems), "hardstyle": torch.tensor(hardstyle)}


def _mag(x: torch.Tensor, n_fft: int, hop: int) -> torch.Tensor:
    """STFT magnitudes, with a floor of 1e-6 inside the square root.

    Not `.abs()`: its gradient is z * (1 / |z|), and where a generated synth's filter or reverb
    tail decays to denormal floats the reciprocal overflows to inf and 0 * inf gives NaN
    (seen on the server, 2026-11-16: about one batch in ten)."""
    w = torch.hann_window(n_fft, device=x.device)
    z = torch.stft(x.reshape(-1, x.shape[-1]), n_fft, hop, window=w, return_complex=True)
    return (torch.view_as_real(z).square().sum(-1) + 1e-12).sqrt()


RESOLUTIONS = ((2048, 512), (1024, 256), (512, 128))


def stems_loss(est: torch.Tensor, ref: torch.Tensor) -> tuple[torch.Tensor, dict]:
    """L1 on the waveforms plus L1 on multi-resolution STFT magnitudes. est, ref: (B, S, C, N),
    already divided by each mix's level, so loud and quiet songs weigh the same."""
    time = (est - ref).abs().mean()
    spec = sum((_mag(est, n, h) - _mag(ref, n, h)).abs().mean() for n, h in RESOLUTIONS) / len(RESOLUTIONS)
    return time + spec, {"l1": float(time.detach()), "stft": float(spec.detach())}


def floored_sdr(est: torch.Tensor, ref: torch.Tensor, mix: torch.Tensor, tau: float = TAU) -> torch.Tensor:
    """Per example and stem, in dB: est, ref (B, S, C, N), mix (B, C, N) -> (B, S).

    The floor (a thousandth of the mix's energy) makes a silent stem scorable: a silent
    answer scores 0 dB, anything else less. A playing stem scores its SDR, capped near +30."""
    floor = tau * mix.square().sum((-2, -1)).unsqueeze(1) + 1e-10
    err = (est - ref).square().sum((-2, -1))
    sig = ref.square().sum((-2, -1))
    return 10 * torch.log10(sig + floor) - 10 * torch.log10(err + floor)


@torch.no_grad()
def validate(net, data: dict, batch: int = 4) -> dict:
    """Scores on the held-out set: per stem, over the examples where it plays, the model's SDR and
    what "all of the mix is this stem" would score (the number to beat). Also the mean over the
    generated hardstyle songs alone."""
    net.eval()
    sdr, base = [], []
    for i in range(0, len(data["mix"]), batch):
        mix, stems = data["mix"][i:i + batch], data["stems"][i:i + batch]
        est = net(mix)
        sdr.append(floored_sdr(est, stems, mix))
        base.append(floored_sdr(mix.unsqueeze(1).expand_as(stems), stems, mix))
    sdr, base = torch.cat(sdr), torch.cat(base)
    playing = data["stems"].square().sum((-2, -1)) > 1e-8
    out: dict = {}
    means = []
    for j, s in enumerate(STEMS):
        p = playing[:, j]
        if p.any():
            out[f"{s}_sdr"] = round(float(sdr[p, j].mean()), 2)
            out[f"{s}_mix_sdr"] = round(float(base[p, j].mean()), 2)
            means.append(out[f"{s}_sdr"])
        if (~p).any():
            out[f"{s}_silent_sdr"] = round(float(sdr[~p, j].mean()), 2)
    out["mean_sdr"] = round(sum(means) / len(means), 3) if means else 0.0
    hs = data["hardstyle"]
    if hs.any():
        out["hardstyle_sdr"] = round(float(sdr[hs][playing[hs]].mean()), 2)
    net.train()
    return out

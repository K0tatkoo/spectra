"""The file model: a band-split transformer that separates a stereo mix into five stems.

Shaped after BS-RoFormer (band-split, then attention along time and across
bands, rotary positions), written here from the idea, small enough to train
on CPUs and later to run on the iPhone's Neural Engine:

1. STFT of the stereo mix (2048-point, hop 512 at 44.1 kHz).
2. The bins are cut into mel-spaced bands, narrow in the bass (3 bins = 64 Hz,
   so a kick and a bass note land in different bands) and wide at the top.
   Each band's real and imaginary parts, both channels, go through its own
   small linear layer to one feature vector: a (bands × frames) grid.
3. Blocks of attention: first along time within each band (the file model sees
   both sides of every moment), then across the bands within each frame.
4. Each band's own head turns its vector back into a complex mask per stem,
   channel and bin. A complex mask can fix phase as well as level.
5. Mixture consistency: whatever the five masks leave over (or take twice) is
   shared out equally, so the stems always sum to the mix exactly.

The mix is normalised by its own level going in and scaled back coming out,
so a quiet and a loud master look alike to the network.
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass

import torch
import torch.nn.functional as F
from torch import nn

from . import RATE, STEMS


@dataclass
class ModelConfig:
    dim: int = 64          # feature size of each band token
    blocks: int = 6        # each = attention along time + attention across bands
    heads: int = 4
    ffn: int = 2           # feed-forward width, as a multiple of dim
    bands: int = 48
    head_hidden: int = 128  # mask head's hidden size per band
    n_fft: int = 2048
    hop: int = 512
    stems: int = len(STEMS)
    channels: int = 2

    @staticmethod
    def preset(name: str) -> "ModelConfig":
        return {
            "tiny": ModelConfig(dim=48, blocks=4, heads=4, bands=40, head_hidden=96),
            "small": ModelConfig(),
            "base": ModelConfig(dim=96, blocks=8, heads=6, bands=56, head_hidden=192),
        }[name]


def band_edges(n_fft: int, bands: int, rate: int = RATE, min_width: int = 3) -> list[tuple[int, int]]:
    """Mel-spaced [start, end) bin ranges covering every bin once, each at least min_width wide."""
    bins = n_fft // 2 + 1
    mel_top = 2595 * math.log10(1 + (rate / 2) / 700)
    hz = [700 * (10 ** (mel_top * i / bands / 2595) - 1) for i in range(bands + 1)]
    edges = [0]
    for f in hz[1:-1]:
        e = max(int(round(f / (rate / n_fft))), edges[-1] + min_width)
        edges.append(e)
    edges.append(bins)
    # Pushing narrow bass bands up can crowd the top: drop edges that end too close to it.
    while len(edges) > 2 and edges[-1] - edges[-2] < min_width:
        edges.pop(-2)
    return list(zip(edges[:-1], edges[1:]))


class Rotary(nn.Module):
    def __init__(self, dim: int, base: float = 10000.0):
        super().__init__()
        self.register_buffer("inv", 1.0 / (base ** (torch.arange(0, dim, 2).float() / dim)), persistent=False)

    def forward(self, n: int, device) -> torch.Tensor:
        """(n, dim / 2) complex: e^(i * position * frequency)."""
        f = torch.outer(torch.arange(n, device=device, dtype=self.inv.dtype), self.inv)
        return torch.polar(torch.ones_like(f), f)


def rope(x: torch.Tensor, cis: torch.Tensor) -> torch.Tensor:
    """Rotates each (even, odd) pair of x (..., n, dim) by its position's angle: one complex multiply."""
    xc = torch.view_as_complex(x.reshape(*x.shape[:-1], -1, 2).contiguous())
    return torch.view_as_real(xc * cis).flatten(-2)


class Attention(nn.Module):
    """Pre-norm self-attention with rotary positions, then a pre-norm feed-forward."""

    def __init__(self, dim: int, heads: int, ffn: int):
        super().__init__()
        self.heads = heads
        self.norm1 = nn.RMSNorm(dim)
        self.qkv = nn.Linear(dim, 3 * dim, bias=False)
        self.proj = nn.Linear(dim, dim, bias=False)
        self.rot = Rotary(dim // heads)
        self.norm2 = nn.RMSNorm(dim)
        self.ff = nn.Sequential(nn.Linear(dim, ffn * dim), nn.GELU(), nn.Linear(ffn * dim, dim))

    def forward(self, x: torch.Tensor) -> torch.Tensor:  # (N, L, D)
        n, l, d = x.shape
        q, k, v = self.qkv(self.norm1(x)).view(n, l, 3, self.heads, d // self.heads).permute(2, 0, 3, 1, 4)
        cis = self.rot(l, x.device)
        y = F.scaled_dot_product_attention(rope(q, cis), rope(k, cis), v)
        x = x + self.proj(y.transpose(1, 2).reshape(n, l, d))
        return x + self.ff(self.norm2(x))


class Block(nn.Module):
    def __init__(self, cfg: ModelConfig):
        super().__init__()
        self.time = Attention(cfg.dim, cfg.heads, cfg.ffn)
        self.band = Attention(cfg.dim, cfg.heads, cfg.ffn)

    def forward(self, h: torch.Tensor) -> torch.Tensor:  # (B, K, T, D)
        b, k, t, d = h.shape
        h = self.time(h.reshape(b * k, t, d)).view(b, k, t, d)
        h = h.transpose(1, 2).reshape(b * t, k, d)
        h = self.band(h).view(b, t, k, d).transpose(1, 2)
        return h.contiguous()


class StemsNet(nn.Module):
    def __init__(self, cfg: ModelConfig | None = None):
        super().__init__()
        self.cfg = cfg = cfg or ModelConfig()
        self.edges = band_edges(cfg.n_fft, cfg.bands)
        per_bin = cfg.channels * 2  # real and imaginary of each channel
        self.enc = nn.ModuleList(
            nn.Sequential(nn.RMSNorm((b - a) * per_bin), nn.Linear((b - a) * per_bin, cfg.dim)) for a, b in self.edges)
        self.blocks = nn.ModuleList(Block(cfg) for _ in range(cfg.blocks))
        self.dec = nn.ModuleList(
            nn.Sequential(nn.RMSNorm(cfg.dim), nn.Linear(cfg.dim, cfg.head_hidden), nn.Tanh(),
                          nn.Linear(cfg.head_hidden, 2 * (b - a) * per_bin * cfg.stems), nn.GLU(-1))
            for a, b in self.edges)
        self.register_buffer("window", torch.hann_window(cfg.n_fft), persistent=False)

    def stft(self, x: torch.Tensor) -> torch.Tensor:
        """(..., N) -> complex (..., F, T)"""
        lead = x.shape[:-1]
        s = torch.stft(x.reshape(-1, x.shape[-1]), self.cfg.n_fft, self.cfg.hop, window=self.window,
                       return_complex=True)
        return s.view(*lead, *s.shape[-2:])

    def istft(self, s: torch.Tensor, n: int) -> torch.Tensor:
        lead = s.shape[:-2]
        x = torch.istft(s.reshape(-1, *s.shape[-2:]), self.cfg.n_fft, self.cfg.hop, window=self.window, length=n)
        return x.view(*lead, n)

    def forward(self, mix: torch.Tensor) -> torch.Tensor:
        """mix (B, C, N) -> stems (B, S, C, N), summing to the mix."""
        cfg = self.cfg
        b, c, n = mix.shape
        scale = mix.reshape(b, -1).std(-1).clamp_min(1e-5).view(b, 1, 1)
        spec = self.stft(mix / scale)                             # (B, C, F, T)
        t = spec.shape[-1]
        feats = torch.view_as_real(spec).permute(0, 3, 2, 1, 4)    # (B, T, F, C, 2)
        h = torch.stack([enc(feats[:, :, a:e].reshape(b, t, -1)) for enc, (a, e) in zip(self.enc, self.edges)], 1)
        for blk in self.blocks:
            h = blk(h)
        masks = [dec(h[:, i]).view(b, t, e - a, c, 2, cfg.stems) for i, (dec, (a, e)) in enumerate(zip(self.dec, self.edges))]
        mask = torch.cat(masks, 2).permute(0, 5, 3, 2, 1, 4).contiguous()  # (B, S, C, F, T, 2)
        est = torch.view_as_complex(mask) * spec.unsqueeze(1)
        est = est + (spec.unsqueeze(1) - est.sum(1, keepdim=True)) / cfg.stems
        return self.istft(est, n) * scale.unsqueeze(1)

    def config_dict(self) -> dict:
        return asdict(self.cfg)


def net_from_checkpoint(ckpt: dict, weights: str = "ema") -> StemsNet:
    net = StemsNet(ModelConfig(**ckpt["config"]))
    net.load_state_dict(ckpt[weights])
    return net


@torch.no_grad()
@torch.inference_mode()
def separate(net: StemsNet, mix: torch.Tensor, chunk_s: float = 8.0, overlap: float = 0.25) -> torch.Tensor:
    """A whole song: (C, N) -> (S, C, N), in overlapping chunks blended with a linear fade.

    The song stays on the CPU; each chunk goes to the network's device and back.
    Inference mode: with autograd on, every chunk's activations stayed alive."""
    c, n = mix.shape
    chunk = int(chunk_s * RATE)
    dev = next(net.parameters()).device
    mix = mix.cpu()
    if n <= chunk:
        return net(mix.unsqueeze(0).to(dev))[0].cpu()
    step = int(chunk * (1 - overlap))
    fade = chunk - step
    ramp = torch.ones(chunk)
    ramp[:fade] = torch.linspace(0, 1, fade + 2)[1:-1]
    ramp[-fade:] = torch.linspace(1, 0, fade + 2)[1:-1]
    out = torch.zeros(net.cfg.stems, c, n)
    weight = torch.zeros(n)
    starts = list(range(0, n - chunk, step)) + [n - chunk]
    for s in starts:
        w = ramp.clone()
        if s == 0:
            w[:fade] = 1
        if s == starts[-1]:
            w[-fade:] = 1
        out[..., s:s + chunk] += net(mix[None, :, s:s + chunk].to(dev))[0].cpu() * w
        weight[s:s + chunk] += w
    return out / weight.clamp_min(1e-8)

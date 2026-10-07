"""Training examples for the splitter, drawn afresh from the corpus every step.

Two kinds, because each fixes the other's weakness:

- **real**: a crop of a song as the app's stem model heard it — its vocals and
  other stems — against that song's true synth. This is the only way to teach
  the routing: whether a lead went to vocals or other, and what bleed rides
  along, is the stem model's doing and cannot be simulated. But each song has
  only one arrangement.
- **remix**: ground-truth stems from different songs, put together with a
  random share of the synth routed into the "vocals" input and the rest into
  "other", plus a little bass/drum bleed. Endless combinations, including
  vocals with no synth at all, and synth with no vocals.

Every example also gets a random level, and some have no synth at all, so the
network learns that silence is an answer too. A real example "with no synth"
is cut from a stretch of a song where the synth is silent — the stem model's
take on guitars, pianos and voices with nothing to find in it, which is what
the second run got wrong on songs it had not heard.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import Dataset

from .corpus import GROUPS, REAL

ACTIVE_DB = -45.0  # a one-second window with synth louder than this counts as "has synth"


@dataclass
class Mix:
    real_share: float = 0.6
    second_song_share: float = 0.5
    eq_share: float = 0.5
    no_synth_share: float = 0.15
    vocals_in_remix_share: float = 0.6
    gain_db: tuple[float, float] = (-12.0, 6.0)
    bleed_db: tuple[float, float] = (-40.0, -18.0)
    corpus_weights: dict | None = None


class Song:
    def __init__(self, path: Path):
        self.path = path
        self.meta = json.loads((path / "meta.json").read_text())
        self.corpus = self.meta["corpus"]
        self.samples = int(self.meta["samples"])
        activity = self.meta.get("synth_activity_db", [])
        self.active = [i for i, db in enumerate(activity) if db > ACTIVE_DB]
        # Runs of whole seconds with no synth in them: (first second, seconds).
        self.quiet: list[tuple[int, int]] = []
        first = None
        for i, db in enumerate(list(activity) + [0.0]):
            if db <= ACTIVE_DB and first is None:
                first = i
            elif db > ACTIVE_DB and first is not None:
                self.quiet.append((first, i - first))
                first = None
        self.has_vocals = self.meta["rms_db"].get("vocals", -200) > -60
        self._arrays = None

    def arrays(self) -> dict:
        # Opened lazily, in whichever DataLoader worker first needs them.
        if self._arrays is None:
            self._arrays = {k: np.load(self.path / f"{k}.npy", mmap_mode="r") for k in GROUPS + REAL}
        return self._arrays

    def crop(self, key: str, start: int, length: int) -> np.ndarray:
        a = self.arrays()[key]
        out = np.zeros(length, dtype=np.float32)
        seg = a[max(start, 0):min(start + length, self.samples)]
        out[max(-start, 0):max(-start, 0) + len(seg)] = seg
        return out


class SplitDataset(Dataset):
    """`length` examples of `crop` samples, reproducible: example i is the same on every run."""

    def __init__(self, songs: list[Path], crop: int, length: int, seed: int, mix: Mix | None = None,
                 real_only: bool = False):
        self.songs = [Song(p) for p in songs]
        self.songs = [s for s in self.songs if s.samples > crop]
        if not self.songs:
            raise ValueError("no songs long enough for the crop")
        self.crop_len = crop
        self.length = length
        self.seed = seed
        self.mix = mix or Mix()
        self.real_only = real_only
        self.by_corpus: dict[str, list[Song]] = {}
        for s in self.songs:
            self.by_corpus.setdefault(s.corpus, []).append(s)
        self.with_synth = {c: [s for s in v if s.active] for c, v in self.by_corpus.items()}
        self.quiet_seconds = -(-crop // 44100) + 1  # whole seconds a quiet run needs to hold a crop anywhere in it
        self.with_quiet = {c: [s for s in v if any(n >= self.quiet_seconds for _, n in s.quiet)]
                           for c, v in self.by_corpus.items()}
        self.with_vocals = [s for s in self.songs if s.has_vocals]
        weights = self.mix.corpus_weights or {c: 1.0 for c in self.by_corpus}
        names = [c for c in self.by_corpus if weights.get(c, 0) > 0]
        w = np.array([weights[c] for c in names], dtype=np.float64)
        self.corpora, self.corpus_p = names, w / w.sum()

    def __len__(self):
        return self.length

    # -- picking ---------------------------------------------------------------

    def _song(self, rng, want_synth: bool, quiet: bool = False) -> Song:
        corpus = self.corpora[rng.choice(len(self.corpora), p=self.corpus_p)]
        if quiet and self.with_quiet[corpus]:
            pool = self.with_quiet[corpus]
        else:
            pool = self.with_synth[corpus] if want_synth and self.with_synth[corpus] else self.by_corpus[corpus]
        return pool[rng.integers(len(pool))]

    def _start(self, song: Song, rng, want_synth: bool, quiet: bool = False) -> int:
        span = song.samples - self.crop_len
        runs = [(a, n) for a, n in song.quiet if n >= self.quiet_seconds] if quiet else []
        if runs:
            # Anywhere inside a run of seconds with no synth.
            a, n = runs[rng.integers(len(runs))]
            lo = a * 44100
            return int(np.clip(lo + rng.integers(n * 44100 - self.crop_len + 1), 0, span))
        if want_synth and song.active:
            # Centre the crop on a second that has synth in it.
            second = song.active[rng.integers(len(song.active))]
            centre = second * 44100 + rng.integers(44100)
            return int(np.clip(centre - self.crop_len // 2, 0, span))
        return int(rng.integers(span + 1))

    @staticmethod
    def _db(rng, lo_hi) -> float:
        return float(10 ** (rng.uniform(*lo_hi) / 20))

    # -- examples ----------------------------------------------------------------

    def _real(self, rng, want_synth: bool):
        quiet = not want_synth
        song = self._song(rng, want_synth, quiet)
        start = self._start(song, rng, want_synth, quiet)
        n = self.crop_len
        v, o, s = song.crop("sg_vocals", start, n), song.crop("sg_other", start, n), song.crop("synth", start, n)
        if not self.real_only and rng.random() < self.mix.second_song_share:
            other = self._song(rng, want_synth, quiet)
            at = self._start(other, rng, want_synth, quiet)
            g = self._db(rng, (-12.0, 0.0))
            v = v + g * other.crop("sg_vocals", at, n)
            o = o + g * other.crop("sg_other", at, n)
            s = s + g * other.crop("synth", at, n)
        return v, o, s

    def _remix(self, rng, want_synth: bool):
        n = self.crop_len
        s = np.zeros(n, dtype=np.float32)
        if want_synth:
            a = self._song(rng, True)
            s = self._db(rng, (-6.0, 3.0)) * a.crop("synth", self._start(a, rng, True), n)
        voc = np.zeros(n, dtype=np.float32)
        if self.with_vocals and rng.random() < self.mix.vocals_in_remix_share:
            b = self.with_vocals[rng.integers(len(self.with_vocals))]
            voc = self._db(rng, (-6.0, 3.0)) * b.crop("vocals", self._start(b, rng, False), n)
        c = self._song(rng, False)
        non = self._db(rng, (-9.0, 3.0)) * c.crop("nonsynth", self._start(c, rng, False), n)
        d = self._song(rng, False)
        bleed = self._db(rng, self.mix.bleed_db) * d.crop("bassdrums", self._start(d, rng, False), n)
        # How much of the synth the stem model would have put in vocals, drifting over the crop.
        t = np.arange(n) / 44100.0
        route = rng.uniform(0, 1) + rng.uniform(0, 0.5) * np.sin(2 * np.pi * t / rng.uniform(1.0, 6.0) + rng.uniform(0, 6.3))
        route = np.clip(route, 0.0, 1.0).astype(np.float32)
        v = voc + route * s + 0.5 * bleed
        o = non + (1 - route) * s + 0.5 * bleed
        return v, o, s

    def __getitem__(self, i: int):
        rng = np.random.default_rng((self.seed, i))
        want_synth = rng.random() >= self.mix.no_synth_share
        if self.real_only or rng.random() < self.mix.real_share:
            v, o, s = self._real(rng, want_synth)
        else:
            v, o, s = self._remix(rng, want_synth)
        g = 1.0 if self.real_only else self._db(rng, self.mix.gain_db)
        x = np.stack([v, o, s]).astype(np.float32) * g
        # The same filter on the inputs and the answer: a linear filter keeps the
        # answer exact, and the network stops leaning on the exact timbre of the
        # patches it trains on. Only drawn here; apply_eq runs it on the GPU, for
        # the whole batch at once (in numpy it was most of the loader's time).
        eq = random_eq_params(rng) if not self.real_only and rng.random() < self.mix.eq_share \
            else np.zeros(EQ_PARAMS, dtype=np.float32)
        return torch.from_numpy(x[:2].copy()), torch.from_numpy(x[2].copy()), torch.from_numpy(eq)


EQ_PARAMS = 8  # on (1/0), tilt dB per octave, then dB, centre (octaves from 1 kHz), width (octaves) of two bumps


def random_eq_params(rng) -> np.ndarray:
    """A smooth random response over log frequency: a tilt and two broad bumps, within ±12 dB."""
    p = [1.0, rng.uniform(-1.5, 1.5)]
    for _ in range(2):
        p += [rng.uniform(-6, 6), rng.uniform(-4, 4), rng.uniform(0.5, 2.0)]
    return np.array(p, dtype=np.float32)


def eq_response(params: torch.Tensor, n: int, rate: int = 44100) -> torch.Tensor:
    """(B, EQ_PARAMS) -> (B, n // 2 + 1) gains; rows that are off are flat."""
    f = torch.fft.rfftfreq(n, 1 / rate, device=params.device)
    lf = torch.log2(f.clamp_min(20.0) / 1000.0)[None]
    p = params[:, :, None]
    db = p[:, 1] * lf
    for k in (2, 5):
        db = db + p[:, k] * torch.exp(-0.5 * ((lf - p[:, k + 1]) / p[:, k + 2]) ** 2)
    gain = 10 ** (db.clamp(-12, 12) / 20)
    return torch.where(p[:, 0] > 0, gain, torch.ones_like(gain))


def apply_eq(x: torch.Tensor, s: torch.Tensor, params: torch.Tensor):
    """The batch's EQs on inputs (B, K, T) and target (B, T) alike."""
    if not bool((params[:, 0] > 0).any()):
        return x, s
    both = torch.cat([x, s[:, None]], 1)
    n = both.shape[-1]
    both = torch.fft.irfft(torch.fft.rfft(both, dim=-1) * eq_response(params, n)[:, None], n=n, dim=-1)
    return both[:, :-1].contiguous(), both[:, -1].contiguous()

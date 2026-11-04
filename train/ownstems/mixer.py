"""Made-up songs for training: every example is a fresh mix of clean stems.

One example = five stereo stems of `seconds` each, and their sum, the mix:

- Each stem is drawn from the catalog (one clip, sometimes two layered), from
  a different source and song than the others, at a random level, and is
  sometimes left out altogether: a model must hear songs without vocals,
  drums or synth as often as songs with them.
- Or, now and then, a whole made-up song from Spectra's generators: synths
  (synthsplit/procsynth.py) over hardstyle drums and bass
  (synthsplit/hardstyle.py) in one tempo and key, the synths ducked by the
  kick as real hardstyle is. The vocals and other stems still come from the
  catalog.
- Each stem gets its own colour: random EQ, a pan or width, polarity, and for
  vocals a production chain (doubling, delay, reverb, saturation), since free
  singing data is dry exercises and real vocals never are.
- A master bus: compression and limiting to a random loudness, as released
  music has (hardstyle masters run near -6 dB RMS). The gain is worked out on
  the mix and applied to every stem alike, so the stems still sum to the mix
  exactly. Nothing non-linear touches the mix itself.

The ground truth is always the stems as they sit in the mix, after their own
processing: what a listener would call "the vocals" of that song.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy import ndimage, signal

from . import RATE, STEMS
from .audio import Library
from .ledger import Clip


@dataclass
class MixConfig:
    seconds: float = 6.0
    split: str = "train"
    # Chance that a stem is left out of an example.
    absent: dict[str, float] = field(default_factory=lambda: {
        "vocals": 0.3, "synth": 0.4, "other": 0.3, "bass": 0.25, "drums": 0.2})
    # Chance of layering a second clip of the same stem (two guitars, a choir over a lead…).
    layered: dict[str, float] = field(default_factory=lambda: {
        "vocals": 0.15, "synth": 0.3, "other": 0.4, "bass": 0.0, "drums": 0.2})
    # Share of examples whose synth, bass and drums come from the generators.
    generated_song: float = 0.25
    # Relative weight of each source when a stem is drawn from the catalog; 1 if unlisted.
    source_weights: dict[str, float] = field(default_factory=dict)
    stem_gain_db: tuple[float, float] = (-12.0, 0.0)
    loudness_db: tuple[float, float] = (-18.0, -6.0)  # RMS of the finished mix, dBFS
    master: float = 0.85  # share of examples that go through the master bus
    # A clip quieter than this over the chosen window is redrawn (silence is not "vocals").
    min_rms_db: float = -50.0


def _db(x: float) -> float:
    return 10 ** (x / 20)


def rms_db(x: np.ndarray) -> float:
    r = float(np.sqrt(np.mean(np.square(x, dtype=np.float64)))) if x.size else 0.0
    return 20 * math.log10(r) if r > 0 else -200.0


def to_stereo(x: np.ndarray, rng) -> np.ndarray:
    """(c, n) -> (2, n): a mono clip is panned, a stereo one gets a random width."""
    if x.shape[0] == 1:
        pan = rng.uniform(-0.6, 0.6) if rng.random() < 0.5 else 0.0
        lg, rg = math.cos((pan + 1) * math.pi / 4), math.sin((pan + 1) * math.pi / 4)
        return np.stack([x[0] * lg * math.sqrt(2), x[0] * rg * math.sqrt(2)])
    x = x[:2]
    mid, side = (x[0] + x[1]) / 2, (x[0] - x[1]) / 2
    side *= rng.uniform(0.3, 1.4)
    return np.stack([mid + side, mid - side])


def _peaking(fc: float, gain_db: float, q: float) -> np.ndarray:
    a = _db(gain_db / 2)
    w = 2 * math.pi * fc / RATE
    alpha = math.sin(w) / (2 * q)
    c = math.cos(w)
    b = [1 + alpha * a, -2 * c, 1 - alpha * a]
    den = [1 + alpha / a, -2 * c, 1 - alpha / a]
    return np.array([b[0] / den[0], b[1] / den[0], b[2] / den[0], 1.0, den[1] / den[0], den[2] / den[0]])


def _shelf(fc: float, gain_db: float, high: bool) -> np.ndarray:
    a = _db(gain_db / 2)
    w = 2 * math.pi * fc / RATE
    c, s = math.cos(w), math.sin(w)
    alpha = s / 2 * math.sqrt(2)
    sa = 2 * math.sqrt(a) * alpha
    if high:
        b = [a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa)]
        den = [(a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa]
    else:
        b = [a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa)]
        den = [(a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa]
    return np.array([b[0] / den[0], b[1] / den[0], b[2] / den[0], 1.0, den[1] / den[0], den[2] / den[0]])


def random_eq(x: np.ndarray, rng) -> np.ndarray:
    sos = [_shelf(rng.uniform(60, 300), rng.uniform(-6, 6), high=False),
           _shelf(rng.uniform(3000, 12000), rng.uniform(-6, 6), high=True)]
    for _ in range(int(rng.integers(0, 3))):
        sos.append(_peaking(rng.uniform(150, 8000), rng.uniform(-6, 6), rng.uniform(0.5, 3.0)))
    return signal.sosfilt(np.stack(sos), x, axis=-1)


def _reverb(x: np.ndarray, rng, wet: tuple[float, float] = (0.1, 0.45)) -> np.ndarray:
    t60 = rng.uniform(0.3, 3.0)
    n = int(min(t60, 2.5) * RATE)
    t = np.arange(n) / RATE
    ir = rng.standard_normal((2, n)) * np.exp(-6.9 * t / t60)
    k = 1.0 - math.exp(-2 * math.pi * rng.uniform(2500, 10000) / RATE)
    ir = signal.lfilter([k], [1, k - 1], ir, axis=-1)
    pre = int(rng.uniform(0.0, 0.05) * RATE)
    ir = np.concatenate([np.zeros((2, pre)), ir], axis=1)
    ir /= np.sqrt(np.sum(ir ** 2, axis=1, keepdims=True)) + 1e-9
    out = np.stack([signal.oaconvolve(x[c], ir[c])[: x.shape[-1]] for c in range(2)])
    return x + rng.uniform(*wet) * out


def _delay(x: np.ndarray, rng) -> np.ndarray:
    d = int(rng.uniform(0.12, 0.5) * RATE)
    fb = rng.uniform(0.15, 0.5)
    wet = np.zeros_like(x)
    g = 1.0
    for k in range(1, 8):
        g *= fb
        if g < 0.03 or k * d >= x.shape[-1]:
            break
        tap = np.zeros_like(x)
        tap[:, k * d:] = x[:, :-k * d]
        wet += g * (tap[::-1] if k % 2 and rng.random() < 0.5 else tap)
    return x + rng.uniform(0.1, 0.4) * wet


def vocal_chain(x: np.ndarray, rng) -> np.ndarray:
    """Make a dry vocal sound produced: doubling, saturation, delay, reverb, in some mix of them."""
    if rng.random() < 0.35:  # a double: the same take, a little late and panned apart
        lag = int(rng.uniform(0.012, 0.035) * RATE)
        dbl = np.zeros_like(x)
        dbl[:, lag:] = x[:, :-lag]
        w = rng.uniform(0.3, 0.7)
        x = np.stack([x[0] + w * dbl[1], x[1] + w * dbl[0]]) / (1 + w)
    if rng.random() < 0.3:
        peak = float(np.max(np.abs(x))) + 1e-9
        x = np.tanh(rng.uniform(1.5, 6.0) * x / peak) * peak
    if rng.random() < 0.35:
        x = _delay(x, rng)
    if rng.random() < 0.7:
        x = _reverb(x, rng)
    return x


def master_gain(mix: np.ndarray, rng, target_db: float | None = None, ceiling_db: float = -0.3) -> np.ndarray:
    """A mastering chain's gain curve (n,), worked out on the mix: compressor, makeup gain
    up to `target_db` (RMS, dBFS), then a look-ahead limiter at `ceiling_db`.

    Applied to every stem alike, so the sum stays exactly the mix. As in a real master,
    the makeup gain drives the mix into the limiter, so a loud target comes out brick-walled
    and still under the ceiling. With no target there is no makeup gain."""
    level = np.max(np.abs(mix), axis=0)
    env = signal.lfilter([1 - math.exp(-1 / (0.01 * RATE))], [1, -math.exp(-1 / (0.01 * RATE))], level)
    thresh = _db(rng.uniform(-24, -10)) * (np.max(env) + 1e-9)
    ratio = rng.uniform(1.5, 4.0)
    over = np.maximum(env / thresh, 1.0)
    comp = over ** (1 / ratio - 1)
    rel = math.exp(-1 / (rng.uniform(0.05, 0.3) * RATE))
    comp = signal.lfilter([1 - rel], [1, -rel], comp, zi=[comp[0] * rel])[0]
    look = int(0.005 * RATE)
    ceiling = _db(ceiling_db)

    def limited(pre: np.ndarray) -> np.ndarray:
        # The gain each sample needs to stay under the ceiling, taken as a minimum over ±5 ms
        # (look-ahead and hold), then averaged over 5 ms. An average over at most the
        # minimum's own window never rises above any sample's need, so no sample exceeds
        # the ceiling, and the gain still moves smoothly.
        need = np.minimum(1.0, ceiling / (np.max(np.abs(mix * pre), axis=0) + 1e-12))
        held = ndimage.minimum_filter1d(need, size=2 * look + 1, mode="nearest")
        lim = ndimage.uniform_filter1d(held, size=look + 1, mode="nearest")
        return pre * np.minimum(lim, need)

    if target_db is None:
        return limited(comp).astype(np.float32)
    # The limiter takes some loudness back, so the makeup is raised until the target is met
    # (up to eight rounds; the loudest targets land up to a dB short, and a near-silent mix
    # never gets more than +30 dB).
    makeup = target_db - rms_db(mix * comp)
    for _ in range(8):
        g = limited(comp * _db(min(makeup, 30.0)))
        short = target_db - rms_db(mix * g)
        if short < 0.25 or makeup >= 30.0:
            break
        makeup += short
    return g.astype(np.float32)


@dataclass
class Example:
    stems: np.ndarray  # (5, 2, n) float32, in STEMS order
    mix: np.ndarray  # (2, n) float32, exactly stems.sum(0)
    info: dict


class Mixer:
    def __init__(self, library: Library, config: MixConfig | None = None, generators: bool = True):
        self.lib = library
        self.cfg = config or MixConfig()
        self.generators = generators
        self.pools: dict[str, list[Clip]] = {
            s: [c for c in library.catalog.select(stem=s, split=self.cfg.split) if c.seconds >= 1.0] for s in STEMS}
        # Cumulative draw weights: a source's weight times the clip's length (capped at a minute,
        # so one long file does not drown a thousand short ones).
        self.cum: dict[str, np.ndarray] = {}
        for s, pool in self.pools.items():
            w = np.array([self.cfg.source_weights.get(c.source, 1.0) * min(c.seconds, 60.0) for c in pool])
            self.cum[s] = np.cumsum(w) / w.sum() if len(w) and w.sum() > 0 else np.zeros(0)

    def _pick(self, stem: str, rng, avoid: set[str]) -> Clip | None:
        pool, cum = self.pools[stem], self.cum[stem]
        if not len(pool):
            return None
        for _ in range(8):  # another song than the stems already in this example
            c = pool[min(int(np.searchsorted(cum, rng.random())), len(pool) - 1)]
            if f"{c.source}:{c.group}" not in avoid:
                return c
        return None

    def _clip_audio(self, clip: Clip, rng, n: int) -> np.ndarray | None:
        seconds = n / RATE
        for _ in range(8):
            start = rng.uniform(0, max(0.0, clip.seconds - seconds))
            x = self.lib.open(clip, start, min(seconds, clip.seconds))
            if rms_db(x) > self.cfg.min_rms_db:
                break
        else:
            return None
        x = to_stereo(x, rng)
        if x.shape[1] < n:  # a short clip lands somewhere inside the window
            pad = np.zeros((2, n), dtype=np.float32)
            at = int(rng.integers(0, n - x.shape[1] + 1))
            pad[:, at:at + x.shape[1]] = x
            x = pad
        return x[:, :n]

    def _generated(self, rng, n: int) -> dict[str, np.ndarray]:
        from synthsplit import hardstyle, procsynth

        mu = procsynth.Music(rng, n / RATE + 0.5)
        bass = np.zeros((2, n))
        drums, kenv, _ = hardstyle.render_drums(rng, mu, n, bass_out=bass)
        synth, _ = procsynth.render_synths(rng, n / RATE, mu)
        synth = hardstyle.sidechain(synth[:, :n], kenv, rng.uniform(0.3, 0.9))
        return {"synth": synth, "drums": drums, "bass": bass.astype(np.float32)}

    def example(self, rng: np.random.Generator) -> Example:
        """A made-up song with at least one stem playing (a silent one teaches nothing)."""
        for _ in range(20):
            ex = self._example(rng)
            if ex.info["present"]:
                return ex
        return ex

    def _example(self, rng: np.random.Generator) -> Example:
        cfg = self.cfg
        n = int(cfg.seconds * RATE)
        stems = np.zeros((len(STEMS), 2, n), dtype=np.float64)
        info: dict = {"clips": {}, "generated": False, "made": []}
        used: set[str] = set()
        made: dict[str, np.ndarray] = {}
        if self.generators and rng.random() < cfg.generated_song:
            made = self._generated(rng, n)
            info["generated"] = True
        for i, stem in enumerate(STEMS):
            if rng.random() < cfg.absent[stem]:
                continue
            layers = []
            # A generated part can be silent in this window (no reverse bass in this song, a
            # breakdown, a synth resting): then the stem comes from the catalog instead.
            if stem in made and rms_db(made[stem]) > cfg.min_rms_db:
                layers.append(made[stem])
                info["made"].append(stem)
            else:
                for _ in range(2 if rng.random() < cfg.layered[stem] else 1):
                    clip = self._pick(stem, rng, used)
                    if clip is None:
                        break
                    x = self._clip_audio(clip, rng, n)
                    if x is None:
                        continue
                    used.add(f"{clip.source}:{clip.group}")
                    info["clips"].setdefault(stem, []).append(f"{clip.artifact}{'#' + clip.member if clip.member else ''}")
                    layers.append(x * _db(rng.uniform(-6, 0)))
            if not layers:
                continue
            x = np.sum(layers, axis=0)
            x = random_eq(x, rng)
            if stem == "vocals":
                x = vocal_chain(x, rng)
            if rng.random() < 0.5:
                x = -x
            r = rms_db(x)
            if r <= -150:
                continue
            stems[i] = x * _db(-20 - r + rng.uniform(*cfg.stem_gain_db))
        mix = stems.sum(axis=0)
        r = rms_db(mix)
        if r > -150:
            target = rng.uniform(*cfg.loudness_db)
            if rng.random() < cfg.master:
                stems *= master_gain(mix, rng, target_db=target)[None, None, :]
            else:  # unmastered: plain gain, stopping short of clipping
                g = _db(target - r)
                peak = float(np.max(np.abs(mix))) * g
                if peak > 0.99:
                    g *= 0.99 / peak
                stems *= g
        stems = stems.astype(np.float32)
        info["present"] = [s for i, s in enumerate(STEMS) if np.any(stems[i])]
        return Example(stems=stems, mix=stems.sum(axis=0, dtype=np.float32), info=info)

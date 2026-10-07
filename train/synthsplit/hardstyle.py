"""Hardstyle drums and bass, made up: the part of a hardstyle song that is NOT synth but sounds like it.

The first renders of the owner's hardstyle (2026-10-08) picked synths out
cleanly wherever they played alone and let the kick and bass through wherever
those played: the stem model leaves part of a distorted kick in vocals and
other, and ~90 % of what leaked sat above 250 Hz — the kick's distorted body,
right where the leads are, so no filter can take it out. The splitter had never
heard a hardstyle kick (its bleed in training was -40 to -18 dB of rock and pop
drums). These songs teach it one: everything here goes in the bassdrums group,
the stem model hears it mixed with made-up synths, and the splitter is scored
on leaving it alone.

- Kick: a sine whose pitch falls from a few hundred Hz into a tail tuned to the
  song's key, a click on top, driven hard (soft clip, sometimes a second hard
  stage), low-passed. Euphoric (cleaner, longer tail) to raw (more drive, a
  pitch bend in the tail).
- Reverse bass on the off-beats in some songs (a distorted saw swelling into
  the next kick), hats on the off-beats, claps on 2 and 4.
- Breakdowns: blocks of bars with no kick at all, as real songs have.
Returns the drums and the kick's envelope, which the caller uses to duck the
synths (sidechain), as every hardstyle lead is.
"""

from __future__ import annotations

import math

import numpy as np
from scipy import signal

from .procsynth import SR, Music

SCALES = ("minor", "harmonic_minor", "phrygian", "dorian")


def music(rng, seconds: float) -> Music:
    return Music(rng, seconds, bpm=float(rng.uniform(145, 158)), scales=SCALES)


def _hz(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def kick(rng, length: int, root_hz: float, raw: float) -> np.ndarray:
    """One kick, `length` samples (up to the next beat). raw 0 (euphoric) .. 1 (raw)."""
    t = np.arange(length) / SR
    f0 = rng.uniform(250, 650)
    drop = rng.uniform(0.008, 0.04)
    f = root_hz + (f0 - root_hz) * np.exp(-t / drop)
    if raw > 0.5 and rng.random() < 0.5:  # raw tails bend
        f = f * 2 ** (rng.uniform(-0.3, 0.3) * np.clip((t - 0.08) / 0.3, 0, 1))
    body = np.sin(2 * np.pi * np.cumsum(f) / SR)
    tail = rng.uniform(0.12, 0.25) + (1 - raw) * rng.uniform(0.0, 0.25)
    env = np.minimum(t / 0.0007, 1.0) * np.exp(-t / tail)
    x = body * env
    click = rng.standard_normal(length) * np.exp(-t / rng.uniform(0.001, 0.004))
    x += rng.uniform(0.1, 0.4) * signal.sosfilt(signal.butter(2, 2000, "hp", fs=SR, output="sos"), click)
    g = rng.uniform(3, 10) + raw * rng.uniform(5, 25)
    x = np.tanh(g * x) / math.tanh(g)
    if raw > 0.6 and rng.random() < 0.6:
        x = np.clip(x * rng.uniform(1.2, 2.0), -1, 1)
    x = signal.sosfilt(signal.butter(2, rng.uniform(5000, 12000), "lp", fs=SR, output="sos"), x)
    return x * np.exp(-np.maximum(t - 0.8 * length / SR, 0) / 0.01)  # never ring into the next kick


def reverse_bass(rng, length: int, root_hz: float) -> np.ndarray:
    t = np.arange(length) / SR
    ph = (np.cumsum(np.full(length, root_hz)) / SR) % 1.0
    x = 2 * ph - 1 + 0.5 * np.sin(2 * np.pi * ph)
    env = np.exp((t - t[-1]) / rng.uniform(0.05, 0.15)) * np.minimum((t[-1] - t) / 0.004, 1.0)
    g = rng.uniform(2, 8)
    x = np.tanh(g * x * env) / math.tanh(g)
    return signal.sosfilt(signal.butter(2, rng.uniform(800, 3000), "lp", fs=SR, output="sos"), x)


def noise_hit(rng, length: int, band: tuple[float, float], decay: float) -> np.ndarray:
    t = np.arange(length) / SR
    lo, hi = band
    sos = signal.butter(2, [lo, min(hi, SR / 2 * 0.95)], "bp", fs=SR, output="sos") if hi else \
        signal.butter(2, lo, "hp", fs=SR, output="sos")
    return signal.sosfilt(sos, rng.standard_normal(length)) * np.exp(-t / decay)


def render_drums(rng, mu: Music, total: int) -> tuple[np.ndarray, np.ndarray, dict]:
    """Stereo drums + bass (2, total), the kick envelope (total,) 0..1, and what was played."""
    beat = int(mu.beat * SR)
    raw = float(rng.uniform(0, 1))
    key_root = _hz(36 + mu.root)  # C2..B2
    while key_root > 75:
        key_root /= 2
    tuned_to_chords = rng.random() < 0.3
    kick_bars = np.ones(mu.bars, dtype=bool)
    b = int(rng.integers(0, 4))
    while b < mu.bars:  # breakdowns: 2-8 bars without a kick, now and then
        if rng.random() < 0.3:
            n = int(rng.choice([2, 4, 4, 8]))
            kick_bars[b:b + n] = False
            b += n
        b += int(rng.choice([4, 8]))
    rev = rng.random() < 0.5
    hats = rng.random() < 0.8
    claps = rng.random() < 0.5
    drums = np.zeros((2, total))
    kenv = np.zeros(total)
    kick_level = 1.0
    for i in range(int(math.ceil(total / beat))):
        start = i * beat
        bar = min(i // 4, mu.bars - 1)
        if start >= total:
            break
        n = min(beat, total - start)
        root = key_root
        if tuned_to_chords:
            root = _hz(mu.pitch(mu.chord_degree[bar], 36))
            while root > 75:
                root /= 2
        if kick_bars[bar]:
            k = kick(rng, n, root, raw) * kick_level
            drums[:, start:start + n] += k
            t = np.arange(n) / SR
            kenv[start:start + n] = np.maximum(kenv[start:start + n], np.exp(-t / 0.12))
            if rev:
                half = beat // 2
                s0 = start + half
                m = min(beat - half, total - s0)
                if m > 0:
                    drums[:, s0:s0 + m] += 0.6 * reverse_bass(rng, m, root * 2)
            if hats:
                s0 = start + beat // 2
                m = min(int(0.08 * SR), total - s0)
                if m > 0:
                    h = noise_hit(rng, m, (rng.uniform(6000, 9000), 0), rng.uniform(0.015, 0.05)) * rng.uniform(0.1, 0.3)
                    pan = rng.uniform(-0.3, 0.3)
                    drums[0, s0:s0 + m] += h * (1 - pan)
                    drums[1, s0:s0 + m] += h * (1 + pan)
            if claps and i % 4 in (1, 3):
                m = min(int(0.3 * SR), total - start)
                c = sum(np.r_[np.zeros(int(j * 0.008 * SR)), noise_hit(rng, m, (900, 2500), rng.uniform(0.08, 0.2))][:m]
                        for j in range(3)) * rng.uniform(0.15, 0.35)
                drums[:, start:start + m] += c
    info = {"kick": "raw" if raw > 0.6 else "euphoric" if raw < 0.3 else "mid", "reverse_bass": rev,
            "breakdown_bars": int((~kick_bars).sum()), "tuned_to_chords": tuned_to_chords}
    return drums.astype(np.float32), kenv.astype(np.float32), info


def sidechain(synth: np.ndarray, kenv: np.ndarray, depth: float) -> np.ndarray:
    k = 1.0 - math.exp(-1.0 / (0.004 * SR))  # a compressor's attack
    g = signal.lfilter([k], [1, k - 1], 1.0 - depth * kenv)
    return (synth * g).astype(np.float32)

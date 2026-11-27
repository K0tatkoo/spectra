"""Jungle and breakcore drums: a break, sped up and chopped (ownstems/render.py, drums lane).

A source break is either programmed (a classic pattern, an Amen-style, Think-style, two-step or funk
groove with ghost notes, played on one of the CC0 / CC BY drum kits with General MIDI keys) or a real
E-GMD performance. It is sped up to the target tempo (pitching it up, as jungle producers do with
records), then cut into sixteenths and re-sequenced: kept, swapped, stuttered, reversed, pitched,
dropped, with snare rolls into phrase ends. Breakcore gets more of all of it and faster stutters.
"""

from __future__ import annotations

import numpy as np

from . import RATE

GM = {"kick": 36, "snare": 38, "ghost": 38, "hat": 42, "open": 46, "ride": 51, "crash": 49}
# 16 steps a bar: which steps each part hits (two bars where the pattern needs them)
PATTERNS = {
    "amen": {"kick": [0, 2, 10, 11, 16, 18, 26], "snare": [4, 7, 9, 12, 15, 20, 23, 25, 28, 31],
             "ride": list(range(0, 32, 2))},
    "think": {"kick": [0, 6, 10, 16, 22, 26, 27], "snare": [4, 12, 20, 28, 30], "hat": list(range(0, 32))},
    "twostep": {"kick": [0, 10, 16, 26], "snare": [4, 12, 20, 28], "hat": list(range(0, 32, 2)), "open": [14, 30]},
    "funk": {"kick": [0, 3, 8, 10, 16, 19, 24, 27], "snare": [4, 12, 14, 20, 28], "hat": list(range(0, 32, 2))},
}


def kit_ok(inst) -> bool:
    """A kit the breaks can use: kick, snare and closed hat all sound."""
    for k in (36, 38, 42):
        y = inst.render([(0.01, 0.2, k, 110)], 0.6, np.random.default_rng(0))
        if float(np.sqrt(np.mean(y ** 2))) < 1e-3:
            return False
    return True


def programmed(rng, kit, bpm: float, bars: int = 2) -> np.ndarray:
    """A pattern played on the kit at `bpm` (mono), with ghost snares and velocity accents."""
    name = str(rng.choice(list(PATTERNS)))
    pat = PATTERNS[name]
    step = 60.0 / bpm / 4
    notes = []
    for b in range(bars // 2 + bars % 2):
        for part, steps in pat.items():
            for s in steps:
                t = (b * 32 + s) * step + float(rng.normal(0, 0.004))
                v = int(np.clip((110 if s % 4 == 0 else 90) + rng.normal(0, 10), 40, 127))
                notes.append((max(0.0, t), 0.15, GM[part], v))
        for s in range(32):  # ghost notes
            if rng.random() < 0.12:
                notes.append((((b * 32 + s) * step), 0.1, GM["ghost"], int(rng.uniform(25, 50))))
    seconds = bars * 16 * step + 0.5
    return kit.render(notes, seconds, rng).mean(0)[: int(bars * 16 * step * RATE)]


def speed(x: np.ndarray, ratio: float) -> np.ndarray:
    """Played `ratio` times faster (and higher), as a sampler does."""
    if abs(ratio - 1) < 1e-3:
        return x
    import soxr

    return soxr.resample(x.astype(np.float32), RATE * ratio, RATE, quality="MQ").astype(np.float32)


def chop(rng, brk: np.ndarray, bpm: float, seconds: float, wild: bool) -> np.ndarray:
    """Re-sequences sixteenth slices of a break at `bpm` into `seconds` of drums."""
    step = int(round(60.0 / bpm / 4 * RATE))
    n_sl = max(1, len(brk) // step)
    slices = [brk[i * step:(i + 1) * step] for i in range(n_sl)]
    out = np.zeros(int(seconds * RATE) + step, np.float32)
    p_swap, p_stut, p_rev, p_pitch, p_drop = (0.3, 0.15, 0.06, 0.08, 0.05) if wild else (0.12, 0.05, 0.02, 0.03, 0.02)
    pos, k = 0, 0
    fade = np.minimum(1, np.minimum(np.arange(step), np.arange(step)[::-1]) / 64.0).astype(np.float32)
    while pos < len(out) - step:
        src = k % n_sl
        if rng.random() < p_swap:
            src = int(rng.integers(0, n_sl))
        s = slices[src].copy()
        if len(s) < step:
            s = np.pad(s, (0, step - len(s)))
        r = rng.random()
        if r < p_drop:
            s[:] = 0
        elif r < p_drop + p_rev:
            s = s[::-1].copy()
        elif r < p_drop + p_rev + p_pitch:
            sp = speed(s, 2 ** (rng.uniform(-7, 7) / 12))
            s = np.pad(sp, (0, max(0, step - len(sp))))[:step]
        if rng.random() < p_stut:  # a stutter: the slice's start, repeated fast
            div = int(rng.choice([2, 4] if not wild else [2, 4, 8]))
            part = s[: step // div]
            s = np.tile(part, div)[:step]
        if k % 32 == 30 and rng.random() < (0.5 if wild else 0.25):  # a roll into the next phrase
            part = s[: step // 4]
            s = np.tile(part * np.linspace(0.6, 1.0, len(part)), 4)[:step]
        s = np.pad(s, (0, max(0, step - len(s))))[:step]  # tiled stutters and rolls may come out a sample short
        out[pos:pos + step] += s * fade
        pos += step
        k += 1
    return out[: int(seconds * RATE)]

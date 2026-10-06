"""Synth parts made up on the spot: random patches playing random, tonal music.

Slakh renders every synth with one of a few hundred patches, and the second
training run (2026-10-02) learned those patches rather than "synth": it scored
well on songs it knew and called other instruments synth in new ones. This
makes as many new patches as asked for, so "synth" has to be learned from
what synths share — oscillators that are band-limited saws, pulses, stacks
and wavetables, a resonant filter that moves, envelopes, glide, and the
effects synths are seldom heard without.

- Oscillators: saw and pulse (PolyBLEP, with PWM), triangle, sine, two-operator
  FM, random morphing wavetables (additive, never above Nyquist), unison
  stacks up to a nine-voice supersaw, noise.
- Shaping: amp ADSR; a 12 or 24 dB/oct low-, band- or high-pass whose cutoff
  follows its own envelope, the key, an LFO and slow build-ups; vibrato,
  glide, pitch drops and zaps.
- Effects: drive (soft, hard, asymmetric, folding), bit crushing, chorus,
  tempo-synced (ping-pong) delay, reverb, sidechain pumping, trance gate.
- Music: a tempo, key and scale; a chord progression; leads (repeating
  motifs), pads, arpeggios, stabs and supersaw chords over it, coming and
  going in sections so a song also has stretches with no synth.

Only leads and pads, as the splitter's target defines synth: nothing below
G2, so synth bass never comes up. Everything is drawn from the `rng` given, so
a seed makes the same part every time.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy import signal

SR = 44100
NYQ = SR / 2
SCALES = {
    "major": (0, 2, 4, 5, 7, 9, 11), "minor": (0, 2, 3, 5, 7, 8, 10), "dorian": (0, 2, 3, 5, 7, 9, 10),
    "phrygian": (0, 1, 3, 5, 7, 8, 10), "harmonic_minor": (0, 2, 3, 5, 7, 8, 11),
    "mixolydian": (0, 2, 4, 5, 7, 9, 10), "pent_minor": (0, 3, 5, 7, 10), "pent_major": (0, 2, 4, 7, 9),
}
LOWEST = 43  # G2: below this a synth is bass, which the target leaves out
KINDS = ("lead", "pad", "arp", "stab", "supersaw")
KIND_P = (0.3, 0.2, 0.2, 0.12, 0.18)


@dataclass
class Note:
    start: float  # seconds
    dur: float    # gate length, seconds
    midi: float
    vel: float = 1.0
    legato: bool = False  # tied to the previous note: glide, no retrigger


# -- the music -----------------------------------------------------------------


class Music:
    """Tempo, key, scale, a chord per bar, and which bars each part plays in."""

    def __init__(self, rng: np.random.Generator, seconds: float):
        self.bpm = float(rng.choice([rng.uniform(70, 110), rng.uniform(110, 135), rng.uniform(135, 160), rng.uniform(140, 180)],
                                    p=[0.2, 0.35, 0.3, 0.15]))
        self.beat = 60.0 / self.bpm
        self.bar = 4 * self.beat
        self.step = self.beat / 4  # a sixteenth
        self.root = int(rng.integers(12))
        self.scale_name = str(rng.choice(list(SCALES)))
        self.scale = SCALES[self.scale_name]
        self.seconds = seconds
        self.bars = int(math.ceil(seconds / self.bar)) + 1
        n = len(self.scale)
        length = int(rng.choice([2, 4, 4, 4, 8]))
        prog = [0] + [int(rng.integers(n)) for _ in range(length - 1)]
        per = int(rng.choice([1, 1, 2]))
        self.chord_degree = [prog[(b // per) % length] for b in range(self.bars)]
        self.chord_bars = per

    def pitch(self, degree: int, base: int) -> int:
        """Scale degree (any integer) above the octave of `base` -> MIDI note."""
        n = len(self.scale)
        octave, d = divmod(degree, n)
        return base - base % 12 + self.root + self.scale[d] + 12 * octave

    def chord(self, bar: int, size: int) -> list[int]:
        """Scale degrees of the bar's chord: stacked thirds, `size` of them."""
        d = self.chord_degree[bar % self.bars]
        return [d + 2 * i for i in range(size)]

    def sections(self, rng) -> np.ndarray:
        """Bars a part plays in: on and off in blocks of 2–8 bars."""
        on = np.zeros(self.bars, dtype=bool)
        b = 0
        state = rng.random() < 0.75
        while b < self.bars:
            n = int(rng.choice([2, 4, 4, 8, 8]))
            on[b:b + n] = state
            b += n
            state = rng.random() < (0.75 if not state else 0.7)
        if not on.any():
            on[: max(2, self.bars // 2)] = True
        return on


def _fit(m: int, lo: int, hi: int) -> int:
    while m < lo:
        m += 12
    while m > hi:
        m -= 12
    return m


def lead_notes(rng, mu: Music, active: np.ndarray, centre: int) -> list[Note]:
    """A repeating one- or two-bar motif, transposed with the chords."""
    bars = int(rng.choice([1, 2, 2]))
    steps = 16 * bars
    lengths = (1, 2, 2, 2, 3, 4, 4, 6, 8)
    motif = []
    pos, deg = 0, int(rng.integers(-2, 5))
    while pos < steps:
        n = min(int(rng.choice(lengths)), steps - pos)
        if rng.random() > 0.15 or not motif:
            motif.append((pos, n, deg))
        pos += n
        deg += int(rng.choice([-2, -1, -1, 0, 1, 1, 2, 3, -3, 4, -4]))
        deg = int(np.clip(deg, -5, 9))
    glide = rng.random() < 0.45
    notes: list[Note] = []
    for b0 in range(0, mu.bars, bars):
        if not active[b0]:
            continue
        shift = mu.chord_degree[b0] if rng.random() < 0.5 else 0
        vary = rng.random() < 0.25
        for p, n, d in motif:
            t = b0 * mu.bar + p * mu.step
            if t >= mu.seconds:
                break
            dd = d + shift + (int(rng.choice([-1, 1, 2])) if vary and rng.random() < 0.3 else 0)
            m = _fit(mu.pitch(dd, centre), max(LOWEST + 5, centre - 12), centre + 12)
            gate = n * mu.step * (rng.uniform(0.9, 1.05) if glide else rng.uniform(0.5, 0.95))
            legato = glide and bool(notes) and abs(notes[-1].start + notes[-1].dur - t) < 0.02
            notes.append(Note(t, gate, m, rng.uniform(0.75, 1.0), legato))
    return notes


def pad_notes(rng, mu: Music, active: np.ndarray, centre: int) -> list[Note]:
    size = int(rng.choice([3, 3, 4, 4, 5]))
    notes = []
    for b in range(0, mu.bars, mu.chord_bars):
        if not active[b]:
            continue
        t = b * mu.bar
        dur = mu.chord_bars * mu.bar * rng.uniform(0.95, 1.02)
        for d in mu.chord(b, size):
            notes.append(Note(t, dur, _fit(mu.pitch(d, centre), max(LOWEST, centre - 9), centre + 12), rng.uniform(0.7, 1.0)))
        if rng.random() < 0.3:
            notes.append(Note(t, dur, max(LOWEST, _fit(mu.pitch(mu.chord_degree[b], centre - 12), centre - 17, centre - 6)), 0.8))
    return notes


def arp_notes(rng, mu: Music, active: np.ndarray, centre: int) -> list[Note]:
    rate = float(rng.choice([1, 2, 2, 4 / 3]))  # sixteenths per note: 16ths, 8ths, 8th triplets
    span = int(rng.choice([1, 2, 2, 3]))
    order = str(rng.choice(["up", "down", "updown", "random"]))
    gate = rng.uniform(0.25, 0.95)
    size = int(rng.choice([3, 4]))
    notes = []
    i = 0
    t = 0.0
    step = rate * mu.step
    while t < mu.seconds:
        b = int(t / mu.bar)
        if active[min(b, mu.bars - 1)]:
            tones = [_fit(mu.pitch(d, centre), max(LOWEST + 5, centre - 12), centre + 12) for d in mu.chord(b, size)]
            tones = sorted(set(tones))
            seq = [m + 12 * o for o in range(span) for m in tones]
            if order == "down":
                seq = seq[::-1]
            elif order == "updown":
                seq = seq + seq[-2:0:-1]
            m = seq[int(rng.integers(len(seq)))] if order == "random" else seq[i % len(seq)]
            notes.append(Note(t, step * gate, m, rng.uniform(0.7, 1.0)))
        i += 1
        t += step
    return notes


def stab_notes(rng, mu: Music, active: np.ndarray, centre: int) -> list[Note]:
    pattern = [s for s in range(16) if rng.random() < 0.3] or [2, 6, 10, 14]
    if rng.random() < 0.4:
        pattern = [2, 6, 10, 14]  # the offbeats
    gate = rng.uniform(0.5, 2.0) * mu.step
    size = int(rng.choice([3, 4]))
    notes = []
    for b in range(mu.bars):
        if not active[b]:
            continue
        for s in pattern:
            t = b * mu.bar + s * mu.step
            if t >= mu.seconds:
                break
            for d in mu.chord(b, size):
                notes.append(Note(t, gate, _fit(mu.pitch(d, centre), max(LOWEST + 5, centre - 7), centre + 9), rng.uniform(0.8, 1.0)))
    return notes


def supersaw_notes(rng, mu: Music, active: np.ndarray, centre: int) -> list[Note]:
    """Big chords: held, or hit on every beat / offbeat (trance, hardstyle)."""
    if rng.random() < 0.4:
        return pad_notes(rng, mu, active, centre)
    hits = [0, 4, 8, 12] if rng.random() < 0.5 else [2, 6, 10, 14]
    if rng.random() < 0.3:
        hits = [s for s in range(0, 16, 2)]
    gate = rng.uniform(1.0, 3.5) * mu.step
    notes = []
    for b in range(mu.bars):
        if not active[b]:
            continue
        for s in hits:
            t = b * mu.bar + s * mu.step
            if t >= mu.seconds:
                break
            for d in mu.chord(b, 3):
                notes.append(Note(t, gate, _fit(mu.pitch(d, centre), max(LOWEST + 5, centre - 7), centre + 9), 1.0))
    return notes


NOTES = {"lead": lead_notes, "pad": pad_notes, "arp": arp_notes, "stab": stab_notes, "supersaw": supersaw_notes}


# -- oscillators -------------------------------------------------------------------


def _phase(f: np.ndarray, phase0: float) -> np.ndarray:
    return (phase0 + np.cumsum(f / SR)) % 1.0


def _blep(t: np.ndarray, dt: np.ndarray) -> np.ndarray:
    out = np.zeros_like(t)
    m = t < dt
    x = t[m] / dt[m]
    out[m] = x + x - x * x - 1.0
    m = t > 1.0 - dt
    x = (t[m] - 1.0) / dt[m]
    out[m] = x * x + x + x + 1.0
    return out


def osc_saw(f, phase0, **_):
    t = _phase(f, phase0)
    dt = np.minimum(f / SR, 0.49)
    return 2.0 * t - 1.0 - _blep(t, dt)


def osc_pulse(f, phase0, pw=0.5, **_):
    t = _phase(f, phase0)
    dt = np.minimum(f / SR, 0.49)
    t2 = (t + pw) % 1.0
    return (2.0 * t - 1.0 - _blep(t, dt)) - (2.0 * t2 - 1.0 - _blep(t2, dt))


def osc_tri(f, phase0, **_):
    t = _phase(f, phase0)
    return 4.0 * np.abs(t - 0.5) - 1.0  # harmonics fall as 1/k²: aliasing stays far down


def osc_sine(f, phase0, **_):
    return np.sin(2 * np.pi * _phase(f, phase0))


def osc_fm(f, phase0, ratio=2.0, index=None, **_):
    t = _phase(f, phase0)
    tm = _phase(f * ratio, 0.0)
    # Keep the sidebands that matter below Nyquist: (index + 1) * modulator < Nyquist.
    idx = np.minimum(index, np.maximum(NYQ / (ratio * f) - 1.0, 0.0))
    return np.sin(2 * np.pi * t + idx * np.sin(2 * np.pi * tm))


def osc_table(f, phase0, tables=None, morph=None, **_):
    """Additive: tables (2, K) harmonic amplitudes, morph (N,) 0..1 between them."""
    t = _phase(f, phase0)
    k_max = tables.shape[1]
    a = np.zeros_like(t)
    b = np.zeros_like(t)
    for k in range(1, k_max + 1):
        ok = (k * f) < NYQ * 0.95
        if not ok.any():
            break
        s = np.sin(2 * np.pi * ((k * t) % 1.0)) * ok
        a += tables[0, k - 1] * s
        b += tables[1, k - 1] * s
    return a + morph * (b - a)


def random_table(rng, k: int = 24) -> np.ndarray:
    ks = np.arange(1, k + 1)
    amp = ks ** -rng.uniform(0.3, 1.6)
    if rng.random() < 0.4:
        amp[1::2] *= rng.uniform(0.0, 0.4)  # hollow, square-ish
    for _ in range(int(rng.integers(0, 3))):  # formant-like bumps
        c, w = rng.uniform(2, k), rng.uniform(1, 5)
        amp *= 1 + rng.uniform(0.5, 4) * np.exp(-0.5 * ((ks - c) / w) ** 2)
    amp *= rng.uniform(0.6, 1.0, k)
    return amp / np.abs(amp).sum() * 1.5


OSCS = {"saw": osc_saw, "pulse": osc_pulse, "tri": osc_tri, "sine": osc_sine, "fm": osc_fm, "table": osc_table}


# -- a patch -------------------------------------------------------------------------


@dataclass
class Osc:
    kind: str
    level: float
    octave: int = 0
    detune: float = 0.0  # cents
    pw: float = 0.5
    pwm: float = 0.0     # PWM depth
    ratio: float = 2.0   # FM
    index: float = 2.0
    index_decay: float = 0.0
    tables: np.ndarray | None = None


@dataclass
class Patch:
    kind: str
    oscs: list[Osc]
    unison: int
    spread: float      # cents, outermost voices
    width: float       # 0 mono .. 1 hard-panned outermost voices
    noise: float
    adsr: tuple[float, float, float, float]
    filt: str          # "lp", "bp", "hp", "none"
    poles: int
    cutoff: float      # Hz
    res: float         # Q
    fenv: float        # octaves
    fadsr: tuple[float, float, float, float]
    keytrack: float
    flfo: tuple[float, float]  # rate Hz, depth octaves
    vibrato: tuple[float, float, float]  # rate Hz, depth cents, delay s
    glide: float       # seconds
    pitch_env: tuple[float, float]  # semitones at the attack, decay time
    mono: bool
    centre: int
    fx: dict = field(default_factory=dict)

    def describe(self) -> str:
        o = "+".join(f"{x.kind}{'' if x.octave == 0 else f'{x.octave:+d}'}" for x in self.oscs)
        fx = ",".join(k for k, v in self.fx.items() if v)
        return f"{self.kind}:{o}x{self.unison}/{self.filt}{self.poles * 12 if self.filt != 'none' else ''}/{fx}"


def random_patch(rng, kind: str) -> Patch:
    u = rng.uniform
    choice = lambda xs, p=None: xs[int(rng.choice(len(xs), p=p))]  # noqa: E731

    def osc(level, kinds=("saw", "pulse", "tri", "sine", "fm", "table"), p=(0.34, 0.2, 0.08, 0.06, 0.14, 0.18)):
        k = choice(kinds, p)
        o = Osc(k, level)
        if k == "pulse":
            o.pw, o.pwm = u(0.1, 0.5), (u(0.05, 0.35) if rng.random() < 0.5 else 0.0)
        if k == "fm":
            o.ratio = float(choice([0.5, 1, 1, 2, 2, 3, 4, 1.5, 3.5, 7]) + (u(-0.01, 0.01) if rng.random() < 0.3 else 0))
            o.index, o.index_decay = u(0.3, 5.0), (u(0.05, 1.0) if rng.random() < 0.6 else 0.0)
        if k == "table":
            o.tables = np.stack([random_table(rng), random_table(rng)])
        return o

    if kind == "supersaw":
        oscs = [Osc("saw", 1.0)] + ([Osc("saw", u(0.3, 0.7), octave=1)] if rng.random() < 0.4 else [])
        unison, spread, width = int(rng.integers(5, 10)), u(15, 50), u(0.6, 1.0)
    else:
        oscs = [osc(1.0)]
        if rng.random() < 0.55:
            second = osc(u(0.3, 0.9), ("saw", "pulse", "tri", "sine", "table"), (0.4, 0.25, 0.1, 0.1, 0.15))
            second.octave = int(choice([0, 0, 1, -1, 2] if kind != "pad" else [0, 1, -1]))
            second.detune = u(-15, 15)
            if second.octave < 0 and kind in ("lead", "arp", "stab"):
                second.octave = 0
            oscs.append(second)
        unison = int(choice([1, 1, 2, 3, 5, 7])) if oscs[0].kind != "table" else int(choice([1, 1, 2, 3]))
        spread, width = u(5, 35), u(0.0, 1.0)

    if kind == "pad":
        adsr = (u(0.05, 1.5), u(0.2, 2.0), u(0.5, 1.0), u(0.3, 2.5))
        centre = int(rng.integers(55, 68))
    elif kind == "lead":
        adsr = (u(0.001, 0.08), u(0.05, 0.6), u(0.4, 1.0), u(0.02, 0.4))
        centre = int(rng.integers(64, 82))
    elif kind == "arp":
        adsr = (u(0.001, 0.01), u(0.05, 0.4), u(0.0, 0.6), u(0.03, 0.3))
        centre = int(rng.integers(60, 80))
    elif kind == "stab":
        adsr = (u(0.001, 0.01), u(0.05, 0.3), u(0.0, 0.7), u(0.03, 0.25))
        centre = int(rng.integers(60, 74))
    else:  # supersaw
        adsr = (u(0.001, 0.05), u(0.1, 1.0), u(0.6, 1.0), u(0.05, 0.6))
        centre = int(rng.integers(60, 74))

    filt = choice(["lp", "lp", "lp", "lp", "bp", "hp", "none"])
    fx = {
        "drive": choice(["", "soft", "hard", "asym", "fold"], (0.6, 0.2, 0.08, 0.08, 0.04)) if kind != "pad" else choice(["", "soft"], (0.85, 0.15)),
        "crush": rng.random() < 0.04,
        "chorus": rng.random() < (0.45 if kind == "pad" else 0.25),
        "delay": rng.random() < (0.45 if kind in ("lead", "arp") else 0.2),
        "reverb": rng.random() < 0.7,
        "pump": rng.random() < (0.35 if kind in ("pad", "supersaw") else 0.15),
        "gate": rng.random() < (0.12 if kind == "pad" else 0.0),
        "highpass": rng.random() < 0.6,
        "buildup": rng.random() < 0.15,
    }
    if kind == "lead" and rng.random() < 0.1:
        fx["drive"] = "screech"
    return Patch(
        kind=kind, oscs=oscs, unison=unison, spread=spread, width=width,
        noise=u(0.02, 0.3) if rng.random() < 0.15 else 0.0,
        adsr=adsr, filt=filt, poles=int(choice([1, 2])), cutoff=float(np.exp(u(np.log(300), np.log(8000)))),
        res=u(0.5, 6.0) if rng.random() < 0.6 else 0.707,
        fenv=u(0.5, 5.0) if rng.random() < 0.7 else 0.0,
        fadsr=(u(0.001, 0.4), u(0.05, 1.5), u(0.0, 0.8), u(0.05, 1.0)),
        keytrack=u(0.0, 1.0),
        flfo=(u(0.1, 8.0), u(0.2, 2.0)) if rng.random() < 0.2 else (0.0, 0.0),
        vibrato=(u(4.0, 7.0), u(5, 40), u(0.0, 0.4)) if rng.random() < (0.45 if kind == "lead" else 0.1) else (0, 0, 0),
        glide=u(0.02, 0.2),
        pitch_env=(float(choice([-12, -7, 12, 24, -24, 5])), u(0.01, 0.15)) if rng.random() < (0.15 if kind in ("lead", "arp", "stab") else 0.0) else (0.0, 0.0),
        mono=kind == "lead",
        centre=centre,
        fx=fx,
    )


# -- rendering -----------------------------------------------------------------------


def adsr_curve(n_gate: int, n_total: int, a: float, d: float, s: float, r: float) -> np.ndarray:
    t = np.arange(n_total) / SR
    env = np.empty(n_total)
    attack = t < a
    env[attack] = t[attack] / max(a, 1.0 / SR)
    rest = ~attack
    env[rest] = s + (1 - s) * np.exp(-(t[rest] - a) / max(d / 4.6, 1e-3))
    gate_end = min(n_gate, n_total)
    level = env[gate_end - 1] if gate_end > 0 else 0.0  # released mid-attack: from wherever it got to
    tail = np.arange(n_total - gate_end) / SR
    env[gate_end:] = level * np.exp(-tail / max(r / 4.6, 1e-3))
    return env


def _voices(p: Patch):
    """(detune cents, pan -1..1) of each unison voice."""
    if p.unison == 1:
        return [(0.0, 0.0)]
    x = np.linspace(-1.0, 1.0, p.unison)
    return [(float(v * p.spread), float(v * p.width)) for v in x]


def render_tone(rng, p: Patch, f: np.ndarray, n_since_start: np.ndarray | None = None) -> np.ndarray:
    """Oscillators and unison for a frequency curve f (N,) -> stereo (2, N)."""
    n = f.shape[0]
    out = np.zeros((2, n))
    t = (n_since_start if n_since_start is not None else np.arange(n)) / SR
    for o in p.oscs:
        base = f * 2.0 ** o.octave * 2.0 ** (o.detune / 1200)
        kw = {}
        if o.kind == "pulse":
            kw["pw"] = np.clip(o.pw + o.pwm * np.sin(2 * np.pi * rng.uniform(0.1, 2.0) * t), 0.05, 0.95) if o.pwm else o.pw
        if o.kind == "fm":
            kw["ratio"] = o.ratio
            kw["index"] = o.index * (np.exp(-t / o.index_decay) if o.index_decay else np.ones(n))
        if o.kind == "table":
            kw["tables"] = o.tables
            rate = rng.uniform(0.05, 1.0)
            kw["morph"] = 0.5 + 0.5 * np.sin(2 * np.pi * rate * t + rng.uniform(0, 6.3)) if rng.random() < 0.7 else \
                np.minimum(t / rng.uniform(0.2, 3.0), 1.0)
        for det, pan in _voices(p):
            w = OSCS[o.kind](base * 2.0 ** (det / 1200), rng.random(), **kw) * o.level
            gl, gr = math.cos((pan + 1) * math.pi / 4), math.sin((pan + 1) * math.pi / 4)
            out[0] += gl * w
            out[1] += gr * w
    if p.noise:
        out += p.noise * rng.standard_normal((2, n)) * 0.5
    return out / math.sqrt(p.unison * len(p.oscs))


def _pitch_mods(p: Patch, since: np.ndarray, t: np.ndarray) -> np.ndarray:
    """Semitones added to the pitch: vibrato that fades in after each attack, and the pitch envelope.
    since: seconds since the voice last (re)triggered; t: seconds since the song began."""
    semis = np.zeros_like(since)
    rate, depth, delay = p.vibrato
    if depth:
        semis += depth / 100 * np.clip((since - delay) / 0.2, 0, 1) * np.sin(2 * np.pi * rate * t)
    amt, decay = p.pitch_env
    if amt:
        semis += amt * np.exp(-since / decay)
    return semis


def render_notes(rng, p: Patch, notes: list[Note], total: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """The dry part (2, total), its filter envelope (total,) and the key it plays (total,) for key tracking."""
    out = np.zeros((2, total))
    fenv = np.zeros(total)
    keys = np.full(total, float(p.centre))
    a, d, s, r = p.adsr
    fa, fd, fs, fr = p.fadsr
    if p.mono:
        # One voice: the pitch glides into tied notes; the others retrigger the envelopes.
        target = np.full(total, float(notes[0].midi))
        since = np.zeros(total)
        amp = np.zeros(total)
        for i, nt in enumerate(notes):
            s0 = int(nt.start * SR)
            if s0 >= total:
                break
            nxt = min(int(notes[i + 1].start * SR), total) if i + 1 < len(notes) else total
            target[s0:] = nt.midi
            gate = int(nt.dur * SR)
            length = min(total - s0, gate + int(r * SR) + 1)
            if nt.legato:
                held = np.full(length, s * nt.vel)
                held[min(gate, length):] = s * nt.vel * np.exp(-np.arange(length - min(gate, length)) / SR / max(r / 4.6, 1e-3))
                amp[s0:s0 + length] = np.maximum(amp[s0:s0 + length], held)
                since[s0:nxt] = since[s0 - 1] + np.arange(1, nxt - s0 + 1) / SR if s0 > 0 else np.arange(nxt - s0) / SR
            else:
                amp[s0:s0 + length] = np.maximum(amp[s0:s0 + length], adsr_curve(gate, length, a, d, s, r) * nt.vel)
                fenv[s0:s0 + length] = np.maximum(fenv[s0:s0 + length], adsr_curve(gate, length, fa, fd, fs, fr))
                since[s0:] = np.arange(total - s0) / SR
        # Portamento: a one-pole slide in pitch, then vibrato and pitch envelope on top.
        k = 1.0 - math.exp(-1.0 / (p.glide * SR))
        midi = signal.lfilter([k], [1, k - 1], target, zi=[target[0] * (1 - k)])[0]
        keys = midi
        midi = midi + _pitch_mods(p, since, np.arange(total) / SR)
        out = render_tone(rng, p, 440.0 * 2.0 ** ((midi - 69) / 12)) * amp
        return out, fenv, keys
    for nt in notes:
        s0 = int(nt.start * SR)
        if s0 >= total:
            continue
        gate = int(nt.dur * SR)
        length = min(total - s0, gate + int(r * SR) + 1)
        since = np.arange(length) / SR
        midi = nt.midi + _pitch_mods(p, since, since + nt.start)
        env = adsr_curve(gate, length, a, d, s, r) * nt.vel
        out[:, s0:s0 + length] += render_tone(rng, p, 440.0 * 2.0 ** ((midi - 69) / 12)) * env
        fenv[s0:s0 + length] = np.maximum(fenv[s0:s0 + length], adsr_curve(gate, length, fa, fd, fs, fr))
        keys[s0:s0 + length] = nt.midi
    return out, fenv, keys


def _biquad(kind: str, fc: float, q: float) -> np.ndarray:
    w = 2 * math.pi * min(fc, NYQ * 0.9) / SR
    alpha = math.sin(w) / (2 * q)
    c = math.cos(w)
    if kind == "lp":
        b = [(1 - c) / 2, 1 - c, (1 - c) / 2]
    elif kind == "hp":
        b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
    else:  # band-pass, 0 dB peak
        b = [alpha, 0.0, -alpha]
    a = [1 + alpha, -2 * c, 1 - alpha]
    return np.array([b[0] / a[0], b[1] / a[0], b[2] / a[0], 1.0, a[1] / a[0], a[2] / a[0]])


def moving_filter(x: np.ndarray, kind: str, cutoff: np.ndarray, q: float, poles: int, block: int = 64) -> np.ndarray:
    """A resonant filter whose cutoff (Hz, per sample) moves: coefficients per block, state carried."""
    n = x.shape[-1]
    out = np.empty_like(x)
    zi = np.zeros((poles, x.shape[0], 2))
    for i in range(0, n, block):
        fc = float(cutoff[min(i + block // 2, n - 1)])
        sos = np.stack([_biquad(kind, fc, q if j == poles - 1 else 0.707) for j in range(poles)])
        out[:, i:i + block], zi = signal.sosfilt(sos, x[:, i:i + block], axis=-1, zi=zi)
    return out


def drive(x: np.ndarray, kind: str, rng) -> np.ndarray:
    peak = np.max(np.abs(x)) + 1e-9
    x = x / peak
    g = rng.uniform(1.5, 12.0) if kind != "screech" else rng.uniform(10, 40)
    if kind in ("soft", "screech"):
        y = np.tanh(g * x)
    elif kind == "hard":
        y = np.clip(g * x, -1, 1)
    elif kind == "asym":
        bias = rng.uniform(0.1, 0.5)
        y = np.tanh(g * x + bias) - math.tanh(bias)
    else:  # fold
        y = np.sin(rng.uniform(1.5, 5.0) * x * math.pi / 2)
    return y * peak


def chorus(x: np.ndarray, rng) -> np.ndarray:
    n = x.shape[-1]
    idx = np.arange(n, dtype=np.float64)
    out = x.copy()
    mix = rng.uniform(0.3, 0.7)
    for ch in range(2):
        for v in range(int(rng.integers(1, 4))):
            base = rng.uniform(0.007, 0.025) * SR
            depth = rng.uniform(0.001, 0.005) * SR
            lfo = np.sin(2 * np.pi * rng.uniform(0.1, 2.0) * idx / SR + rng.uniform(0, 6.3) + ch * math.pi / 2)
            out[ch] += mix * np.interp(idx - base - depth * lfo, idx, x[ch], left=0.0)
    return out / (1 + mix)


def delay(x: np.ndarray, mu: Music, rng) -> np.ndarray:
    d = int(float(rng.choice([0.5, 0.75, 1.0, 0.375, 0.25])) * mu.beat * SR)
    fb = rng.uniform(0.2, 0.6)
    pingpong = rng.random() < 0.5
    wet = np.zeros_like(x)
    g = 1.0
    for k in range(1, 12):
        g *= fb
        if g < 0.02 or k * d >= x.shape[-1]:
            break
        tap = np.zeros_like(x)
        tap[:, k * d:] = x[:, :-k * d]
        if pingpong and k % 2:
            tap = tap[::-1]
        wet += g * tap
    k = 1.0 - math.exp(-2 * math.pi * rng.uniform(2000, 8000) / SR)
    wet = signal.lfilter([k], [1, k - 1], wet, axis=-1)
    return x + rng.uniform(0.2, 0.6) * wet


def reverb(x: np.ndarray, rng) -> np.ndarray:
    t60 = rng.uniform(0.4, 4.0)
    n = int(min(t60, 3.0) * SR)
    t = np.arange(n) / SR
    ir = rng.standard_normal((2, n)) * np.exp(-6.9 * t / t60)
    k = 1.0 - math.exp(-2 * math.pi * rng.uniform(2000, 10000) / SR)
    ir = signal.lfilter([k], [1, k - 1], ir, axis=-1)
    pre = int(rng.uniform(0.0, 0.04) * SR)
    ir = np.concatenate([np.zeros((2, pre)), ir], axis=1)
    ir /= np.sqrt(np.sum(ir ** 2, axis=1, keepdims=True)) + 1e-9
    wet = np.stack([signal.oaconvolve(x[c], ir[c])[: x.shape[-1]] for c in range(2)])
    return x + rng.uniform(0.15, 0.6) * wet


def pump(x: np.ndarray, mu: Music, rng) -> np.ndarray:
    t = np.arange(x.shape[-1]) / SR
    ph = (t % mu.beat) / mu.beat
    rel = rng.uniform(0.3, 0.8)
    depth = rng.uniform(0.4, 0.95)
    g = 1 - depth * np.clip(1 - ph / rel, 0, 1) ** 2
    k = 1.0 - math.exp(-1.0 / (rng.uniform(0.002, 0.01) * SR))  # a compressor's attack, not a click
    return x * signal.lfilter([k], [1, k - 1], g, zi=[g[0] * (1 - k)])[0]


def trance_gate(x: np.ndarray, mu: Music, rng) -> np.ndarray:
    t = np.arange(x.shape[-1]) / SR
    pattern = rng.random(16) < 0.6
    step = ((t / mu.step) % 16).astype(int)
    g = pattern[step].astype(np.float64)
    k = 1.0 - math.exp(-1.0 / (0.003 * SR))
    g = signal.lfilter([k], [1, k - 1], g)
    return x * g


def render_part(rng, mu: Music, kind: str, total: int) -> tuple[np.ndarray, str]:
    """One synth part over the whole song, stereo (2, total), RMS 1 where it plays."""
    p = random_patch(rng, kind)
    notes = NOTES[kind](rng, mu, mu.sections(rng), p.centre)
    if not notes:
        return np.zeros((2, total)), p.describe()
    x, fenv, keys = render_notes(rng, p, notes, total)
    if p.filt != "none":
        t = np.arange(total) / SR
        octs = p.fenv * fenv + p.keytrack * (keys - p.centre) / 12
        if p.flfo[1]:
            octs = octs + p.flfo[1] * np.sin(2 * np.pi * p.flfo[0] * t)
        if p.fx.get("buildup"):
            span = rng.uniform(4, 16) * mu.bar
            octs = octs - rng.uniform(1.5, 4) * (1 - np.clip((t % span) / span, 0, 1))
        cutoff = np.clip(p.cutoff * 2.0 ** octs, 40.0, NYQ * 0.9)
        if p.filt == "hp":
            cutoff = np.clip(cutoff * 0.3, 40.0, 4000.0)
        x = moving_filter(x, p.filt, cutoff, p.res, p.poles)
    fx = p.fx
    if fx.get("drive"):
        x = drive(x, fx["drive"], rng)
        if fx["drive"] == "screech":
            x = moving_filter(x, "bp", np.full(total, rng.uniform(800, 3000)), rng.uniform(1, 4), 1) * 2 + 0.3 * x
    if fx.get("crush"):
        bits = rng.uniform(4, 10)
        hold = int(rng.integers(1, 6))
        peak = np.max(np.abs(x)) + 1e-9
        x = np.round(x / peak * 2 ** bits) / 2 ** bits * peak
        x = np.repeat(x[:, ::hold], hold, axis=1)[:, :total]
    if fx.get("highpass"):
        x = signal.sosfilt(signal.butter(2, rng.uniform(80, 350), "hp", fs=SR, output="sos"), x, axis=-1)
    if fx.get("chorus"):
        x = chorus(x, rng)
    if fx.get("gate"):
        x = trance_gate(x, mu, rng)
    if fx.get("pump"):
        x = pump(x, mu, rng)
    if fx.get("delay"):
        x = delay(x, mu, rng)
    if fx.get("reverb"):
        x = reverb(x, rng)
    x = np.nan_to_num(x)
    loud = np.sqrt(np.mean(np.square(x), axis=0))
    playing = loud > 1e-4 * (loud.max() + 1e-12)
    rms = float(np.sqrt(np.mean(np.square(x[:, playing])))) if playing.any() else 0.0
    return (x / rms if rms > 0 else x), p.describe()


def render_synths(rng, seconds: float) -> tuple[np.ndarray, dict]:
    """1–3 synth parts over one piece of music -> stereo float32 (2, N), peak ≤ 1, and what was played."""
    mu = Music(rng, seconds)
    total = int(seconds * SR)
    count = int(rng.choice([1, 2, 3], p=[0.55, 0.35, 0.1]))
    kinds = [str(k) for k in rng.choice(KINDS, size=count, p=KIND_P)]
    out = np.zeros((2, total))
    parts = []
    for k in kinds:
        x, desc = render_part(rng, mu, k, total)
        out += x * 10 ** (rng.uniform(-8, 0) / 20)
        parts.append(desc)
    peak = float(np.max(np.abs(out)))
    if peak > 0:
        out *= 0.9 / peak
    info = {"bpm": round(mu.bpm, 1), "key": int(mu.root), "scale": mu.scale_name, "parts": parts}
    return out.astype(np.float32), info

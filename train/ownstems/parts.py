"""Made-up instrument parts: the notes a player of one instrument might play in jazz, pop, rock, metal
or breakcore, for the renderer to play on a sampled instrument (ownstems/render.py). Classical parts
come from Mutopia's scores instead (ownstems/midi.py).

A part is a list of (start_s, dur_s, pitch, velocity). It follows one chord progression in one key at
one tempo, with the genre's feel (swing, pushes, palm mutes), the role's way of playing (comping,
lines, held chords, strums, riffs, arpeggios), human timing and dynamics, phrases with breaths, and now
and then a few bars of rest, as a real part has.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

GENRES = {
    "jazz": {"bpm": (90, 240), "swing": (0.58, 0.68), "jitter": 0.012, "modes": ("major", "minor", "dorian")},
    "pop": {"bpm": (75, 130), "swing": (0.5, 0.54), "jitter": 0.008, "modes": ("major", "major", "minor")},
    "rock": {"bpm": (95, 170), "swing": (0.5, 0.52), "jitter": 0.01, "modes": ("major", "minor", "mixolydian")},
    "metal": {"bpm": (90, 210), "swing": (0.5, 0.5), "jitter": 0.004, "modes": ("minor", "phrygian", "harmonic")},
    "breakcore": {"bpm": (160, 200), "swing": (0.5, 0.5), "jitter": 0.003, "modes": ("minor", "minor", "dorian")},
}
SCALES = {
    "major": (0, 2, 4, 5, 7, 9, 11), "minor": (0, 2, 3, 5, 7, 8, 10), "dorian": (0, 2, 3, 5, 7, 9, 10),
    "mixolydian": (0, 2, 4, 5, 7, 9, 10), "phrygian": (0, 1, 3, 5, 7, 8, 10), "harmonic": (0, 2, 3, 5, 7, 8, 11),
}
# Progressions as scale degrees (0 = the tonic), one chord a bar; the genre picks among its own.
PROGRESSIONS = {
    "jazz": [(1, 4, 0, 5), (0, 5, 1, 4), (2, 5, 1, 4), (1, 4, 0, 0), (0, 3, 1, 4)],
    "pop": [(0, 4, 5, 3), (5, 3, 0, 4), (0, 3, 4, 3), (0, 5, 3, 4), (3, 0, 4, 5)],
    "rock": [(0, 6, 3, 0), (0, 3, 4, 3), (0, 5, 2, 6), (0, 3, 0, 4), (0, 0, 3, 4)],
    "metal": [(0, 5, 6, 0), (0, 1, 0, 6), (0, 3, 5, 4), (0, 0, 5, 6), (0, 6, 5, 6)],
    "breakcore": [(0, 5, 2, 6), (0, 3, 5, 4), (0, 0, 5, 6), (5, 3, 0, 4)],
}


@dataclass
class Song:
    genre: str
    bpm: float
    swing: float
    key: int  # tonic pitch class
    mode: str
    progression: tuple
    seconds: float

    @property
    def beat(self) -> float:
        return 60.0 / self.bpm

    def chord(self, bar: int, sevenths: bool) -> list[int]:
        """Pitch classes of the bar's chord, root first."""
        sc = SCALES[self.mode]
        deg = self.progression[bar % len(self.progression)]
        tones = [sc[(deg + k) % 7] + 12 * ((deg + k) // 7) for k in ((0, 2, 4, 6) if sevenths else (0, 2, 4))]
        return [(self.key + t) % 12 for t in tones]

    def scale(self) -> list[int]:
        return [(self.key + s) % 12 for s in SCALES[self.mode]]

    def time(self, beat_pos: float) -> float:
        """Seconds of a position in beats, swung: off-beat eighths land late by the swing ratio."""
        whole, frac = divmod(beat_pos, 1.0)
        if self.swing > 0.5 and abs(frac - 0.5) < 1e-6:
            frac = self.swing
        return (whole + frac) * self.beat


def new_song(rng, genre: str, seconds: float) -> Song:
    g = GENRES[genre]
    return Song(genre, float(rng.uniform(*g["bpm"])), float(rng.uniform(*g["swing"])), int(rng.integers(0, 12)),
                str(rng.choice(g["modes"])), tuple(PROGRESSIONS[genre][int(rng.integers(len(PROGRESSIONS[genre])))]),
                seconds)


def _near(pc: int, around: int, lo: int, hi: int) -> int:
    """The pitch of pitch class `pc` nearest `around`, kept inside lo..hi."""
    p = around + ((pc - around) % 12)
    if p - around > 6:
        p -= 12
    while p < lo:
        p += 12
    while p > hi:
        p -= 12
    return p


def _human(rng, song: Song, t: float, vel: float, jitter: float | None = None) -> tuple[float, int]:
    j = GENRES[song.genre]["jitter"] if jitter is None else jitter
    return max(0.0, t + float(rng.normal(0, j))), int(max(15, min(127, vel + rng.normal(0, 8))))


def _rest_bars(rng, n_bars: int) -> set[int]:
    """A few bars where the part lays out, as real parts do (none in a third of parts)."""
    rest = set()
    if rng.random() < 0.66:
        for _ in range(int(rng.integers(1, 3))):
            start = int(rng.integers(0, max(1, n_bars - 2)))
            rest.update(range(start, start + int(rng.integers(1, 4))))
    return rest


def keys_part(rng, song: Song, lo: int, hi: int, short: bool) -> list:
    """Piano, organ, harpsichord, mallets: comping, block chords, arpeggios or broken chords."""
    style = str(rng.choice({"jazz": ["comp", "comp", "arp"], "pop": ["block", "arp", "broken"],
                            "rock": ["block", "block", "arp"], "metal": ["arp", "block"],
                            "breakcore": ["arp", "stab", "arp"]}[song.genre]))
    centre = int(rng.uniform(lo + 0.35 * (hi - lo), lo + 0.6 * (hi - lo)))
    n_bars = int(math.ceil(song.seconds / (4 * song.beat)))
    rest, notes = _rest_bars(rng, n_bars), []
    sev = song.genre == "jazz" or rng.random() < 0.2
    for bar in range(n_bars):
        if bar in rest:
            continue
        pcs = song.chord(bar, sev)
        voicing = sorted(_near(pc, centre, lo, hi) for pc in (pcs[1:] if style == "comp" and len(pcs) > 3 else pcs))
        bass = _near(pcs[0], centre - 12, lo, hi)
        b0 = bar * 4
        if style == "comp":  # syncopated jazz comping: the Charleston and pushes
            hits = [(0, 1.2), (1.5, 0.6)] if rng.random() < 0.5 else [(0.5 * int(rng.integers(0, 3)), 0.8), (2.5, 1.0)]
            for pos, length in hits:
                for p in voicing:
                    t, v = _human(rng, song, song.time(b0 + pos), 75)
                    notes.append((t, length * song.beat * (0.4 if short else 1), p, v))
        elif style in ("block", "stab"):
            step = 1.0 if style == "block" and rng.random() < 0.5 else 0.5
            pos = 0.0
            while pos < 4:
                if style == "stab" and rng.random() < 0.35:
                    pos += step
                    continue
                for p in [bass] + voicing:
                    t, v = _human(rng, song, song.time(b0 + pos), 85 if pos % 2 == 0 else 70)
                    notes.append((t, step * song.beat * (0.35 if short or style == "stab" else 0.95), p, v))
                pos += step
        else:  # arpeggios and broken chords
            tones = [bass] + voicing + [voicing[0] + 12 if voicing[0] + 12 <= hi else voicing[-1]]
            step = 0.25 if song.genre in ("breakcore", "metal") or rng.random() < 0.4 else 0.5
            order = tones + tones[-2:0:-1] if style == "arp" else [tones[i] for i in (0, 2, 1, 3, 2, 3, 1, 2)]
            for k in range(int(4 / step)):
                t, v = _human(rng, song, song.time(b0 + k * step), 80 if k % 4 == 0 else 65)
                notes.append((t, step * song.beat * (0.5 if short else 1.6), order[k % len(order)], v))
    return notes


def line_part(rng, song: Song, lo: int, hi: int, short: bool) -> list:
    """One voice: a melody, a riff, held tones. Steps and leaps over the scale, chord tones on strong
    beats, breaths between phrases; in jazz, eighth-note lines with chromatic approach notes."""
    lo2, hi2 = lo + (hi - lo) // 6, hi - (hi - lo) // 5
    scale = song.scale()
    n_bars = int(math.ceil(song.seconds / (4 * song.beat)))
    rest, notes = _rest_bars(rng, n_bars), []
    p = int(rng.integers(lo2, hi2 + 1))
    held = rng.random() < (0.15 if short else 0.3)  # long tones: a pad-like part
    pos = 0.0
    while pos < n_bars * 4:
        bar = int(pos // 4)
        if bar in rest:
            pos = (bar + 1) * 4.0
            continue
        if held:
            length = float(rng.choice([2.0, 4.0]))
        elif song.genre == "jazz":
            length = float(rng.choice([0.5, 0.5, 0.5, 1.0, 1.5]))
        elif song.genre in ("breakcore", "metal"):
            length = float(rng.choice([0.25, 0.25, 0.5, 1.0]))
        else:
            length = float(rng.choice([0.5, 1.0, 1.0, 1.5, 2.0]))
        if rng.random() < 0.12:  # a breath
            pos += length
            continue
        chord = song.chord(bar, song.genre == "jazz")
        if pos % 2 == 0 or held:
            p = _near(int(rng.choice(chord)), p, lo2, hi2)
        else:
            idx = min(range(7), key=lambda i: abs((scale[i] - p) % 12))
            step = int(rng.choice([-2, -1, -1, 1, 1, 2, 3, -3]))
            p = _near(scale[(idx + step) % 7], p + step * 2, lo2, hi2)
            if song.genre == "jazz" and rng.random() < 0.2:
                p = min(hi2, max(lo2, p + int(rng.choice([-1, 1]))))
        t, v = _human(rng, song, song.time(pos), 90 if pos % 1 == 0 else 72)
        notes.append((t, length * song.beat * (0.4 if short else float(rng.uniform(0.75, 1.0))), p, v))
        pos += length
    return notes


def section_part(rng, song: Song, lo: int, hi: int, short: bool) -> list:
    """String sections and organs: held chords (pads), or with short articulations, an ostinato."""
    if short:
        return keys_part(rng, song, lo, hi, True)
    centre = int(lo + 0.5 * (hi - lo))
    n_bars = int(math.ceil(song.seconds / (4 * song.beat)))
    rest, notes = _rest_bars(rng, n_bars), []
    for bar in range(n_bars):
        if bar in rest:
            continue
        for p in sorted({_near(pc, centre, lo, hi) for pc in song.chord(bar, rng.random() < 0.3)}):
            t, v = _human(rng, song, song.time(bar * 4), 70, 0.03)
            notes.append((t, 4 * song.beat * 0.98, p, v))
    return notes


GUITAR_LO, GUITAR_HI = 40, 76  # standard tuning, low E to the 12th fret of the high E


def guitar_part(rng, song: Song, lo: int, hi: int, short: bool, drive: bool) -> tuple[list, str]:
    """Strums (pop, rock), fingerpicking, power-chord riffs palm-muted (rock, metal, breakcore) or a lead
    line. Returns the notes and the style."""
    lo, hi = max(lo, GUITAR_LO - 4), min(hi, GUITAR_HI + 12)
    styles = {"jazz": ["comp", "pick", "lead"], "pop": ["strum", "strum", "pick", "lead"],
              "rock": ["power", "strum", "lead", "power"], "metal": ["power", "power", "gallop", "lead"],
              "breakcore": ["power", "lead", "pick"]}[song.genre]
    if drive:
        styles = [s for s in styles if s in ("power", "gallop", "lead")] or ["power"]
    style = str(rng.choice(styles))
    if style == "lead":
        return line_part(rng, song, max(lo, 52), hi, False), style
    if style == "comp":
        return keys_part(rng, song, max(lo, 48), min(hi, 72), False), style
    if style == "pick":
        return keys_part(rng, Song(**{**song.__dict__, "genre": "pop"}), max(lo, 40), min(hi, 76), False), style
    n_bars = int(math.ceil(song.seconds / (4 * song.beat)))
    rest, notes = _rest_bars(rng, n_bars), []
    drop = int(rng.choice([0, 0, -2])) if song.genre == "metal" else 0
    for bar in range(n_bars):
        if bar in rest:
            continue
        pcs = song.chord(bar, False)
        root = _near(pcs[0], 43 + drop, 38 + drop, 52)
        if style in ("power", "gallop"):
            shape = [root, root + 7, root + 12]
            grid = ([0, 0.5, 0.75] if style == "gallop" else [0.5]) if song.genre != "rock" else [0.5]
            pos = 0.0
            while pos < 4:
                muted = song.genre in ("metal", "breakcore") and rng.random() < 0.7
                for step in grid if style == "gallop" else [0]:
                    t0 = pos + step
                    if t0 >= 4:
                        break
                    dur = (0.12 if muted else 0.45) * song.beat * (2 if not muted and rng.random() < 0.2 else 1)
                    for k, p in enumerate(shape if not muted or rng.random() < 0.5 else shape[:1]):
                        t, v = _human(rng, song, song.time(bar * 4 + t0) + 0.004 * k, 100 if t0 % 1 == 0 else 85)
                        notes.append((t, dur, p, v))
                pos += 1.0 if style == "gallop" else (0.25 if song.genre in ("metal", "breakcore") else 0.5)
        else:  # strum: a chord shape, strings a few ms apart, down on beats, up between
            shape = sorted({root} | {_near(pc, root + 9, root + 4, min(hi, root + 19)) for pc in pcs}
                           | {_near(pcs[0], root + 12, root + 10, root + 14)})
            pattern = [(0, "d"), (1, "d"), (1.5, "u"), (2.5, "u"), (3, "d"), (3.5, "u")] if rng.random() < 0.6 else \
                [(k * 0.5, "d" if k % 2 == 0 else "u") for k in range(8)]
            for pos, way in pattern:
                strings = shape if way == "d" else shape[::-1][: max(3, len(shape) - 2)]
                spread = float(rng.uniform(0.006, 0.02))
                for k, p in enumerate(strings):
                    t, v = _human(rng, song, song.time(bar * 4 + pos) + k * spread, 88 if way == "d" else 70)
                    notes.append((t, 0.5 * song.beat * (0.6 if short else 1.0), p, v))
    return notes, style


def plucked_part(rng, song: Song, lo: int, hi: int, short: bool) -> list:
    """Zithers, banjos, lyres, harps: arpeggios, sometimes tremolo-picked."""
    notes = keys_part(rng, Song(**{**song.__dict__, "genre": "pop" if song.genre == "jazz" else song.genre}),
                      lo, hi, short)
    if rng.random() < 0.25:  # tremolo: each note picked fast
        out = []
        for t, d, p, v in notes:
            k = max(1, int(d / 0.07))
            out += [(t + i * 0.07, 0.08, p, int(v * (0.85 + 0.15 * (i % 2)))) for i in range(min(k, 12))]
        return out
    return notes


def make_part(rng, song: Song, role: str, lo: int, hi: int, short: bool, drive: bool = False) -> tuple[list, str]:
    """The notes of one part for an instrument of `role` with sampled keys lo..hi, and its style (notes
    the last bar would start after the part's end are dropped)."""
    if role == "keys":
        notes, style = keys_part(rng, song, lo, hi, short), "keys"
    elif role == "mono":
        notes, style = line_part(rng, song, lo, hi, short), "line"
    elif role == "section":
        notes, style = section_part(rng, song, lo, hi, short), "section"
    elif role == "guitar":
        notes, style = guitar_part(rng, song, lo, hi, short, drive)
    else:
        notes, style = plucked_part(rng, song, lo, hi, short), "plucked"
    return [n for n in notes if n[0] < song.seconds], style

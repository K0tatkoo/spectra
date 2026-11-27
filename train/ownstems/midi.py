"""Standard MIDI files, read to the notes a renderer needs (no dependency).

Format 0 and 1, metrical time (ticks per quarter note; SMPTE time is refused),
running status, tempo changes anywhere, note-on at velocity 0 as a note-off,
and the sustain pedal (CC 64): a note released while the pedal is down keeps
sounding until the pedal comes up, as a pianist's would. Used for Mutopia's
scores and Guitar-TECHS's annotations (ownstems/render.py).
"""

from __future__ import annotations

import struct
from dataclasses import dataclass


@dataclass
class Note:
    start: float  # seconds
    end: float
    pitch: int  # MIDI note, 60 = middle C
    velocity: int  # 1..127
    channel: int  # 0..15 (9 is General MIDI drums)
    program: int  # the channel's program when the note started (0..127)
    track: int


def _vlq(b: bytes, i: int) -> tuple[int, int]:
    v = 0
    while True:
        c = b[i]
        i += 1
        v = (v << 7) | (c & 0x7F)
        if not c & 0x80:
            return v, i


def read_midi(data: bytes) -> list[Note]:
    """Every note in the file, sorted by start time."""
    if data[:4] != b"MThd":
        raise ValueError("not a standard MIDI file")
    hlen = struct.unpack(">I", data[4:8])[0]
    fmt, ntrk, division = struct.unpack(">HHH", data[8:14])
    if division & 0x8000:
        raise ValueError("SMPTE time division is not supported")
    if fmt > 1:
        raise ValueError(f"MIDI format {fmt} is not supported")
    events = []  # (tick, order, track, kind, a, b, c)
    i, order = 8 + hlen, 0
    for trk in range(ntrk):
        if data[i:i + 4] != b"MTrk":
            break
        tlen = struct.unpack(">I", data[i + 4:i + 8])[0]
        b, j, end, tick, status = data, i + 8, i + 8 + tlen, 0, 0
        while j < end:
            delta, j = _vlq(b, j)
            tick += delta
            c = b[j]
            if c == 0xFF:  # meta
                kind = b[j + 1]
                n, j = _vlq(b, j + 2)
                if kind == 0x51 and n == 3:
                    events.append((tick, order, trk, "tempo", int.from_bytes(b[j:j + 3], "big"), 0, 0))
                j += n
            elif c in (0xF0, 0xF7):  # sysex
                n, j = _vlq(b, j + 1)
                j += n
            else:
                if c & 0x80:
                    status = c
                    j += 1
                hi, ch = status & 0xF0, status & 0x0F
                if hi in (0xC0, 0xD0):
                    a = b[j]
                    j += 1
                    if hi == 0xC0:
                        events.append((tick, order, trk, "program", ch, a, 0))
                else:
                    a, v = b[j], b[j + 1]
                    j += 2
                    if hi == 0x90 and v > 0:
                        events.append((tick, order, trk, "on", ch, a, v))
                    elif hi == 0x80 or (hi == 0x90 and v == 0):
                        events.append((tick, order, trk, "off", ch, a, 0))
                    elif hi == 0xB0 and a == 64:
                        events.append((tick, order, trk, "pedal", ch, v, 0))
            order += 1
        i = end
    events.sort(key=lambda e: (e[0], e[1]))
    # ticks to seconds through the tempo map (500,000 µs a quarter until a tempo event says otherwise)
    t_sec, last_tick, tempo = 0.0, 0, 500_000
    program, held, pedal, sustained = [0] * 16, {}, [False] * 16, {}
    notes: list[Note] = []

    def close(key, t):
        start, vel, prog, trk = held.pop(key)
        notes.append(Note(start, max(t, start + 0.01), key[1], vel, key[0], prog, trk))

    for tick, _, trk, kind, a, b2, c in events:
        t_sec += (tick - last_tick) * tempo / 1e6 / division
        last_tick = tick
        if kind == "tempo":
            tempo = a
        elif kind == "program":
            program[a] = b2
        elif kind == "on":
            key = (a, b2)
            if key in held:  # the same key struck again: the first one ends here
                sustained.pop(key, None)
                close(key, t_sec)
            held[key] = (t_sec, c, program[a], trk)
        elif kind == "off":
            key = (a, b2)
            if key in held:
                if pedal[a]:
                    sustained[key] = True
                else:
                    close(key, t_sec)
        elif kind == "pedal":
            down = b2 >= 64
            if pedal[a] and not down:
                for key in [k for k in sustained if k[0] == a]:
                    sustained.pop(key)
                    if key in held:
                        close(key, t_sec)
            pedal[a] = down
    for key in list(held):
        close(key, t_sec)
    notes.sort(key=lambda n: (n.start, n.pitch))
    return notes

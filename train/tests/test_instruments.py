"""The instruments lane's renderer: MIDI reading, the sample player, the part generators, the amp, the
breaks, render licences, and the new sources' indexing rules."""

from __future__ import annotations

import json
import math
import struct

import numpy as np
import pytest
import soundfile as sf

from ownstems import RATE


# --- MIDI -------------------------------------------------------------------------------------

def _vlq(n: int) -> bytes:
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.append(0x80 | (n & 0x7F))
        n >>= 7
    return bytes(reversed(out))


def _smf(tracks: list[list[tuple[int, bytes]]], division: int = 480) -> bytes:
    data = b"MThd" + struct.pack(">IHHH", 6, 1, len(tracks), division)
    for evs in tracks:
        body = b"".join(_vlq(d) + e for d, e in evs) + b"\x00\xff\x2f\x00"
        data += b"MTrk" + struct.pack(">I", len(body)) + body
    return data


def test_midi_reads_tempo_running_status_velocity_zero_and_the_pedal():
    from ownstems.midi import read_midi

    tempo = [(0, b"\xff\x51\x03" + (1_000_000).to_bytes(3, "big"))]  # 60 BPM: a quarter is 1 s
    notes = [(0, b"\x90\x3c\x64"), (480, b"\x3c\x00"),  # C4 for a quarter, off by velocity 0 (running status)
             (0, b"\xb0\x40\x7f"), (0, b"\x90\x40\x50"), (240, b"\x80\x40\x00"),  # pedal down, E4 off early…
             (720, b"\xb0\x40\x00")]  # …held until the pedal comes up at 3.0 s
    got = read_midi(_smf([tempo, notes]))
    assert [(n.pitch, n.velocity) for n in got] == [(60, 100), (64, 80)]
    assert abs(got[0].start) < 1e-9 and abs(got[0].end - 1.0) < 1e-9
    assert abs(got[1].start - 1.0) < 1e-9 and abs(got[1].end - 3.0) < 1e-9


# --- the sample player ------------------------------------------------------------------------

def _tone(hz: float, seconds: float = 2.0, weak_fundamental: bool = False) -> np.ndarray:
    t = np.arange(int(seconds * RATE)) / RATE
    amps = [0.15 if weak_fundamental else 1.0, 0.8, 0.5, 0.3]
    x = sum(a * np.sin(2 * np.pi * hz * (k + 1) * t) for k, a in enumerate(amps))
    return (x / np.max(np.abs(x)) * 0.5 * np.exp(-t / 1.5)).astype(np.float32)


def test_note_names_and_sample_names():
    from ownstems.sampler import _parse_name, note_number

    assert [note_number(x) for x in ("c4", "C#3", "db2", "a0", "60", "x")] == [60, 49, 37, 21, 60, None]
    assert _parse_name("Oboe_Stacc_F5_v1_rr2_Main") == (77, 1, 2)
    assert _parse_name("KSHarp_C5_mf1") == (72, 5, 1)
    assert _parse_name("no_note_here") is None


@pytest.mark.parametrize("hz", [55.0, 110.0, 261.6, 880.0])
def test_yin_finds_the_fundamental_even_when_it_is_weak(hz):
    from ownstems.sampler import yin

    for weak in (False, True):
        f = yin(_tone(hz, 0.5, weak)[2000:2000 + 8192])
        assert f is not None and abs(f / hz - 1) < 0.01


def _sample_dir(tmp_path, notes: dict[str, float]):
    for name, hz in notes.items():
        sf.write(tmp_path / f"{name}.wav", _tone(hz), RATE)
    return str(tmp_path)


def test_player_pitches_shapes_and_rotates(tmp_path):
    from ownstems.sampler import Instrument, Region, note_pitch

    d = _sample_dir(tmp_path, {"a4_rr1": 440.0, "a4_rr2": 440.0, "hat": 3000.0, "open": 2000.0})
    inst = Instrument("t", [Region(src=(d, "a4_rr1.wav"), lokey=60, hikey=80, keycenter=69, seq_length=2,
                                   seq_position=1, release=0.05),
                            Region(src=(d, "a4_rr2.wav"), lokey=60, hikey=80, keycenter=69, seq_length=2,
                                   seq_position=2, release=0.05)])
    y = inst.render([(0.0, 0.8, 72, 100)], 1.5, np.random.default_rng(0))
    assert abs(note_pitch(y) - 72) < 0.3  # three semitones up from the A
    tail = np.abs(y[:, int(1.0 * RATE):]).max()
    assert tail < 0.01 * np.abs(y).max()  # released after 0.8 s, gone by 1.0
    used = []
    orig = Instrument._voice
    Instrument._voice = lambda self, r, *a: used.append(r.src[1]) or orig(self, r, *a)
    try:
        inst.render([(0.0, 0.2, 69, 90), (0.5, 0.2, 69, 90), (1.0, 0.2, 69, 90)], 1.5, np.random.default_rng(0))
    finally:
        Instrument._voice = orig
    assert used == ["a4_rr1.wav", "a4_rr2.wav", "a4_rr1.wav"]  # round robin
    kit = Instrument("k", [Region(src=(d, "open.wav"), lokey=46, hikey=46, keycenter=46, keytrack=0, group=2, off_by=1,
                                  loop_mode="one_shot"),
                           Region(src=(d, "hat.wav"), lokey=42, hikey=42, keycenter=42, keytrack=0, group=1,
                                  loop_mode="one_shot")])
    y = kit.render([(0.0, 0.1, 46, 100), (0.5, 0.1, 42, 100)], 1.5, np.random.default_rng(0)).mean(0)
    spec_after = np.abs(np.fft.rfft(y[int(0.6 * RATE):int(1.2 * RATE)]))
    f = np.fft.rfftfreq(int(0.6 * RATE), 1 / RATE)
    assert spec_after[(f > 1900) & (f < 2100)].max() < 0.05 * spec_after[(f > 2900) & (f < 3100)].max()  # choked


def test_sfz_defines_includes_controllers_and_paths(tmp_path):
    import zipfile

    from ownstems.sampler import load_sfz, note_pitch

    root = tmp_path / "lib"
    (root / "Samples").mkdir(parents=True)
    (root / "Data").mkdir()
    sf.write(root / "Samples" / "tone 57.wav", _tone(220.0), RATE)
    sf.write(root / "Samples" / "tone 69.wav", _tone(440.0), RATE)
    (root / "Data" / "sample.txt").write_text("sample=tone $KEY.wav pitch_keycenter=$KEY\n")
    (root / "main.sfz").write_text(
        "<control> default_path=Samples/ set_hdcc74=0.5\n<global> amplitude_oncc74=100 locc74=1 // mic on\n"
        "<region> #define $KEY 57 lokey=50 hikey=62 #include \"Data/sample.txt\"\n"
        "<region> #define $KEY 69 lokey=63 hikey=80 #include \"Data/sample.txt\"\n"
        "<region> sample=missing.wav key=90\n")
    zp = tmp_path / "lib.zip"
    with zipfile.ZipFile(zp, "w") as z:
        for p in root.rglob("*"):
            if p.is_file():
                z.write(p, "lib/" + p.relative_to(root).as_posix())
    inst = load_sfz(str(zp), "lib/main.sfz")
    assert len(inst.regions) == 2 and inst.skipped.get("file not found") == 1
    assert sorted(r.keycenter for r in inst.regions) == [57, 69]
    assert all(abs(r.amplitude - 50) < 1 for r in inst.regions)  # set_hdcc74=0.5 → half the mic
    y = inst.render([(0.0, 0.6, 60, 100)], 1.0, np.random.default_rng(0))
    assert abs(note_pitch(y) - 60) < 0.3


# --- parts, amp, breaks, licences ---------------------------------------------------------------

@pytest.mark.parametrize("genre", ["jazz", "pop", "rock", "metal", "breakcore"])
def test_parts_stay_in_range_and_in_time(genre):
    from ownstems import parts

    for seed in range(4):
        rng = np.random.default_rng(seed)
        song = parts.new_song(rng, genre, 30.0)
        for role, (lo, hi) in (("keys", (36, 96)), ("mono", (55, 84)), ("section", (40, 80)), ("guitar", (40, 86)),
                               ("plucked", (48, 84))):
            notes, style = parts.make_part(rng, song, role, lo, hi, short=seed % 2 == 0, drive=seed == 3)
            assert notes, (genre, role)
            for t, d, p, v in notes:
                assert 0 <= t < 30 and d > 0 and 1 <= v <= 127
                assert lo - 6 <= p <= hi + 12, (genre, role, style, p)


def test_amp_is_finite_and_bright_enough():
    from ownstems.amp import amp

    x = _tone(110.0, 1.0)
    for gain in ("crunch", "high"):
        y = amp(x, np.random.default_rng(1), gain)
        assert y.shape == x.shape and np.all(np.isfinite(y)) and abs(np.abs(y).max() - 0.9) < 1e-3


@pytest.mark.parametrize("bpm", [160.0, 171.3, 199.9])
def test_chopped_breaks_have_the_asked_length(bpm):
    from ownstems.breaks import chop

    rng = np.random.default_rng(int(bpm))
    brk = rng.standard_normal(int(4 * 16 * 60 / bpm / 4 * RATE)).astype(np.float32) * 0.1
    for wild in (False, True):
        y = chop(rng, brk, bpm, 12.0, wild)
        assert len(y) == 12 * RATE and np.all(np.isfinite(y))


def test_render_licences():
    from ownstems.render import licence_of

    assert licence_of([("CC0-1.0", ""), ("PDM-1.0", "")]) == ("CC0-1.0", "")
    lic, credit = licence_of([("CC0-1.0", ""), ("CC-BY-4.0", "B"), ("CC-BY-3.0", "A"), ("CC-BY-4.0", "B")])
    assert lic == "CC-BY-4.0" and credit == "A; B"


# --- the new sources ------------------------------------------------------------------------------

def test_ccmixter_parts_keep_licences_and_drop_excluded_uploads(tmp_path):
    from ownstems.ledger import Ledger
    from ownstems.sources import ccmixter_parts as cp

    raw = tmp_path / "sources" / "instruments-raw"
    rows = []
    for uid, lic, fam in ((70070, "by/3.0", "guitar"), (5, "by/3.0", "bass"), (6, "by-nc/3.0", "piano"), (7, "zero/1.0", "drums")):
        p = raw / "ccmixter" / fam / str(uid) / "x.wav"
        p.parent.mkdir(parents=True)
        sf.write(p, _tone(110.0, 1.0), RATE)
        url = f"http://creativecommons.org/{'publicdomain' if 'zero' in lic else 'licenses'}/{lic}/"
        rows.append({"source": "ccmixter", "upload_id": uid, "family": fam, "label": fam, "file": p.relative_to(raw).as_posix(),
                     "licence_evidence": f"https://ccmixter.org/files/someone/{uid} (upload licence {url})",
                     "referer": f"https://ccmixter.org/files/someone/{uid}", "url": "https://ccmixter.org/content/x.wav",
                     "credit": '"x" by someone (ccMixter)', "sha256": "0" * 64})
    (raw / "manifest.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
    arts, clips = cp.index(tmp_path, Ledger(tmp_path))
    assert sorted((c.stem, a.licence) for a, c in zip(arts, clips)) == [("bass", "CC-BY-3.0"), ("drums", "CC0-1.0")]
    assert all(c.group == "someone" for c in clips) and arts[0].credit

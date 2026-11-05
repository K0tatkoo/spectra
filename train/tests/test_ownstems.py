"""The clean-data pipeline: licences, the ledger's refusals, and the mixer's promises."""

from __future__ import annotations

import json

import numpy as np
import pytest
import soundfile as sf

from ownstems import RATE, STEMS
from ownstems.audio import Library
from ownstems.ledger import Artifact, Catalog, Clip, Ledger, RefusedError, split_for, today
from ownstems.licences import normalize
from ownstems.mixer import Mixer, MixConfig, master_gain


# ── licences ──────────────────────────────────────────────────────────────

@pytest.mark.parametrize("text, want", [
    ("https://creativecommons.org/publicdomain/zero/1.0/", "CC0-1.0"),
    ("http://creativecommons.org/publicdomain/zero/1.0/legalcode", "CC0-1.0"),
    ("https://creativecommons.org/licenses/by/4.0/", "CC-BY-4.0"),
    ("https://creativecommons.org/licenses/by/4.0/legalcode", "CC-BY-4.0"),
    ("http://creativecommons.org/licenses/by/3.0/us/", "CC-BY-3.0"),
    ("http://creativecommons.org/licenses/by/2.5/", "CC-BY-2.5"),
    ("Creative Commons 0", "CC0-1.0"),
    ("cc-by-4.0", "CC-BY-4.0"),
    ("CC-BY-4.0", "CC-BY-4.0"),
])
def test_allowed_licences(text, want):
    assert normalize(text) == (want, None)


@pytest.mark.parametrize("text, why", [
    ("https://creativecommons.org/licenses/by-nc/4.0/", "NonCommercial"),
    ("https://creativecommons.org/licenses/by-nc-sa/4.0/", "NonCommercial"),
    ("https://creativecommons.org/licenses/by-sa/4.0/", "ShareAlike"),
    ("https://creativecommons.org/licenses/by-nd/4.0/", "NoDerivatives"),
    ("http://creativecommons.org/licenses/sampling+/1.0/", "Sampling Plus"),
    ("Attribution", "without a version"),
    ("Attribution NonCommercial", "NonCommercial"),
    ("for research purposes only", "research"),
    ("", "no licence"),
    (None, "no licence"),
    ("https://example.com/our-terms", "unrecognised"),
])
def test_refused_licences(text, why):
    lid, reason = normalize(text)
    assert lid is None and why in reason


# ── ledger and catalog ────────────────────────────────────────────────────

def _artifact(root, name="a.wav", licence="CC-BY-4.0", credit="Someone, CC BY", source="test"):
    p = root / "sources" / source / name
    p.parent.mkdir(parents=True, exist_ok=True)
    if not p.exists():
        sf.write(p, np.zeros(RATE, dtype=np.float32), RATE)
    led = Ledger(root)
    return led, led.make(id=f"{source}/{name}", path=p, source=source, licence=licence,
                         licence_url="https://example.org/licence", credit=credit, origin="https://example.org/a")


def test_ledger_refuses_off_list_licence(tmp_path):
    led, a = _artifact(tmp_path, licence="CC-BY-NC-4.0")
    with pytest.raises(RefusedError):
        led.write_source("test", [a])


def test_ledger_refuses_cc_by_without_credit(tmp_path):
    led, a = _artifact(tmp_path, credit=" ")
    with pytest.raises(RefusedError):
        led.write_source("test", [a])


def test_hand_edited_ledger_is_refused_on_load(tmp_path):
    led, a = _artifact(tmp_path)
    led.write_source("test", [a])
    f = tmp_path / "ledger" / "test.jsonl"
    row = json.loads(f.read_text())
    row["licence"] = "CC-BY-NC-SA-4.0"
    f.write_text(json.dumps(row) + "\n")
    with pytest.raises(RefusedError):
        Ledger(tmp_path)


def test_catalog_refuses_clip_without_artifact(tmp_path):
    led, a = _artifact(tmp_path)
    led.write_source("test", [a])
    cat = Catalog(tmp_path, led)
    stray = Clip(artifact="test/not-in-ledger.wav", stem="vocals", source="test", split="train", group="g",
                 seconds=1.0, rate=RATE, channels=1)
    with pytest.raises(RefusedError):
        cat.write_source("test", [stray])


def test_library_refuses_unlisted_clip(tmp_path):
    led, a = _artifact(tmp_path)
    led.write_source("test", [a])
    lib = Library(tmp_path)
    stray = Clip(artifact="test/b.wav", stem="vocals", source="test", split="train", group="g",
                 seconds=1.0, rate=RATE, channels=1)
    with pytest.raises(RefusedError):
        lib.open(stray)


def test_check_finds_changed_files(tmp_path):
    led, a = _artifact(tmp_path)
    led.write_source("test", [a])
    assert Ledger(tmp_path).check(deep=True) == []
    (tmp_path / a.path).write_bytes(b"not audio")
    assert Ledger(tmp_path).check() == ["size changed: test/a.wav"]


def test_split_is_deterministic_and_about_a_tenth():
    assert split_for("vocalset", "f1") == split_for("vocalset", "f1")
    share = np.mean([split_for("x", str(i)) == "valid" for i in range(5000)])
    assert 0.08 < share < 0.12


def test_credits_list_each_credit_once(tmp_path):
    led, a = _artifact(tmp_path, name="a.wav", credit="Dataset X (CC BY 4.0)")
    _, b = _artifact(tmp_path, name="b.wav", credit="Dataset X (CC BY 4.0)")
    led.write_source("test", [a, b])
    assert led.credits().count("Dataset X") == 1


# ── mixer ─────────────────────────────────────────────────────────────────

def _tone_library(root) -> Library:
    """Two clips per stem, each a different made-up sound, all CC0 in a fake source."""
    rng = np.random.default_rng(0)
    t = np.arange(4 * RATE) / RATE
    sounds = {
        "vocals": lambda k: np.sin(2 * np.pi * (220 + 30 * k) * t * (1 + 0.01 * np.sin(6 * t))),
        "synth": lambda k: np.sign(np.sin(2 * np.pi * (440 + 50 * k) * t)) * 0.5,
        "other": lambda k: np.sin(2 * np.pi * (330 + 20 * k) * t) * np.exp(-((t % 0.5) * 4)),
        "bass": lambda k: np.sin(2 * np.pi * (55 + 5 * k) * t),
        "drums": lambda k: rng.standard_normal(t.size) * np.exp(-((t % 0.25) * 30)),
    }
    led = Ledger(root)
    arts, clips = [], []
    for stem, make in sounds.items():
        for k in range(2):
            p = root / "sources" / "tones" / f"{stem}{k}.wav"
            p.parent.mkdir(parents=True, exist_ok=True)
            x = make(k).astype(np.float32) * 0.5
            sf.write(p, np.stack([x, x]).T if stem == "synth" else x, RATE)
            a = led.make(id=f"tones/{stem}{k}", path=p, source="tones", licence="CC0-1.0",
                         licence_url="https://example.org/cc0", credit="", origin="https://example.org")
            arts.append(a)
            clips.append(Clip(artifact=a.id, stem=stem, source="tones", split="train", group=f"{stem}{k}",
                              seconds=4.0, rate=RATE, channels=2 if stem == "synth" else 1))
    led.write_source("tones", arts)
    Catalog(root, led).write_source("tones", clips)
    return Library(root)


def test_mixer_stems_sum_to_the_mix(tmp_path):
    mixer = Mixer(_tone_library(tmp_path), MixConfig(seconds=3.0, generated_song=0.0))
    rng = np.random.default_rng(1)
    for _ in range(12):
        ex = mixer.example(rng)
        assert ex.stems.shape == (len(STEMS), 2, 3 * RATE) and ex.mix.shape == (2, 3 * RATE)
        assert np.all(np.isfinite(ex.stems))
        assert np.max(np.abs(ex.stems.sum(axis=0) - ex.mix)) < 1e-6
        assert np.max(np.abs(ex.mix)) <= 0.99 + 1e-6
        for i, s in enumerate(STEMS):  # a stem that is absent is silent, one that is present is not
            assert (s in ex.info["present"]) == bool(np.any(ex.stems[i]))


def test_mixer_never_uses_two_clips_of_one_group(tmp_path):
    mixer = Mixer(_tone_library(tmp_path), MixConfig(seconds=2.0, generated_song=0.0,
                                                     layered={s: 1.0 for s in STEMS}))
    rng = np.random.default_rng(2)
    for _ in range(10):
        ex = mixer.example(rng)
        for stem, used in ex.info["clips"].items():
            assert len(used) == len(set(used))


def test_mixer_generated_song_is_one_hardstyle_song(tmp_path):
    mixer = Mixer(_tone_library(tmp_path), MixConfig(seconds=2.0, generated_song=1.0,
                                                     song_catalog={"vocals": 1.0, "other": 1.0}))
    for seed in range(6):
        ex = mixer.example(np.random.default_rng(seed))
        assert ex.info["generated"]
        assert 145 <= ex.info["song"]["bpm"] <= 158
        # drums, bass and synth come only from the generated song, never from another song
        assert set(ex.info["made"]) <= {"synth", "bass", "drums"}
        assert set(ex.info["clips"]) <= {"vocals", "other"}
        for i, stem in enumerate(STEMS):
            if stem in ("synth", "bass", "drums") and stem not in ex.info["made"]:
                assert not np.any(ex.stems[i])
        assert np.max(np.abs(ex.stems.sum(0) - ex.mix)) < 1e-5


def test_loudness_meter_reads_a_reference_sine():
    from ownstems.loudness import lufs

    t = np.arange(8 * RATE) / RATE
    s = 0.1 * np.sin(2 * np.pi * 997 * t)
    assert abs(lufs(np.stack([s, s])) + 20.0) < 0.1  # a stereo 997 Hz sine reads its own level
    # Playing 2 s on, 2 s off: the gate measures what plays (pyloudnorm reads -20.52 too),
    # where RMS would read 3 dB lower.
    half = s * (t % 4.0 < 2.0)
    assert abs(lufs(np.stack([half, half])) + 20.52) < 0.05


def test_stems_are_levelled_by_loudness_not_rms(tmp_path):
    from ownstems.loudness import lufs

    mixer = Mixer(_tone_library(tmp_path), MixConfig(seconds=2.0))
    t = np.arange(2 * RATE) / RATE
    bright = np.stack([np.sign(np.sin(2 * np.pi * 3000 * t))] * 2) * 0.3
    deep = np.stack([np.sin(2 * np.pi * 45 * t)] * 2) * 0.3
    got = [lufs(mixer._colour(x, "other", np.random.default_rng(1), (0.0, 0.0), eq=False)) for x in (bright, deep)]
    assert all(abs(g + 20.0) < 0.1 for g in got)


def test_master_gain_respects_the_ceiling():
    rng = np.random.default_rng(4)
    mix = (rng.standard_normal((2, 2 * RATE)) * np.linspace(0.1, 3.0, 2 * RATE)).astype(np.float32)
    g = master_gain(mix, rng, ceiling_db=-1.0)
    assert np.max(np.abs(mix * g)) <= 10 ** (-1.0 / 20) + 1e-6
    assert np.all((g > 0) & (g <= 1.0 + 1e-6))


def _drum_loop(rng, seconds=4.0):
    """Kick-like bursts four to the bar over a quiet bed: a 20 dB crest factor, like an unmastered mix."""
    n = int(seconds * RATE)
    t = np.arange(RATE // 4) / RATE
    hit = np.sin(2 * np.pi * 55 * t) * np.exp(-t / 0.05)
    x = rng.standard_normal((2, n)) * 0.01
    for start in range(0, n - len(t), RATE // 4):
        x[:, start:start + len(t)] += 0.5 * hit
    return x.astype(np.float32)


@pytest.mark.parametrize("target", [-18.0, -12.0, -7.0])
def test_master_gain_drives_into_the_limiter(target):
    from ownstems.mixer import rms_db

    rng = np.random.default_rng(8)
    mix = _drum_loop(rng)
    out = mix * master_gain(mix, rng, target_db=target)
    assert np.max(np.abs(out)) <= 10 ** (-0.3 / 20) + 1e-6
    assert abs(rms_db(out) - target) < 1.0


def test_hardstyle_bass_out_splits_the_old_sum():
    from synthsplit import hardstyle, procsynth

    n = 3 * RATE
    old, _, _ = hardstyle.render_drums(np.random.default_rng(5), procsynth.Music(np.random.default_rng(6), 4.0), n)
    bass = np.zeros((2, n))
    new, _, _ = hardstyle.render_drums(np.random.default_rng(5), procsynth.Music(np.random.default_rng(6), 4.0), n,
                                       bass_out=bass)
    assert np.max(np.abs(new + bass - old)) < 1e-5


# ── ccMixter's filter ─────────────────────────────────────────────────────

def _upload(lic="http://creativecommons.org/licenses/by/3.0/", tags=",acappella,", files=()):
    return {"upload_id": 1, "upload_name": "Song", "user_name": "Artist", "license_url": lic, "upload_tags": tags,
            "upload_extra": {"systags": "", "usertags": "", "ccud": "", "featuring": "Singer"},
            "file_page_url": "https://ccmixter.org/files/Artist/1",
            "files": [{"file_name": n, "file_nicname": nick, "file_format_info": {"media-type": "audio"}}
                      for nick, n in files]}


def test_ccmixter_keeps_only_labelled_vocals():
    from ownstems.sources import ccmixter as cc

    u = _upload(files=[("Dry Main Acapella", "a.mp3"), ("wet vox", "b.mp3"), ("mp3", "c.mp3"),
                       ("Drum Track", "d.mp3"), ("Scratchapella", "e.mp3"), ("mixed song", "f.mp3"),
                       ("Instrumental", "g.mp3"), ("wet vox preview", "h.mp3"), ("dry backing", "i.mp3"),
                       ("Wet Mix Acapella", "j.mp3"), ("Drum Loops", "k.zip")])
    assert [f["file_name"] for f in cc.vocal_files(u)] == ["a.mp3", "b.mp3", "i.mp3", "j.mp3"]
    assert cc.credit_of(u) == '"Song" by Artist feat. Singer (ccMixter, https://ccmixter.org/files/Artist/1)'


@pytest.mark.parametrize("lic, tags, keep", [
    ("http://creativecommons.org/licenses/by/3.0/", ",acappella,", True),
    ("http://creativecommons.org/publicdomain/zero/1.0/", ",acappella,", True),
    ("https://creativecommons.org/licenses/by-nc/4.0/", ",acappella,", False),
    ("http://creativecommons.org/licenses/by-sa/3.0/", ",acappella,", False),
    ("http://creativecommons.org/licenses/sampling+/1.0/", ",acappella,", False),
    ("http://creativecommons.org/licenses/by/3.0/", ",acappella,AI_generated,", False),
    ("http://creativecommons.org/licenses/by/3.0/", ",acappella,non_commercial,", False),
    ("http://creativecommons.org/licenses/by/3.0/", ",acappella,remix,", False),
])
def test_ccmixter_upload_rules(lic, tags, keep):
    from ownstems.sources import ccmixter as cc

    assert cc.keep_upload(_upload(lic=lic, tags=tags))[0] is keep


def test_mixer_never_returns_a_silent_example(tmp_path):
    mixer = Mixer(_tone_library(tmp_path), MixConfig(seconds=1.0, absent={s: 0.9 for s in STEMS}))
    rng = np.random.default_rng(7)
    for _ in range(10):
        assert mixer.example(rng).info["present"]


# ── zips without zip64 records ────────────────────────────────────────────

def _wrap_offsets(path, wrap):
    """Stores every offset in the central directory modulo `wrap`, as a writer
    without zip64 does at 2**32 (macOS Archive Utility on VocalSet1-2.zip)."""
    import struct

    data = bytearray(path.read_bytes())
    eocd = data.rfind(b"PK\x05\x06")
    cd_size, cd_off = struct.unpack("<II", data[eocd + 12:eocd + 20])
    p = cd_off
    while p < cd_off + cd_size:
        assert data[p:p + 4] == b"PK\x01\x02"
        n, m, k = struct.unpack("<HHH", data[p + 28:p + 34])
        off = struct.unpack("<I", data[p + 42:p + 46])[0]
        data[p + 42:p + 46] = struct.pack("<I", off % wrap)
        p += 46 + n + m + k
    data[eocd + 16:eocd + 20] = struct.pack("<I", cd_off % wrap)
    path.write_bytes(bytes(data))


def test_zip_with_wrapped_offsets_is_repaired(tmp_path):
    import zipfile

    from ownstems.zips import repair

    rng = np.random.default_rng(3)
    members = {f"by_singer/f{i}/take_{i}.wav": rng.bytes(3000) for i in range(8)}
    path = tmp_path / "wrapped.zip"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for name, data in members.items():
            z.writestr(name, data)
    _wrap_offsets(path, 4096)

    with zipfile.ZipFile(path) as z, pytest.raises(zipfile.BadZipFile):
        for name in members:
            z.read(name)  # what Python alone does with such a zip
    with zipfile.ZipFile(path) as z:
        assert repair(z, wrap=4096) > 0
        assert {n: z.read(n) for n in members} == members


def test_sound_zip_is_left_alone(tmp_path):
    import zipfile

    from ownstems.zips import open_zip, repair

    path = tmp_path / "fine.zip"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("a.wav", b"x" * 5000)
        z.writestr("b.wav", b"y" * 5000)
    with zipfile.ZipFile(path) as z:
        assert repair(z, wrap=4096) == 0
    with open_zip(path) as z:
        assert z.read("b.wav") == b"y" * 5000

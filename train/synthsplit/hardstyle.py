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
  the next kick). Percussion from a per-song drum-machine kit (since 2026-11-24):
  metallic hats in a few patterns, sometimes a ride, claps and snares on 2 and 4
  through a room, snare rolls into drops, crashes on phrases and drops.
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
# Hardstyle songs lean on leads and supersaws (procsynth.KINDS: lead, pad, arp, stab, supersaw).
KIND_P = (0.35, 0.15, 0.08, 0.1, 0.32)


def music(rng, seconds: float) -> Music:
    return Music(rng, seconds, bpm=float(rng.uniform(145, 158)), scales=SCALES)


def _hz(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def _peaking(fc: float, gain_db: float, q: float) -> np.ndarray:
    """An RBJ peaking EQ as one second-order section."""
    a = 10 ** (gain_db / 40)
    w = 2 * math.pi * fc / SR
    alpha, c = math.sin(w) / (2 * q), math.cos(w)
    b, d = [1 + alpha * a, -2 * c, 1 - alpha * a], [1 + alpha / a, -2 * c, 1 - alpha / a]
    return np.array([[b[0] / d[0], b[1] / d[0], b[2] / d[0], 1.0, d[1] / d[0], d[2] / d[0]]])


def kick(rng, length: int, root_hz: float, raw: float, clean: bool = False, hold: float | None = None) -> np.ndarray:
    """One kick, `length` samples (up to the next beat). raw 0 (euphoric) .. 1 (raw).

    `clean`: an 808-style kick instead, the pitched sine boom with only a little drive and a
    click. `hold`: the punch falls to this level (0..1) and the tail holds there until the kick
    is cut, as many real hardstyle kicks do, instead of dying away. Both are for the own-stems
    model (v3: it took a clean held boom for drums); with neither, the draws are as before.

    A hardstyle kick is not an 808. An 808 is a clean sub boom; a hardstyle kick's
    tail is a distorted tone whose grit sits in the mids (the "tok"), made the way
    producers make it: drive the pitched sine hard, thin its fundamental, boost a band
    of mids and drive again so that band clips into the tail, then layer a clean sub
    back underneath and low-pass. Raw kicks are driven harder and bend in pitch. (The first version drove the sine once: 66-78 % of its energy
    stayed under 120 Hz and the owner heard an 808.)
    """
    t = np.arange(length) / SR
    f0 = rng.uniform(250, 650)
    drop = rng.uniform(0.008, 0.04)
    f = root_hz + (f0 - root_hz) * np.exp(-t / drop)
    if raw > 0.5 and rng.random() < 0.5:  # raw tails bend
        f = f * 2 ** (rng.uniform(-0.3, 0.3) * np.clip((t - 0.08) / 0.3, 0, 1))
    body = np.sin(2 * np.pi * np.cumsum(f) / SR)
    tail = rng.uniform(0.12, 0.25) + (1 - raw) * rng.uniform(0.0, 0.25)
    env = np.minimum(t / 0.0007, 1.0) * np.exp(-t / tail)
    if hold is not None:
        env = np.minimum(t / 0.0007, 1.0) * (hold + (1 - hold) * np.exp(-t / 0.03))
    x = body * env
    if clean:  # nearly undriven: v2 gave a drive of 1-3 to bass already, a pure sine boom to drums
        g = rng.uniform(0.3, 1.5)
        x = np.tanh(g * x) / math.tanh(g)
        click = rng.standard_normal(length) * np.exp(-t / rng.uniform(0.001, 0.004))
        # often no click at all: a bare sine boom is what v2 gave to drums (91 %)
        x += rng.uniform(0.05, 0.3) * (rng.random() < 0.5) * signal.sosfilt(
            signal.butter(2, 2000, "hp", fs=SR, output="sos"), click)
        x = x / (np.max(np.abs(x)) + 1e-9)
        return x * np.exp(-np.maximum(t - 0.8 * length / SR, 0) / 0.01)
    g = rng.uniform(6, 12) + raw * rng.uniform(6, 20)  # driven hard: harmonics all the way up
    x = np.tanh(g * x) / math.tanh(g)
    # Thin the driven tail's fundamental (a clean sub goes back under it), boost the tok,
    # and drive again so the boosted band clips into the tail.
    x = signal.sosfilt(signal.butter(2, rng.uniform(1.5, 2.5) * root_hz, "hp", fs=SR, output="sos"), x)
    x = signal.sosfilt(_peaking(rng.uniform(180, 700), rng.uniform(6, 12) + raw * rng.uniform(0, 6),
                                rng.uniform(0.7, 2.0)), x)
    x = x / (np.max(np.abs(x)) + 1e-9)
    g = rng.uniform(3, 8) + raw * rng.uniform(3, 12)
    x = np.tanh(g * x) / math.tanh(g)
    if raw > 0.6 and rng.random() < 0.6:
        x = np.clip(x * rng.uniform(1.2, 2.0), -1, 1)
    sub = body * env * rng.uniform(1.0, 1.6)  # the clean sub, same pitch and envelope
    x = x * rng.uniform(0.5, 0.8) + sub
    click = rng.standard_normal(length) * np.exp(-t / rng.uniform(0.001, 0.004))
    x += rng.uniform(0.1, 0.4) * signal.sosfilt(signal.butter(2, 2000, "hp", fs=SR, output="sos"), click)
    x = signal.sosfilt(signal.butter(2, rng.uniform(5000, 8000) + raw * rng.uniform(0, 4000), "lp", fs=SR,
                                     output="sos"), x)
    x = x / (np.max(np.abs(x)) + 1e-9)
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


METAL = np.array([1.0, 1.4471, 1.6170, 1.9265, 2.5028, 2.6637])  # an 808 cymbal's six square waves


def metal_hit(rng, length: int, base: float, decay: float, hp: float, noise: float) -> np.ndarray:
    """A hat, ride or crash the way drum machines make one: six detuned square waves and some
    noise, high-passed, with a sharp attack."""
    t = np.arange(length) / SR
    x = sum(np.sign(np.sin(2 * np.pi * base * r * t + rng.uniform(0, 2 * np.pi))) for r in METAL)
    x = x + noise * 3 * rng.standard_normal(length)
    x = signal.sosfilt(signal.butter(2, hp, "hp", fs=SR, output="sos"), x)
    x = x * np.minimum(t / 0.0004, 1.0) * np.exp(-t / decay)
    return x / (np.max(np.abs(x)) + 1e-9)


def clap_hit(rng, length: int, centre: float, tail: float) -> np.ndarray:
    """A clap: three to five short noise bursts a few ms apart, then a band-passed noise tail."""
    t = np.arange(length) / SR
    x = np.zeros(length)
    at = 0
    for _ in range(int(rng.integers(3, 6))):
        m = length - at
        if m <= 0:
            break
        x[at:] += rng.standard_normal(m) * np.exp(-t[:m] / rng.uniform(0.002, 0.005))
        at += int(rng.uniform(0.005, 0.012) * SR)
    x += rng.standard_normal(length) * np.exp(-t / tail) * rng.uniform(0.3, 0.7)
    lo, hi = centre / rng.uniform(1.5, 2.5), min(centre * rng.uniform(1.5, 3.0), SR / 2 * 0.9)
    x = signal.sosfilt(signal.butter(2, [lo, hi], "bp", fs=SR, output="sos"), x)
    return x / (np.max(np.abs(x)) + 1e-9)


def snare_hit(rng, length: int) -> np.ndarray:
    """A snare: a pitched body falling a little, under band-passed noise."""
    t = np.arange(length) / SR
    f = rng.uniform(170, 260) * (1 + 0.5 * np.exp(-t / 0.01))
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / rng.uniform(0.04, 0.1))
    nz = signal.sosfilt(signal.butter(2, [rng.uniform(1200, 2500), rng.uniform(6000, 10000)], "bp", fs=SR,
                                      output="sos"), rng.standard_normal(length)) * np.exp(-t / rng.uniform(0.06, 0.2))
    x = body * rng.uniform(0.3, 0.8) + nz
    x = x * np.minimum(t / 0.0005, 1.0)
    return x / (np.max(np.abs(x)) + 1e-9)


def room(rng, x: np.ndarray, wet: float) -> np.ndarray:
    """A plain stereo reverb: the bus convolved with decaying, low-passed noise (2, n) -> (2, n)."""
    n_ir = int(rng.uniform(0.3, 1.4) * SR)
    t = np.arange(n_ir) / SR
    pre = int(rng.uniform(0.005, 0.025) * SR)
    lp = signal.butter(2, rng.uniform(3000, 9000), "lp", fs=SR, output="sos")
    out = np.empty_like(x)
    for ch in range(2):
        ir = signal.sosfilt(lp, rng.standard_normal(n_ir)) * np.exp(-t / (t[-1] / 5))
        ir[:pre] = 0
        ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
        out[ch] = x[ch] + wet * signal.fftconvolve(x[ch], ir)[:x.shape[1]]
    return out


def _add(bus: np.ndarray, at: int, hit: np.ndarray, gain: float, pan: float = 0.0) -> None:
    m = min(len(hit), bus.shape[1] - at)
    if m > 0 and at >= 0:
        bus[0, at:at + m] += hit[:m] * gain * (1 - pan)
        bus[1, at:at + m] += hit[:m] * gain * (1 + pan)


def render_drums(rng, mu: Music, total: int, bass_out: np.ndarray | None = None,
                 kick_styles: bool = False) -> tuple[np.ndarray, np.ndarray, dict]:
    """Stereo drums + bass (2, total), the kick envelope (total,) 0..1, and what was played.

    With `bass_out` (2, total), the bass goes there instead of into the drums (the own-stems
    model scores bass and drums apart; the synth splitter wanted them together): the offbeat
    reverse bass, and the kick's pitched tail. A hardstyle kick is one sample, but a listener
    hears its first few tens of ms (the punch and click) as the drum and the pitched tail that
    carries the key as the bass (the owner, 2026-11-24, on v0 putting it all in drums; the
    app's StemgenRT lanes split it the same way). The split is a crossfade in time, so drums +
    bass is exactly the whole kick, and every random draw happens either way.

    Percussion as real hardstyle has it (v0 had only filtered-noise blips and never learned
    that hats and claps are drums): metallic drum-machine hats in one of a few patterns,
    sometimes a ride, claps and snares on 2 and 4 through a room, snare rolls into the kick's
    return after a breakdown, crashes on phrases and drops.

    `kick_styles` (the own-stems model, v3): three songs in ten get a clean 808-style kick and
    about a third a held tail, so a clean held boom is heard as the bass line too. Off, the
    draws are exactly as before (the synth splitter's data does not move)."""
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
    # The kit: one sound per part for the whole song, as a producer picks samples.
    punch_s, fade_s = rng.uniform(0.015, 0.04), rng.uniform(0.008, 0.025)
    hat_style = str(rng.choice(["off8", "off8", "16", "off8+16"])) if rng.random() < 0.85 else "none"
    hat_base, hat_hp = rng.uniform(280, 520), rng.uniform(6000, 9500)
    hat_decay, hat_noise = rng.uniform(0.012, 0.045), rng.uniform(0.1, 0.8)
    open_hats = hat_style != "none" and rng.random() < 0.35
    ride = rng.random() < 0.2
    claps = rng.random() < 0.75
    clap_centre, clap_tail = rng.uniform(900, 2200), rng.uniform(0.05, 0.25)
    snare = rng.random() < 0.4  # layered under the clap, or alone when there is no clap
    rolls = rng.random() < 0.6
    crashes = rng.random() < 0.75
    in_breaks = rng.random() < 0.5  # hats and claps keep going through breakdowns
    hat_level, clap_level = rng.uniform(0.25, 0.6), rng.uniform(0.35, 0.7)
    snare_level, ride_level = rng.uniform(0.3, 0.6), rng.uniform(0.12, 0.25)
    wet = rng.uniform(0.15, 0.5)
    clean_kick, hold = False, None
    if kick_styles:
        clean_kick = bool(rng.random() < 0.3)
        hold = float(rng.uniform(0.35, 0.8)) if rng.random() < 0.35 else None
    drums = np.zeros((2, total))
    verb = np.zeros((2, total))  # claps, snares and crashes go through the room
    kenv = np.zeros(total)
    sixteenth = beat // 4
    hat_len = int(min(0.25, 5 * hat_decay) * SR)
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
        playing = kick_bars[bar]
        if playing:
            k = kick(rng, n, root, raw, clean=clean_kick, hold=hold)
            if bass_out is None:
                drums[:, start:start + n] += k
            else:
                t = np.arange(n) / SR
                w = np.clip(1 - (t - punch_s) / fade_s, 0, 1)
                w = 0.5 - 0.5 * np.cos(np.pi * w)  # 1 through the punch, then a smooth fade to 0
                drums[:, start:start + n] += k * w
                bass_out[:, start:start + n] += k * (1 - w)
            t = np.arange(n) / SR
            kenv[start:start + n] = np.maximum(kenv[start:start + n], np.exp(-t / 0.12))
            if rev:
                half = beat // 2
                s0 = start + half
                m = min(beat - half, total - s0)
                if m > 0:
                    (drums if bass_out is None else bass_out)[:, s0:s0 + m] += 0.6 * reverse_bass(rng, m, root * 2)
        if playing or in_breaks:
            if hat_style != "none":
                steps = {"off8": [2], "16": [0, 1, 2, 3], "off8+16": [2] if bar % 2 == 0 else [1, 2, 3]}[hat_style]
                for st in steps:
                    is_open = open_hats and st == 2 and rng.random() < 0.7
                    d = hat_decay * (rng.uniform(3, 6) if is_open else 1)
                    hit = metal_hit(rng, int(min(0.6, 5 * d) * SR) if is_open else hat_len, hat_base, d, hat_hp,
                                    hat_noise)
                    accent = 1.0 if st == 2 else rng.uniform(0.45, 0.75)
                    _add(drums, start + st * sixteenth, hit, hat_level * accent * rng.uniform(0.85, 1.0),
                         rng.uniform(-0.3, 0.3))
            if ride and playing:
                _add(drums, start, metal_hit(rng, int(0.6 * SR), hat_base * 0.7, rng.uniform(0.2, 0.4),
                                             hat_hp * 0.6, 0.2), ride_level)
            if i % 4 in (1, 3):
                m = int(0.5 * SR)
                if claps:
                    _add(verb, start, clap_hit(rng, m, clap_centre, clap_tail), clap_level)
                if snare:
                    _add(verb, start, snare_hit(rng, m), snare_level * (0.6 if claps else 1.0))
        # A roll into the kick's return: the last bar of a breakdown, getting faster and louder.
        nxt = min(bar + 1, mu.bars - 1)
        if rolls and not playing and kick_bars[nxt] and nxt != bar:
            pos = i % 4
            per = (2, 4, 4, 8)[pos]
            for j in range(per):
                at = start + j * beat // per
                lvl = snare_level * (0.35 + 0.65 * (pos * per + j) / (4 * per))
                _add(verb, at, snare_hit(rng, int(0.2 * SR)) if snare or not claps
                     else clap_hit(rng, int(0.2 * SR), clap_centre, 0.05), lvl)
        drop = playing and bar > 0 and not kick_bars[bar - 1] and i % 4 == 0
        if crashes and (i % 32 == 0 or drop):  # phrases, and the drop after a breakdown
            m = int(2.0 * SR)
            for ch in range(2):  # each side its own: a wide cymbal
                c = metal_hit(rng, m, rng.uniform(180, 380), rng.uniform(0.6, 1.5), rng.uniform(3000, 5000), 0.7)
                drums[ch, start:start + m] += c[:max(0, min(m, total - start))] * rng.uniform(0.12, 0.25)
    drums += room(rng, verb, wet)
    info = {"kick": "raw" if raw > 0.6 else "euphoric" if raw < 0.3 else "mid", "reverse_bass": rev,
            "breakdown_bars": int((~kick_bars).sum()), "tuned_to_chords": tuned_to_chords, "hats": hat_style,
            "claps": claps, "snare": snare}
    if kick_styles:
        info["kick_style"] = "clean" if clean_kick else info["kick"]
        info["kick_held"] = hold is not None
    return drums.astype(np.float32), kenv.astype(np.float32), info


def sidechain(synth: np.ndarray, kenv: np.ndarray, depth: float) -> np.ndarray:
    k = 1.0 - math.exp(-1.0 / (0.004 * SR))  # a compressor's attack
    g = signal.lfilter([k], [1, k - 1], 1.0 - depth * kenv)
    return (synth * g).astype(np.float32)

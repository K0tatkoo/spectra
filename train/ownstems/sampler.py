"""A sample player for the instrument renders: notes in, a stereo part out.

One Instrument is a list of Regions (a sample, the keys and velocities it
answers, how it is pitched, looped and shaped), built four ways:

- `load_sfz`: an SFZ file, read inside the library's zip (nothing unpacked).
  Supported: <control> <global> <master> <group> <region> with inheritance,
  #define and #include, default_path, key / lokey / hikey / pitch_keycenter
  (note names, c4 = 60), lovel / hivel, lorand / hirand, seq_length /
  seq_position (round robin), sw_last / sw_lokey / sw_hikey / sw_default
  (one articulation per part), locc / hicc and set_cc (conditions on
  controllers, at their defaults), amplitude, amplitude_onccN (mic mixes set by
  controllers), volume, pan, tune, transpose, pitch_keytrack, amp_veltrack,
  ampeg attack / hold / decay / sustain / release, loop_mode / loop_start /
  loop_end, offset / offset_random / end, group / off_by (a closed hi-hat
  chokes an open one), trigger=attack (release-triggered regions are skipped).
  Ignored: filters, EQ, LFOs, pitch bend, curves, effects, crossfades.
- `from_named_samples`: libraries that only name their samples (VSCO-2 CE,
  VCSL): note, velocity layer and round robin are read from each file name.
- `from_egfxset`: EGFxSet's <effect>/<pickup>/<string>-<fret>.wav, a guitar
  played through real pedals, one note per string and fret.
- `from_tinysol`: TinySOL's notes, from its metadata table.

Pitch is shifted by resampling (soxr), sample rates are brought to 44.1 kHz
on load, and decoded samples are kept in a per-process cache.
"""

from __future__ import annotations

import csv
import io
import math
import re
import zipfile
from collections import OrderedDict, defaultdict
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

import numpy as np
import soundfile as sf

from . import RATE

try:
    import soxr
except ImportError:  # the renders need it; importing this module for tests does not
    soxr = None

_NOTE = {"c": 0, "d": 2, "e": 4, "f": 5, "g": 7, "a": 9, "b": 11}
_NOTE_RE = re.compile(r"^([a-gA-G])([#b♯♭]?)(-?\d)$")


def note_number(s: str | int | None) -> int | None:
    """'60', 'c4' (= 60), 'C#3', 'db2' → MIDI note; None when it is not one."""
    if s is None:
        return None
    if isinstance(s, int):
        return s
    s = s.strip()
    if re.fullmatch(r"-?\d+", s):
        return int(s)
    m = _NOTE_RE.match(s)
    if not m:
        return None
    n = _NOTE[m.group(1).lower()] + {"#": 1, "♯": 1, "b": -1, "♭": -1}.get(m.group(2), 0)
    return n + 12 * (int(m.group(3)) + 1)


@dataclass
class Region:
    src: tuple[str, str]  # (archive or folder, member path inside it)
    lokey: int = 0
    hikey: int = 127
    keycenter: int = 60
    lovel: int = 1
    hivel: int = 127
    lorand: float = 0.0
    hirand: float = 1.0
    seq_length: int = 1
    seq_position: int = 1
    sw_last: int | None = None
    volume: float = 0.0  # dB
    amplitude: float = 100.0  # %
    pan: float = 0.0  # -100..100
    veltrack: float = 100.0  # %
    tune: float = 0.0  # cents
    transpose: int = 0
    keytrack: float = 100.0  # cents a key
    attack: float = 0.0
    hold: float = 0.0
    decay: float = 0.0
    sustain: float = 100.0  # %
    release: float = 0.001
    loop_mode: str = "no_loop"
    loop_start: int = 0
    loop_end: int = 0
    offset: int = 0
    offset_random: int = 0
    end: int = 0
    group: int = 0
    off_by: int = 0


@dataclass
class Instrument:
    name: str
    regions: list[Region]
    tags: list[str] = field(default_factory=list)
    skipped: dict = field(default_factory=dict)  # why SFZ regions were left out, counted

    def keys(self) -> tuple[int, int]:
        return min(r.lokey for r in self.regions), max(r.hikey for r in self.regions)

    def switches(self) -> list[int]:
        return sorted({r.sw_last for r in self.regions if r.sw_last is not None})

    def render(self, notes, seconds: float, rng, switch: int | None = None) -> np.ndarray:
        """notes: (start_s, dur_s, pitch, velocity) → float32 (2, samples). One keyswitch articulation
        per call (`switch`, else the first one the library defines)."""
        sws = self.switches()
        sw = switch if switch is not None else (sws[0] if sws else None)
        n_out = int(seconds * RATE)
        out = np.zeros((2, n_out), np.float32)
        voices, rr = [], defaultdict(int)
        for start, dur, pitch, vel in sorted(notes):
            if start >= seconds:
                continue
            roll = rng.random()
            cand = [r for r in self.regions if r.lokey <= pitch <= r.hikey and r.lovel <= vel <= r.hivel
                    and r.lorand <= roll < r.hirand and (r.sw_last is None or r.sw_last == sw)]
            if not cand:
                continue
            seq = rr[pitch]
            rr[pitch] += 1
            pick = [r for r in cand if r.seq_length <= 1 or r.seq_position == seq % r.seq_length + 1] or cand
            for r in pick:
                voices.append([start, dur, pitch, vel, r, None])
        # chokes: a voice whose off_by group is struck later stops there
        for i, v in enumerate(voices):
            if v[4].off_by:
                for w in voices[i + 1:]:
                    if w[4].group == v[4].off_by and w[0] > v[0]:
                        v[5] = w[0]
                        break
        for start, dur, pitch, vel, r, cut in voices:
            y = self._voice(r, dur, pitch, vel, rng, None if cut is None else cut - start)
            if y is None:
                continue
            a = int(start * RATE)
            m = min(y.shape[1], n_out - a)
            if m > 0:
                out[:, a:a + m] += y[:, :m]
        return out

    def _voice(self, r: Region, dur: float, pitch: int, vel: int, rng, cut: float | None):
        x = load_sample(r.src)
        if x is None:
            return None
        x = x[:, :r.end] if r.end else x
        off = r.offset + (int(rng.integers(0, r.offset_random + 1)) if r.offset_random else 0)
        if off >= x.shape[1]:
            return None
        one_shot = r.loop_mode == "one_shot"
        release = max(r.release, 0.03 if not one_shot else r.release)
        length = x.shape[1] / RATE if one_shot else dur + release
        if cut is not None:
            length = min(length, cut + 0.008)
        semis = (pitch - r.keycenter) * r.keytrack / 100 + r.transpose + r.tune / 100
        speed = 2 ** (semis / 12)
        need = int(length * RATE * speed) + 64
        looping = r.loop_mode in ("loop_continuous", "loop_sustain") and r.loop_end > r.loop_start + 32
        src = x[:, off:]
        if looping and need > src.shape[1]:
            ls, le = max(r.loop_start - off, 0), r.loop_end - off
            body, loop = src[:, :le], src[:, ls:le]
            reps = int(math.ceil((need - body.shape[1]) / loop.shape[1])) + 1
            src = np.concatenate([body] + [loop] * reps, axis=1)
        src = src[:, :need]
        if abs(speed - 1) > 1e-4:
            if soxr is None:
                raise RuntimeError("soxr is needed to pitch samples")
            y = np.ascontiguousarray(soxr.resample(np.ascontiguousarray(src.T), RATE * speed, RATE, quality="MQ").T)
        else:
            y = src
        n = min(y.shape[1], int(length * RATE))
        if n <= 16:
            return None
        y = y[:, :n].astype(np.float32)
        y *= _envelope(r, n, None if one_shot else dur, cut)
        g = 10 ** (r.volume / 20) * r.amplitude / 100
        g *= 10 ** (r.veltrack / 100 * 40 * math.log10(max(vel, 1) / 127) / 20)
        p = (np.clip(r.pan, -100, 100) / 100 + 1) * math.pi / 4
        if y.shape[0] == 1:
            return np.vstack([y[0] * math.cos(p), y[0] * math.sin(p)]) * (g * math.sqrt(2))
        return y[:2] * np.array([[math.cos(p)], [math.sin(p)]], np.float32) * (g * math.sqrt(2))


def _envelope(r: Region, n: int, dur: float | None, cut: float | None) -> np.ndarray:
    t = np.arange(n) / RATE
    env = np.ones(n, np.float32)
    if r.attack > 0:
        env *= np.clip(t / r.attack, 0, 1)
    s = r.sustain / 100
    if r.decay > 0 and s < 1:
        k = np.clip((t - r.attack - r.hold) / r.decay, 0, None)
        env *= np.where(t > r.attack + r.hold, s + (1 - s) * np.exp(-5 * k), 1.0)
    stop = dur if dur is not None else None
    if cut is not None:
        stop = cut if stop is None else min(stop, cut)
        rel = 0.008 if stop == cut else max(r.release, 0.03)
    else:
        rel = max(r.release, 0.03)
    if stop is not None:
        env *= np.where(t > stop, np.exp(-6.9 * np.clip(t - stop, 0, None) / rel), 1.0)
    return env


# --- samples, decoded once per process ------------------------------------------------------

_ZIPS: dict[str, "Store"] = {}
_CACHE: "OrderedDict[tuple[str, str], np.ndarray | None]" = OrderedDict()
_CACHE_BYTES = [0]
CACHE_LIMIT = 600 << 20


class Store:
    """A library's files: a zip read in place, or a folder."""

    def __init__(self, path: str):
        self.path = str(path)
        self.zip = zipfile.ZipFile(self.path) if not Path(self.path).is_dir() else None
        if self.zip is not None:
            self.names = set(self.zip.NameToInfo)
        else:
            base = Path(self.path)
            self.names = {p.relative_to(base).as_posix() for p in base.rglob("*") if p.is_file()}

    def read(self, member: str) -> bytes:
        return self.zip.read(member) if self.zip is not None else (Path(self.path) / member).read_bytes()

    def namelist(self) -> list[str]:
        return sorted(self.names)


def _open(src: tuple[str, str]) -> bytes | str:
    path, member = src
    if not member:
        return path
    z = _ZIPS.get(path)
    if z is None:
        z = _ZIPS[path] = Store(path)
    return z.read(member)


def load_sample(src: tuple[str, str]) -> np.ndarray | None:
    """float32 (channels, samples) at 44.1 kHz, or None for a file that will not decode."""
    if src in _CACHE:
        _CACHE.move_to_end(src)
        return _CACHE[src]
    try:
        data = _open(src)
        x, rate = sf.read(io.BytesIO(data) if isinstance(data, bytes) else data, dtype="float32", always_2d=True)
        x = np.ascontiguousarray(x.T)
        if rate != RATE:
            x = np.ascontiguousarray(soxr.resample(x.T, rate, RATE, quality="HQ").T, dtype=np.float32)
    except Exception:
        x = None
    _CACHE[src] = x
    _CACHE_BYTES[0] += 0 if x is None else x.nbytes
    while _CACHE_BYTES[0] > CACHE_LIMIT and len(_CACHE) > 1:
        _, old = _CACHE.popitem(last=False)
        _CACHE_BYTES[0] -= 0 if old is None else old.nbytes
    return x


# --- SFZ ------------------------------------------------------------------------------------

_INT = {"lovel", "hivel", "seq_length", "seq_position", "transpose", "offset", "offset_random", "end",
        "loop_start", "loop_end", "loopstart", "loopend", "group", "off_by"}
_FLOAT = {"volume", "amplitude", "pan", "amp_veltrack", "tune", "pitch_keytrack", "ampeg_attack", "ampeg_hold",
          "ampeg_decay", "ampeg_sustain", "ampeg_release", "lorand", "hirand"}
_FIELD = {"amp_veltrack": "veltrack", "pitch_keytrack": "keytrack", "ampeg_attack": "attack", "ampeg_hold": "hold",
          "ampeg_decay": "decay", "ampeg_sustain": "sustain", "ampeg_release": "release", "loopstart": "loop_start",
          "loopend": "loop_end", "loopmode": "loop_mode"}
_TOKEN = re.compile(r"<(\w+)>|(\w+)=")


_DIRECTIVE = re.compile(r'#define\s+(\$\w+)\s+(\S+)|#include\s+"([^"]+)"')


def _subst(t: str, defines: dict) -> str:
    for k in sorted(defines, key=len, reverse=True):
        t = t.replace(k, defines[k])
    return t


def _sfz_text(z: "Store", member: str, root_dir: str, defines: dict, depth: int = 0) -> str:
    """The .sfz with its #includes expanded and $defines substituted, both taken in reading order
    wherever they stand (some libraries put `<region> #define $KEY 21 … #include "x.txt"` on one
    line, the include then reading that $KEY). Include paths are relative to the main file's folder."""
    raw = z.read(member).decode("utf-8", "replace")
    raw = re.sub(r"/\*.*?\*/", " ", raw, flags=re.S)
    raw = re.sub(r"//[^\n]*", "", raw)
    out, pos = [], 0
    for m in _DIRECTIVE.finditer(raw):
        out.append(_subst(raw[pos:m.start()], defines))
        pos = m.end()
        if m.group(1):
            defines[m.group(1)] = m.group(2)
        elif depth < 8:
            inc = _norm(f"{root_dir}/{m.group(3)}")
            if inc in z.names:
                out.append(_sfz_text(z, inc, root_dir, defines, depth + 1))
    out.append(_subst(raw[pos:], defines))
    return "\n".join(out)


def _norm(path: str) -> str:
    parts = []
    for p in path.replace("\\", "/").split("/"):
        if p in ("", "."):
            continue
        if p == "..":
            if parts:
                parts.pop()
        else:
            parts.append(p)
    return "/".join(parts)


def load_sfz(zip_path: str, member: str, name: str | None = None, tags=()) -> Instrument:
    """An Instrument from one .sfz inside a zip or folder; sample paths resolve against the .sfz's folder."""
    z = _ZIPS.get(str(zip_path)) or _ZIPS.setdefault(str(zip_path), Store(str(zip_path)))
    root_dir = str(PurePosixPath(member).parent)
    text = _sfz_text(z, member, root_dir, {})
    scopes = {"control": {}, "global": {}, "master": {}, "group": {}, "region": {}}
    order = ["global", "master", "group", "region"]
    # Controllers at the values a player starts with: volume 100, pan centre, expression full, the
    # rest 0, unless the library sets them (set_ccN, or set_hdccN as a fraction of full).
    current, regions, cc = None, [], {7: 100.0, 10: 64.0, 11: 127.0}
    marks = list(_TOKEN.finditer(text))
    for i, m in enumerate(marks):
        if m.group(1):
            h = m.group(1).lower()
            if current == "region":
                regions.append({**scopes["global"], **scopes["master"], **scopes["group"], **scopes["region"]})
            if h in scopes:
                current = h
                scopes[h] = {}
                for lower in order[order.index(h) + 1:] if h in order else []:
                    scopes[lower] = {}
            else:
                current = None  # <curve>, <effect>, …: their opcodes are not a region's
            continue
        if current is None:
            continue
        end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        val = text[m.end():end].strip()
        key = m.group(2).lower()
        if key != "sample":
            val = val.split()[0] if val.split() else ""
        scopes[current][key] = val
        if current == "control" and key.startswith(("set_cc", "set_hdcc")):
            try:
                if key.startswith("set_hdcc"):
                    cc[int(key[8:])] = float(val) * 127
                else:
                    cc[int(key[6:])] = float(val)
            except ValueError:
                pass
    if current == "region":
        regions.append({**scopes["global"], **scopes["master"], **scopes["group"], **scopes["region"]})
    default_path = scopes["control"].get("default_path", "")
    note_offset = int(float(scopes["control"].get("note_offset", 0) or 0))
    out, skipped = [], defaultdict(int)
    for op in regions:
        if op.get("trigger", "attack") not in ("attack", "first", "legato"):
            skipped["trigger " + op.get("trigger", "")] += 1
            continue
        if "sample" not in op:
            skipped["no sample"] += 1
            continue
        smp = op["sample"]
        if smp.startswith("*"):
            skipped["built-in sample"] += 1
            continue
        path = _norm(f"{root_dir}/{default_path}/{smp}")
        if path not in z.names:  # Karoryfer's layout: a variable ARIA sets elsewhere ($sample_dir), or
            rest = re.sub(r"^\$\w+/?", "", _norm(f"{default_path}/{smp}"))  # samples beside Programs/
            for guess in ("../Samples", "Samples", "..", ""):
                alt = _norm(f"{root_dir}/{guess}/{rest}")
                if alt in z.names:
                    path = alt
                    break
        if path not in z.names:
            skipped["file not found"] += 1
            if skipped["file not found"] == 1:
                skipped["e.g. " + path] = 1
            continue
        if not _cc_ok(op, cc):
            skipped["controller condition"] += 1
            continue
        r = Region(src=(str(zip_path), path))
        key = note_number(op.get("key"))
        if key is not None:
            r.lokey = r.hikey = r.keycenter = key
        for k in ("lokey", "hikey", "pitch_keycenter"):
            n = note_number(op.get(k))
            if n is not None:
                setattr(r, "keycenter" if k == "pitch_keycenter" else k, n)
        if note_number(op.get("pitch_keycenter")) is None and key is None:
            r.keycenter = r.lokey if r.lokey == r.hikey else 60
        for k, v in op.items():
            f = _FIELD.get(k, k)
            try:
                if k in _INT:
                    setattr(r, f, int(float(v)))
                elif k in _FLOAT:
                    setattr(r, f, float(v))
                elif k in ("loop_mode", "loopmode"):
                    r.loop_mode = v
            except (ValueError, TypeError):
                pass
        amp = [(int(k[len("amplitude_oncc"):]), float(v)) for k, v in op.items()
               if re.fullmatch(r"amplitude_oncc\d+", k) and _num(v) is not None]
        if amp:
            base = float(op.get("amplitude", 0))
            r.amplitude = max(0.0, min(100.0, base + sum(x * cc.get(n, 0) / 127 for n, x in amp)))
            if r.amplitude <= 0:
                skipped["silent at default controllers"] += 1
                continue
        sw = note_number(op.get("sw_last"))
        r.sw_last = sw
        r.lokey += note_offset
        r.hikey += note_offset
        r.keycenter += note_offset
        out.append(r)
    sw_default = note_number(scopes["control"].get("sw_default") or scopes["global"].get("sw_default"))
    inst = Instrument(name or PurePosixPath(member).stem, out, list(tags), dict(skipped))
    if sw_default is not None and sw_default in inst.switches():  # the library's own default first
        inst.regions.sort(key=lambda r: r.sw_last != sw_default)
    return inst


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _cc_ok(op: dict, cc: dict) -> bool:
    for k, v in op.items():
        m = re.fullmatch(r"(lo|hi)cc(\d+)", k)
        if m and _num(v) is not None:
            val = cc.get(int(m.group(2)), 0)
            if (m.group(1) == "lo" and val < float(v)) or (m.group(1) == "hi" and val > float(v)):
                return False
    return True


# --- checking that a library's notes are where its names say -------------------------------------

def _harmonic_score(x: np.ndarray, f0: float) -> float:
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    hz = RATE / len(x)
    score = 0.0
    for h, w in ((1, 1.0), (2, 0.5), (3, 0.33), (4, 0.25)):
        k = int(round(h * f0 / hz))
        if 2 <= k < len(spec) - 2:
            score += w * float(spec[k - 2:k + 3].max())
    return score


def yin(x: np.ndarray, fmin: float = 27.0, fmax: float = 2500.0, threshold: float = 0.15) -> float | None:
    """Fundamental frequency of a mono stretch by YIN (de Cheveigné and Kawahara, 2002), which does
    not take the second harmonic for the note; None when there is no clear periodicity."""
    x = np.asarray(x, np.float64)
    maxlag, minlag = int(RATE / fmin), max(2, int(RATE / fmax))
    w = len(x) - maxlag
    if w < maxlag // 2 or not np.any(x):
        return None
    cs = np.concatenate([[0.0], np.cumsum(x * x)])
    lags = np.arange(maxlag + 1)
    n = 1 << int(math.ceil(math.log2(len(x) + w)))
    acf = np.fft.irfft(np.fft.rfft(x, n) * np.conj(np.fft.rfft(x[:w], n)), n)[:maxlag + 1]
    d = (cs[w] - cs[0]) + (cs[lags + w] - cs[lags]) - 2 * acf
    d[0] = 0.0
    cm = np.ones_like(d)
    cm[1:] = d[1:] * lags[1:] / np.maximum(np.cumsum(d[1:]), 1e-12)
    below = np.nonzero(cm[minlag:] < threshold)[0]
    if len(below):
        tau = int(below[0]) + minlag
        while tau + 1 <= maxlag and cm[tau + 1] < cm[tau]:
            tau += 1
    else:
        tau = int(np.argmin(cm[minlag:])) + minlag
        if cm[tau] > 0.35:
            return None
    if 1 <= tau < maxlag:  # parabolic interpolation
        a, b, c = cm[tau - 1], cm[tau], cm[tau + 1]
        den = a - 2 * b + c
        tau = tau + (0.5 * (a - c) / den if den else 0.0)
    return RATE / tau if tau > 0 else None


def note_pitch(y: np.ndarray, skip: float = 0.06, length: int = 8192) -> float | None:
    """The MIDI pitch (fractional) of the first note in a rendered or sampled stretch."""
    m = y.mean(0) if y.ndim == 2 else y
    if not np.any(m):
        return None
    a = int(np.argmax(np.abs(m) > 0.1 * np.abs(m).max()))
    seg = m[a + int(skip * RATE): a + int(skip * RATE) + length]
    f = yin(seg) if len(seg) == length else None
    return None if f is None else 69 + 12 * math.log2(f / 440)


def octave_error(inst: Instrument, rng, tries: int = 6) -> int:
    """How many semitones (a multiple of 12) the regions' key centres are off from what their samples
    sound (YIN on samples spread over the range); 0 when they agree or the votes don't."""
    regs = sorted((r for r in inst.regions if r.keytrack > 0), key=lambda r: r.keycenter)
    if not regs:
        return 0
    picks = [regs[int(i)] for i in np.linspace(0, len(regs) - 1, min(tries, len(regs)))]
    votes = defaultdict(int)
    for r in picks:
        x = load_sample(r.src)
        if x is None:
            continue
        est = note_pitch(x)
        if est is None:
            continue
        diff = est - r.keycenter
        k = round(diff / 12)
        if abs(diff - 12 * k) <= 1.0 and -2 <= k <= 2:
            votes[12 * k] += 1
    if not votes:
        return 0
    best = max(votes, key=votes.get)
    return best if votes[best] >= max(2, (sum(votes.values()) + 1) // 2) else 0


def quality(inst: Instrument, rng, pitched: bool = True) -> dict:
    """Renders five notes across the instrument's sampled range and measures them: how many sound
    at the pitch asked for (within 0.7 of a semitone, by YIN) and how loud they are. `passed` when at
    least four are right (pitched) and at least four are louder than -60 dB (a library may gate one
    dynamic layer behind a controller)."""
    centres = sorted(r.keycenter for r in inst.regions)
    if not centres:
        return {"passed": False, "why": "no regions"}
    keys = [int(np.quantile(centres, q)) for q in (0.15, 0.35, 0.5, 0.65, 0.85)]
    right, levels = 0, []
    for k in keys:
        y = inst.render([(0.02, 0.9, k, 100)], 1.3, rng)
        levels.append(20 * math.log10(float(np.sqrt(np.mean(y ** 2))) + 1e-12))
        est = note_pitch(y) if pitched else None
        right += est is not None and abs(est - k) <= 0.7
    loud = sum(lv > -60 for lv in levels)
    ok = (right >= 4 or not pitched) and loud >= 4
    return {"passed": bool(ok), "right": right, "loud": loud, "keys": keys, "min_db": round(min(levels), 1),
            "why": "" if ok else ("pitch" if pitched and right < 4 else "silent")}


def calibrated(inst: Instrument, rng) -> Instrument:
    """The instrument with its key centres moved by the octave error, if any."""
    err = octave_error(inst, rng)
    if err:
        for r in inst.regions:
            r.keycenter += err
            r.lokey = max(0, r.lokey + err)
            r.hikey = min(127, r.hikey + err)
        inst.tags.append(f"octave_fix{err:+d}")
    return inst


# --- libraries that only name their samples --------------------------------------------------

_VEL_WORDS = ["ppp", "pp", "p", "mp", "mf", "f", "ff", "fff"]


def _parse_name(stem: str):
    """(note, velocity rank, round robin) from a sample's file name, or None."""
    toks = re.split(r"[_\- ]+", stem)
    note = vel = rr = None
    for t in toks:
        n = note_number(t) if _NOTE_RE.match(t) else None
        if n is not None and note is None:
            note = n
            continue
        tl = t.lower()
        m = re.fullmatch(r"(?:v|vl|vel)(\d+)", tl)
        if m:
            vel = int(m.group(1))
        elif re.fullmatch(r"(ppp|pp|p|mp|mf|f|ff|fff)\d?", tl):
            vel = _VEL_WORDS.index(re.sub(r"\d", "", tl)) + 1
        m = re.fullmatch(r"rr(\d+)", tl)
        if m:
            rr = int(m.group(1))
    return None if note is None else (note, vel or 1, rr or 1)


def from_named_samples(zip_path: str, folder: str, name: str, tags=(), release: float = 0.25,
                       skip=("release", "releases", "rel", "noise")) -> Instrument:
    """Regions from the samples directly in `folder` (and its sub-folders, minus release samples):
    notes split the keyboard halfway between sampled notes, velocity layers split 1..127 evenly,
    round robins rotate."""
    z = _ZIPS.get(str(zip_path)) or _ZIPS.setdefault(str(zip_path), Store(str(zip_path)))
    rows = []
    for n in z.namelist():
        if not n.startswith(folder.rstrip("/") + "/") or not n.lower().endswith((".wav", ".flac", ".ogg")):
            continue
        sub = [p.lower() for p in PurePosixPath(n[len(folder):]).parts[:-1]]
        if any(s in skip or s.startswith("rel") for s in sub):
            continue
        parsed = _parse_name(PurePosixPath(n).stem)
        if parsed:
            rows.append((parsed, n))
    if not rows:
        return Instrument(name, [], list(tags))
    notes = sorted({p[0] for p, _ in rows})
    bounds = {n: ((notes[i - 1] + n) // 2 + 1 if i else 0, (n + notes[i + 1]) // 2 if i + 1 < len(notes) else 127)
              for i, n in enumerate(notes)}
    by_note = defaultdict(list)
    for (note, vel, rr), member in rows:
        by_note[note].append((vel, rr, member))
    regions = []
    for note, items in by_note.items():
        vels = sorted({v for v, _, _ in items})
        for v, rr, member in items:
            k = vels.index(v)
            lo, hi = 1 + 127 * k // len(vels), 127 * (k + 1) // len(vels)
            rrs = sorted({r for vv, r, _ in items if vv == v})
            regions.append(Region(src=(str(zip_path), member), lokey=bounds[note][0], hikey=bounds[note][1],
                                  keycenter=note, lovel=lo, hivel=hi, seq_length=len(rrs),
                                  seq_position=rrs.index(rr) + 1, release=release))
    return Instrument(name, regions, list(tags))


# --- EGFxSet: one note per string and fret through each effect ---------------------------------

OPEN_STRINGS = {1: 64, 2: 59, 3: 55, 4: 50, 5: 45, 6: 40}  # E4 B3 G3 D3 A2 E2, string 1 the highest


def from_egfxset(zip_path: str, effect: str, pickup: str, tags=()) -> Instrument:
    """Each pitch from every string and fret that plays it, chosen at random note by note."""
    z = _ZIPS.get(str(zip_path)) or _ZIPS.setdefault(str(zip_path), Store(str(zip_path)))
    by_pitch = defaultdict(list)
    for n in z.namelist():
        p = PurePosixPath(n)
        m = re.fullmatch(r"(\d)-(\d+)\.wav", p.name)
        if m and len(p.parts) >= 3 and p.parts[-2] == pickup:
            s, fret = int(m.group(1)), int(m.group(2))
            by_pitch[OPEN_STRINGS[s] + fret].append(n)
    regions = []
    for pitch, members in by_pitch.items():
        for i, mbr in enumerate(sorted(members)):
            regions.append(Region(src=(str(zip_path), mbr), lokey=pitch, hikey=pitch, keycenter=pitch,
                                  lorand=i / len(members), hirand=(i + 1) / len(members), release=0.08))
    return Instrument(f"EGFxSet {effect} {pickup}", regions, list(tags))


# --- TinySOL ------------------------------------------------------------------------------------

def from_tinysol(meta_csv: str, audio_root: str, instrument: str, tags=(), technique: str = "ord") -> Instrument:
    """One TinySOL instrument (its 'Instrument (in full)' name), ordinario notes, dynamics as layers.
    `audio_root` is the unpacked TinySOL.tar.gz; the metadata's paths are relative to it."""
    rows = list(csv.DictReader(open(meta_csv, encoding="utf-8")))
    rows = [r for r in rows if r["Instrument (in full)"] == instrument and r["Technique (abbr.)"] == technique]
    regions = []
    notes = sorted({n for n in (note_number(r["Pitch"]) for r in rows) if n is not None})
    for r in rows:
        note = note_number(r["Pitch"])
        if note is None:
            continue
        i = notes.index(note)
        lo = (notes[i - 1] + note) // 2 + 1 if i else 0
        hi = (note + notes[i + 1]) // 2 if i + 1 < len(notes) else 127
        dyn = sorted({int(x["Dynamics ID"]) for x in rows if note_number(x["Pitch"]) == note})
        k = dyn.index(int(r["Dynamics ID"]))  # 0 pp … 4 ff
        regions.append(Region(src=(str(Path(audio_root) / r["Path"]), ""), lokey=lo, hikey=hi, keycenter=note,
                              lovel=1 + 127 * k // len(dyn), hivel=127 * (k + 1) // len(dyn), release=0.2))
    return Instrument(f"TinySOL {instrument}", regions, list(tags))

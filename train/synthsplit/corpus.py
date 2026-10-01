"""The prepared training corpus: one folder per song, as mono float16 arrays.

    <root>/<corpus>/<song>/
        meta.json        corpus, id, split, samples, gain, rms per array, synth activity
        synth.npy        ground truth: every synth lead and pad
        vocals.npy       ground truth: the vocals
        nonsynth.npy     ground truth: the rest of "other" (guitar, piano, organ, strings, fx…)
        bassdrums.npy    ground truth: bass (synth bass included) and drums
        sg_vocals.npy    what the app's stem model makes of the whole mix: its vocals…
        sg_other.npy     …and its other. These are the splitter's real inputs.

Mono because the app hands the splitter mono stems. float16 halves the disk
(−66 dB rounding, far below anything a scope shows).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from . import stemgen

GROUPS = ("synth", "vocals", "nonsynth", "bassdrums")
REAL = ("sg_vocals", "sg_other")
# Every mix is brought to this level before the stem model hears it: roughly
# where released music sits. The trainer varies the level from there.
TARGET_RMS_DB = -16.0
ACTIVITY_WINDOW = stemgen.SAMPLE_RATE  # one second


def rms_db(x: np.ndarray) -> float:
    r = float(np.sqrt(np.mean(np.square(x, dtype=np.float64)))) if x.size else 0.0
    return 20 * np.log10(r) if r > 0 else -200.0


def activity(x: np.ndarray, window: int = ACTIVITY_WINDOW) -> list[float]:
    """RMS in dBFS of each whole window."""
    n = x.shape[-1] // window
    if n == 0:
        return []
    blocks = x[..., :n * window].reshape(-1, n, window) if x.ndim > 1 else x[:n * window].reshape(1, n, window)
    r = np.sqrt(np.mean(np.square(blocks, dtype=np.float64), axis=(0, 2)))
    return [round(float(20 * np.log10(v)) if v > 0 else -200.0, 1) for v in r]


PREROLL = 2 * stemgen.SAMPLE_RATE  # stem model warm-up before a kept window, then dropped


def synth_window(synth: np.ndarray, keep: int) -> int:
    """Start of the `keep`-sample window with the most synth energy, on whole seconds."""
    rate = stemgen.SAMPLE_RATE
    seconds = synth.shape[-1] // rate
    k = keep // rate
    if seconds <= k:
        return 0
    energy = np.square(synth[..., :seconds * rate].reshape(-1, seconds, rate), dtype=np.float64).sum(axis=(0, 2))
    return int(np.argmax(np.convolve(energy, np.ones(k), mode="valid"))) * rate


def choose_window(groups: dict[str, np.ndarray], keep_seconds: float) -> tuple[int, int]:
    """The part of a song to keep: all of it, or the keep_seconds with the most synth."""
    total = min(g.shape[-1] for g in groups.values())
    keep = int(keep_seconds * stemgen.SAMPLE_RATE)
    if keep and total > keep:
        start = synth_window(groups["synth"][:, :total], keep)
        return start, start + keep
    return 0, total


def write_song(out_dir: Path, corpus: str, song_id: str, split: str, groups: dict[str, np.ndarray],
               sg: stemgen.StemgenRT, extra: dict | None = None, keep_seconds: float = 0,
               window: tuple[int, int] | None = None) -> dict:
    """groups: stereo (2, T) float32 arrays for each of GROUPS (zeros where absent).

    keep_seconds > 0 keeps only the window of that length with the most synth
    in it (or `window` names it); the stem model still hears the two seconds
    before it, so its output there is past its warm-up."""
    total = min(g.shape[-1] for g in groups.values())
    groups = {k: v[:, :total].astype(np.float32) for k, v in groups.items()}
    mix = sum(groups.values())
    start, end = window if window is not None else choose_window(groups, keep_seconds)
    pre = min(start, PREROLL)
    window = mix[:, start:end]
    gain = 10 ** ((TARGET_RMS_DB - rms_db(window)) / 20)
    peak = float(np.max(np.abs(window))) * gain
    if peak > 0.99:
        gain *= 0.99 / peak
    groups = {k: v[:, start:end] * gain for k, v in groups.items()}
    stems = sg.separate(mix[:, start - pre:end] * gain)[:, :, pre:]
    total = end - start

    out_dir.mkdir(parents=True, exist_ok=True)
    arrays = {k: groups[k].mean(axis=0) for k in GROUPS}
    arrays["sg_vocals"] = stems[stemgen.ORDER.index("vocals")].mean(axis=0)
    arrays["sg_other"] = stems[stemgen.ORDER.index("other")].mean(axis=0)
    for k, v in arrays.items():
        np.save(out_dir / f"{k}.npy", v.astype(np.float16))
    meta = {
        "corpus": corpus,
        "id": song_id,
        "split": split,
        "samples": int(total),
        "gain_db": round(20 * np.log10(gain), 2),
        "kept_from_s": round(start / stemgen.SAMPLE_RATE, 2),
        "rms_db": {k: round(rms_db(v), 1) for k, v in arrays.items()},
        "synth_activity_db": activity(arrays["synth"]),
        **(extra or {}),
    }
    (out_dir / "meta.json").write_text(json.dumps(meta) + "\n")
    return meta


def load_song(song_dir: Path) -> tuple[dict, dict[str, np.ndarray]]:
    meta = json.loads((song_dir / "meta.json").read_text())
    arrays = {k: np.load(song_dir / f"{k}.npy", mmap_mode="r") for k in GROUPS + REAL}
    return meta, arrays


def list_songs(root: Path, split: str | None = None) -> list[Path]:
    out = []
    for meta in sorted(root.glob("*/*/meta.json")):
        if split is None or json.loads(meta.read_text()).get("split") == split:
            out.append(meta.parent)
    return out

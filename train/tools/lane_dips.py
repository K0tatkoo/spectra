"""Where a model's bass and vocals dim against the app's lanes on the owner's songs, and where the
missing sound went. v2's bass dimmed for seconds at a time because it handed the 808 to drums, and
its vocals dimmed into synth (train/STEMS.md, "Why v2's bass dims").

    python tools/lane_dips.py --ours D:/stems-clean/listen-stems-v2 --app D:/synthsplit/hardstyle-renders

--ours is tools/separate.py's output (<song>/{mix,vocals,...}.wav); --app is tools/render_many.py's,
paired as tools/compare_listen.py pairs them. A frame (23 ms, smoothed over ~93 ms) counts where the
app's lane plays (within 15 dB of its loud end); a dip is where ours sits > 6 dB under the app's,
after the song's median offset between the two.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from ownstems import RATE, STEMS  # noqa: E402
from compare_listen import app_files, safe  # noqa: E402

HOP = 1024
PROBES = {"bass": (0.0, 120.0, "drums"), "vocals": (300.0, 4000.0, "synth")}  # lane, band, where it went


def power(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    f, _, z = signal.stft(x, RATE, nperseg=4096, noverlap=4096 - HOP, boundary=None, padded=False)
    return (np.abs(z[(f >= lo) & (f < hi)]) ** 2).sum(0)


def smooth_db(p: np.ndarray) -> np.ndarray:
    return 10 * np.log10(np.convolve(p, np.ones(4) / 4, mode="same") + 1e-12)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ours", required=True, type=Path)
    ap.add_argument("--app", required=True, type=Path)
    ap.add_argument("--splitter", default="hs-last")
    ap.add_argument("--json", type=Path)
    args = ap.parse_args()
    fr = HOP / RATE
    stats = {p: {"dip": [], "deep": [], "runs": [], "share_dip": np.zeros(len(STEMS)), "share_ok": np.zeros(len(STEMS))}
             for p in PROBES}
    longest = []
    songs = sorted(d for d in args.ours.iterdir() if (d / "mix.wav").exists())
    for d in songs:
        app = app_files(args.app / safe(d.name), args.splitter)
        ours = np.stack([sf.read(d / f"{s}.wav", dtype="float32", always_2d=True)[0].mean(1) for s in STEMS])
        for lane, (lo, hi, _) in PROBES.items():
            a = smooth_db(power(sf.read(app[lane], dtype="float32", always_2d=True)[0].mean(1), lo, hi))
            P = np.stack([power(x, lo, hi) for x in ours])
            m = min(len(a), P.shape[1])
            a, P = a[:m], P[:, :m]
            o = smooth_db(P[STEMS.index(lane)])
            act = a > np.percentile(a, 90) - 15
            diff = o - a
            diff -= np.median(diff[act])
            dip = act & (diff < -6)
            st = stats[lane]
            st["dip"].append(dip.sum() / act.sum() * 100)
            st["deep"].append((act & (diff < -10)).sum() / act.sum() * 100)
            st["share_dip"] += P[:, dip].sum(1)
            st["share_ok"] += P[:, act & ~dip].sum(1)
            i = 0
            while i < m:  # dips, letting gaps of up to two frames inside one
                if dip[i]:
                    j = i
                    while j < m and dip[j:j + 3].any():
                        j += 1
                    st["runs"].append((j - i) * fr)
                    if lane == "bass":
                        longest.append(((j - i) * fr, d.name, i * fr))
                    i = j
                else:
                    i += 1
    out: dict = {}
    for lane, (lo, hi, went) in PROBES.items():
        st = stats[lane]
        runs = np.array(st["runs"]) if st["runs"] else np.zeros(1)
        sd, so = st["share_dip"] / max(st["share_dip"].sum(), 1e-30), st["share_ok"] / st["share_ok"].sum()
        row = {"dip_pct": round(float(np.mean(st["dip"])), 1), "deep_pct": round(float(np.mean(st["deep"])), 1),
               "long_share_pct": round(float(runs[runs >= 1].sum() / max(runs.sum(), 1e-9) * 100), 0),
               f"to_{went}_in_dips_pct": round(float(sd[STEMS.index(went)] * 100), 0),
               f"to_{went}_elsewhere_pct": round(float(so[STEMS.index(went)] * 100), 0)}
        out[lane] = row
        print(f"{lane} ({lo:g}-{hi:g} Hz), {len(songs)} songs: dips > 6 dB under the app in {row['dip_pct']} % of the "
              f"frames where the app's lane plays (> 10 dB: {row['deep_pct']} %); {row['long_share_pct']:.0f} % of the "
              f"dipped time in dips of 1 s or more; in dips {row[f'to_{went}_in_dips_pct']:.0f} % of that band sits in "
              f"{went} (elsewhere {row[f'to_{went}_elsewhere_pct']:.0f} %)")
    print("longest bass dips:")
    for length, name, t in sorted(longest, reverse=True)[:8]:
        print(f"  {length:5.1f} s at {int(t // 60)}:{t % 60:04.1f}  {name}")
    out["longest_bass_dips"] = [{"s": round(a, 1), "song": b, "at": round(c, 1)} for a, b, c in sorted(longest, reverse=True)[:8]]
    if args.json:
        args.json.write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()

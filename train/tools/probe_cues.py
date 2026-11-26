"""Which cue makes a model call a sound "drums"? Clean held-out drums (E-GMD) and generated hardstyle
drums, each changed one way at a time, are fed to the model alone, and the table shows where their
energy above 2 kHz goes. v1 sent half of it to vocals once the top octave was cut (train/STEMS.md); a
model that hears drums by their sound keeps them in drums whatever the top end.

Two more since v2 (train/STEMS.md, "Why v2's bass dims"): hardstyle kicks alone, distorted as the
generator makes them and clean as an 808, decaying or held, with the share of their energy below
120 Hz that lands in bass (v2 gave a clean boom 91 % to drums); and held-out ccMixter vocals dry,
produced and driven hard, with the share of all their energy per lane (v2 gave driven vocals
25-29 % to synth).

    python tools/probe_cues.py --root D:/stems-clean --ckpt runs/v2/best.pt --device cuda
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ownstems import RATE, STEMS  # noqa: E402
from ownstems.audio import Library  # noqa: E402
from ownstems.mixer import _reverb, lowpass_sos  # noqa: E402
from ownstems.model import net_from_checkpoint, separate  # noqa: E402


def mp3(x: np.ndarray) -> np.ndarray:
    """An MP3 round trip (libsndfile's LAME at its default quality), trimmed or padded to length."""
    b = io.BytesIO()
    sf.write(b, x.T.astype(np.float32), RATE, format="MP3")
    b.seek(0)
    y = sf.read(b, always_2d=True, dtype="float32")[0].T
    return y[:, :x.shape[-1]] if y.shape[-1] >= x.shape[-1] else np.pad(y, ((0, 0), (0, x.shape[-1] - y.shape[-1])))


def lowpass(x: np.ndarray, hz: float) -> np.ndarray:
    return signal.sosfilt(lowpass_sos(np.random.default_rng(0), hz), x, axis=-1)


VARIANTS = {
    "as is": lambda x, r: x,
    "low-passed 16 kHz": lambda x, r: lowpass(x, 16000),
    "low-passed 12 kHz": lambda x, r: lowpass(x, 12000),
    "MP3 round trip": lambda x, r: mp3(x),
    "reverb": lambda x, r: _reverb(x, r),
}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, required=True, help="the data root (ledger, catalog, sources)")
    ap.add_argument("--ckpt", type=Path, required=True)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--clips", type=int, default=12)
    ap.add_argument("--json", type=Path, help="also write the table here")
    args = ap.parse_args()

    ck = torch.load(args.ckpt, map_location="cpu", weights_only=False)
    net = net_from_checkpoint(ck).eval().to(args.device)

    def lanes_hi(x: np.ndarray) -> np.ndarray:
        est = separate(net, torch.from_numpy(np.ascontiguousarray(x, dtype=np.float32))).numpy()
        out = []
        for j in range(len(STEMS)):
            f, _, z = signal.stft(est[j].mean(0), RATE, nperseg=4096, noverlap=2048)
            out.append((np.abs(z) ** 2).sum(1)[f >= 2000].sum())
        return np.array(out)

    def lanes_band(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
        est = separate(net, torch.from_numpy(np.ascontiguousarray(x, dtype=np.float32))).numpy()
        f, _, z = signal.stft(est.mean(1), RATE, nperseg=4096, noverlap=2048)
        return (np.abs(z[:, (f >= lo) & (f < hi)]) ** 2).sum((1, 2))

    def level(x: np.ndarray) -> np.ndarray:
        return x / (np.sqrt(np.mean(x ** 2)) + 1e-9) * 0.1

    rng = np.random.default_rng(5)
    lib = Library(args.root)
    pool = [c for c in lib.catalog.select(stem="drums", split="valid", source="egmd") if c.seconds >= 6]
    egmd = []
    for _ in range(args.clips):
        c = pool[int(rng.integers(len(pool)))]
        x = lib.open(c, rng.uniform(0, c.seconds - 6), 6.0)
        egmd.append(level(np.repeat(x[:1], 2, 0) if x.shape[0] == 1 else x[:2]))
    from synthsplit import hardstyle

    gen = []
    for i in range(args.clips):
        r, n = np.random.default_rng([99, i]), 6 * RATE
        mu = hardstyle.music(r, 6.5)
        d, _, _ = hardstyle.render_drums(r, mu, n, bass_out=np.zeros((2, n)))
        gen.append(level(d[:, :n]))

    table: dict = {}
    for name, clips in (("E-GMD drums (held out)", egmd), ("generated hardstyle drums", gen)):
        print(f"\n{name}, {len(clips)} x 6 s fed alone: % of the energy above 2 kHz in each lane")
        print(f"  {'variant':20}" + "".join(f"{s:>8}" for s in STEMS))
        for vn, fn in VARIANTS.items():
            r = np.random.default_rng(11)
            acc = sum(lanes_hi(fn(x, r)) for x in clips)
            sh = acc / acc.sum() * 100
            table.setdefault(name, {})[vn] = {s: round(float(v), 1) for s, v in zip(STEMS, sh)}
            print(f"  {vn:20}" + "".join(f"{v:8.0f}" for v in sh))

    beat = int(60 / 150 * RATE)
    kicks = {"distorted, decaying (as trained)": {}, "distorted, held tail": {"hold": 0.6},
             "clean 808, decaying": {"clean": True}, "clean 808, held": {"clean": True, "hold": 0.6},
             "bare sine boom, held": None}

    def bare_boom(r, n, root):  # the pitch drop and a held tail, no drive, no click
        t = np.arange(n) / RATE
        f = root + (r.uniform(250, 650) - root) * np.exp(-t / r.uniform(0.008, 0.04))
        env = np.minimum(t / 0.0007, 1) * (0.55 + 0.45 * np.exp(-t / 0.03)) * np.clip((n / RATE - 0.03 - t) / 0.02, 0, 1)
        return np.sin(2 * np.pi * np.cumsum(f) / RATE) * env
    name = "kicks alone, 16 at 150 BPM, 8 kits"
    print(f"\n{name}: % of the energy below 120 Hz in each lane")
    print(f"  {'kick':34}" + "".join(f"{s:>8}" for s in STEMS))
    for kn, kw in kicks.items():
        acc = np.zeros(len(STEMS))
        for seed in range(8):
            r = np.random.default_rng([7, seed])
            root, raw = r.uniform(38, 55), r.uniform(0, 1)
            x = np.concatenate([bare_boom(r, beat, root) if kw is None else hardstyle.kick(r, beat, root, raw, **kw)
                                for _ in range(16)])
            acc += lanes_band(level(np.stack([x, x])), 0, 120)
        sh = acc / acc.sum() * 100
        table.setdefault(name, {})[kn] = {s: round(float(v), 1) for s, v in zip(STEMS, sh)}
        print(f"  {kn:34}" + "".join(f"{v:8.0f}" for v in sh))

    from ownstems.mixer import brighten, vocal_chain

    def drive(x, r):
        p = np.max(np.abs(x)) + 1e-9
        return np.tanh(r.uniform(8, 20) * x / p) * p

    pool = [c for c in lib.catalog.select(stem="vocals", split="valid", source="ccmixter") if c.seconds >= 6]
    pick = np.random.default_rng(1).choice(len(pool), min(10, len(pool)), replace=False)
    vox = []
    for i in pick:
        x = lib.open(pool[int(i)], 0.0, 6.0)
        vox.append(np.repeat(x[:1], 2, 0) if x.shape[0] == 1 else x[:2])
    name = "ccMixter vocals (held out)"
    print(f"\n{name}, {len(vox)} x 6 s fed alone: % of all their energy in each lane")
    print(f"  {'variant':34}" + "".join(f"{s:>8}" for s in STEMS))
    for vn, fn in {"dry": lambda x, r: x, "vocal chain (as trained)": vocal_chain, "driven hard (tanh 8-20)": drive,
                   "brighten()": brighten}.items():
        acc = np.zeros(len(STEMS))
        for k, x in enumerate(vox):
            acc += lanes_band(level(fn(x.astype(np.float64), np.random.default_rng(k))), 0, RATE / 2 + 1)
        sh = acc / acc.sum() * 100
        table.setdefault(name, {})[vn] = {s: round(float(v), 1) for s, v in zip(STEMS, sh)}
        print(f"  {vn:34}" + "".join(f"{v:8.0f}" for v in sh))
    if args.json:
        args.json.write_text(json.dumps(table, indent=1))


if __name__ == "__main__":
    main()

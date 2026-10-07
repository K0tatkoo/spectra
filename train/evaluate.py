"""Scores a trained splitter on the held-out songs, song by song.

    python evaluate.py --data D:/data/synthsplit --run runs/first
    python evaluate.py --data D:/data/synthsplit --onnx ../app/src/main/assets/stems/synth-split.onnx

Every held-out song is run from its start in 30 s excerpts, as the app's stem
model heard it, and the synth lane is scored (floored SDR, dB) against the
song's true synth — next to the answers that need no network. Excerpts with
synth playing and excerpts without (under −30 dB of the inputs) are summed up
apart: the second run's trouble was calling other instruments synth, which
only the silent ones show. With --onnx the exported file is run frame by frame
through the reference signal path, so the number is the one the phone would get.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import numpy as np
import torch

from synthsplit.corpus import list_songs, load_song
from synthsplit.dsp import SplitDSP
from synthsplit.layout import Layout
from synthsplit.losses import floored_sdr_loss
from synthsplit.model import net_from_checkpoint, split_batch
from synthsplit.reference import StreamSplit

EXCERPT_SECONDS = float(os.environ.get("EVAL_EXCERPT_S", "30"))


def sdr(est: np.ndarray, ref: np.ndarray, inputs: np.ndarray) -> float:
    e = torch.from_numpy(est[None].astype(np.float32))
    r = torch.from_numpy(ref[None].astype(np.float32))
    energy = torch.tensor([float(np.square(inputs, dtype=np.float64).sum())])
    return -float(floored_sdr_loss(e, r, energy)[0])


def onnx_runner(path: Path):
    import onnx
    import onnxruntime as ort

    meta = {p.key: p.value for p in onnx.load(str(path)).metadata_props}
    lay = Layout(sample_rate=int(meta["sample_rate"]), n_fft=int(meta["n_fft"]), hop=int(meta["hop"]),
                 inputs=tuple(meta["inputs"].split(",")), edges=tuple(int(e) for e in meta["band_edges"].split(",")))
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    state_shape = [d for d in sess.get_inputs() if d.name == "state"][0].shape

    def run(x: np.ndarray) -> np.ndarray:
        state = [np.zeros(state_shape, dtype=np.float32)]

        def mask_fn(power, frame):
            m, state[0] = sess.run(["mask", "next_state"], {"power": power.reshape(1, 1, -1).astype(np.float32),
                                                             "state": state[0]})
            return m.reshape(power.shape).astype(np.float64)

        split = StreamSplit(lay, mask_fn)
        n, h = lay.n_fft, lay.hop
        padded = np.concatenate([np.zeros((x.shape[0], n)), x], axis=1)
        out = []
        for pos in range(h, x.shape[1] + 1, h):
            s, _, _ = split.frame(padded[:, pos:pos + n])
            if pos >= 2 * h:
                out.append(s)
        return np.concatenate(out)

    return lay, run


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, type=Path)
    ap.add_argument("--ckpt", default="best.pt", help="which checkpoint in the run folder: best.pt or checkpoint.pt")
    ap.add_argument("--run", type=Path)
    ap.add_argument("--onnx", type=Path)
    ap.add_argument("--out", type=Path, help="write per-song scores here as JSON")
    args = ap.parse_args()

    if args.onnx:
        lay, run = onnx_runner(args.onnx)
    else:
        ckpt = torch.load(args.run / args.ckpt, map_location="cpu")
        d = ckpt["layout"]
        lay = Layout(sample_rate=d["sample_rate"], n_fft=d["n_fft"], hop=d["hop"], inputs=tuple(d["inputs"]),
                     edges=tuple(d["edges"]))
        net = net_from_checkpoint(ckpt, lay)
        net.load_state_dict(ckpt["ema"])
        net.eval()
        dsp = SplitDSP(lay)

        def run(x: np.ndarray) -> np.ndarray:
            with torch.no_grad():
                est, _, _ = split_batch(dsp, net, torch.from_numpy(x.astype(np.float32))[None])
            return est[0].numpy()

    # A whole number of hops: the signal path works in whole frames.
    excerpt = int(EXCERPT_SECONDS * 44100) // lay.hop * lay.hop
    rows = []
    for song_dir in list_songs(args.data, "valid"):
        meta, arrays = load_song(song_dir)
        total = meta["samples"] // lay.hop * lay.hop
        for start in range(0, total - excerpt + 1, excerpt):
            x = np.stack([np.asarray(arrays["sg_vocals"][start:start + excerpt], dtype=np.float64),
                          np.asarray(arrays["sg_other"][start:start + excerpt], dtype=np.float64)])
            ref = np.asarray(arrays["synth"][start:start + excerpt - lay.hop], dtype=np.float64)
            est = run(x)
            warm = 44100 // 2
            inputs = x[:, warm:excerpt - lay.hop].sum(0)
            playing = float(np.sum(ref[warm:] ** 2)) >= 1e-3 * float(np.sum(inputs ** 2))
            row = {"song": song_dir.name, "corpus": meta["corpus"], "start_s": start / 44100, "playing": playing,
                   "sdr": sdr(est[warm:], ref[warm:], inputs),
                   "none": sdr(np.zeros_like(ref[warm:]), ref[warm:], inputs),
                   "other": sdr(x[1, warm:excerpt - lay.hop], ref[warm:], inputs),
                   "vocals_other": sdr(inputs, ref[warm:], inputs)}
            rows.append(row)
            print(json.dumps(row), flush=True)
    for corpus in sorted({r["corpus"] for r in rows}):
        for playing, label in ((True, "synth playing"), (False, "no synth")):
            rs = [r for r in rows if r["corpus"] == corpus and r["playing"] == playing]
            if rs:
                means = {k: round(float(np.mean([r[k] for r in rs])), 2) for k in ("sdr", "none", "other", "vocals_other")}
                print(f"{corpus} ({label}): {len(rs)} excerpts · {means}")
    if args.out:
        args.out.write_text(json.dumps(rows, indent=1) + "\n")


if __name__ == "__main__":
    main()

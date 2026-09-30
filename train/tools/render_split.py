"""Renders what the phone's Stems page would separate from any song, as WAVs.

    python tools/render_split.py song.flac --onnx ../app/src/main/assets/stems/synth-split.onnx --out renders/song

Writes the stem model's four stems, then the splitter's three lanes — synth,
vocals without synth, other without synth — all mono, 44.1 kHz. For listening
to a trained model on music no dataset has stems for (hardstyle, say), and for
comparing it with an offline separator's synth stem.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate import onnx_runner  # noqa: E402
from synthsplit import stemgen  # noqa: E402
from synthsplit.reference import StreamSplit  # noqa: E402


def load(path: Path) -> np.ndarray:
    audio, rate = sf.read(str(path), dtype="float32", always_2d=True)
    audio = audio.T
    if audio.shape[0] == 1:
        audio = np.repeat(audio, 2, axis=0)
    audio = audio[:2]
    if rate != stemgen.SAMPLE_RATE:
        import soxr

        audio = soxr.resample(audio.T, rate, stemgen.SAMPLE_RATE).T.astype(np.float32)
    return audio


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("song", type=Path)
    ap.add_argument("--onnx", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    ap.add_argument("--seconds", type=float, default=0, help="only the first this many seconds (0 = all)")
    args = ap.parse_args()

    mix = load(args.song)
    if args.seconds:
        mix = mix[:, :int(args.seconds * stemgen.SAMPLE_RATE)]
    sg = stemgen.StemgenRT(stemgen.fetch_model(args.model_cache))
    stems = sg.separate(mix)
    args.out.mkdir(parents=True, exist_ok=True)
    rate = stemgen.SAMPLE_RATE
    for name, s in zip(stemgen.ORDER, stems):
        sf.write(args.out / f"stem_{name}.wav", s.mean(axis=0), rate)

    lay, _ = onnx_runner(args.onnx)
    mono = {name: s.mean(axis=0).astype(np.float64) for name, s in zip(stemgen.ORDER, stems)}
    x = np.stack([mono[i] for i in lay.inputs])
    x = x[:, :x.shape[1] // lay.hop * lay.hop]

    import onnxruntime as ort

    sess = ort.InferenceSession(str(args.onnx), providers=["CPUExecutionProvider"])
    state = [np.zeros([d for d in sess.get_inputs() if d.name == "state"][0].shape, dtype=np.float32)]

    def mask_fn(power, frame):
        m, state[0] = sess.run(["mask", "next_state"], {"power": power.reshape(1, 1, -1).astype(np.float32),
                                                         "state": state[0]})
        return m.reshape(power.shape).astype(np.float64)

    split = StreamSplit(lay, mask_fn)
    n, h = lay.n_fft, lay.hop
    padded = np.concatenate([np.zeros((x.shape[0], n)), x], axis=1)
    synth, rests = [], []
    for pos in range(h, x.shape[1] + 1, h):
        s, r, _ = split.frame(padded[:, pos:pos + n])
        if pos >= 2 * h:
            synth.append(s)
            rests.append(r)
    sf.write(args.out / "synth.wav", np.concatenate(synth), rate)
    rests = np.concatenate(rests, axis=1)
    for name, r in zip(lay.inputs, rests):
        sf.write(args.out / f"{name}_without_synth.wav", r, rate)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()

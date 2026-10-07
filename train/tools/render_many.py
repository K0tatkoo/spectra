"""Renders a folder of songs through the stem model once and through several splitters, for listening.

    python tools/render_many.py --songs D:/training_songs_hardstyle --out D:/synthsplit/hardstyle-renders \\
        --model all=D:/synthsplit/runs/third-all/synth-split-last.onnx \\
        --model conv=D:/synthsplit/runs/third-conv/synth-split-last.onnx --workers 15

Per song, in <out>/<song>/: the stem model's four stems (stem_*.wav), then for
each splitter its synth lane (synth_<name>.wav) and what is left of vocals and
other (vocals_without_synth_<name>.wav, ...), all mono 44.1 kHz. The stem model
is the slow part, so it runs once per song however many splitters there are;
songs run in parallel, one process each. Reads anything soundfile reads (MP3
included). summary.txt says how much of vocals + other each splitter called
synth, song by song: a number to look at next to the listening, not a score.
"""

from __future__ import annotations

import os

os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")  # see train.py: one thread per worker, or Windows runs out of commit

import argparse
import re
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate import onnx_runner  # noqa: E402
from synthsplit import stemgen  # noqa: E402
from synthsplit.reference import StreamSplit  # noqa: E402
from tools.render_split import load  # noqa: E402

AUDIO = {".mp3", ".flac", ".wav", ".ogg", ".aif", ".aiff"}


def safe(name: str) -> str:
    return re.sub(r"[^\w\-.]+", "_", name).strip("_")[:80]


def split_with(onnx: Path, mono: dict[str, np.ndarray]):
    import onnxruntime as ort

    lay, _ = onnx_runner(onnx)
    x = np.stack([mono[i] for i in lay.inputs])
    x = x[:, :x.shape[1] // lay.hop * lay.hop]
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(onnx), so, providers=["CPUExecutionProvider"])
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
    return lay.inputs, np.concatenate(synth), np.concatenate(rests, axis=1), x


def render(song: str, out_root: str, models: list[tuple[str, str]], model_cache: str) -> str:
    song_path = Path(song)
    out = Path(out_root) / safe(song_path.stem)
    out.mkdir(parents=True, exist_ok=True)
    rate = stemgen.SAMPLE_RATE
    mix = load(song_path)
    sg = stemgen.StemgenRT(Path(model_cache))
    stems = sg.separate(mix)
    mono = {name: s.mean(axis=0).astype(np.float64) for name, s in zip(stemgen.ORDER, stems)}
    for name, s in mono.items():
        sf.write(out / f"stem_{name}.wav", s.astype(np.float32), rate)
    lines = []
    for label, onnx in models:
        inputs, synth, rests, x = split_with(Path(onnx), mono)
        sf.write(out / f"synth_{label}.wav", synth.astype(np.float32), rate)
        for name, r in zip(inputs, rests):
            sf.write(out / f"{name}_without_synth_{label}.wav", r.astype(np.float32), rate)
        total = float(np.sum(x[:, :synth.shape[0]].sum(0) ** 2)) or 1.0
        share = float(np.sum(synth ** 2)) / total
        lines.append(f"{label}: {100 * share:.1f} % of vocals+other called synth")
    return f"{song_path.stem}\n  " + "\n  ".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--songs", required=True, type=Path, help="a folder of songs, or one song")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--model", action="append", required=True, help="name=path/to/synth-split.onnx (repeatable)")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    args = ap.parse_args()

    models = [tuple(m.split("=", 1)) for m in args.model]
    songs = [args.songs] if args.songs.is_file() else sorted(p for p in args.songs.iterdir() if p.suffix.lower() in AUDIO)
    cache = str(stemgen.fetch_model(args.model_cache))
    args.out.mkdir(parents=True, exist_ok=True)
    print(f"{len(songs)} songs x {len(models)} splitters -> {args.out}", flush=True)
    results = []
    with ProcessPoolExecutor(min(args.workers, len(songs))) as pool:
        futures = [pool.submit(render, str(s), str(args.out), models, cache) for s in songs]
        for i, f in enumerate(as_completed(futures), 1):
            results.append(f.result())
            print(f"[{i}/{len(songs)}] {results[-1]}", flush=True)
    (args.out / "summary.txt").write_text("\n".join(sorted(results)) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

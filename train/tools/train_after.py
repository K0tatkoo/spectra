"""One training run start to finish, nobody watching: train, score, export, render. Any OS.

    python tools/train_after.py --root D:/synthsplit --run third --wait -- --steps 120000 --workers 8

With --wait it first waits for tools/prepare_all.py to log "all done"; with
--after NAME, for run NAME to finish (to queue a second run behind a first). Then:
train.py (everything after -- is passed on) -> evaluate.py on best.pt ->
export.py -> evaluate.py on the export, as the phone would run it -> the
listening songs in <root>/listen rendered through it. Run folder
<root>/runs/<run>; logs <root>/logs/<run>-*.log; milestones in pipeline.log.
Run again with --resume after -- to carry on a stopped run.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

T = Path(__file__).resolve().parents[1]  # train/
PY = sys.executable


def main():
    argv = sys.argv[1:]
    passed = argv[argv.index("--") + 1:] if "--" in argv else []
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--run", required=True)
    ap.add_argument("--wait", action="store_true", help="wait for prepare_all.py to finish first")
    ap.add_argument("--after", help="wait for this run to finish first")
    args = ap.parse_args(argv[:argv.index("--")] if "--" in argv else argv)

    logs = args.root / "logs"
    pipeline = logs / "pipeline.log"
    run_dir = args.root / "runs" / args.run
    run_dir.mkdir(parents=True, exist_ok=True)

    def log(msg: str):
        line = f"[{time.strftime('%a %H:%M:%S')}] {args.run}: {msg}"
        print(line, flush=True)
        with open(pipeline, "a", encoding="utf-8") as f:
            f.write(line + "\n")

    def run(name: str, *cmd) -> bool:
        with open(logs / f"{args.run}-{name}.log", "a", encoding="utf-8") as out:
            rc = subprocess.call([PY, "-u", *map(str, cmd)], cwd=T, stdout=out, stderr=subprocess.STDOUT)
        lines = (logs / f"{args.run}-{name}.log").read_text(encoding="utf-8", errors="replace").strip().splitlines()
        log(f"{name} {'done' if rc == 0 else f'FAILED (exit {rc})'} · {(lines[-1] if lines else '')[:300]}")
        return rc == 0

    waits = (["] all done"] if args.wait else []) + ([f"] {args.after}: all done"] if args.after else [])
    if waits:
        log(f"waiting for: {', '.join(waits)}")
        while True:
            text = pipeline.read_text(encoding="utf-8", errors="replace") if pipeline.exists() else ""
            if all(w in text for w in waits):
                break
            time.sleep(60)
    corpus = args.root / "corpus"
    log(f"training: {' '.join(passed)}")
    started = time.time()
    trained = run("train", "train.py", "--data", corpus, "--out", run_dir, *passed)
    result = run_dir / "result.json"
    if not trained and not (result.exists() and result.stat().st_mtime > started):
        return 1  # a crash after result.json is written (Windows, on exit) still counts as trained
    ckpt = "best.pt" if (run_dir / "best.pt").exists() else "checkpoint.pt"
    run("eval", "evaluate.py", "--data", corpus, "--run", run_dir, "--ckpt", ckpt, "--out", run_dir / "eval.json")
    onnx = run_dir / "synth-split.onnx"
    if run("export", "export.py", "--run", run_dir, "--ckpt", ckpt, "--out", onnx):
        run("eval-onnx", "evaluate.py", "--data", corpus, "--onnx", onnx, "--out", run_dir / "eval-onnx.json")
        for mixture in sorted((args.root / "listen").glob("*/mixture.wav")):
            run("render", "tools/render_split.py", mixture, "--onnx", onnx, "--out", run_dir / "renders" / mixture.parent.name,
                "--seconds", 90)
    log("all done")
    return 0


if __name__ == "__main__":
    sys.exit(main())

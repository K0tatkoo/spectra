"""Builds the whole corpus, step by step, with nobody watching. Any OS.

    python tools/prepare_all.py --root D:/synthsplit
    python tools/prepare_all.py --root D:/synthsplit --moisesdb C:/Users/Kotatko/Downloads/moisesdb.zip

Expects the downloads already running or done (tools/fetch_parallel.py for
MUSDB18-HQ into <root>/downloads, tools/fetch_slakh.py into <root>/slakh), and
then, in order:

1. waits for musdb18hq.zip, takes its vocals (prepare/musdb_vocals.py) and two
   held-out mixtures to listen to (<root>/listen);
2. Slakh songs as their stems land, until the download is done (prepare/slakh.py --watch);
3. with --moisesdb: waits for it to be there (fetch_parallel.py only gives the
   zip its name once its CRCs check out), then MoisesDB itself;
4. made-up synths over Slakh backing tracks (prepare/procedural.py), then
   over MoisesDB's.

The real corpora come first, so a run on real music alone can start while
the made-up synths are still being made. The stem model is the cost: about
4.5 s of audio a second on the desktop (one call per 128 samples, 11 ms, no
batching), so the sizes below are what fits in a night.

Each step logs "<step>: done" to pipeline.log, so a training run can start
after any of them (tools/train_after.py --wait-step).

Every step skips what is already done, so running it again carries on. The
corpus lands in <root>/corpus, logs in <root>/logs (pipeline.log says what
happened when). Training is started separately (train.py), once the corpus
is looked at.
"""

from __future__ import annotations

import os

# numpy's and scipy's bundled OpenBLAS reserve memory for every core in every
# process the moment they are imported: 1.5 GB a process on the 24-thread
# desktop, so a pool of workers hits Windows' commit limit. Each worker here
# computes on one thread anyway.
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")

import argparse
import subprocess
import sys
import time
import zipfile
from pathlib import Path

T = Path(__file__).resolve().parents[1]  # train/
PY = sys.executable
# Children write their logs in UTF-8 and so does this; Windows would use cp1252,
# which cannot print the "·" the scripts' summaries use, and a print would crash this.
ENV = {**os.environ, "PYTHONIOENCODING": "utf-8"}
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--moisesdb", type=Path, help="moisesdb.zip, or the folder it was unpacked to")
    ap.add_argument("--workers", type=int, default=14,
                    help="stem model processes for whole songs (one core each; ~1 GB each while a song is loaded)")
    ap.add_argument("--light-workers", type=int, default=0,
                    help="stem model processes for the made-up-synth songs, which are short (default: --workers)")
    ap.add_argument("--moisesdb-workers", type=int, default=0,
                    help="stem model processes for MoisesDB, fewer if a training run shares the machine (default: --workers)")
    ap.add_argument("--slakh-train", type=int, default=750, help="Slakh training songs (plus 60 held out)")
    ap.add_argument("--moisesdb-seconds", type=float, default=0,
                    help="keep this much of each MoisesDB song, where its synth is (0 = whole songs)")
    ap.add_argument("--procedural", type=int, default=1600, help="made-up-synth training songs over Slakh backing")
    ap.add_argument("--procedural-moisesdb", type=int, default=-1,
                    help="... over MoisesDB backing (default: half of --procedural)")
    ap.add_argument("--procedural-seconds", type=float, default=40)
    args = ap.parse_args()
    light = args.light_workers or args.workers

    root = args.root
    logs = root / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    corpus = root / "corpus"
    vocals = root / "musdb-vocals"
    musdb = root / "downloads" / "musdb18hq.zip"

    def log(msg: str):
        line = f"[{time.strftime('%a %H:%M:%S')}] {msg}"
        print(line, flush=True)
        with open(logs / "pipeline.log", "a", encoding="utf-8") as f:
            f.write(line + "\n")

    def run(name: str, *argv) -> bool:
        log(f"{name}: start")
        with open(logs / f"{name}.log", "a", encoding="utf-8") as out:
            rc = subprocess.call([PY, "-u", *map(str, argv)], cwd=T, env=ENV, stdout=out, stderr=subprocess.STDOUT)
        tail = (logs / f"{name}.log").read_text(encoding="utf-8", errors="replace").strip().splitlines()[-1:] or [""]
        log(f"{name}: {'done' if rc == 0 else f'FAILED (exit {rc})'} · {tail[0][:200]}")
        return rc == 0

    log("start")
    # 1. MUSDB18-HQ's vocals, once the zip is whole (fetch_parallel only renames it into place when the MD5 matches).
    while not musdb.exists():
        time.sleep(60)
    if not (vocals / "index.json").exists():
        if not run("musdb-vocals", "prepare/musdb_vocals.py", "--zip", musdb, "--out", vocals):
            return 1
    listen = root / "listen"
    if not listen.exists():
        with zipfile.ZipFile(musdb) as z:
            mixes = sorted(n for n in z.namelist() if "/test/" in "/" + n and n.endswith("/mixture.wav"))
            wanted = [m for m in mixes if any(k in m for k in ("Skelpolu - Resurrection",
                                                                 "Ben Carrigan - We'll Talk About It All Tonight"))]
            for name in (wanted + [m for m in mixes if m not in wanted])[:2]:
                song = listen / Path(name).parts[-2].replace(" ", "_")
                song.mkdir(parents=True, exist_ok=True)
                (song / "mixture.wav").write_bytes(z.read(name))
                log(f"listen: {song.name}")

    # 2. Slakh, as its stems arrive; returns once the download is done.
    run("slakh-prepare", "prepare/slakh.py", "--slakh", root / "slakh", "--vocals", vocals, "--out", corpus,
        "--workers", args.workers, "--watch", "--max-train", args.slakh_train, "--max-valid", 60)
    if not (root / "slakh" / "DOWNLOAD_DONE").exists():
        log("slakh download not finished: stopping here (run again once it is)")
        return 1

    # 3. MoisesDB.
    if args.moisesdb:
        if not args.moisesdb.exists():
            log(f"waiting for {args.moisesdb}")
            while not args.moisesdb.exists():
                time.sleep(60)
        run("moisesdb-prepare", "prepare/moisesdb.py", "--moisesdb", args.moisesdb, "--out", corpus,
            "--workers", args.moisesdb_workers or args.workers, "--max-seconds", args.moisesdb_seconds)

    # 4. Made-up synths over Slakh backing, then over MoisesDB's.
    seconds = ("--seconds", args.procedural_seconds)
    run("procedural-slakh", "prepare/procedural.py", "--source", "slakh", "--slakh", root / "slakh", "--vocals", vocals,
        "--out", corpus, "--train", args.procedural, "--valid", 80, "--workers", light, *seconds)
    if args.moisesdb:
        count = args.procedural // 2 if args.procedural_moisesdb < 0 else args.procedural_moisesdb
        run("procedural-moisesdb", "prepare/procedural.py", "--source", "moisesdb", "--moisesdb", args.moisesdb,
            "--vocals", vocals, "--out", corpus, "--train", count, "--valid", 40, "--workers", light, *seconds)
    songs = {d.name: len(list(d.glob("*/meta.json"))) for d in sorted(corpus.glob("*")) if d.is_dir()}
    log(f"all done · corpus: {songs}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

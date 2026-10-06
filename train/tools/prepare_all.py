"""Builds the whole corpus, step by step, with nobody watching. Any OS.

    python tools/prepare_all.py --root D:/synthsplit
    python tools/prepare_all.py --root D:/synthsplit --moisesdb D:/synthsplit/moisesdb/moisesdb_v0.1

Expects the downloads already running or done (tools/fetch_parallel.py for
MUSDB18-HQ into <root>/downloads, tools/fetch_slakh.py into <root>/slakh), and
then, in order:

1. waits for musdb18hq.zip, takes its vocals (prepare/musdb_vocals.py) and two
   held-out mixtures to listen to (<root>/listen);
2. Slakh songs as their stems land, until the download is done (prepare/slakh.py --watch);
3. made-up synths over Slakh backing tracks (prepare/procedural.py);
4. with --moisesdb: MoisesDB itself, then made-up synths over its backing tracks.

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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--moisesdb", type=Path, help="the unpacked MoisesDB (folder holding the provider folders)")
    ap.add_argument("--workers", type=int, default=14, help="stem model processes (one core each)")
    ap.add_argument("--procedural", type=int, default=1600, help="made-up-synth training songs per backing corpus")
    args = ap.parse_args()

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
            rc = subprocess.call([PY, "-u", *map(str, argv)], cwd=T, stdout=out, stderr=subprocess.STDOUT)
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
        "--workers", args.workers, "--watch")
    if not (root / "slakh" / "DOWNLOAD_DONE").exists():
        log("slakh download not finished: stopping here (run again once it is)")
        return 1

    # 3. Made-up synths over Slakh backing.
    run("procedural-slakh", "prepare/procedural.py", "--source", "slakh", "--slakh", root / "slakh", "--vocals", vocals,
        "--out", corpus, "--train", args.procedural, "--valid", 80, "--workers", args.workers)

    # 4. MoisesDB, and made-up synths over its backing.
    if args.moisesdb:
        run("moisesdb-prepare", "prepare/moisesdb.py", "--moisesdb", args.moisesdb, "--out", corpus,
            "--workers", args.workers)
        run("procedural-moisesdb", "prepare/procedural.py", "--source", "moisesdb", "--moisesdb", args.moisesdb,
            "--vocals", vocals, "--out", corpus, "--train", args.procedural // 2, "--valid", 40,
            "--workers", args.workers)
    songs = {d.name: len(list(d.glob("*/meta.json"))) for d in sorted(corpus.glob("*")) if d.is_dir()}
    log(f"all done · corpus: {songs}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Renders the "instruments" lane's made-up and Mutopia parts and the drums lane's jungle and breakcore
breaks, offline, into <root>/sources/rendered-instruments/ (FLAC, mono) with index.jsonl, which
ownstems/sources/rendered_instruments.py indexes into the ledger and catalog.

    python tools/render_instruments.py --root D:/stems-clean --workers 5 --gen-hours 28 --mutopia-hours 8 \\
        --break-hours 6 --reamp-hours 2
    python tools/render_instruments.py --root D:/stems-clean --preview 4     # a listening page of a few

Needs presets.json (tools/instrument_presets.py). Drum kits are checked here (breaks.kit_ok) and kept
in kit-presets.json. Jobs are seeded by their number, so a run can stop and carry on: a job whose file
is already listed in index.jsonl is skipped.
"""

from __future__ import annotations

import os

os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")

import argparse
import json
import sys
import time
from multiprocessing import Pool
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ownstems import breaks, presets, render, sampler  # noqa: E402

GENRES = ["jazz", "pop", "rock", "metal", "breakcore"]
OUT = ("sources", "rendered-instruments")
_R: render.Renderer | None = None


def _init(root: str, inst: list, kits: list, mut: list) -> None:
    global _R
    _R = render.Renderer(Path(root), inst, kits, mut)


def _job(job: dict) -> dict | None:
    rng = np.random.default_rng([20261127, job["n"]])
    t0 = time.time()
    try:
        if job["kind"] == "generated":
            y, meta = _R.generated(rng, job["genre"], job["seconds"])
        elif job["kind"] == "reamp":
            got = _R.reamp(rng, job["seconds"])
            if not got:
                return None
            y, meta = got
        elif job["kind"] == "mutopia":
            got = None
            for _ in range(6):
                rec = _R.mutopia[int(rng.integers(len(_R.mutopia)))]
                got = _R.mutopia_piece(rng, rec, job["seconds"])
                if got:
                    break
            if not got:
                return None
            y, meta = got
        else:
            y, meta = _R.break_part(rng, job["genre"], job["seconds"])
    except Exception as e:  # one bad job must not stop the run
        return {"n": job["n"], "error": f"{type(e).__name__}: {e}"[:300]}
    if float(np.sqrt(np.mean(y ** 2))) < 1e-4:
        return {"n": job["n"], "error": "silent"}
    rel = f"{job['kind']}/{meta['genre']}/{job['n']:05d}.flac"
    seconds = render.write_flac(Path(_R.root, *OUT, rel), y, rng)
    lic, credit = render.licence_of([tuple(c) for c in meta["components"]])
    return {"n": job["n"], "file": rel, "seconds": round(seconds, 2), "stem": "drums" if job["kind"] == "break" else "other",
            "licence": lic, "credit": credit, "render_seconds": round(time.time() - t0, 1), **meta}


def kit_presets(root: Path) -> list[dict]:
    path = presets.raw(root) / "kit-presets.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    names = lambda p: sampler.Store(p).namelist()  # noqa: E731
    ok = []
    for c in presets.kit_candidates(root, names):
        try:
            if breaks.kit_ok(presets.build(c)):
                ok.append(c)
        except Exception:
            pass
    path.write_text(json.dumps(ok, indent=1), encoding="utf-8")
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--seconds", type=float, default=40.0)
    ap.add_argument("--gen-hours", type=float, default=28.0)
    ap.add_argument("--mutopia-hours", type=float, default=8.0)
    ap.add_argument("--break-hours", type=float, default=6.0)
    ap.add_argument("--reamp-hours", type=float, default=2.0)
    ap.add_argument("--preview", type=int, default=0, help="render N of each kind and genre to listen to, then stop")
    args = ap.parse_args()
    inst = json.loads((presets.raw(args.root) / "presets.json").read_text(encoding="utf-8"))
    kits = kit_presets(args.root)
    mut = render.load_mutopia(args.root)
    print(f"{len(inst)} instrument programs, {len(kits)} drum kits, {len(mut)} Mutopia pieces", flush=True)
    per = lambda h: int(h * 3600 / args.seconds)  # noqa: E731
    jobs, n = [], 0
    if args.preview:
        plan = [("generated", g, args.preview) for g in GENRES] + [("mutopia", "classical", args.preview)] + \
               [("break", s, args.preview) for s in ("jungle", "breakcore")] + [("reamp", "guitar", args.preview)]
        base = 90000
    else:
        g, b = per(args.gen_hours), per(args.break_hours)
        plan = [("generated", gen, g // len(GENRES)) for gen in GENRES] + [("mutopia", "classical", per(args.mutopia_hours))] \
            + [("break", s, b // 2) for s in ("jungle", "breakcore")] + [("reamp", "guitar", per(args.reamp_hours))]
        base = 0
    for kind, genre, count in plan:
        for _ in range(count):
            jobs.append({"n": base + n, "kind": kind, "genre": genre, "seconds": args.seconds})
            n += 1
    out = Path(args.root, *OUT)
    out.mkdir(parents=True, exist_ok=True)
    index = out / ("preview.jsonl" if args.preview else "index.jsonl")
    done = {json.loads(line)["n"] for line in index.read_text(encoding="utf-8").splitlines()} if index.exists() else set()
    todo = [j for j in jobs if j["n"] not in done]
    print(f"{len(jobs)} jobs, {len(todo)} to do", flush=True)
    t0, ok, bad, secs = time.time(), 0, 0, 0.0
    with Pool(args.workers, initializer=_init, initargs=(str(args.root), inst, kits, mut)) as pool, \
            open(index, "a", encoding="utf-8") as f:
        for r in pool.imap_unordered(_job, todo, chunksize=2):
            if r is None or "error" in (r or {}):
                bad += 1
                if r:
                    print(f"job {r['n']}: {r['error']}", flush=True)
                continue
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
            f.flush()
            ok += 1
            secs += r["seconds"]
            if ok % 100 == 0:
                print(f"{ok} rendered ({secs / 3600:.1f} h), {bad} failed, {time.time() - t0:.0f} s", flush=True)
    print(f"done: {ok} rendered ({secs / 3600:.1f} h of audio), {bad} failed, {time.time() - t0:.0f} s", flush=True)


if __name__ == "__main__":
    main()

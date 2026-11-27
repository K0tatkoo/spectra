"""Builds and checks every instrument program the renderer could play (ownstems/presets.py), and writes
the ones that pass to <root>/sources/instruments-raw/presets.json (the rest, with why, to
presets-rejected.json).

    python tools/instrument_presets.py --root D:/stems-clean --workers 5

Each program is loaded, its octave naming checked against what its samples sound (sampler.octave_error),
then five notes across its range are rendered and measured (sampler.quality).
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

from ownstems import presets, sampler  # noqa: E402

_NAMES: dict[str, list[str]] = {}


def names(path: str) -> list[str]:
    if path not in _NAMES:
        _NAMES[path] = sampler.Store(path).namelist()
    return _NAMES[path]


def check(c: dict) -> dict:
    t0 = time.time()
    rng = np.random.default_rng(abs(hash(c["name"])) % 2**32)
    try:
        inst = presets.build(c)
        if not inst.regions:
            return {**c, "passed": False, "why": "no regions", "skipped": inst.skipped}
        shift = sampler.octave_error(inst, rng)
        c = {**c, "octave_fix": shift}
        inst = presets.build(c)
        q = sampler.quality(inst, rng, pitched=c["family"] not in ("mallets",))
        lo, hi = inst.keys()
        return {**c, **q, "regions": len(inst.regions), "keys": [lo, hi], "switches": inst.switches()[:12],
                "skipped": inst.skipped, "seconds": round(time.time() - t0, 1)}
    except Exception as e:  # a program the player can't load is simply not used
        return {**c, "passed": False, "why": f"{type(e).__name__}: {e}"[:200]}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    cands = presets.candidates(args.root, names)
    print(f"{len(cands)} programs to check", flush=True)
    with Pool(args.workers) as pool:
        results = pool.map(check, cands, chunksize=4)
    ok = [r for r in results if r.get("passed")]
    bad = [r for r in results if not r.get("passed")]
    out = presets.raw(args.root)
    (out / "presets.json").write_text(json.dumps(ok, indent=1), encoding="utf-8")
    (out / "presets-rejected.json").write_text(json.dumps(bad, indent=1), encoding="utf-8")
    fams: dict[str, int] = {}
    for r in ok:
        fams[r["family"]] = fams.get(r["family"], 0) + 1
    print(f"{len(ok)} passed, {len(bad)} rejected; passed by family: {dict(sorted(fams.items()))}")
    why: dict[str, int] = {}
    for r in bad:
        w = r.get("why", "?").split(":")[0]
        why[w] = why.get(w, 0) + 1
    print("rejected because:", why)
    libs: dict[str, list[int]] = {}
    for r in results:
        libs.setdefault(r["lib"], [0, 0])[0 if r.get("passed") else 1] += 1
    print("by library (passed, rejected):", libs)


if __name__ == "__main__":
    main()

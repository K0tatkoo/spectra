"""How much clean data there is: hours per stem, per source, per split, and the licences behind it."""

from __future__ import annotations

from collections import defaultdict

from . import STEMS
from .ledger import Catalog, Ledger


def summary(ledger: Ledger, catalog: Catalog) -> dict:
    hours: dict = defaultdict(lambda: defaultdict(lambda: {"train": 0.0, "valid": 0.0, "clips": 0}))
    for c in catalog.clips:
        h = hours[c.stem][c.source]
        h[c.split] += c.seconds / 3600
        h["clips"] += 1
    licences: dict = defaultdict(lambda: {"files": 0, "gb": 0.0})
    for a in ledger.artifacts.values():
        licences[a.licence]["files"] += 1
        licences[a.licence]["gb"] += a.bytes / 1e9
    return {
        "stems": {s: {src: {k: round(v, 2) if isinstance(v, float) else v for k, v in d.items()}
                      for src, d in sorted(hours[s].items())} for s in STEMS},
        "licences": {k: {"files": v["files"], "gb": round(v["gb"], 1)} for k, v in sorted(licences.items())},
        "sources": sorted({a.source for a in ledger.artifacts.values()}),
    }


def table(s: dict) -> str:
    lines = [f"{'stem':<8} {'source':<14} {'train h':>9} {'valid h':>9} {'clips':>8}"]
    for stem in STEMS:
        rows = s["stems"][stem]
        if not rows:
            lines.append(f"{stem:<8} {'—':<14} {0:>9.1f} {0:>9.1f} {0:>8}")
            continue
        for src, d in rows.items():
            lines.append(f"{stem:<8} {src:<14} {d['train']:>9.1f} {d['valid']:>9.1f} {d['clips']:>8}")
        tt = sum(d["train"] for d in rows.values())
        tv = sum(d["valid"] for d in rows.values())
        lines.append(f"{'':<8} {'= total':<14} {tt:>9.1f} {tv:>9.1f}")
    if "generated" in s["sources"]:
        lines.append("synth, bass, drums also come from Spectra's generators: unlimited, made per example")
    lines.append("")
    lines.append("licences: " + ", ".join(f"{k} {v['files']} files / {v['gb']} GB" for k, v in s["licences"].items()))
    return "\n".join(lines)

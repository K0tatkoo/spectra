"""The clean-data pipeline for Spectra's own stem model (train/STEMS.md).

    python tools/stems_data.py --root D:/stems-clean fetch slakh2100 --adopt D:/synthsplit/slakh
    python tools/stems_data.py --root D:/stems-clean fetch egmd
    python tools/stems_data.py --root D:/stems-clean index all
    python tools/stems_data.py --root D:/stems-clean check [--deep]
    python tools/stems_data.py --root D:/stems-clean report
    python tools/stems_data.py --root D:/stems-clean credits
    python tools/stems_data.py --root D:/stems-clean render --count 8 --out D:/stems-clean/listen

fetch     downloads a source into <root>/sources/<name>/ (checksums as published)
index     hashes its files into the ledger and lists its clips in the catalog
check     re-reads the ledger and catalog (refusing anything off the allow-list),
          then checks every file is still there at its size (--deep: and hash)
report    hours per stem, per source, per split
credits   the CC BY attribution block for THIRD_PARTY_NOTICES, from the ledger
render    writes example mixes and their five stems as WAVs, to listen to
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ownstems import STEMS  # noqa: E402
from ownstems.ledger import Catalog, Ledger  # noqa: E402
from ownstems.sources import registry  # noqa: E402


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True, type=Path)
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("source")
    f.add_argument("--adopt", type=Path, help="slakh2100: move in a copy fetch_slakh.py already made")
    f.add_argument("--subset", action="append", help="librittsr: which subsets (repeatable)")
    f.add_argument("--limit", type=int, help="ccmixter: stop after this many uploads")
    i = sub.add_parser("index")
    i.add_argument("source", help="a source name, or all")
    i.add_argument("--workers", type=int, default=8)
    c = sub.add_parser("check")
    c.add_argument("--deep", action="store_true")
    sub.add_parser("report")
    sub.add_parser("credits")
    r = sub.add_parser("render")
    r.add_argument("--count", type=int, default=8)
    r.add_argument("--out", type=Path, required=True)
    r.add_argument("--seconds", type=float, default=10.0)
    r.add_argument("--split", default="valid")
    r.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    root = args.root
    root.mkdir(parents=True, exist_ok=True)
    mods = registry()

    if args.cmd == "fetch":
        if args.source not in mods:
            sys.exit(f"unknown source {args.source}; known: {', '.join(mods)}")
        mods[args.source].fetch(root, args)
    elif args.cmd == "index":
        names = list(mods) if args.source == "all" else [args.source]
        ledger = Ledger(root)
        catalog = Catalog(root, ledger)
        for name in names:
            mod = mods[name]
            try:
                arts, clips = mod.index(root, ledger, args.workers)
            except SystemExit as e:
                print(f"{name}: skipped — {e}")
                continue
            ledger.write_source(name, arts)
            catalog.write_source(name, clips)
            print(f"{name}: {len(arts)} artifacts, {len(clips)} clips, "
                  f"{sum(c.seconds for c in clips) / 3600:.1f} h")
    elif args.cmd == "check":
        ledger = Ledger(root)  # refuses off-list licences while loading
        catalog = Catalog(root, ledger)  # refuses clips without an allowed artifact
        problems = ledger.check(deep=args.deep)
        for p in problems:
            print(p)
        print(f"{len(ledger.artifacts)} artifacts, {len(catalog.clips)} clips, {len(problems)} problems")
        sys.exit(1 if problems else 0)
    elif args.cmd == "report":
        from ownstems.report import summary, table

        s = summary(Ledger(root), Catalog(root, Ledger(root)))
        (root / "report.json").write_text(json.dumps(s, indent=1) + "\n")
        print(table(s))
    elif args.cmd == "credits":
        text = Ledger(root).credits()
        (root / "credits.txt").write_text(text, encoding="utf-8")
        print(text, end="")
    elif args.cmd == "render":
        import numpy as np
        import soundfile as sf

        from ownstems.audio import Library
        from ownstems.mixer import MixConfig, Mixer

        lib = Library(root)
        mixer = Mixer(lib, MixConfig(seconds=args.seconds, split=args.split))
        rng = np.random.default_rng(args.seed)
        args.out.mkdir(parents=True, exist_ok=True)
        for k in range(args.count):
            ex = mixer.example(rng)
            d = args.out / f"example-{k:02d}"
            d.mkdir(exist_ok=True)
            sf.write(d / "mix.wav", ex.mix.T, 44100, subtype="PCM_16")
            for s, x in zip(STEMS, ex.stems):
                if np.any(x):
                    sf.write(d / f"{s}.wav", x.T, 44100, subtype="PCM_16")
            (d / "info.json").write_text(json.dumps(ex.info, indent=1) + "\n")
            print(f"{d.name}: {', '.join(ex.info['present'])}{' (generated song)' if ex.info['generated'] else ''}")


if __name__ == "__main__":
    main()

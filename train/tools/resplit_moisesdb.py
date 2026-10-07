"""Relabels prepared MoisesDB songs with prepare/moisesdb.py's split_for, without preparing them again.

    python tools/resplit_moisesdb.py --corpus D:/synthsplit/corpus

The split is only a field in each song's meta.json. Made-up-synth songs over
MoisesDB backing take their split from it too, so run this before those are made.
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare.moisesdb import split_for  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", required=True, type=Path)
    args = ap.parse_args()
    counts = collections.Counter()
    changed = 0
    for path in sorted((args.corpus / "moisesdb").glob("*/meta.json")):
        meta = json.loads(path.read_text())
        split = split_for(meta.get("artist", ""))
        if meta["split"] != split:
            meta["split"] = split
            path.write_text(json.dumps(meta) + "\n")
            changed += 1
        counts[(split, meta["rms_db"]["synth"] > -60)] += 1
    print(f"{changed} songs relabelled · held out {counts[('valid', True)] + counts[('valid', False)]} "
          f"({counts[('valid', True)]} with synth), training {counts[('train', True)] + counts[('train', False)]} "
          f"({counts[('train', True)]} with synth)")


if __name__ == "__main__":
    main()

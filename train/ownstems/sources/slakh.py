"""Slakh2100 (flac redux): 2,100 MIDI songs rendered with real instrument patches, a stem per instrument.

Licence: CC BY 4.0, stated on the Zenodo record https://zenodo.org/records/4599666
(Manilow, Wichern, Seetharaman, Le Roux — "Cutting Music Source Separation Some
Slakh", WASPAA 2019).

Fetching: tools/fetch_slakh.py streams the 104 GB archive, keeps metadata and
stems, and writes DOWNLOAD_DONE only once the whole stream's MD5 matches the
published one. A copy made that way (the synth-lane runs left one on the
desktop) can be adopted instead of downloaded again: `fetch --adopt <dir>`
moves it in, refusing one without DOWNLOAD_DONE.

Stems: drums → drums; the Bass class → bass; Synth Lead / Synth Pad, and the GM
programs that are synth patches filed under other families (SynthStrings 1/2,
Synth Voice, SynthBrass 1/2, see prepare/relabel_slakh.py) → synth; Synth
Effects and Sound Effects are left out; everything else → other. Slakh's own
split: train → train, validation and test → valid. Group = the track.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import yaml

from ..audio import probe
from ..ledger import Clip, Ledger
from . import TOOLS, artifacts_for, source_dir

NAME = "slakh2100"
TITLE = "Slakh2100"
LICENCE = "CC-BY-4.0"
LICENCE_URL = "https://zenodo.org/records/4599666"
CREDIT = "Slakh2100 (Manilow, Wichern, Seetharaman and Le Roux, 2019), Zenodo record 4599666"
ORIGIN = "https://zenodo.org/api/records/4599666/files/slakh2100_flac_redux.tar.gz/content"
PUBLISHED_MD5 = "md5:f4b71b6c45ac9b506f59788456b3f0c4"  # of the whole archive, checked by fetch_slakh.py

SYNTH_PROGRAMS = {50, 51, 54, 62, 63}
SPLITS = {"train": "train", "validation": "valid", "test": "valid"}


def stem_of(meta: dict) -> str | None:
    c = (meta.get("inst_class") or "").lower()
    if meta.get("is_drum"):
        return "drums"
    if c == "bass":
        return "bass"
    if "effects" in c:
        return None
    if c in ("synth lead", "synth pad") or meta.get("program_num") in SYNTH_PROGRAMS:
        return "synth"
    return "other"


def fetch(root: Path, args) -> None:
    dest = source_dir(root, NAME)
    if (dest / "DOWNLOAD_DONE").exists():
        print(f"{NAME}: already here ({(dest / 'DOWNLOAD_DONE').read_text().strip()})")
        return
    if getattr(args, "adopt", None):
        src = Path(args.adopt)
        if not (src / "DOWNLOAD_DONE").exists():
            sys.exit(f"{src} has no DOWNLOAD_DONE: its archive MD5 was never confirmed, so it can't be adopted")
        for item in src.iterdir():
            shutil.move(str(item), str(dest / item.name))
        print(f"{NAME}: adopted {src} (moved, same volume)")
        return
    subprocess.run([sys.executable, str(TOOLS / "fetch_slakh.py"), "--out", str(dest)], check=True)


def index(root: Path, ledger: Ledger, workers: int = 8):
    base = source_dir(root, NAME)
    if not (base / "DOWNLOAD_DONE").exists():
        raise SystemExit(f"{NAME}: not fetched (no DOWNLOAD_DONE in {base})")
    files, ids, clips = [], [], []
    for meta_file in sorted(base.glob("slakh2100_flac_redux/*/Track*/metadata.yaml")):
        track = meta_file.parent
        split = SPLITS.get(track.parent.name)
        if split is None:
            continue
        meta = yaml.safe_load(meta_file.read_text())
        for sid, s in (meta.get("stems") or {}).items():
            stem = stem_of(s)
            f = track / "stems" / f"{sid}.flac"
            if stem is None or not s.get("audio_rendered", True) or not f.exists():
                continue
            seconds, rate, channels = probe(f)
            aid = f"{NAME}/{track.name}/{sid}.flac"
            files.append(f)
            ids.append(aid)
            tags = [f"class:{s.get('inst_class', '')}", f"program:{s.get('program_num', '')}"]
            clips.append(Clip(artifact=aid, stem=stem, source=NAME, split=split, group=track.name,
                              seconds=round(seconds, 3), rate=rate, channels=channels, tags=tags))
    published = {f: PUBLISHED_MD5 for f in files}
    arts = artifacts_for(ledger, sys.modules[__name__], files, ids, workers, published=published)
    return arts, clips

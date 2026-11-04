"""E-GMD, the Expanded Groove MIDI Dataset: 444 h of drum performances re-recorded on 43 kits.

Licence: CC BY 4.0. Zenodo record 4300943 (licence field cc-by-4.0), and the
Magenta page https://magenta.tensorflow.org/datasets/e-gmd: "made available by
Google LLC under a Creative Commons Attribution 4.0 International (CC BY 4.0)
License". Checked 2026-11-04.

Files (as published on the record): e-gmd-v1.0.0.zip, 96,422,999,145 bytes,
md5 510af23329b0472a8349d1aaf8fb98dd; e-gmd-v1.0.0.csv, 9,614,713 bytes,
md5 a212cb4b1aec205ef1882d9f9bb6150a. Audio is 44.1 kHz WAV, read in place
from the zip. The CSV's columns: drummer, session, id, style, bpm, beat_type,
time_signature, duration, split, midi_filename, audio_filename, kit_name.

Stem: drums. Split: the CSV's own (train → train, validation and test → valid),
which keeps one performance on one side across all 43 kits. Group = the MIDI
file (the performance).
"""

from __future__ import annotations

import csv
import io
import sys
import zipfile
from pathlib import Path

from ..audio import probe
from ..ledger import Clip, Ledger
from . import artifacts_for, download, source_dir

NAME = "egmd"
TITLE = "E-GMD"
LICENCE = "CC-BY-4.0"
LICENCE_URL = "https://magenta.tensorflow.org/datasets/e-gmd"
CREDIT = ("E-GMD: Expanded Groove MIDI Dataset (Callender, Hawthorne and Engel, 2020), Google LLC, "
          "Zenodo record 4300943")
ORIGIN = "https://zenodo.org/records/4300943"
ZIP = ("e-gmd-v1.0.0.zip", 96_422_999_145, "510af23329b0472a8349d1aaf8fb98dd")
CSV = ("e-gmd-v1.0.0.csv", 9_614_713, "a212cb4b1aec205ef1882d9f9bb6150a")
SPLITS = {"train": "train", "validation": "valid", "test": "valid"}


def _url(name: str) -> str:
    return f"https://zenodo.org/api/records/4300943/files/{name}/content"


def fetch(root: Path, args) -> None:
    d = source_dir(root, NAME)
    for name, size, md5 in (CSV, ZIP):
        download(_url(name), d / name, size, md5)


def index(root: Path, ledger: Ledger, workers: int = 8):
    d = source_dir(root, NAME)
    zpath, cpath = d / ZIP[0], d / CSV[0]
    if not (zpath.exists() and cpath.exists()):
        raise SystemExit(f"{NAME}: not fetched")
    with zipfile.ZipFile(zpath) as z:
        # The CSV's paths start at drummerN/; the zip may put a top folder above them.
        names = {n[n.find("drummer"):] if "drummer" in n else n: n for n in z.namelist() if n.endswith(".wav")}
        first = next(iter(names.values()))
        _, rate, channels = probe(io.BytesIO(z.read(first)))
    clips = []
    with open(cpath, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            member = names.get(row["audio_filename"])
            if member is None:
                continue
            clips.append(Clip(artifact=f"{NAME}/{ZIP[0]}", stem="drums", source=NAME,
                              split=SPLITS.get(row["split"], "train"), group=row["midi_filename"],
                              seconds=round(float(row["duration"]), 3), rate=rate, channels=channels,
                              member=member, tags=[f"kit:{row['kit_name']}", f"style:{row['style']}",
                                                   f"bpm:{row['bpm']}", f"drummer:{row['drummer']}"]))
    if not clips:
        raise SystemExit(f"{NAME}: no CSV row matched a file in the zip")
    published = {zpath: f"md5:{ZIP[2]}", cpath: f"md5:{CSV[2]}"}
    arts = artifacts_for(ledger, sys.modules[__name__], [zpath, cpath],
                         [f"{NAME}/{ZIP[0]}", f"{NAME}/{CSV[0]}"], workers, published=published)
    return arts, clips

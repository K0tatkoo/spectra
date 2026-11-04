"""GuitarSet: 360 acoustic-guitar excerpts (6 players, comping and soloing), mono microphone take.

Licence: CC BY 4.0, the licence field of Zenodo record 3371780 (Xi, Bittner,
Pauwels, Ye and Bello, ISMIR 2018). Checked 2026-11-04.

File: audio_mono-mic.zip, 656,927,981 bytes, md5 275966d6610ac34999b58426beb119c3,
read in place. Members are named <player>_<style><n>-<bpm>-<key>_<comp|solo>_mic.wav.

Stem: other. Group = player + piece (a comp and a solo over one progression stay together).
"""

from __future__ import annotations

import io
import sys
from pathlib import Path, PurePosixPath

from ..audio import probe
from ..zips import open_zip
from ..ledger import Clip, Ledger, split_for
from . import artifacts_for, download, source_dir

NAME = "guitarset"
TITLE = "GuitarSet"
LICENCE = "CC-BY-4.0"
LICENCE_URL = "https://zenodo.org/records/3371780"
CREDIT = "GuitarSet (Xi, Bittner, Pauwels, Ye and Bello, 2018), Zenodo record 3371780"
ORIGIN = "https://zenodo.org/api/records/3371780/files/audio_mono-mic.zip/content"
ZIP = ("audio_mono-mic.zip", 656_927_981, "275966d6610ac34999b58426beb119c3")


def fetch(root: Path, args) -> None:
    download(ORIGIN, source_dir(root, NAME) / ZIP[0], ZIP[1], ZIP[2])


def index(root: Path, ledger: Ledger, workers: int = 8):
    zpath = source_dir(root, NAME) / ZIP[0]
    if not zpath.exists():
        raise SystemExit(f"{NAME}: not fetched")
    clips = []
    with open_zip(zpath) as z:
        for n in sorted(z.namelist()):
            base = PurePosixPath(n).name
            if not base.endswith(".wav") or base.startswith("._"):
                continue
            parts = base.split("_")
            group = "_".join(parts[:2]) if len(parts) >= 3 else base
            seconds, rate, channels = probe(io.BytesIO(z.read(n)))
            clips.append(Clip(artifact=f"{NAME}/{ZIP[0]}", stem="other", source=NAME,
                              split=split_for(NAME, group), group=group, seconds=round(seconds, 3), rate=rate,
                              channels=channels, member=n, tags=["acoustic_guitar", parts[2] if len(parts) > 2 else ""]))
    arts = artifacts_for(ledger, sys.modules[__name__], [zpath], [f"{NAME}/{ZIP[0]}"], workers,
                         published={zpath: f"md5:{ZIP[2]}"})
    return arts, clips

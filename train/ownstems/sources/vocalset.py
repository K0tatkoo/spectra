"""VocalSet: 10.1 h of solo singing, 20 professional singers, many techniques, recorded dry.

Licence: CC BY 4.0, the licence field of Zenodo record 1442513, "VocalSet: A
Singing Voice Dataset" (Wilkins, Seetharaman, Wahl and Pardo, ISMIR 2018),
v1.2. Checked 2026-11-04.

File: VocalSet1-2.zip, 5,991,573,193 bytes, md5 c5e5efab412637fc94972b93c343a2f0,
read in place. Version 1.2 holds the same recordings in three trees (by singer,
by technique, by vowel), so only one tree is indexed: the by-singer one when it
is there, otherwise one copy of each file name.

Stem: vocals. Group = the singer (f1…f9, m1…m11), so no voice is on both sides.
Split: of each gender, the last singer in order is held out, so valid always
holds a female and a male voice (a tenth of 20 singers by hash could be none).
"""

from __future__ import annotations

import io
import re
import sys
from pathlib import PurePosixPath
from pathlib import Path

from ..audio import probe
from ..zips import open_zip
from ..ledger import Clip, Ledger
from . import artifacts_for, download, source_dir

NAME = "vocalset"
TITLE = "VocalSet"
LICENCE = "CC-BY-4.0"
LICENCE_URL = "https://zenodo.org/records/1442513"
CREDIT = "VocalSet (Wilkins, Seetharaman, Wahl and Pardo, 2018), Zenodo record 1442513"
ORIGIN = "https://zenodo.org/api/records/1442513/files/VocalSet1-2.zip/content"
ZIP = ("VocalSet1-2.zip", 5_991_573_193, "c5e5efab412637fc94972b93c343a2f0")
SINGER = re.compile(r"^(?:(f|m)(\d+)|(female|male)(\d+))$", re.IGNORECASE)


def singer_of(member: str) -> str | None:
    p = PurePosixPath(member)
    for part in [*p.parts[:-1], p.name.split("_")[0]]:
        m = SINGER.match(part)
        if m:
            g = (m.group(1) or m.group(3)[0]).lower()
            return f"{g}{int(m.group(2) or m.group(4))}"
    return None


def fetch(root: Path, args) -> None:
    download(ORIGIN, source_dir(root, NAME) / ZIP[0], ZIP[1], ZIP[2])


def index(root: Path, ledger: Ledger, workers: int = 8):
    zpath = source_dir(root, NAME) / ZIP[0]
    if not zpath.exists():
        raise SystemExit(f"{NAME}: not fetched")
    rows = []
    with open_zip(zpath) as z:
        wavs = [n for n in z.namelist() if n.lower().endswith(".wav") and "/__MACOSX/" not in f"/{n}"]
        tree = [n for n in wavs if "by_singer" in n.lower()] or wavs
        seen = set()
        for n in sorted(tree):
            base = PurePosixPath(n).name
            if base in seen:
                continue
            seen.add(base)
            singer = singer_of(n)
            if singer is None:
                continue
            seconds, rate, channels = probe(io.BytesIO(z.read(n)))
            rows.append((n, singer, seconds, rate, channels))
    if not rows:
        raise SystemExit(f"{NAME}: no singer folders recognised in the zip")
    singers = sorted({r[1] for r in rows}, key=lambda s: (s[0], int(s[1:])))
    held = {max((s for s in singers if s[0] == g), key=lambda s: int(s[1:]), default=None) for g in "fm"}
    clips = [Clip(artifact=f"{NAME}/{ZIP[0]}", stem="vocals", source=NAME,
                  split="valid" if singer in held else "train", group=singer, seconds=round(sec, 3),
                  rate=rate, channels=ch, member=n, tags=["singing", "dry"] + [f"path:{PurePosixPath(n).parent}"])
             for n, singer, sec, rate, ch in rows]
    arts = artifacts_for(ledger, sys.modules[__name__], [zpath], [f"{NAME}/{ZIP[0]}"], workers,
                         published={zpath: f"md5:{ZIP[2]}"})
    return arts, clips

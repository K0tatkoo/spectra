"""Guitar-TECHS: electric guitar played by three guitarists, recorded at once as a direct input (DI)
and through an amplifier and microphone: chords, scales, techniques and musical excerpts.

Licence: CC BY 4.0, the licence field (metadata.license.id = cc-by-4.0) of
Zenodo record 14963133 (Pedroza Villalobos, Taheri, Abreu, Corey and Roman,
2025), read 2026-11-27 through Zenodo's API. Fetched the same day by
train/prepare/instruments/fetch_instruments.py, each zip checked against
Zenodo's MD5; read in place.

Members: <set>/audio/micamp/*.wav (the amp microphone) and
<set>/audio/directinput/*.wav (the dry DI, also what the renderer re-amps
for rock and metal guitar, ownstems/amp.py). The single-note sets are left
out (one note at a time is not a part), and so are the MIDI, the videos and
macOS's __MACOSX copies.

Stem: other. Group = player and set (P1_chords…), so a take's DI and mic stay
on one side of the split.
"""

from __future__ import annotations

import io
import sys
from pathlib import Path, PurePosixPath

from ..audio import probe
from ..ledger import Clip, Ledger, split_for
from ..zips import open_zip
from . import artifacts_for

NAME = "guitar-techs"
TITLE = "Guitar-TECHS"
LICENCE = "CC-BY-4.0"
LICENCE_URL = "https://zenodo.org/records/14963133"
CREDIT = ("Guitar-TECHS (Hegel Emmanuel Pedroza Villalobos, Termeh Taheri, Wallace Abreu, Ryan Corey, "
          "Iran R. Roman, 2025), Zenodo record 14963133")
ORIGIN = "https://zenodo.org/api/records/14963133/files"
DIR = ("instruments-raw", "instruments", "Guitar-TECHS")
SETS = ("P1_chords", "P1_scales", "P1_techniques", "P2_chords", "P2_scales", "P2_techniques", "P3_music")


def fetch(root: Path, args) -> None:
    print(f"{NAME}: fetched by train/prepare/instruments/fetch_instruments.py")


def members(z) -> list[tuple[str, str]]:
    """(member, kind) for every usable take: kind is 'micamp' or 'directinput'."""
    out = []
    for n in sorted(z.namelist()):
        p = PurePosixPath(n)
        if "__MACOSX" in p.parts or p.name.startswith(".") or p.suffix.lower() != ".wav":
            continue
        if len(p.parts) >= 3 and p.parts[-3] == "audio" and p.parts[-2] in ("micamp", "directinput"):
            out.append((n, p.parts[-2]))
    return out


def index(root: Path, ledger: Ledger, workers: int = 8):
    d = Path(root, "sources", *DIR)
    zips = [d / f"{s}.zip" for s in SETS if (d / f"{s}.zip").exists()]
    if not zips:
        raise SystemExit(f"{NAME}: not fetched")
    clips = []
    for zpath in zips:
        group = zpath.stem
        with open_zip(zpath) as z:
            for n, kind in members(z):
                seconds, rate, channels = probe(io.BytesIO(z.read(n)))
                clips.append(Clip(artifact=f"{NAME}/{zpath.name}", stem="other", source=NAME,
                                  split=split_for(NAME, group), group=group, seconds=round(seconds, 3), rate=rate,
                                  channels=channels, member=n, tags=["electric_guitar", kind, group]))
    arts = artifacts_for(ledger, sys.modules[__name__], zips, [f"{NAME}/{z.name}" for z in zips], workers)
    print(f"{NAME}: {len(clips)} clips in {len(zips)} zips")
    return arts, clips

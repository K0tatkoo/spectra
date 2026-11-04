"""Spectra's own generators: made-up synths (synthsplit/procsynth.py) and hardstyle drums
and bass (synthsplit/hardstyle.py), rendered fresh for each example by the mixer.

Licence: our own code and its output ("OWN"). Nothing is downloaded and no
file is stored; the ledger records the generators' source hash, so a model
can be traced to the exact code that made its synthetic data.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from ..ledger import Artifact, Ledger, today

NAME = "generated"
TITLE = "Spectra's generators"
LICENCE = "OWN"
LICENCE_URL = ""
CREDIT = ""
ORIGIN = ""
FILES = ("synthsplit/procsynth.py", "synthsplit/hardstyle.py")


def fetch(root: Path, args) -> None:
    print(f"{NAME}: nothing to download — rendered on the fly")


def index(root: Path, ledger: Ledger, workers: int = 8):
    train = Path(__file__).resolve().parents[2]
    h = hashlib.sha256()
    for f in FILES:
        h.update((train / f).read_bytes())
    art = Artifact(id=f"{NAME}/procsynth+hardstyle", path="", source=NAME, licence=LICENCE, licence_url="",
                   credit="", origin="", bytes=0, sha256=h.hexdigest(), fetched=today(),
                   note="code hash of " + " + ".join(FILES))
    return [art], []

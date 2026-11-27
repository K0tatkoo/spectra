"""Spectra's own renders for the "instruments" lane and for jungle / breakcore drums
(tools/render_instruments.py, ownstems/render.py): made-up jazz, pop, rock, metal and breakcore parts
and Mutopia scores played on the CC0 / CC BY sample libraries, Guitar-TECHS's DI takes through an amp
model, and breaks chopped from the CC0 / CC BY drum kits and from E-GMD.

Licence: each render's own, worked out from what it is made of (render.licence_of): CC BY when any
component is (with every component's credit), else CC0 (public-domain scores and CC0 samples played
by our own code). Components and their licences: train/STEMS.md, "Instrument sources"; the libraries'
licences in ownstems/presets.py, each read on its own page or API on 2026-11-27. The file list is
sources/rendered-instruments/index.jsonl, one row per render with its components.

Stem: "other" for instrument parts, "drums" for breaks. Group = the first instrument program (or kit,
or E-GMD session, or Guitar-TECHS set), so a held-out song is played on programs training never heard.
"""

from __future__ import annotations

import json
from pathlib import Path

from ..ledger import Artifact, Clip, Ledger, sha256_file, split_for, today
from ..licences import ALLOWED, NEEDS_CREDIT

NAME = "rendered-instruments"
TITLE = "Spectra's instrument and break renders"
LICENCE = "per render"
LICENCE_URL = "https://github.com/K0tatkoo/spectra/blob/main/train/STEMS.md"
CREDIT = ""
ORIGIN = "train/tools/render_instruments.py"
DIR = ("sources", "rendered-instruments")


def fetch(root: Path, args) -> None:
    print(f"{NAME}: rendered by train/tools/render_instruments.py, not downloaded")


def index(root: Path, ledger: Ledger, workers: int = 8):
    d = Path(root, *DIR)
    rows = [json.loads(line) for line in (d / "index.jsonl").read_text(encoding="utf-8").splitlines()] \
        if (d / "index.jsonl").exists() else []
    if not rows:
        raise SystemExit(f"{NAME}: nothing rendered")
    arts, clips = [], []
    for r in rows:
        p = d / r["file"]
        if not p.exists() or r["licence"] not in ALLOWED:
            continue
        if r["licence"] in NEEDS_CREDIT and not r.get("credit"):
            continue
        aid = f"{NAME}/{r['file']}"
        parts = ", ".join(f"{lic}" + (f" ({c})" if c else "") for lic, c in r.get("components", []))
        arts.append(Artifact(id=aid, path=p.relative_to(Path(root)).as_posix(), source=NAME, licence=r["licence"],
                             licence_url=ALLOWED[r["licence"]] or LICENCE_URL, credit=r.get("credit", ""),
                             origin=ORIGIN, bytes=p.stat().st_size, sha256=sha256_file(p), fetched=today(),
                             note=f"{r['kind']}/{r['genre']}: {', '.join(r.get('programs') or [r.get('kit') or r.get('clip') or ''])}"
                                  f"; components {parts}"[:900]))
        tags = [r["kind"], r["genre"], *r.get("families", [])]
        clips.append(Clip(artifact=aid, stem=r["stem"], source=NAME, split=split_for(NAME, r["group"]),
                          group=r["group"], seconds=r["seconds"], rate=44100, channels=1, tags=tags))
    print(f"{NAME}: {len(clips)} clips from {len(rows)} renders")
    return arts, clips

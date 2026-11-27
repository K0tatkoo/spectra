"""ccMixter instrument, bass and drum parts: real played parts that musicians put up for remixing.

Licence: per upload, as each upload's own `license_url` says; only CC BY (any
version) and CC0 pass (licences.normalize), re-checked here from the URL the
fetch recorded. Fetched 2026-11-27 by train/prepare/instruments: ccm_list.py
listed every upload tagged `sample` plus an instrument tag (guitar, piano,
violin, saxophone, trumpet, organ… 23 tags) under CC BY and CC0 through the
query API (https://ccmixter.org/query-api, terms approved by the owner
2026-11-17), one request a second; ccm_pick.py dropped remixes, covers and
non-commercial or AI-tagged uploads, and kept a file only when its own label
(ccMixter's per-file nickname, or its file name when the label is only a
format) names a part, sorted into a family; fetch_list.py downloaded each with
its upload page as Referer and checked it against the SHA-1 ccMixter publishes.
Files: sources/instruments-raw/ccmixter/<family>/<upload>/<file>, recorded in
sources/instruments-raw/manifest.jsonl (licence URL, credit, SHA-256).

Stem: "bass" for the bass family, "drums" for drums, "other" for every other
family (guitar, piano, organ, strings, sax, brass, winds, plucked, reeds,
mallets). Group = the uploader, split the way ccmixter.py splits that
uploader's a cappellas, so one musician stays on one side.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from ..audio import probe
from ..ledger import Artifact, Clip, Ledger, split_for, today
from ..licences import NEEDS_CREDIT, normalize

NAME = "ccmixter-parts"
TITLE = "ccMixter instrument, bass and drum parts"
LICENCE = "per upload"
LICENCE_URL = "https://ccmixter.org/query-api"
CREDIT = ""
ORIGIN = "https://ccmixter.org/api/query"
RAW = "instruments-raw"
STEM_OF = {"bass": "bass", "drums": "drums"}
# Uploads whose own title or description says they are made from someone else's recording, so the
# uploader's licence cannot cover them. Found 2026-11-27 by reading every picked upload whose title,
# labels, tags or description mention chops, records, years, TV, films, covers or other artists
# (82 of 1,019; the rest were the uploaders' own playing).
EXCLUDE = {
    70070: "a chop of a 1928 recording",
    1886: "drums sampled from a TV programme",
    1889: "drums sampled from a Japanese TV programme",
}


def fetch(root: Path, args) -> None:
    print(f"{NAME}: fetched by train/prepare/instruments (ccm_list.py, ccm_pick.py, fetch_list.py)")


def uploader(page: str) -> str:
    m = re.search(r"ccmixter\.org/files/([^/]+)/", page or "")
    return m.group(1) if m else "unknown"


def index(root: Path, ledger: Ledger, workers: int = 8):
    raw = Path(root) / "sources" / RAW
    manifest = raw / "manifest.jsonl"
    if not manifest.exists():
        raise SystemExit(f"{NAME}: not fetched")
    arts, clips, skipped = [], [], 0
    for line in manifest.read_text(encoding="utf-8").splitlines():
        r = json.loads(line)
        if r.get("source") != "ccmixter":
            continue
        if int(r["upload_id"]) in EXCLUDE:
            skipped += 1
            continue
        m = re.search(r"\(upload licence (\S+)\)", r.get("licence_evidence", ""))
        lic_url = m.group(1) if m else ""
        lid, _ = normalize(lic_url) if lic_url else (None, "no licence URL recorded")
        p = raw / r["file"]
        if lid is None or not p.exists():
            skipped += 1
            continue
        try:
            seconds, rate, channels = probe(p)
        except RuntimeError:
            skipped += 1  # a file libsndfile can't read is not training data
            continue
        aid = f"{NAME}/{r['upload_id']}/{p.name}"
        arts.append(Artifact(id=aid, path=p.relative_to(Path(root)).as_posix(), source=NAME, licence=lid,
                             licence_url=lic_url, credit=r.get("credit", "") if lid in NEEDS_CREDIT else "",
                             origin=r.get("referer") or r["url"], bytes=p.stat().st_size, sha256=r["sha256"],
                             fetched=r.get("fetched", today())[:10],
                             published_checksum=f"sha1-base32:{r['sha1_b32']}" if r.get("sha1_b32") else ""))
        who = uploader(r.get("referer", ""))
        clips.append(Clip(artifact=aid, stem=STEM_OF.get(r["family"], "other"), source=NAME,
                          split=split_for("ccmixter", who), group=who, seconds=round(seconds, 3), rate=rate,
                          channels=channels, tags=[r["family"], r.get("label", "")]))
    print(f"{NAME}: {len(clips)} clips, {skipped} skipped (licence or unreadable)")
    return arts, clips

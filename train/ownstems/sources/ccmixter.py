"""ccMixter a cappellas: real, produced vocals that their singers put up for remixing.

Licence: per upload, as each upload's own `license_url` says. Only CC BY (any
version) and CC0 pass (licences.normalize); NC, SA, ND and Sampling Plus are
dropped. Query API: https://ccmixter.org/query-api (checked 2026-11-04);
`lic=` takes one licence per query, so CC BY and CC0 are two queries, and the
API returns nothing for `limit` above ~5, so pages are 5 long.

An upload tagged `acappella` is not always a plain vocal, so an upload is
dropped when it is:
- tagged non_commercial, or AI-generated (whose training data nobody can vouch for);
- a remix (its parts would be other people's work, under their licences).

Of what is left, only audio files are kept, and only those whose own label
(ccMixter's per-file nickname) says it is a vocal: "Dry Main Acapella",
"wet vox", "dry backing vocals". A file whose label is just its format ("mp3")
may be the whole song, so it is skipped, and so are zips, previews, loops,
beats, drums, scratches, instrumentals and mixed songs.

Each upload's API record is kept next to its files (upload.json): the
licence, the credit line ("<title>" by <artist>) and the page it came from.
The files' SHA-1 as ccMixter publishes it is kept as the published checksum.

Requests go one a second; ccMixter's terms forbid overburdening the service.
Stem: vocals. Group = the artist, so one voice stays on one side of the split.
"""

from __future__ import annotations

import json
import os
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path

from ..audio import probe
from ..ledger import Artifact, Clip, Ledger, sha256_file, split_for, today
from ..licences import NEEDS_CREDIT, normalize
from . import source_dir

NAME = "ccmixter"
TITLE = "ccMixter a cappellas"
LICENCE = "per upload"
LICENCE_URL = "https://ccmixter.org/query-api"
CREDIT = ""
ORIGIN = "https://ccmixter.org/api/query"
UA = "SpectraStemsData/1.0 (+https://github.com/K0tatkoo/spectra)"
API = "https://ccmixter.org/api/query"
PAGE = 5
BAD_TAGS = {"non_commercial", "noncommercial", "ai_generated", "ai-generated", "ai", "remix"}
AUDIO_EXT = {".mp3", ".wav", ".flac", ".ogg", ".aif", ".aiff"}
NOT_VOCAL = re.compile(r"instrumental|\bbeat|drum|loop|karaoke|backing track|music bed|preview|scratch|cymbal|"
                       r"\bfx\b|effect", re.IGNORECASE)
VOCAL_WORD = re.compile(r"acap|a cap|pella|vocal|vox|voice|sing|rap\b|verse|chorus|harmon|\bdry\b|\bwet\b|\bbv\b",
                        re.IGNORECASE)
WHOLE_SONG = re.compile(r"\bmix(ed)?\b|\bsong\b|\bfull\b|master", re.IGNORECASE)
STRICT_VOCAL = re.compile(r"acap|a cap|pella|vocal|vox", re.IGNORECASE)


def _get(params: dict) -> list:
    url = API + "?" + urllib.parse.urlencode({"f": "json", **params})
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                body = r.read()
            return json.loads(body) if body.strip() else []
        except (OSError, json.JSONDecodeError):
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"ccMixter API kept failing: {url}")


def tags_of(u: dict) -> set[str]:
    raw = ",".join([u.get("upload_tags") or "", *((u.get("upload_extra") or {}).get(k) or "" for k in
                                                   ("systags", "usertags", "ccud"))])
    return {t.strip().lower() for t in raw.split(",") if t.strip()}


def keep_upload(u: dict) -> tuple[bool, str]:
    lid, why = normalize(u.get("license_url"))
    if lid is None:
        return False, why
    bad = tags_of(u) & BAD_TAGS
    if bad:
        return False, f"tagged {', '.join(sorted(bad))}"
    return True, lid


def vocal_files(u: dict) -> list[dict]:
    out = []
    for f in u.get("files") or []:
        info = f.get("file_format_info") or {}
        name = f.get("file_name") or ""
        if info.get("media-type") != "audio" or Path(name).suffix.lower() not in AUDIO_EXT:
            continue
        label = (f.get("file_nicname") or "").strip()
        if not VOCAL_WORD.search(label) or NOT_VOCAL.search(label):
            continue  # no label saying "vocal" (a bare "mp3" may be the whole song)
        if WHOLE_SONG.search(label) and not STRICT_VOCAL.search(label):
            continue  # "mixed song", "main mix": the whole song, not the vocal
        out.append(f)
    return out


def credit_of(u: dict) -> str:
    feat = ((u.get("upload_extra") or {}).get("featuring") or "").strip()
    by = u.get("user_name", "").strip() + (f" feat. {feat}" if feat else "")
    return f"\"{u.get('upload_name', '').strip()}\" by {by} (ccMixter, {u.get('file_page_url', '')})"


def fetch(root: Path, args) -> None:
    d = source_dir(root, NAME)
    limit = getattr(args, "limit", None)
    kept = skipped = 0
    seen: set[int] = set()
    with open(d / "fetch.log", "a", encoding="utf-8") as log:
        _fetch_pages(d, log, limit, kept, skipped, seen)


def _fetch_pages(d: Path, log, limit, kept: int, skipped: int, seen: set[int]) -> None:
    for lic in ("by", "zero"):
        offset = 0
        while True:
            page = _get({"tags": "acappella", "lic": lic, "limit": PAGE, "offset": offset})
            time.sleep(1)
            if not page:
                break
            offset += PAGE
            for u in page:
                uid = int(u["upload_id"])
                if uid in seen:
                    continue
                seen.add(uid)
                ok, why = keep_upload(u)
                files = vocal_files(u) if ok else []
                if not ok or not files:
                    skipped += 1
                    log.write(f"skip {uid}: {why if not ok else 'no vocal audio file'}\n")
                    continue
                ud = d / str(uid)
                ud.mkdir(exist_ok=True)
                for f in files:
                    dest = ud / Path(f["file_name"]).name
                    if dest.exists():
                        continue
                    part = dest.with_name(dest.name + ".part")
                    req = urllib.request.Request(f["download_url"], headers={"User-Agent": UA})
                    with urllib.request.urlopen(req, timeout=120) as r, open(part, "wb") as out:
                        while chunk := r.read(1 << 20):
                            out.write(chunk)
                    os.replace(part, dest)
                    time.sleep(1)
                (ud / "upload.json").write_text(json.dumps({**u, "_licence": why, "_kept_files":
                                                            [Path(f["file_name"]).name for f in files],
                                                            "_fetched": today()}, ensure_ascii=False))
                kept += 1
                if limit and kept >= limit:
                    print(f"{NAME}: stopped at --limit {limit}")
                    return
            print(f"{NAME}: lic={lic} offset {offset}: {kept} kept, {skipped} skipped", flush=True)
    print(f"{NAME}: done, {kept} uploads kept, {skipped} skipped (reasons in fetch.log)")


def index(root: Path, ledger: Ledger, workers: int = 8):
    d = source_dir(root, NAME)
    metas = sorted(d.glob("*/upload.json"))
    if not metas:
        raise SystemExit(f"{NAME}: not fetched")
    arts, clips = [], []
    for m in metas:
        u = json.loads(m.read_text(encoding="utf-8"))
        ok, lid = keep_upload(u)  # re-checked: a rule change applies to files already fetched
        if not ok:
            continue
        sha1s = {Path(f["file_name"]).name: (f.get("file_extra") or {}).get("sha1", "") for f in u.get("files") or []}
        artist = (u.get("user_name") or "unknown").strip()
        for name in u.get("_kept_files", []):
            p = m.parent / name
            if not p.exists():
                continue
            try:
                seconds, rate, channels = probe(p)
            except RuntimeError:
                continue  # a file libsndfile can't read is not training data
            aid = f"{NAME}/{m.parent.name}/{name}"
            a = Artifact(id=aid, path=p.relative_to(ledger.root).as_posix(), source=NAME, licence=lid,
                         licence_url=u["license_url"], credit=credit_of(u) if lid in NEEDS_CREDIT else "",
                         origin=u.get("file_page_url", ""), bytes=p.stat().st_size, sha256=sha256_file(p),
                         fetched=u.get("_fetched", today()),
                         published_checksum=f"sha1-base32:{sha1s.get(name)}" if sha1s.get(name) else "")
            arts.append(a)
            tags = sorted(t for t in tags_of(u) if t in ("male_vocals", "female_vocals", "rap", "spoken_word"))
            clips.append(Clip(artifact=aid, stem="vocals", source=NAME, split=split_for(NAME, artist), group=artist,
                              seconds=round(seconds, 3), rate=rate, channels=channels, tags=["singing", *tags]))
    return arts, clips

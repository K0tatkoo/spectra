"""The provenance ledger and the catalog: what we have, where it came from, under which licence.

    <root>/
        ledger/<source>.jsonl    one Artifact per file we hold (or per archive)
        catalog/<source>.jsonl   one Clip per usable piece of audio, pointing at an artifact
        sources/<source>/...     the files themselves

An Artifact is a file on disk: where it came from, its licence, the credit
line it needs, its size and SHA-256. A Clip is one stem's worth of audio
inside an artifact (an archive member, or the file itself): which stem it
feeds, its split, and the group it must stay with (a singer, a song, a
drummer) so nothing leaks between train and valid.

`Ledger.add` refuses an artifact whose licence is not allow-listed, and
`Catalog.add` refuses a clip whose artifact is not in the ledger. The data
loader opens audio only through `Library.open`, which checks both again, so
an unlisted file can never reach training by accident. `check()` re-verifies
everything on disk before a run.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

from . import STEMS
from .licences import ALLOWED, NEEDS_CREDIT, allowed

VALID_PERCENT = 10


class RefusedError(ValueError):
    """Data that must not be used for training."""


@dataclass
class Artifact:
    id: str  # unique within the ledger, e.g. "slakh2100/Track00001/S01.flac"
    path: str  # relative to the root, posix separators
    source: str  # e.g. "slakh2100"
    licence: str  # an id from licences.ALLOWED
    licence_url: str  # where the source states it
    credit: str  # the attribution line (required for CC BY)
    origin: str  # the URL it was fetched from
    bytes: int
    sha256: str
    fetched: str  # ISO date
    published_checksum: str = ""  # e.g. "md5:…" as the source publishes it, when it does
    note: str = ""


@dataclass
class Clip:
    artifact: str  # Artifact.id
    stem: str  # one of STEMS
    source: str
    split: str  # "train" or "valid"
    group: str  # what must stay on one side of the split (singer, song, drummer…)
    seconds: float
    rate: int
    channels: int
    member: str = ""  # path inside the artifact when it is an archive
    tags: list[str] = field(default_factory=list)


def sha256_file(path: Path, chunk: int = 1 << 22) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while b := f.read(chunk):
            h.update(b)
    return h.hexdigest()


def today() -> str:
    return _dt.date.today().isoformat()


def split_for(source: str, group: str) -> str:
    """Deterministic: the same group always lands on the same side, whoever indexes it."""
    h = int(hashlib.sha1(f"{source}:{group}".encode()).hexdigest()[:8], 16)
    return "valid" if h % 100 < VALID_PERCENT else "train"


def _read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    tmp.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    tmp.replace(path)


class Ledger:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.artifacts: dict[str, Artifact] = {}
        for f in sorted((self.root / "ledger").glob("*.jsonl")):
            for row in _read_jsonl(f):
                a = Artifact(**row)
                self._validate(a)
                self.artifacts[a.id] = a

    @staticmethod
    def _validate(a: Artifact) -> None:
        if not allowed(a.licence):
            raise RefusedError(f"{a.id}: licence {a.licence!r} is not allow-listed")
        if a.licence in NEEDS_CREDIT and not a.credit.strip():
            raise RefusedError(f"{a.id}: {a.licence} needs a credit line")
        if a.licence != "OWN" and not (a.origin and a.licence_url):
            raise RefusedError(f"{a.id}: third-party data needs its origin and licence URL")
        if not a.sha256 and a.licence != "OWN":
            raise RefusedError(f"{a.id}: no SHA-256")

    def file(self, a: Artifact) -> Path:
        return self.root / a.path

    def make(self, *, id: str, path: Path, source: str, licence: str, licence_url: str, credit: str,
             origin: str, published_checksum: str = "", note: str = "", sha256: str | None = None) -> Artifact:
        """An Artifact for a file already on disk (hashing it unless `sha256` is given)."""
        rel = Path(path).resolve().relative_to(self.root.resolve()).as_posix()
        p = self.root / rel
        return Artifact(id=id, path=rel, source=source, licence=licence, licence_url=licence_url, credit=credit,
                        origin=origin, bytes=p.stat().st_size, sha256=sha256 or sha256_file(p), fetched=today(),
                        published_checksum=published_checksum, note=note)

    def write_source(self, source: str, artifacts: list[Artifact]) -> None:
        """Replace one source's ledger file (sources are indexed independently)."""
        for a in artifacts:
            if a.source != source:
                raise ValueError(f"{a.id} belongs to {a.source}, not {source}")
            self._validate(a)
        _write_jsonl(self.root / "ledger" / f"{source}.jsonl", [asdict(a) for a in artifacts])
        self.artifacts = {k: v for k, v in self.artifacts.items() if v.source != source}
        self.artifacts.update({a.id: a for a in artifacts})

    def check(self, deep: bool = False) -> list[str]:
        """Problems on disk: missing files, wrong sizes, and with deep=True wrong hashes."""
        problems = []
        for a in self.artifacts.values():
            if a.licence == "OWN" and not a.path:
                continue
            p = self.file(a)
            if not p.exists():
                problems.append(f"missing: {a.id} ({a.path})")
            elif p.stat().st_size != a.bytes:
                problems.append(f"size changed: {a.id}")
            elif deep and sha256_file(p) != a.sha256:
                problems.append(f"hash changed: {a.id}")
        return problems

    def credits(self) -> str:
        """The attribution block for THIRD_PARTY_NOTICES: one line per source and credit."""
        by_source: dict[str, dict[str, set[str]]] = {}
        for a in self.artifacts.values():
            if a.licence == "OWN":
                continue
            by_source.setdefault(a.source, {}).setdefault(a.credit, set()).add(a.licence)
        lines = []
        for source in sorted(by_source):
            for credit in sorted(by_source[source]):
                lics = ", ".join(sorted(by_source[source][credit]))
                lines.append(f"- {credit} — {lics} ({ALLOWED[sorted(by_source[source][credit])[0]]})")
        return "\n".join(lines) + ("\n" if lines else "")


class Catalog:
    def __init__(self, root: Path, ledger: Ledger):
        self.root = Path(root)
        self.ledger = ledger
        self.clips: list[Clip] = []
        for f in sorted((self.root / "catalog").glob("*.jsonl")):
            for row in _read_jsonl(f):
                c = Clip(**row)
                self._validate(c)
                self.clips.append(c)

    def _validate(self, c: Clip) -> None:
        if c.stem not in STEMS:
            raise ValueError(f"{c.artifact}: unknown stem {c.stem!r}")
        if c.split not in ("train", "valid"):
            raise ValueError(f"{c.artifact}: unknown split {c.split!r}")
        a = self.ledger.artifacts.get(c.artifact)
        if a is None:
            raise RefusedError(f"clip from {c.artifact}: not in the ledger")
        if not allowed(a.licence):
            raise RefusedError(f"clip from {c.artifact}: licence {a.licence!r}")

    def write_source(self, source: str, clips: list[Clip]) -> None:
        for c in clips:
            if c.source != source:
                raise ValueError(f"{c.artifact} belongs to {c.source}, not {source}")
            self._validate(c)
        _write_jsonl(self.root / "catalog" / f"{source}.jsonl", [asdict(c) for c in clips])
        self.clips = [c for c in self.clips if c.source != source] + clips

    def select(self, stem: str | None = None, split: str | None = None, source: str | None = None) -> list[Clip]:
        return [c for c in self.clips
                if (stem is None or c.stem == stem) and (split is None or c.split == split)
                and (source is None or c.source == source)]

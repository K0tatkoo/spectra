"""One module per data source. Each one says where its data comes from, under which
licence, and how to turn the files into ledger artifacts and catalog clips.

Every source module defines:

    NAME, TITLE, LICENCE, LICENCE_URL, CREDIT, ORIGIN
    fetch(root, args)              -> downloads into <root>/sources/<NAME>/
    index(root, ledger, workers)   -> (list[Artifact], list[Clip])

A licence is written here only after reading it on the source's own page, and
each module's docstring quotes where.
"""

from __future__ import annotations

import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from ..ledger import Artifact, Ledger, sha256_file

TOOLS = Path(__file__).resolve().parents[2] / "tools"


def source_dir(root: Path, name: str) -> Path:
    d = Path(root) / "sources" / name
    d.mkdir(parents=True, exist_ok=True)
    return d


def download(url: str, out: Path, size: int | None = None, md5: str | None = None, connections: int = 6) -> Path:
    """A whole file, over several connections, checked against the published MD5 (tools/fetch_parallel.py).

    The file appears under its own name only once it is whole and its checksum matches."""
    out = Path(out)
    if out.exists() and (size is None or out.stat().st_size == size):
        print(f"already here: {out.name}", flush=True)
        return out
    cmd = [sys.executable, str(TOOLS / "fetch_parallel.py"), url, "--out", str(out), "--resume",
           "--connections", str(connections)]
    if size:
        cmd += ["--size", str(size)]
    if md5:
        cmd += ["--md5", md5]
    subprocess.run(cmd, check=True)
    return out


def hash_all(paths: list[Path], workers: int = 8) -> dict[Path, str]:
    """SHA-256 of many files at once (disk-bound, so threads are enough)."""
    with ThreadPoolExecutor(workers) as ex:
        return dict(zip(paths, ex.map(sha256_file, paths)))


def artifacts_for(ledger: Ledger, mod, files: list[Path], ids: list[str], workers: int,
                  credit: str | None = None, published: dict[Path, str] | None = None) -> list[Artifact]:
    hashes = hash_all(files, workers)
    return [ledger.make(id=i, path=f, source=mod.NAME, licence=mod.LICENCE, licence_url=mod.LICENCE_URL,
                        credit=credit or mod.CREDIT, origin=mod.ORIGIN, sha256=hashes[f],
                        published_checksum=(published or {}).get(f, ""))
            for f, i in zip(files, ids)]


def registry() -> dict:
    from . import ccmixter, egmd, generated, guitarset, librittsr, slakh, vocalset

    mods = (slakh, egmd, vocalset, guitarset, librittsr, ccmixter, generated)
    return {m.NAME: m for m in mods}

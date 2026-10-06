"""Downloads Slakh2100 (flac redux) and keeps only what the corpus needs.

    python tools/fetch_slakh.py --out D:/synthsplit/slakh

The 104 GB archive is a gzipped tar whose songs are shuffled through it, so
it has to be read whole. It is fetched over several connections
(tools/fetch_parallel.py), unpacked on the fly, and only each track's
metadata.yaml and stems/*.flac are written (~65 %): no mixes, no MIDI, and
nothing from the `omitted` split. Pure Python, so it runs the same on
Windows, where a binary pipe into tar is not to be trusted.

Each file is written as .part and renamed when complete, so
prepare/slakh.py --watch never reads half a stem. DOWNLOAD_DONE is written
once the whole stream's MD5 matches; --watch stops waiting then. A gzip
stream cannot be resumed, so after a stop it starts over, skipping files
already on disk at the right size.
"""

from __future__ import annotations

import argparse
import io
import os
import sys
import tarfile
import time
from pathlib import Path, PurePosixPath

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_parallel import Stream  # noqa: E402

URL = "https://zenodo.org/api/records/4599666/files/slakh2100_flac_redux.tar.gz/content"
SIZE = 104_322_767_708
MD5 = "f4b71b6c45ac9b506f59788456b3f0c4"
SPLITS = ("train", "validation", "test")


class Reader(io.RawIOBase):
    """A file over an iterator of byte chunks."""

    def __init__(self, chunks):
        self.chunks = iter(chunks)
        self.view = memoryview(b"")

    def readable(self):
        return True

    def readinto(self, b) -> int:
        while not len(self.view):
            try:
                self.view = memoryview(next(self.chunks))
            except StopIteration:
                return 0
        n = min(len(b), len(self.view))
        b[:n] = self.view[:n]
        self.view = self.view[n:]
        return n


def wanted(name: str) -> bool:
    p = PurePosixPath(name).parts  # slakh2100_flac_redux/<split>/TrackNNNNN/...
    if len(p) < 4 or p[1] not in SPLITS or p[-1].startswith("._"):
        return False
    return (len(p) == 4 and p[3] == "metadata.yaml") or (len(p) == 5 and p[3] == "stems" and p[4].endswith(".flac"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, type=Path, help="slakh2100_flac_redux/ is created inside")
    ap.add_argument("--connections", type=int, default=6)
    args = ap.parse_args()

    done = args.out / "DOWNLOAD_DONE"
    if done.exists():
        print(f"already done: {done.read_text().strip()}")
        return
    args.out.mkdir(parents=True, exist_ok=True)
    stream = Stream(URL, SIZE, args.connections)
    reader = io.BufferedReader(Reader(stream), 1 << 20)
    kept = skipped = 0
    t0 = time.time()
    with tarfile.open(fileobj=reader, mode="r|gz") as tar:
        for m in tar:
            if not m.isfile() or not wanted(m.name):
                continue
            dest = args.out / Path(*PurePosixPath(m.name).parts)
            if dest.exists() and dest.stat().st_size == m.size:
                skipped += 1
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            part = dest.with_name(dest.name + ".part")
            with tar.extractfile(m) as src, open(part, "wb") as f:
                while chunk := src.read(1 << 20):
                    f.write(chunk)
            os.replace(part, dest)
            kept += 1
            if kept % 2000 == 0:
                print(f"{kept} files kept ({time.time() - t0:.0f} s)", flush=True)
    while reader.read(1 << 20):  # the tar ends before the stream does: the MD5 needs all of it
        pass
    if not stream.check(MD5):
        sys.exit(2)
    done.write_text(f"{time.strftime('%Y-%m-%d %H:%M')} · {kept} files written, {skipped} already here\n")
    print(f"done: {kept} files written, {skipped} already here")


if __name__ == "__main__":
    main()

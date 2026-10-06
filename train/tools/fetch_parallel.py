"""Downloads a large file in order over several connections, to stdout or a file.

    python tools/fetch_parallel.py URL --size BYTES --md5 HEX | tar -xzf - ...
    python tools/fetch_parallel.py URL --size BYTES --md5 HEX --out musdb18hq.zip

Zenodo gives one connection a few MB/s; several ranged requests together use
the whole line. Segments are fetched ahead in parallel and written out
strictly in order, so whatever reads stdout sees one ordinary stream — the
Slakh archive is shuffled (a song's files are spread over all 104 GB), so it
has to be read whole, but never needs to be stored whole. Memory use is
bounded by --ahead × --segment. The MD5 of the whole stream is checked at
the end against the one the archive publishes; a mismatch exits non-zero.

On Windows, pipe through cmd, never PowerShell (5.1 re-encodes a pipe as
text), or use --out, or stream() from Python as tools/fetch_slakh.py does.
--out writes <out>.part and renames it only once the MD5 matches, so a file
under its own name is always whole.

Links that expire (MoisesDB's are signed and short-lived): with --resume the
download carries on from <out>.part — the last few MB are fetched again, in
case the writer stopped mid-block — so a stopped download needs only a fresh
link, never a fresh start. An expired or refused link stops it at once
instead of retrying. Without --size the size is asked of the server; without
--md5 a zip is checked with its own CRCs instead.

A signed link is a password to the file for as long as it lives: pass it as
@<file holding the link> to keep it off command lines (and out of cmd, which
would read its & and %).
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


class LinkRefused(Exception):
    """The server said no (expired or refused link): retrying the same URL cannot help."""


def fetch(url: str, start: int, end: int, tries: int = 8) -> bytes:
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end - 1}"})
            with urllib.request.urlopen(req, timeout=120) as r:
                if r.status != 206:
                    raise IOError(f"server ignored the range (HTTP {r.status})")
                data = r.read()
            if len(data) != end - start:
                raise IOError(f"short segment: {len(data)} of {end - start} bytes")
            return data
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404, 410):
                raise LinkRefused(f"HTTP {e.code} at byte {start}: the link has expired or is refused") from None
            if attempt == tries - 1:
                raise
            print(f"[fetch] {start}: {e}; retrying", file=sys.stderr, flush=True)
            time.sleep(min(60, 5 * 2 ** attempt))
        except Exception as e:  # noqa: BLE001 — every other failure is retried the same way
            if attempt == tries - 1:
                raise
            print(f"[fetch] {start}: {e}; retrying", file=sys.stderr, flush=True)
            time.sleep(min(60, 5 * 2 ** attempt))
    raise AssertionError("unreachable")


class Stream:
    """Iterates over the file's bytes in order; .md5 is the digest of what has been yielded."""

    def __init__(self, url: str, size: int, connections: int = 6, segment: int = 32 << 20, ahead: int = 16,
                 start: int = 0):
        self.url, self.size, self.connections, self.segment, self.ahead = url, size, connections, segment, ahead
        self.md5 = hashlib.md5()  # of what this stream yields: the whole file only when start is 0
        self.start = start
        self.written = start

    def __iter__(self):
        starts = list(range(self.start, self.size, self.segment))
        slots = threading.Semaphore(self.ahead)
        t0 = time.time()
        last_report = 0

        def job(start: int) -> bytes:
            return fetch(self.url, start, min(start + self.segment, self.size))

        with ThreadPoolExecutor(self.connections) as pool:
            futures = []
            submitted = 0

            def top_up():
                nonlocal submitted
                while submitted < len(starts) and slots.acquire(blocking=False):
                    futures.append(pool.submit(job, starts[submitted]))
                    submitted += 1

            top_up()
            for i in range(len(starts)):
                while i >= len(futures):  # can only happen if every slot is taken
                    time.sleep(0.05)
                    top_up()
                data = futures[i].result()
                futures[i] = None
                slots.release()
                top_up()
                self.md5.update(data)
                self.written += len(data)
                yield data
                if self.written - last_report >= 1 << 30 or self.written == self.size:
                    last_report = self.written
                    rate = (self.written - self.start) / max(time.time() - t0, 1e-6) / 1e6
                    eta = (self.size - self.written) / max(rate * 1e6, 1) / 60
                    print(f"[fetch] {self.written / 1e9:.1f} / {self.size / 1e9:.1f} GB · {rate:.1f} MB/s · "
                          f"{eta:.0f} min left", file=sys.stderr, flush=True)

    def check(self, expected: str) -> bool:
        digest = self.md5.hexdigest()
        if expected and digest != expected:
            print(f"[fetch] MD5 MISMATCH: got {digest}, expected {expected}", file=sys.stderr, flush=True)
            return False
        print(f"[fetch] done, {self.written} bytes, md5 {digest}" + (" (verified)" if expected else ""),
              file=sys.stderr, flush=True)
        return True


def remote_size(url: str) -> int:
    req = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            total = (r.headers.get("Content-Range") or "").rpartition("/")[2]
            if r.status != 206 or not total.isdigit():
                raise SystemExit(f"[fetch] the server does not serve ranges (HTTP {r.status}): cannot download in parts")
            return int(total)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"[fetch] HTTP {e.code}: the link has expired or is refused") from None


def file_md5(path: Path, upto: int) -> "hashlib._Hash":
    h = hashlib.md5()
    with open(path, "rb") as f:
        left = upto
        while left and (chunk := f.read(min(left, 64 << 20))):
            h.update(chunk)
            left -= len(chunk)
    return h


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("url", help="the URL, or @file holding it")
    ap.add_argument("--size", type=int, default=0, help="bytes (default: ask the server)")
    ap.add_argument("--md5", default="")
    ap.add_argument("--out", type=Path, help="write here instead of stdout")
    ap.add_argument("--resume", action="store_true", help="carry on from <out>.part")
    ap.add_argument("--connections", type=int, default=6)
    ap.add_argument("--segment", type=int, default=32 << 20)
    ap.add_argument("--ahead", type=int, default=16, help="segments fetched ahead of the writer")
    args = ap.parse_args()
    if args.url.startswith("@"):
        args.url = Path(args.url[1:]).read_text(encoding="utf-8").strip()

    size = args.size or remote_size(args.url)
    if args.out and args.out.exists() and args.out.stat().st_size == size:
        print(f"[fetch] {args.out} is already here", file=sys.stderr)
        return
    part = args.out.with_name(args.out.name + ".part") if args.out else None
    start = 0
    if args.resume and part and part.exists():
        # Fetch the last few MB again: whoever wrote the part may have stopped mid-block.
        start = max(0, min(part.stat().st_size, size) - (8 << 20)) // (1 << 20) * (1 << 20)
        with open(part, "r+b") as f:
            f.truncate(start)
        print(f"[fetch] resuming at {start / 1e9:.2f} of {size / 1e9:.2f} GB", file=sys.stderr, flush=True)
    stream = Stream(args.url, size, args.connections, args.segment, args.ahead, start=start)
    if start and args.md5:
        stream.md5 = file_md5(part, start)
    out = open(part, "ab" if start else "wb") if part else sys.stdout.buffer
    try:
        for data in stream:
            out.write(data)
    except BrokenPipeError:
        print("[fetch] reader went away", file=sys.stderr)
        sys.exit(1)
    except LinkRefused as e:
        out.flush()
        print(f"[fetch] {e}. Stopped at {stream.written / 1e9:.2f} GB: run again with --resume and a fresh link.",
              file=sys.stderr, flush=True)
        sys.exit(3)
    finally:
        out.flush()
        if part:
            out.close()
    if not stream.check(args.md5 if not start or args.md5 else ""):
        sys.exit(2)
    if part and not args.md5 and part.suffix.lower() == ".part" and args.out.suffix.lower() == ".zip":
        import zipfile
        print("[fetch] no MD5 to check against: checking the zip's own CRCs instead", file=sys.stderr, flush=True)
        with zipfile.ZipFile(part) as z:
            bad = z.testzip()
        if bad:
            print(f"[fetch] ZIP DAMAGED: {bad} fails its CRC", file=sys.stderr, flush=True)
            sys.exit(2)
        print("[fetch] zip checked: every member's CRC matches", file=sys.stderr, flush=True)
    if part:
        os.replace(part, args.out)


if __name__ == "__main__":
    main()

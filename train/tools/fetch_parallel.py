"""Streams a large download to stdout, in order, over several connections.

    python tools/fetch_parallel.py URL --size BYTES --md5 HEX | tar -xzf - ...

Zenodo gives one connection a few MB/s; several ranged requests together use
the whole line. Segments are fetched ahead in parallel and written out
strictly in order, so whatever reads stdout sees one ordinary stream — the
Slakh archive is shuffled (a song's files are spread over all 104 GB), so it
has to be read whole, but never needs to be stored whole. Memory use is
bounded by --ahead × --segment. The MD5 of the whole stream is checked at
the end against the one the archive publishes; a mismatch exits non-zero.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor


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
        except Exception as e:  # noqa: BLE001 — every failure is retried the same way
            if attempt == tries - 1:
                raise
            print(f"[fetch] {start}: {e}; retrying", file=sys.stderr, flush=True)
            time.sleep(min(60, 5 * 2 ** attempt))
    raise AssertionError("unreachable")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("url")
    ap.add_argument("--size", type=int, required=True)
    ap.add_argument("--md5", default="")
    ap.add_argument("--connections", type=int, default=6)
    ap.add_argument("--segment", type=int, default=32 << 20)
    ap.add_argument("--ahead", type=int, default=16, help="segments fetched ahead of the writer")
    args = ap.parse_args()

    out = sys.stdout.buffer
    md5 = hashlib.md5()
    starts = list(range(0, args.size, args.segment))
    slots = threading.Semaphore(args.ahead)
    t0 = time.time()
    written = 0
    last_report = 0

    def job(start: int) -> bytes:
        return fetch(args.url, start, min(start + args.segment, args.size))

    with ThreadPoolExecutor(args.connections) as pool:
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
            md5.update(data)
            try:
                out.write(data)
            except BrokenPipeError:
                print("[fetch] reader went away", file=sys.stderr)
                sys.exit(1)
            written += len(data)
            if written - last_report >= 1 << 30 or written == args.size:
                last_report = written
                rate = written / max(time.time() - t0, 1e-6) / 1e6
                eta = (args.size - written) / max(rate * 1e6, 1) / 60
                print(f"[fetch] {written / 1e9:.1f} / {args.size / 1e9:.1f} GB · {rate:.1f} MB/s · {eta:.0f} min left",
                      file=sys.stderr, flush=True)
    out.flush()
    digest = md5.hexdigest()
    if args.md5 and digest != args.md5:
        print(f"[fetch] MD5 MISMATCH: got {digest}, expected {args.md5}", file=sys.stderr, flush=True)
        sys.exit(2)
    print(f"[fetch] done, {written} bytes, md5 {digest}" + (" (verified)" if args.md5 else ""), file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()

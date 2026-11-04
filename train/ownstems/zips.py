"""Opening zip archives that are bigger than 4 GB but were written without zip64 records.

macOS Archive Utility does this (VocalSet1-2.zip, 6 GB, is one): every offset
in the central directory is stored modulo 2**32, so Python's zipfile looks in
the wrong place and fails with "Truncated file header". `open_zip` repairs the
offsets in memory: the true offset of each entry is its stored offset plus
some multiple of 2**32, and the right multiple is the one where the entry's
own local header (signature and file name) is found. The file on disk is
never changed, so its published checksum still holds. A sound archive is
returned untouched.
"""

from __future__ import annotations

import struct
import zipfile
from pathlib import Path

LOCAL = b"PK\x03\x04"
WRAP = 1 << 32


def _name_bytes(i: zipfile.ZipInfo) -> bytes:
    return i.orig_filename.encode("utf-8" if i.flag_bits & 0x800 else "cp437")


def _header_at(fp, off: int, size: int, want: bytes) -> bool:
    if off < 0 or off + 30 > size:
        return False
    fp.seek(off)
    h = fp.read(30)
    if h[:4] != LOCAL:
        return False
    n = struct.unpack("<H", h[26:28])[0]
    return fp.read(n) == want


def repair(z: zipfile.ZipFile, wrap: int = WRAP) -> int:
    """Fixes wrapped header offsets in place; returns how many entries moved."""
    fp = z.fp
    assert fp is not None, "the zip must be open"
    fp.seek(0, 2)
    size = fp.tell()
    infos = z.infolist()
    stride = max(1, len(infos) // 64)
    probe = infos[::stride] + infos[-1:]
    if all(_header_at(fp, i.header_offset, size, _name_bytes(i)) for i in probe):
        return 0
    moved = 0
    for i in infos:
        want = _name_bytes(i)
        if _header_at(fp, i.header_offset, size, want):
            continue
        low = i.header_offset % wrap
        for k in range(size // wrap + 1):
            if _header_at(fp, low + k * wrap, size, want):
                i.header_offset = low + k * wrap
                moved += 1
                break
        else:
            raise zipfile.BadZipFile(f"{i.filename}: no local header at any offset that wraps to {low}")
    # Python 3.12+ guards against overlapping entries with each entry's end,
    # worked out from the offsets it read; work it out again from the true ones.
    if hasattr(infos[0], "_end_offset"):
        end = z.start_dir
        for i in sorted(infos, key=lambda i: i.header_offset, reverse=True):
            i._end_offset = end
            end = i.header_offset
    return moved


def open_zip(path: Path | str) -> zipfile.ZipFile:
    z = zipfile.ZipFile(path)
    try:
        repair(z)
    except Exception:
        z.close()
        raise
    return z

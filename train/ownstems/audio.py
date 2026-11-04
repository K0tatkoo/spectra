"""Reading clips: the only way training audio is opened.

`Library.open` takes a catalog Clip and returns float32 (channels, samples)
at 44.1 kHz. It re-checks that the clip's artifact is in the ledger under an
allowed licence, so nothing reaches the mixer around the ledger.
Archive members (E-GMD's zip, say) are read in place; nothing is unpacked.
"""

from __future__ import annotations

import io
import zipfile
from pathlib import Path

import numpy as np
import soundfile as sf

from . import RATE
from .ledger import Catalog, Clip, Ledger, RefusedError
from .licences import allowed

try:
    import soxr
except ImportError:  # only needed for sources not at 44.1 kHz
    soxr = None


def resample(x: np.ndarray, rate: int) -> np.ndarray:
    if rate == RATE:
        return x
    if soxr is None:
        raise RuntimeError("soxr is needed to resample")
    return np.ascontiguousarray(soxr.resample(x.T, rate, RATE, quality="HQ").T, dtype=np.float32)


class Library:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.ledger = Ledger(self.root)
        self.catalog = Catalog(self.root, self.ledger)
        self._zips: dict[str, zipfile.ZipFile] = {}

    def _bytes_of(self, clip: Clip) -> io.BytesIO | Path:
        a = self.ledger.artifacts.get(clip.artifact)
        if a is None or not allowed(a.licence):
            raise RefusedError(f"{clip.artifact}: not an allowed ledger entry")
        path = self.ledger.file(a)
        if not clip.member:
            return path
        z = self._zips.get(a.id)
        if z is None:
            z = self._zips[a.id] = zipfile.ZipFile(path)
        return io.BytesIO(z.read(clip.member))

    def open(self, clip: Clip, start_s: float = 0.0, seconds: float | None = None) -> np.ndarray:
        """float32 (channels, samples) at 44.1 kHz, from start_s for `seconds` (all of it if None)."""
        src = self._bytes_of(clip)
        with sf.SoundFile(src) as f:
            rate = f.samplerate
            first = int(start_s * rate)
            frames = -1 if seconds is None else int(round(seconds * rate))
            if first:
                f.seek(min(first, f.frames))
            x = f.read(frames, dtype="float32", always_2d=True).T
        return resample(np.ascontiguousarray(x), rate)

    def close(self) -> None:
        for z in self._zips.values():
            z.close()
        self._zips.clear()


def probe(src) -> tuple[float, int, int]:
    """(seconds, rate, channels) of a file or file-like object."""
    info = sf.info(src)
    return info.frames / info.samplerate, info.samplerate, info.channels

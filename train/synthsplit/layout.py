"""The splitter's framing contract, shared with the app.

Everything here is mirrored in app/src/main/java/com/n3d/spectra/stems/SplitLayout.kt.
The band edges are computed only here and travel inside the exported model as
metadata, so the app never recomputes them; the two window formulas are written
out in both languages and pinned by the DSP fixture test on each side.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

FORMAT_KEY = "spectra.format"
FORMAT = "synth-split/1"

# Order matters: it is the model's input order, and the app reads it back.
DEFAULT_INPUTS = ("vocals", "other")


def band_edges(n_fft: int = 2048, sample_rate: int = 44100,
               per_bin_below_hz: float = 4000.0, bands_above: int = 48) -> list[int]:
    """One bin per band up to `per_bin_below_hz`, where notes sit close
    together, then `bands_above` log-spaced bands to Nyquist."""
    bins = n_fft // 2 + 1
    split = int(round(per_bin_below_hz * n_fft / sample_rate))
    split = max(1, min(split, bins - 1))
    edges = list(range(split))
    wide = np.unique(np.round(np.geomspace(split, bins, bands_above + 1)).astype(int))
    edges += [int(e) for e in wide]
    assert edges[0] == 0 and edges[-1] == bins
    assert all(b > a for a, b in zip(edges, edges[1:]))
    return edges


@dataclass(frozen=True)
class Layout:
    sample_rate: int = 44100
    n_fft: int = 2048
    hop: int = 512
    inputs: tuple[str, ...] = DEFAULT_INPUTS
    edges: tuple[int, ...] = field(default_factory=lambda: tuple(band_edges()))

    def __post_init__(self):
        assert self.n_fft & (self.n_fft - 1) == 0, "n_fft must be a power of two"
        assert 0 < 2 * self.hop <= self.n_fft, "hop must be at most half the frame"
        assert self.edges[0] == 0 and self.edges[-1] == self.bins

    @property
    def bins(self) -> int:
        return self.n_fft // 2 + 1

    @property
    def bands(self) -> int:
        return len(self.edges) - 1

    @property
    def features(self) -> int:
        """Network inputs (and outputs) per frame: every input's band powers."""
        return len(self.inputs) * self.bands

    def band_of_bin(self) -> np.ndarray:
        out = np.empty(self.bins, dtype=np.int64)
        for b in range(self.bands):
            out[self.edges[b]:self.edges[b + 1]] = b
        return out

    def band_widths(self) -> np.ndarray:
        e = np.asarray(self.edges)
        return (e[1:] - e[:-1]).astype(np.float64)

    def analysis_window(self) -> np.ndarray:
        """A slow rise (half a Hann of 2·(n_fft − hop)), then the falling half of a Hann of 2·hop."""
        n, h = self.n_fft, self.hop
        rise = n - h
        idx = np.arange(n)
        w = np.where(idx < rise, hann(2 * rise, idx), hann(2 * h, idx - (n - 2 * h)))
        return np.sqrt(w)

    def synthesis_window(self) -> np.ndarray:
        """Zero but for the last 2·hop samples, where analysis × synthesis = Hann(2·hop)."""
        n, h = self.n_fft, self.hop
        a = self.analysis_window()
        start = n - 2 * h
        idx = np.arange(n)
        product = hann(2 * h, idx - start)
        with np.errstate(divide="ignore", invalid="ignore"):
            mid = np.where(a > 0, product / a, 0.0)
        return np.where(idx < start, 0.0, np.where(idx < n - h, mid, np.sqrt(np.clip(product, 0, None))))

    def metadata(self) -> dict[str, str]:
        """What the app needs to run the model, as ONNX metadata_props."""
        return {
            FORMAT_KEY: FORMAT,
            "sample_rate": str(self.sample_rate),
            "n_fft": str(self.n_fft),
            "hop": str(self.hop),
            "band_edges": ",".join(str(e) for e in self.edges),
            "inputs": ",".join(self.inputs),
        }


def hann(length: int, n) -> np.ndarray:
    """Periodic Hann: copies half its length apart sum to exactly 1."""
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * np.asarray(n, dtype=np.float64) / length)

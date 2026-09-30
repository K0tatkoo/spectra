"""The app's stem model, run offline over training mixes.

The splitter never sees clean studio stems in the app: it sees what StemgenRT
made of the mix, bleed and misrouting included — and a synth lead mostly lands
in StemgenRT's *vocals*. So the training inputs come from this exact model
file (the one app/build.gradle.kts pins), called exactly the way the app calls
it: 128 samples at a time, eight states carried, output one hop late.
"""

from __future__ import annotations

import hashlib
import urllib.request
from pathlib import Path

import numpy as np

# Pinned to the bytes the app ships (app/build.gradle.kts), not to upstream's latest.
MODEL_URL = ("https://media.githubusercontent.com/media/sweetspotsoundsystem/stemgen-rt/"
             "990df8ee5baa621f042d4534dacc65afee0a96ce/model/model.onnx")
MODEL_SHA256 = "08424ca91feae8d4746442a35ebf70489dea70ea6e81401b39483cf02d497748"
MODEL_BYTES = 37_532_574

SAMPLE_RATE = 44100
HOP = 128
ORDER = ("drums", "bass", "vocals", "other")
STATE_SHAPES = {
    "audio_history": (1, 2, 896),
    "fusion_hidden": (2, 1, 1000),
    "spectral_numerator_tail": (1, 4, 2, 128),
    "waveform_tail": (1, 4, 2, 128),
    "attention_keys": (1, 31, 64),
    "attention_values": (1, 31, 128),
    "spec_memory_hidden": (1, 1, 500),
    "waveform_memory_hidden": (1, 1, 500),
}


def fetch_model(cache: Path) -> Path:
    """Downloads the pinned model once and refuses any other bytes."""
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists() or cache.stat().st_size != MODEL_BYTES:
        part = cache.with_suffix(".part")
        with urllib.request.urlopen(MODEL_URL) as r, open(part, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        part.replace(cache)
    digest = hashlib.sha256(cache.read_bytes()).hexdigest()
    if digest != MODEL_SHA256:
        raise RuntimeError(f"{cache} is not the pinned stem model (sha256 {digest})")
    return cache


class StemgenRT:
    def __init__(self, model_path: Path, threads: int = 1):
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        so.add_session_config_entry("session.intra_op.allow_spinning", "0")
        self.session = ort.InferenceSession(str(model_path), so, providers=["CPUExecutionProvider"])
        self.outputs = [o.name for o in self.session.get_outputs()]

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        """stereo (2, T) float32 at 44.1 kHz -> stems (4, 2, T) in ORDER, aligned with the input."""
        assert stereo.ndim == 2 and stereo.shape[0] == 2
        total = stereo.shape[1]
        hops = -(-total // HOP) + 1  # one extra, silent hop flushes the last one out
        padded = np.zeros((2, hops * HOP), dtype=np.float32)
        padded[:, :total] = stereo
        states = {k: np.zeros(v, dtype=np.float32) for k, v in STATE_SHAPES.items()}
        out = np.empty((4, 2, hops * HOP), dtype=np.float32)
        feeds = dict(states)
        for i in range(hops):
            feeds["audio_chunk"] = padded[None, :, i * HOP:(i + 1) * HOP]
            results = dict(zip(self.outputs, self.session.run(self.outputs, feeds)))
            out[:, :, i * HOP:(i + 1) * HOP] = results["separated_chunk"][0]
            for k in STATE_SHAPES:
                feeds[k] = results["next_" + k]
        # Each call returns the hop before the one it was given.
        return out[:, :, HOP:HOP + total]

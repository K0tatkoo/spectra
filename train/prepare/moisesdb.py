"""MoisesDB -> the splitter's corpus (synthsplit/corpus.py).

MoisesDB labels every track: "synth lead" and "synth pad" sit under "other
keys", which is exactly the synth stem (Leads + pads; synth bass stays in
bass, where MoisesDB already puts it). The whole mix is also run through the
app's stem model, so the splitter trains on what it will really be handed.

    python prepare/moisesdb.py --moisesdb D:/data/moisesdb/moisesdb_v0.1 --out D:/data/synthsplit --workers 8

Resumable: a song whose meta.json exists is skipped. About 10 % of artists are
held out for validation, chosen by a hash of the artist's name so the split
never moves between runs.
"""

from __future__ import annotations

import os

# numpy's and scipy's bundled OpenBLAS reserve memory for every core in every
# process the moment they are imported: 1.5 GB a process on the 24-thread
# desktop, so a pool of workers hits Windows' commit limit. Each worker here
# computes on one thread anyway.
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")

import argparse
import hashlib
import json
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from synthsplit import corpus, stemgen  # noqa: E402

_sg = None


def classify(stem_name: str, track_type: str) -> str:
    stem = stem_name.lower().replace(" ", "_")
    kind = track_type.lower().replace("_", " ")
    if stem == "other_keys" and "synth" in kind:
        return "synth"
    if stem == "vocals":
        return "vocals"
    if stem in ("bass", "drums"):
        return "bassdrums"
    return "nonsynth"


def split_for(artist: str) -> str:
    h = int(hashlib.sha1(artist.strip().lower().encode()).hexdigest(), 16)
    return "valid" if h % 10 == 0 else "train"


def load_stereo(path: Path) -> np.ndarray:
    audio, rate = sf.read(str(path), dtype="float32", always_2d=True)
    if rate != stemgen.SAMPLE_RATE:
        raise ValueError(f"{path}: {rate} Hz, expected {stemgen.SAMPLE_RATE}")
    audio = audio.T
    if audio.shape[0] == 1:
        audio = np.repeat(audio, 2, axis=0)
    return audio[:2]


def _init(model_path: str):
    global _sg
    _sg = stemgen.StemgenRT(Path(model_path))


def prepare(track_dir: str, out_root: str) -> str:
    track = Path(track_dir)
    data = json.loads((track / "data.json").read_text())
    song_id = track.name
    out = Path(out_root) / "moisesdb" / song_id
    if (out / "meta.json").exists():
        return f"skip {song_id}"
    groups: dict[str, list[np.ndarray]] = {g: [] for g in corpus.GROUPS}
    kinds: dict[str, list[str]] = {g: [] for g in corpus.GROUPS}
    for stem in data.get("stems", []):
        name = stem.get("stemName", "")
        for t in stem.get("tracks", []):
            path = track / name / f"{t['id']}.{t.get('extension', 'wav')}"
            if not path.exists():
                continue
            g = classify(name, t.get("trackType", ""))
            groups[g].append(load_stereo(path))
            kinds[g].append(f"{name}/{t.get('trackType', '')}")
    lengths = [a.shape[1] for v in groups.values() for a in v]
    if not lengths:
        return f"empty {song_id}"
    total = max(lengths)

    def summed(arrs):
        acc = np.zeros((2, total), dtype=np.float32)
        for a in arrs:
            acc[:, :a.shape[1]] += a
        return acc

    stacked = {g: summed(v) for g, v in groups.items()}
    meta = corpus.write_song(
        out, "moisesdb", song_id, split_for(data.get("artist", "")), stacked, _sg,
        extra={"artist": data.get("artist", ""), "song": data.get("song", ""), "genre": data.get("genre", ""),
               "sources": kinds},
    )
    return f"done {song_id} ({meta['split']}, synth {meta['rms_db']['synth']} dB)"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--moisesdb", required=True, type=Path, help="folder holding the provider folders")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    args = ap.parse_args()

    model = stemgen.fetch_model(args.model_cache)
    tracks = sorted(p.parent for p in args.moisesdb.glob("*/*/data.json"))
    print(f"{len(tracks)} MoisesDB tracks")
    with ProcessPoolExecutor(args.workers, initializer=_init, initargs=(str(model),)) as pool:
        futures = [pool.submit(prepare, str(t), str(args.out)) for t in tracks]
        for i, f in enumerate(as_completed(futures), 1):
            print(f"[{i}/{len(tracks)}] {f.result()}", flush=True)


if __name__ == "__main__":
    main()

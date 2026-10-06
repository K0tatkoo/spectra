"""MoisesDB -> the splitter's corpus (synthsplit/corpus.py).

MoisesDB labels every track: "synth lead" and "synth pad" sit under "other
keys", which is exactly the synth stem (Leads + pads; synth bass stays in
bass, where MoisesDB already puts it). The whole mix is also run through the
app's stem model, so the splitter trains on what it will really be handed.

    python prepare/moisesdb.py --moisesdb C:/Users/Kotatko/Downloads/moisesdb.zip --out D:/synthsplit/corpus --workers 14

--moisesdb is the downloaded moisesdb.zip itself (read in place: unpacked it
is 149 GB more on disk) or the folder it was unpacked to. Inside, each song is
moisesdb_v0.1/<song id>/data.json with its stems at <stem name>/<track id>.wav.

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
import io
import json
import sys
import zipfile
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


# -- songs, from the zip or from folders ------------------------------------------------
# A song is named by a string: its folder, or "<zip>!<folder inside the zip>".

_zips: dict[str, zipfile.ZipFile] = {}


def _zip(path: str) -> zipfile.ZipFile:
    if path not in _zips:  # once per process: each worker opens its own
        _zips[path] = zipfile.ZipFile(path)
    return _zips[path]


def tracks(root: Path) -> list[str]:
    if root.suffix.lower() == ".zip":
        return sorted(f"{root}!{n[:-len('/data.json')]}" for n in _zip(str(root)).namelist() if n.endswith("/data.json"))
    return sorted(str(p.parent) for p in root.glob("**/data.json"))


def song_id(track: str) -> str:
    return track.replace("\\", "/").rstrip("/").rpartition("/")[2]


def read_bytes(track: str, rel: str) -> bytes | None:
    if "!" in track:
        zpath, inner = track.split("!", 1)
        try:
            return _zip(zpath).read(f"{inner}/{rel}")
        except KeyError:
            return None
    path = Path(track) / rel
    return path.read_bytes() if path.exists() else None


def song_data(track: str) -> dict:
    return json.loads(read_bytes(track, "data.json"))


def stems(track: str, data: dict | None = None):
    """(relative path, group, track type) of every track the song has audio for."""
    for stem in (data or song_data(track)).get("stems", []):
        name = stem.get("stemName", "")
        for t in stem.get("tracks", []):
            yield f"{name}/{t['id']}.{t.get('extension', 'wav')}", classify(name, t.get("trackType", "")), \
                f"{name}/{t.get('trackType', '')}"


def decode(data: bytes, where: str = "", start: int = 0, length: int | None = None) -> np.ndarray:
    """WAV bytes -> stereo float32 (2, T), optionally only `length` samples from `start`."""
    stop = None if length is None else start + length
    audio, rate = sf.read(io.BytesIO(data), start=start, stop=stop, dtype="float32", always_2d=True)
    if rate != stemgen.SAMPLE_RATE:
        raise ValueError(f"{where}: {rate} Hz, expected {stemgen.SAMPLE_RATE}")
    audio = audio.T
    if audio.shape[0] == 1:
        audio = np.repeat(audio, 2, axis=0)
    return audio[:2]


def frames(data: bytes) -> int:
    return sf.info(io.BytesIO(data)).frames


def _init(model_path: str):
    global _sg
    _sg = stemgen.StemgenRT(Path(model_path))


def prepare(track: str, out_root: str) -> str:
    sid = song_id(track)
    out = Path(out_root) / "moisesdb" / sid
    if (out / "meta.json").exists():
        return f"skip {sid}"
    data = song_data(track)
    # Each stem is added to its group as soon as it is decoded: holding every stem of a
    # song first took 1.5–2 GB a worker.
    groups: dict[str, np.ndarray] = {}
    kinds: dict[str, list[str]] = {g: [] for g in corpus.GROUPS}
    for rel, g, kind in stems(track, data):
        raw = read_bytes(track, rel)
        if raw is None:
            continue
        a = decode(raw, f"{sid}/{rel}")
        del raw
        n = max([a.shape[1]] + [v.shape[1] for v in groups.values()])
        for k in corpus.GROUPS:  # every group as long as the longest stem so far
            v = groups.get(k)
            if v is None or v.shape[1] < n:
                grown = np.zeros((2, n), dtype=np.float32)
                if v is not None:
                    grown[:, :v.shape[1]] = v
                groups[k] = grown
        groups[g][:, :a.shape[1]] += a
        kinds[g].append(kind)
    if not groups:
        return f"empty {sid}"
    stacked = groups
    meta = corpus.write_song(
        out, "moisesdb", sid, split_for(data.get("artist", "")), stacked, _sg,
        extra={"artist": data.get("artist", ""), "song": data.get("song", ""), "genre": data.get("genre", ""),
               "sources": kinds},
    )
    return f"done {sid} ({meta['split']}, synth {meta['rms_db']['synth']} dB)"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--moisesdb", required=True, type=Path, help="moisesdb.zip, or the folder it was unpacked to")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    args = ap.parse_args()

    model = stemgen.fetch_model(args.model_cache)
    songs = tracks(args.moisesdb)
    print(f"{len(songs)} MoisesDB songs", flush=True)
    with ProcessPoolExecutor(args.workers, initializer=_init, initargs=(str(model),)) as pool:
        futures = [pool.submit(prepare, t, str(args.out)) for t in songs]
        for i, f in enumerate(as_completed(futures), 1):
            print(f"[{i}/{len(songs)}] {f.result()}", flush=True)


if __name__ == "__main__":
    main()

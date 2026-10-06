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


def prepare(track: str, out_root: str, keep: float = 0) -> str:
    """keep > 0 keeps only that many seconds: where the synth is, or the middle of a song
    with none. The synth stems are decoded whole to find the place; every other stem only
    for the kept stretch and the stem model's warm-up before it."""
    sid = song_id(track)
    out = Path(out_root) / "moisesdb" / sid
    if (out / "meta.json").exists():
        return f"skip {sid}"
    data = song_data(track)
    listed = [(rel, g, kind) for rel, g, kind in stems(track, data)]
    present = []
    total = 0
    synth = None
    for rel, g, kind in listed:
        raw = read_bytes(track, rel)
        if raw is None:
            continue
        present.append((rel, g, kind))
        total = max(total, frames(raw))
        if g == "synth":
            a = decode(raw, f"{sid}/{rel}")
            if synth is None or synth.shape[1] < a.shape[1]:
                grown = np.zeros((2, max(a.shape[1], 0 if synth is None else synth.shape[1])), dtype=np.float32)
                if synth is not None:
                    grown[:, :synth.shape[1]] = synth
                synth = grown
            synth[:, :a.shape[1]] += a
    if not present:
        return f"empty {sid}"
    keep_n = int(keep * stemgen.SAMPLE_RATE)
    if keep_n and total > keep_n:
        if synth is not None and np.any(synth):
            padded = np.zeros((2, total), dtype=np.float32)
            padded[:, :synth.shape[1]] = synth
            begin = corpus.synth_window(padded, keep_n)
        else:
            begin = (total - keep_n) // 2 // stemgen.SAMPLE_RATE * stemgen.SAMPLE_RATE
        stop = begin + keep_n
    else:
        begin, stop = 0, total
    pre = min(begin, corpus.PREROLL)
    w0 = begin - pre  # every array covers [w0, stop): warm-up, then the kept stretch
    groups = {g: np.zeros((2, stop - w0), dtype=np.float32) for g in corpus.GROUPS}
    if synth is not None:
        seg = synth[:, w0:stop]
        groups["synth"][:, :seg.shape[1]] = seg
    del synth
    kinds: dict[str, list[str]] = {g: [] for g in corpus.GROUPS}
    for rel, g, kind in present:
        kinds[g].append(kind)
        if g == "synth":
            continue
        a = decode(read_bytes(track, rel), f"{sid}/{rel}", w0, stop - w0)
        groups[g][:, :a.shape[1]] += a
    meta = corpus.write_song(
        out, "moisesdb", sid, split_for(data.get("artist", "")), groups, _sg, window=(pre, stop - w0),
        extra={"artist": data.get("artist", ""), "song": data.get("song", ""), "genre": data.get("genre", ""),
               "sources": kinds, "kept_from_s": round(begin / stemgen.SAMPLE_RATE, 2),
               "song_seconds": round(total / stemgen.SAMPLE_RATE, 1)},
    )
    return f"done {sid} ({meta['split']}, synth {meta['rms_db']['synth']} dB)"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--moisesdb", required=True, type=Path, help="moisesdb.zip, or the folder it was unpacked to")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--max-seconds", type=float, default=0,
                    help="keep only this much of each song, where its synth is (0 = all of it)")
    ap.add_argument("--model-cache", type=Path, default=Path("models/stemgen-rt-08424ca9.onnx"))
    args = ap.parse_args()

    model = stemgen.fetch_model(args.model_cache)
    songs = tracks(args.moisesdb)
    print(f"{len(songs)} MoisesDB songs", flush=True)
    with ProcessPoolExecutor(args.workers, initializer=_init, initargs=(str(model),)) as pool:
        futures = [pool.submit(prepare, t, str(args.out), args.max_seconds) for t in songs]
        for i, f in enumerate(as_completed(futures), 1):
            print(f"[{i}/{len(songs)}] {f.result()}", flush=True)


if __name__ == "__main__":
    main()

"""Moves Slakh's synth patches that General MIDI files elsewhere into the synth stem.

Slakh labels a stem by its General MIDI family, so SynthStrings 1/2 and Synth
Voice sit in "Strings (continued)" and SynthBrass 1/2 in "Brass" — synth
pads and synth stabs by any listener's ear, but "not synth" by family. The
first training run (2026-10-01) learned to call them synth anyway and was
scored as wrong for it: 4 of the 11 held-out songs "without synth" had them.

Moving a stem between synth and nonsynth leaves the mix — and so the stem
model's outputs, the expensive part — exactly as it was; only the two target
arrays change. The amount moved is added to one and taken from the other, so
they still sum to what they did.

    python prepare/relabel_slakh.py --corpus ~/synthsplit-data/corpus --slakh ~/synthsplit-data/slakh
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare.slakh import SYNTH_PROGRAMS, classify, load_stereo  # noqa: E402
from synthsplit import corpus  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", required=True, type=Path)
    ap.add_argument("--slakh", required=True, type=Path)
    args = ap.parse_args()

    moved_songs = 0
    for meta_path in sorted((args.corpus / "slakh").glob("*/meta.json")):
        meta = json.loads(meta_path.read_text())
        if meta.get("relabelled") is not None:
            continue
        md = yaml.safe_load((args.slakh / "slakh2100_flac_redux" / meta["slakh_split"] / meta["id"] / "metadata.yaml").read_text())
        moving = []
        for sid, info in (md.get("stems") or {}).items():
            if not info.get("audio_rendered", True):
                continue
            old = classify(info.get("inst_class", ""), bool(info.get("is_drum", False)))
            new = classify(info.get("inst_class", ""), bool(info.get("is_drum", False)), info.get("program_num"))
            if old == "nonsynth" and new == "synth":
                moving.append((sid, SYNTH_PROGRAMS[info.get("program_num")]))
        song = meta_path.parent
        if moving:
            start = int(round(meta["kept_from_s"])) * 44100
            n = int(meta["samples"])
            gain = 10 ** (meta["gain_db"] / 20)
            moved = np.zeros(n, dtype=np.float64)
            for sid, _ in moving:
                a = load_stereo(args.slakh / "slakh2100_flac_redux" / meta["slakh_split"] / meta["id"] / "stems" / f"{sid}.flac")
                seg = a[:, start:start + n].mean(axis=0)
                moved[:len(seg)] += seg * gain
            synth = np.load(song / "synth.npy").astype(np.float64) + moved
            nonsynth = np.load(song / "nonsynth.npy").astype(np.float64) - moved
            np.save(song / "synth.npy", synth.astype(np.float16))
            np.save(song / "nonsynth.npy", nonsynth.astype(np.float16))
            meta["rms_db"]["synth"] = round(corpus.rms_db(synth), 1)
            meta["rms_db"]["nonsynth"] = round(corpus.rms_db(nonsynth), 1)
            meta["synth_activity_db"] = corpus.activity(synth)
            meta["sources"]["synth"] = meta["sources"].get("synth", []) + [name for _, name in moving]
            moved_songs += 1
            print(f"{meta['id']} ({meta['split']}): {', '.join(name for _, name in moving)} -> synth "
                  f"(synth now {meta['rms_db']['synth']} dB)", flush=True)
        meta["relabelled"] = [name for _, name in moving]
        meta_path.write_text(json.dumps(meta) + "\n")
    print(f"{moved_songs} songs relabelled")


if __name__ == "__main__":
    main()

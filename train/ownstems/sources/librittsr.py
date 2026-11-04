"""LibriTTS-R: read English speech, restored to studio quality — for rap and MC-style vocals.

Licence: CC BY 4.0, stated on https://www.openslr.org/141/ ("License: CC BY 4.0")
(Koizumi et al., 2023, derived from LibriTTS / LibriSpeech, public-domain audiobooks
from LibriVox). Checked 2026-11-04.

Files: train_clean_100.tar.gz by default (8.1 GB, md5 6df668d8f5f33e70876bfa33862ad02b,
from https://www.openslr.org/resources/141/md5sum.txt); --subset adds others.
Unpacked once (a gzipped tar can't be read in place); only the .wav files are kept.
Folder layout: LibriTTS_R/<subset>/<speaker>/<chapter>/<utterance>.wav.

Stem: vocals, tagged "speech" so the mixer can weigh it apart from singing.
Group = the speaker.
"""

from __future__ import annotations

import os
import sys
import tarfile
from pathlib import Path

from ..audio import probe
from ..ledger import Clip, Ledger, split_for
from . import artifacts_for, download, source_dir

NAME = "librittsr"
TITLE = "LibriTTS-R"
LICENCE = "CC-BY-4.0"
LICENCE_URL = "https://www.openslr.org/141/"
CREDIT = ("LibriTTS-R (Koizumi, Zen, Karita, Ding, Yatabe, Morioka, Bacchiani, Zhang, Han and Bapna, 2023), "
          "openslr.org/141, from LibriVox recordings")
ORIGIN = "https://www.openslr.org/resources/141/"
SUBSETS = {
    "train_clean_100": "6df668d8f5f33e70876bfa33862ad02b",
    "train_clean_360": "382eb3e64394b3da6a559f864339b22c",
    "dev_clean": "2c1f5312914890634cc2d15783032ff3",
}


def fetch(root: Path, args) -> None:
    d = source_dir(root, NAME)
    for sub in getattr(args, "subset", None) or ["train_clean_100"]:
        if sub not in SUBSETS:
            sys.exit(f"{NAME}: unknown subset {sub}; known: {', '.join(SUBSETS)}")
        done = d / f"{sub}.UNPACKED"
        if done.exists():
            print(f"{NAME}: {sub} already unpacked")
            continue
        tgz = download(ORIGIN + f"{sub}.tar.gz", d / f"{sub}.tar.gz", None, SUBSETS[sub])
        n = 0
        with tarfile.open(tgz, "r|gz") as tar:
            for m in tar:
                if not (m.isfile() and m.name.endswith(".wav")) or ".." in m.name.split("/"):
                    continue
                dest = d / m.name
                dest.parent.mkdir(parents=True, exist_ok=True)
                part = dest.with_name(dest.name + ".part")
                with tar.extractfile(m) as src, open(part, "wb") as f:
                    f.write(src.read())
                os.replace(part, dest)
                n += 1
        done.write_text(f"{n} files from {tgz.name} (md5 {SUBSETS[sub]} checked at download)\n")
        print(f"{NAME}: {sub}: {n} files unpacked")


def index(root: Path, ledger: Ledger, workers: int = 8):
    d = source_dir(root, NAME)
    files = sorted(d.glob("LibriTTS_R/*/*/*/*.wav"))
    if not files:
        raise SystemExit(f"{NAME}: not fetched")
    clips, ids = [], []
    for f in files:
        rel = f.relative_to(d).as_posix()
        speaker = f.parent.parent.name
        seconds, rate, channels = probe(f)
        aid = f"{NAME}/{rel}"
        ids.append(aid)
        clips.append(Clip(artifact=aid, stem="vocals", source=NAME, split=split_for(NAME, speaker), group=speaker,
                          seconds=round(seconds, 3), rate=rate, channels=channels, tags=["speech", f.parts[-4]]))
    arts = artifacts_for(ledger, sys.modules[__name__], files, ids, workers)
    return arts, clips

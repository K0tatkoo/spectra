"""Scores a checkpoint on another run's held-out set (valid.pt), as train_stems.py's validation does.

    python tools/score_on.py --ckpt D:/stems-clean/runs/v4/best.pt --valid D:/stems-clean/runs/v1-test/valid.pt

v4 labels a drum's sub bass, so it builds its own held-out set; scored on v1's (which v2 and v3
also used), its vocals, synth and "other" stay comparable with theirs. Its bass and drums do not:
that set labels the drums' sub drums.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ownstems.model import net_from_checkpoint  # noqa: E402
from ownstems.training import validate  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ckpt", required=True, type=Path)
    ap.add_argument("--valid", required=True, type=Path)
    ap.add_argument("--device", default="cpu")
    args = ap.parse_args()
    net = net_from_checkpoint(torch.load(args.ckpt, map_location="cpu", weights_only=False)).to(args.device)
    data = torch.load(args.valid, weights_only=False)
    print(json.dumps({"ckpt": str(args.ckpt), "valid": str(args.valid), **validate(net, data)}))


if __name__ == "__main__":
    main()

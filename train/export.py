"""Exports a trained splitter as the ONNX file the app loads, and proves it.

    python export.py --run runs/first --out ../app/src/main/assets/stems/synth-split.onnx

The graph is one frame of the network (SplitStep): `power` in, `mask` out,
`state` carried as `next_state` — the contract OnnxMaskNet.kt checks on load.
The layout (frame size, hop, band edges, input stems) goes into the file's
metadata, so the app needs no change for a retrained model with other numbers.

Before writing, the export is run frame by frame in ONNX Runtime and compared
with the PyTorch network run over the whole sequence at once; any difference
above 1e-5 stops it.

    python export.py --contract-fixture

writes instead a tiny untrained network (hidden 8) and what it answers to a
fixed input, into app/src/test/resources/synthsplit/, for the app's
OnnxMaskNetTest: the contract, checked from the Kotlin side through the same
ONNX Runtime the phone uses.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
from pathlib import Path

import numpy as np
import torch

from synthsplit.layout import Layout
from synthsplit.model import SplitStep, SynthSplitNet

APP = Path(__file__).resolve().parents[1] / "app"


def layout_from(d: dict) -> Layout:
    return Layout(sample_rate=d["sample_rate"], n_fft=d["n_fft"], hop=d["hop"],
                  inputs=tuple(d["inputs"]), edges=tuple(d["edges"]))


def export(net: SynthSplitNet, path: Path, provenance: dict[str, str]) -> None:
    import onnx

    lay = net.layout
    step = SplitStep(net).eval()
    power = torch.full((1, 1, lay.features), 1e-3)
    state = net.initial_state(1)
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(step, (power, state), str(path), input_names=["power", "state"],
                      output_names=["mask", "next_state"], opset_version=17, do_constant_folding=True,
                      dynamo=False)
    model = onnx.load(str(path))
    for k, v in {**lay.metadata(), **provenance}.items():
        entry = model.metadata_props.add()
        entry.key, entry.value = k, v
    onnx.checker.check_model(model)
    onnx.save(model, str(path))


def verify(net: SynthSplitNet, path: Path, frames: int = 300, seed: int = 0) -> tuple[np.ndarray, np.ndarray, float]:
    """Frame-by-frame ONNX Runtime against the whole-sequence PyTorch network."""
    import onnxruntime as ort

    lay = net.layout
    rng = np.random.default_rng(seed)
    # A plausible walk of band powers: log-normal around music-like levels, drifting.
    logp = np.cumsum(rng.normal(0, 0.3, (frames, lay.features)), axis=0) + rng.normal(-2, 3, lay.features)
    powers = np.exp(logp).astype(np.float32)
    with torch.no_grad():
        want, _ = net(torch.from_numpy(powers)[None])
    want = want[0].numpy()
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    state = np.zeros((net.layers, 1, net.hidden), dtype=np.float32)
    got = np.empty_like(want)
    for f in range(frames):
        mask, state = sess.run(["mask", "next_state"], {"power": powers[f][None, None], "state": state})
        got[f] = mask[0, 0]
    return powers, got, float(np.abs(got - want).max())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", type=Path, help="a training run folder (uses its averaged weights)")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--contract-fixture", action="store_true")
    args = ap.parse_args()

    if args.contract_fixture:
        torch.manual_seed(7)
        net = SynthSplitNet(Layout(), hidden=8, layers=2).eval()
        out = APP / "src/test/resources/synthsplit"
        model = out / "net-fixture.onnx"
        export(net, model, {"spectra.note": "untrained contract fixture, not a real model"})
        powers, masks, err = verify(net, model, frames=64, seed=1)
        assert err < 1e-5, f"ONNX Runtime differs from PyTorch by {err}"
        (out / "net-fixture.bin").write_bytes(np.concatenate([powers.ravel(), masks.ravel()]).astype("<f4").tobytes())
        (out / "net-fixture.json").write_text(json.dumps({"frames": 64, "features": net.layout.features,
                                                          "layout": "float32 LE: powers[frames][features], masks[frames][features]"}) + "\n")
        print(f"wrote the contract fixture to {out} (ONNX vs PyTorch {err:.1e})")
        return

    if not args.run or not args.out:
        ap.error("--run and --out are required (or --contract-fixture)")
    ckpt = torch.load(args.run / "checkpoint.pt", map_location="cpu")
    lay = layout_from(ckpt["layout"])
    net = SynthSplitNet(lay, int(ckpt["hidden"]), int(ckpt["layers"]))
    net.load_state_dict(ckpt["ema"])
    net.eval()
    result = {}
    if (args.run / "result.json").exists():
        result = json.loads((args.run / "result.json").read_text())
    provenance = {
        "spectra.trained": datetime.date.today().isoformat(),
        "spectra.steps": str(ckpt["step"]),
        "spectra.valid_sdr_db": f"{result.get('valid_sdr', float('nan')):.2f}",
        "spectra.data": "MoisesDB (CC BY-NC-SA 4.0), Slakh2100 (CC BY 4.0)",
        "spectra.network": f"GRU {ckpt['layers']}x{ckpt['hidden']}",
    }
    export(net, args.out, provenance)
    _, _, err = verify(net, args.out)
    if err > 1e-5:
        args.out.unlink()
        raise SystemExit(f"ONNX Runtime differs from PyTorch by {err}: not written")
    data = args.out.read_bytes()
    print(f"wrote {args.out}: {len(data) / 1e6:.2f} MB, sha256 {hashlib.sha256(data).hexdigest()}, "
          f"ONNX vs PyTorch {err:.1e}, {sum(p.numel() for p in net.parameters()) / 1e6:.2f} M parameters")


if __name__ == "__main__":
    main()

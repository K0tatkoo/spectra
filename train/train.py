"""Trains the synth splitter.

    python train.py --data D:/data/synthsplit --out runs/first
    python train.py --data D:/data/synthsplit --out runs/first --hours 9    # as many steps as fit in 9 h
    python train.py --data D:/data/synthsplit --out runs/first --resume     # after a stop or a crash

Most steps train on short crops, because the GRU's cost grows with crop
length and barely with batch size; the last --long-share of them on long
crops, so the network also learns to run for a long time from one start, as
it does on the phone.

Writes runs/<name>/checkpoint.pt every --save-every steps (weights, averaged
weights, optimiser, step) and one line of metrics.jsonl per report. Validation
is on held-out songs as the app's stem model heard them, scored against three
answers that need no network: no synth at all, "all of other is synth", and
"all of vocals and other is synth". A model that cannot beat those is not
worth shipping.
"""

from __future__ import annotations

import argparse
import copy
import dataclasses
import json
import math
import time
from pathlib import Path

import torch
from torch.utils.data import DataLoader

from synthsplit.corpus import list_songs
from synthsplit.data import Mix, SplitDataset
from synthsplit.dsp import SplitDSP
from synthsplit.layout import Layout
from synthsplit.losses import floored_sdr_loss
from synthsplit.model import LOG_EPS, SynthSplitNet, split_batch

RATE = 44100
WARM = RATE // 2  # the first half second of a crop is the network finding its feet: not scored


def pick_device(name: str) -> torch.device:
    if name != "auto":
        return torch.device(name)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def crop_samples(seconds: float, hop: int) -> int:
    return int(seconds * RATE) // hop * hop


def score(dsp, net, x: torch.Tensor, s: torch.Tensor, hop: int):
    """Returns (loss per example, estimate) for a batch: x (B, K, T), s (B, T)."""
    est, _, _ = split_batch(dsp, net, x)
    end = x.shape[-1] - hop
    ref = s[:, :end]
    energy = x[:, :, WARM:end].sum(1).square().sum(-1)
    return floored_sdr_loss(est[:, WARM:], ref[:, WARM:], energy), est


@torch.no_grad()
def measure_features(dsp, loader, device, batches: int, features: int):
    """Mean and spread of every log band power over some training examples."""
    # Summed in float64 on the CPU: MPS has no float64, and a float32 sum of
    # millions of squares loses the digits the spread lives in.
    total = torch.zeros(features, dtype=torch.float64)
    square = torch.zeros_like(total)
    count = 0
    for i, (x, _) in enumerate(loader):
        if i >= batches:
            break
        _, power = dsp.analyze(x.to(device))
        b, k, f, bands = power.shape
        logp = torch.log(power.permute(0, 2, 1, 3).reshape(b * f, k * bands) + LOG_EPS).cpu().double()
        total += logp.sum(0)
        square += logp.square().sum(0)
        count += logp.shape[0]
    mean = total / count
    std = (square / count - mean.square()).clamp_min(1e-4).sqrt()
    return mean.float().to(device), std.float().to(device)


@torch.no_grad()
def validate(dsp, net, loader, device, hop: int):
    losses, zero, other, both = [], [], [], []
    for x, s in loader:
        x, s = x.to(device), s.to(device)
        loss, _ = score(dsp, net, x, s, hop)
        losses.append(loss)
        end = x.shape[-1] - hop
        energy = x[:, :, WARM:end].sum(1).square().sum(-1)
        ref = s[:, WARM:end]
        zero.append(floored_sdr_loss(torch.zeros_like(ref), ref, energy))
        other.append(floored_sdr_loss(x[:, 1, WARM:end], ref, energy))
        both.append(floored_sdr_loss(x[:, :, WARM:end].sum(1), ref, energy))
    sdr = lambda v: -torch.cat(v).mean().item()  # noqa: E731
    return {"valid_sdr": sdr(losses), "baseline_none": sdr(zero), "baseline_other": sdr(other),
            "baseline_vocals_other": sdr(both)}


def lr_at(step: int, args) -> float:
    if step < args.warmup:
        return args.lr * (step + 1) / args.warmup
    t = min(1.0, (step - args.warmup) / max(1, args.steps - args.warmup))
    return args.min_lr + 0.5 * (args.lr - args.min_lr) * (1 + math.cos(math.pi * t))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--device", default="auto")
    ap.add_argument("--steps", type=int, default=100_000)
    ap.add_argument("--hours", type=float, default=0,
                    help="fit the run in this long instead: --steps is set from the measured speed")
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--crop-seconds", type=float, default=2.0)
    ap.add_argument("--long-crop-seconds", type=float, default=6.0)
    ap.add_argument("--long-share", type=float, default=0.2, help="share of the steps on long crops")
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--min-lr", type=float, default=1e-5)
    ap.add_argument("--warmup", type=int, default=1000)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--ema", type=float, default=0.999)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--seed", type=int, default=20260930)
    ap.add_argument("--report-every", type=int, default=200)
    ap.add_argument("--valid-every", type=int, default=2500)
    ap.add_argument("--save-every", type=int, default=1000)
    ap.add_argument("--valid-examples", type=int, default=384)
    args = ap.parse_args()

    device = pick_device(args.device)
    torch.backends.cudnn.benchmark = True
    layout = Layout()
    hop = layout.hop
    crop = crop_samples(args.crop_seconds, hop)
    args.out.mkdir(parents=True, exist_ok=True)
    ckpt_path = args.out / "checkpoint.pt"

    train_songs = list_songs(args.data, "train")
    valid_songs = list_songs(args.data, "valid")
    print(f"{len(train_songs)} training songs, {len(valid_songs)} held out · device {device}")
    long_crop = crop_samples(args.long_crop_seconds, hop)
    long_batch = max(1, round(args.batch * crop / long_crop))
    # Long enough for any run; example i is the same on every run either way.
    train_ds = SplitDataset(train_songs, crop, 1 << 31, args.seed, Mix())
    long_ds = SplitDataset(train_songs, long_crop, 1 << 31, args.seed + 1, Mix())
    valid_ds = SplitDataset(valid_songs, long_crop, args.valid_examples, 12345, real_only=True)
    valid_loader = DataLoader(valid_ds, batch_size=long_batch, num_workers=args.workers)

    dsp = SplitDSP(layout).to(device)
    net = SynthSplitNet(layout, args.hidden, args.layers).to(device)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, betas=(0.9, 0.99), weight_decay=1e-4)
    step = 0
    if args.resume and ckpt_path.exists():
        ckpt = torch.load(ckpt_path, map_location=device)
        net.load_state_dict(ckpt["net"])
        ema = copy.deepcopy(net)
        ema.load_state_dict(ckpt["ema"])
        opt.load_state_dict(ckpt["opt"])
        step = ckpt["step"]
        print(f"resumed at step {step}")
    else:
        probe = DataLoader(train_ds, batch_size=args.batch, num_workers=args.workers)
        mean, std = measure_features(dsp, probe, device, 32, layout.features)
        net.mean.copy_(mean)
        net.std.copy_(std)
        ema = copy.deepcopy(net)
    ema.requires_grad_(False)

    if args.resume and ckpt_path.exists() and "steps" in ckpt:
        args.steps = int(ckpt["steps"])  # keep the schedule the run was started with

    def loader_for(long: bool, from_step: int):
        ds, b = (long_ds, long_batch) if long else (train_ds, args.batch)
        # Example i is the same on every run, so resuming carries on with the next ones.
        return iter(DataLoader(ds, batch_size=b, sampler=range(from_step * b, len(ds)), num_workers=args.workers,
                               pin_memory=device.type == "cuda", persistent_workers=False, drop_last=True))

    def save():
        tmp = ckpt_path.with_suffix(".tmp")
        torch.save({"step": step, "steps": args.steps, "net": net.state_dict(), "ema": ema.state_dict(), "opt": opt.state_dict(),
                    "args": {k: str(v) for k, v in vars(args).items()},
                    "layout": dataclasses.asdict(layout), "hidden": args.hidden, "layers": args.layers}, tmp)
        tmp.replace(ckpt_path)

    log = open(args.out / "metrics.jsonl", "a")
    running, seen, t0 = 0.0, 0, time.time()
    started, started_step = time.time(), step
    net.train()
    long_from = lambda: int(args.steps * (1 - args.long_share))  # noqa: E731
    long = step >= long_from()
    batches = loader_for(long, step)
    while step < args.steps:
        if not long and step >= long_from():
            long = True
            batches = loader_for(True, step)
            print(f"step {step}: switching to {args.long_crop_seconds:g} s crops, batch {long_batch}", flush=True)
        x, s = next(batches)
        for g in opt.param_groups:
            g["lr"] = lr_at(step, args)
        x, s = x.to(device, non_blocking=True), s.to(device, non_blocking=True)
        loss, _ = score(dsp, net, x, s, hop)
        loss = loss.mean()
        opt.zero_grad(set_to_none=True)
        loss.backward()
        grad = torch.nn.utils.clip_grad_norm_(net.parameters(), 5.0)
        opt.step()
        with torch.no_grad():
            for pe, p in zip(ema.parameters(), net.parameters()):
                pe.mul_(args.ema).add_(p.detach(), alpha=1 - args.ema)
        step += 1
        running += loss.item()
        seen += 1
        if args.hours and not args.resume and step == started_step + 20:
            started = time.time()  # past the loader's start-up and the GPU's first compiles
        if args.hours and not args.resume and step == started_step + 80:
            # Fit the run in the time given: short-crop steps at the speed just
            # measured, long-crop steps at ~1.75x that (555 vs 320 ms on the M1 Pro), 5 % kept for validation.
            per = (time.time() - started) / 60
            budget = args.hours * 3600 * 0.95
            args.steps = int(budget / (per * (1 - args.long_share) + per * 1.75 * args.long_share))
            print(f"{per * 1000:.0f} ms a step: {args.steps} steps fit in {args.hours:g} h", flush=True)

        if step % args.report_every == 0:
            row = {"step": step, "train_sdr": -running / seen, "lr": lr_at(step, args), "grad": float(grad),
                   "sec_per_step": (time.time() - t0) / seen}
            running, seen, t0 = 0.0, 0, time.time()
            if step % args.valid_every == 0:
                ema.eval()
                row.update(validate(dsp, ema, valid_loader, device, hop))
            print(json.dumps(row), flush=True)
            log.write(json.dumps(row) + "\n")
            log.flush()
        if step % args.save_every == 0:
            save()
    save()
    ema.eval()
    final = validate(dsp, ema, valid_loader, device, hop)
    final["step"] = step
    (args.out / "result.json").write_text(json.dumps(final, indent=1) + "\n")
    print("final", json.dumps(final))


if __name__ == "__main__":
    main()

"""Trains the own-stems file model (ownstems/model.py) on the clean data (train/STEMS.md).

    python train_stems.py --root ~/stems-clean --out runs/v0 --preset small --steps 40000
    python train_stems.py --root ~/stems-clean --out runs/v0 --resume          # after a stop, crash or reboot
    python train_stems.py --bench --preset small --batch 4 --seconds 4         # seconds per step, no data needed

Every example is a fresh made-up song from the mixer, built by --workers
processes while the network trains on --threads threads. Sized for CPUs: the
server trains at reduced load for days (start it with tools/stems-train.sh,
which pins it to half the cores at the lowest priority).

Writes into --out: checkpoint.pt (every --save-minutes, and on SIGTERM / Ctrl-C),
best.pt (the averaged weights with the best held-out mean SDR), metrics.jsonl
(one line per report and per validation), status.json (where it is now, for
a quick look), valid.pt (the held-out songs, built once so every validation
scores the same ones). Touch <out>/PAUSE to pause it; delete the file to go on.
"""

from __future__ import annotations

import os

os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")  # numpy/scipy in the workers: one thread each

import argparse
import copy
import dataclasses
import json
import math
import signal
import sys
import time
from pathlib import Path

import torch
from torch.utils.data import DataLoader

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ownstems.mixer import MixConfig  # noqa: E402
from ownstems.model import ModelConfig, StemsNet  # noqa: E402
from ownstems.ledger import Catalog, Ledger  # noqa: E402
from ownstems.training import MixStream, fixed_set, stems_loss, valid_signature, validate, worker_init  # noqa: E402

RATE = 44100


def lr_at(step: int, args) -> float:
    if step < args.warmup:
        return args.lr * (step + 1) / args.warmup
    t = min(1.0, (step - args.warmup) / max(1, args.steps - args.warmup))
    return args.min_lr + 0.5 * (args.lr - args.min_lr) * (1 + math.cos(math.pi * t))


def normalised(mix: torch.Tensor, stems: torch.Tensor):
    """Divide each example by its mix's level: the loss then weighs every song the same."""
    s = mix.reshape(mix.shape[0], -1).std(-1).clamp_min(1e-5)
    return mix / s.view(-1, 1, 1), stems / s.view(-1, 1, 1, 1)


def atomic_save(obj, path: Path):
    tmp = path.with_name(path.name + ".tmp")
    torch.save(obj, tmp)
    os.replace(tmp, path)


def bench(args):
    torch.set_flush_denormal(True)
    torch.set_num_threads(args.threads)
    cfg = ModelConfig.preset(args.preset)
    net = StemsNet(cfg)
    params = sum(p.numel() for p in net.parameters())
    opt = torch.optim.AdamW(net.parameters(), lr=1e-4)
    n = int(args.seconds * RATE)
    g = torch.Generator().manual_seed(0)
    mix_stems = torch.randn(args.batch, cfg.stems, 2, n, generator=g) * 0.1
    mix = mix_stems.sum(1)
    times = []
    for i in range(args.bench_steps + 2):
        t0 = time.perf_counter()
        m, s = normalised(mix, mix_stems)
        loss, _ = stems_loss(net(m), s)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step()
        if i >= 2:  # the first steps warm the allocator and kernels up
            times.append(time.perf_counter() - t0)
    per = sum(times) / len(times)
    print(json.dumps({"preset": args.preset, "params_m": round(params / 1e6, 2), "threads": args.threads,
                      "batch": args.batch, "seconds": args.seconds, "s_per_step": round(per, 2),
                      "audio_s_per_wall_s": round(args.batch * args.seconds / per, 2),
                      "steps_per_day": int(86400 / per)}))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, help="the clean-data root (sources/, ledger/, catalog/)")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--preset", default="small", choices=("tiny", "small", "base"))
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--steps", type=int, default=40000, help="length of the LR schedule; may change on --resume")
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--seconds", type=float, default=4.0, help="length of each training example")
    ap.add_argument("--lr", type=float, default=4e-4)
    ap.add_argument("--min-lr", type=float, default=1e-5)
    ap.add_argument("--warmup", type=int, default=1000)
    ap.add_argument("--weight-decay", type=float, default=0.01)
    ap.add_argument("--clip", type=float, default=5.0)
    ap.add_argument("--ema", type=float, default=0.999)
    ap.add_argument("--threads", type=int, default=8, help="torch threads for the network")
    ap.add_argument("--workers", type=int, default=3, help="processes building examples")
    ap.add_argument("--report-every", type=int, default=50)
    ap.add_argument("--valid-every", type=int, default=1000)
    ap.add_argument("--valid-examples", type=int, default=32)
    ap.add_argument("--valid-seconds", type=float, default=6.0)
    ap.add_argument("--save-minutes", type=float, default=20.0)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--vocal-weights", default="vocalset=6,ccmixter=10",
                    help="source weights. The default draws vocals about 45 %% from ccMixter's 24 h of real "
                         "produced a cappellas, 28 %% from VocalSet's 8 h of singing, 27 %% from LibriTTS-R's "
                         "48 h of speech (a clip counts its length, capped at a minute)")
    ap.add_argument("--bench", action="store_true")
    ap.add_argument("--bench-steps", type=int, default=5)
    args = ap.parse_args()
    if args.bench:
        return bench(args)
    if not (args.root and args.out):
        ap.error("--root and --out are needed to train")

    # Denormal floats (decaying synth tails) are slow on x86 and broke a gradient once: flush them.
    torch.set_flush_denormal(True)
    torch.set_num_threads(args.threads)
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    ckpt_path = out / "checkpoint.pt"
    ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False) if args.resume and ckpt_path.exists() else None
    if args.resume and ckpt is None:
        print("no checkpoint to resume; starting fresh", flush=True)

    cfg = ModelConfig(**ckpt["config"]) if ckpt else ModelConfig.preset(args.preset)
    net = StemsNet(cfg)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, betas=(0.9, 0.99), weight_decay=args.weight_decay)
    step, best = 0, -1e9
    if ckpt:
        net.load_state_dict(ckpt["net"])
        opt.load_state_dict(ckpt["opt"])
        step, best = ckpt["step"], ckpt.get("best", -1e9)
    ema = copy.deepcopy(net)
    if ckpt:
        ema.load_state_dict(ckpt["ema"])
    ema.requires_grad_(False)
    params = sum(p.numel() for p in net.parameters())
    print(f"model {dataclasses.asdict(cfg)}: {params / 1e6:.2f} M parameters, {len(net.edges)} bands; "
          f"step {step} of {args.steps}", flush=True)

    weights = {}
    for item in filter(None, args.vocal_weights.split(",")):
        k, v = item.split("=")
        weights[k.strip()] = float(v)
    mix_cfg = MixConfig(seconds=args.seconds, split="train", source_weights=weights)

    valid_cfg = MixConfig(seconds=args.valid_seconds, split="valid", source_weights=weights)
    valid_path = out / "valid.pt"
    valid = torch.load(valid_path, weights_only=False) if valid_path.exists() else None
    new_set = None
    if valid is not None:
        now = valid_signature(Catalog(args.root, Ledger(args.root)), valid_cfg)
        if valid.get("signature") != now:
            # New held-out data or new weights: the old set no longer tests what training sees.
            # Both it and the best weights chosen on it are kept, renamed; "best" starts over.
            os.replace(valid_path, out / f"valid-until-{step}.pt")
            if (out / "best.pt").exists():
                os.replace(out / "best.pt", out / f"best-until-{step}.pt")
            new_set = {"was": valid.get("signature"), "now": now}
            valid, best = None, -1e9
    if valid is None:
        t0 = time.time()
        valid = fixed_set(args.root, valid_cfg, args.valid_examples, seed=12345)
        atomic_save(valid, valid_path)
        print(f"held-out set: {len(valid['mix'])} songs ({int(valid['hardstyle'].sum())} hardstyle) "
              f"in {time.time() - t0:.0f} s, from {valid['signature']['clips']}", flush=True)

    # A new seed per start, so a resumed run hears new songs rather than the first ones again.
    stream = MixStream(args.root, mix_cfg, seed=args.seed * 1_000_003 + step)
    loader = iter(DataLoader(stream, batch_size=args.batch, num_workers=args.workers, worker_init_fn=worker_init,
                             prefetch_factor=4, persistent_workers=False))

    stop = {"now": False}

    def on_signal(signum, _):
        print(f"signal {signum}: saving and stopping", flush=True)
        stop["now"] = True

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    def save():
        atomic_save({"step": step, "steps": args.steps, "config": dataclasses.asdict(cfg), "net": net.state_dict(),
                     "ema": ema.state_dict(), "opt": opt.state_dict(), "best": best, "args": vars(args) | {
                         "root": str(args.root), "out": str(out)}}, ckpt_path)

    metrics = open(out / "metrics.jsonl", "a")

    def log(row: dict):
        metrics.write(json.dumps(row) + "\n")
        metrics.flush()
        print(json.dumps(row), flush=True)

    def run_valid():
        nonlocal best
        t0 = time.time()
        row = {"step": step, "valid": validate(ema, valid), "valid_seconds": round(time.time() - t0)}
        log(row)
        if row["valid"]["mean_sdr"] > best:
            best = row["valid"]["mean_sdr"]
            atomic_save({"step": step, "config": dataclasses.asdict(cfg), "ema": ema.state_dict(),
                         "valid": row["valid"], "seconds": args.seconds}, out / "best.pt")
        save()

    if new_set and step > 0:
        # A score on the new set now, so its curve starts where the training is, not 1000 steps on.
        log({"step": step, "event": "new held-out set", **new_set})
        run_valid()

    net.train()
    last_save = time.time()
    run_loss = run_l1 = run_spec = 0.0
    seen = 0
    t_data = t_step = 0.0
    t_report = time.time()
    skipped = saved_bad = 0
    while step < args.steps and not stop["now"]:
        if (out / "PAUSE").exists():
            print("paused (delete PAUSE to go on)", flush=True)
            save()
            while (out / "PAUSE").exists() and not stop["now"]:
                time.sleep(30)
            print("going on", flush=True)
            t_report = time.time()
            continue
        t0 = time.perf_counter()
        mix, stems = next(loader)
        t1 = time.perf_counter()
        for g in opt.param_groups:
            g["lr"] = lr_at(step, args)
        m, s = normalised(mix, stems)
        loss, parts = stems_loss(net(m), s)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        grad = torch.nn.utils.clip_grad_norm_(net.parameters(), args.clip)
        if torch.isfinite(grad) and torch.isfinite(loss):
            opt.step()
        else:  # skip it, and keep the batch with the weights that choked on it, to find out why
            skipped += 1
            if saved_bad < 5:
                atomic_save({"mix": mix, "stems": stems, "step": step, "loss": float(loss.detach()),
                             "grad": float(grad), "net": net.state_dict(), "config": dataclasses.asdict(cfg)},
                            out / f"nonfinite-{step}.pt")
                saved_bad += 1
        with torch.no_grad():
            for pe, p in zip(ema.parameters(), net.parameters()):
                pe.mul_(args.ema).add_(p.detach(), alpha=1 - args.ema)
        step += 1
        t_data += t1 - t0
        t_step += time.perf_counter() - t1
        run_loss += float(loss.detach())
        run_l1 += parts["l1"]
        run_spec += parts["stft"]
        seen += 1

        if step % args.report_every == 0:
            wall = time.time() - t_report
            row = {"step": step, "loss": round(run_loss / seen, 5), "l1": round(run_l1 / seen, 5),
                   "stft": round(run_spec / seen, 5), "lr": float(f"{lr_at(step, args):.3g}"),
                   "grad": round(float(grad), 3), "skipped": skipped, "s_per_step": round(wall / seen, 2),
                   "data_wait": round(t_data / seen, 2), "net": round(t_step / seen, 2),
                   "time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
            log(row)
            per = wall / seen
            (out / "status.json").write_text(json.dumps({
                **row, "steps": args.steps, "best_mean_sdr": best,
                "eta_hours": round((args.steps - step) * per / 3600, 1)}, indent=1) + "\n")
            run_loss = run_l1 = run_spec = 0.0
            seen = 0
            t_data = t_step = 0.0
            t_report = time.time()

        if step % args.valid_every == 0 or step == args.steps:
            t0 = time.time()
            run_valid()
            last_save = time.time()
            t_report += time.time() - t0  # validation time is not training speed

        if time.time() - last_save > args.save_minutes * 60:
            save()
            last_save = time.time()

    save()
    print(f"stopped at step {step}" + (" (done)" if step >= args.steps else ""), flush=True)
    metrics.close()
    # Workers are daemonic; exiting the interpreter takes them down.
    sys.exit(0 if step >= args.steps or stop["now"] else 1)


if __name__ == "__main__":
    main()

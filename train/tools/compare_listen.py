"""Pairs the own-stems model's lanes with what the app's Stems page makes of the same songs, to listen.

    python tools/compare_listen.py --ours D:/stems-clean/listen-v0 --app D:/synthsplit/hardstyle-renders \\
        --splitter hs-last --out D:/stems-clean/listen-v0-compare

--ours is tools/separate.py's output: <song>/{mix,vocals,synth,other,bass,drums}.wav.
--app is tools/render_many.py's output (feature/synth-stem): StemgenRT's stem_*.wav plus one
splitter's synth_<name>.wav and {vocals,other}_without_synth_<name>.wav. The app's five lanes
are StemgenRT's drums and bass, the splitter's synth, and vocals and other with the synth taken
out; `hs-last` is the splitter the app ships (fourth-hardstyle, last checkpoint).

Writes <out>/<song>/{mix,app_<lane>,v0_<lane>}.mp3 and <out>/index.html: per song, pick a lane
and flip between the two at the same moment. Lanes are mono on both sides (the app's are), and
each song gets one gain for all its files so neither side is louder by processing. The songs are
only listened to: nothing here feeds training.
"""

from __future__ import annotations

import argparse
import html
import json
import re
from pathlib import Path

import numpy as np
import soundfile as sf

LANES = ("vocals", "synth", "other", "bass", "drums")
RATE = 44100


def safe(name: str) -> str:
    """tools/render_many.py's folder name for a song."""
    return re.sub(r"[^\w\-.]+", "_", name).strip("_")[:80]


def app_files(d: Path, splitter: str) -> dict[str, Path]:
    return {"vocals": d / f"vocals_without_synth_{splitter}.wav", "synth": d / f"synth_{splitter}.wav",
            "other": d / f"other_without_synth_{splitter}.wav", "bass": d / "stem_bass.wav",
            "drums": d / "stem_drums.wav"}


def mono(path: Path) -> np.ndarray:
    x, rate = sf.read(path, dtype="float32", always_2d=True)
    if rate != RATE:
        raise SystemExit(f"{path}: {rate} Hz, expected {RATE}")
    return x.mean(axis=1)


def write(path: Path, x: np.ndarray, fmt: str) -> None:
    if fmt == "mp3":
        sf.write(path, x, RATE, format="MP3", subtype="MPEG_LAYER_III")
    else:
        sf.write(path, x, RATE, format="FLAC", subtype="PCM_16")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ours", required=True, type=Path)
    ap.add_argument("--app", required=True, type=Path)
    ap.add_argument("--splitter", default="hs-last")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--label", default="v0", help="what to call our model on the page")
    args = ap.parse_args()
    fmt = "mp3" if "MP3" in sf.available_formats() else "flac"
    args.out.mkdir(parents=True, exist_ok=True)
    songs = []
    for d in sorted(p for p in args.ours.iterdir() if (p / "mix.wav").exists()):
        app_dir = args.app / safe(d.name)
        app = app_files(app_dir, args.splitter)
        missing = [str(p) for p in app.values() if not p.exists()]
        if missing:
            print(f"skip {d.name}: no app render ({missing[0]})")
            continue
        mix, _ = sf.read(d / "mix.wav", dtype="float32", always_2d=True)
        ours = {lane: mono(d / f"{lane}.wav") for lane in LANES}
        theirs = {lane: mono(p) for lane, p in app.items()}
        n = min(len(mix), *(len(x) for x in ours.values()), *(len(x) for x in theirs.values()))
        peak = max(float(np.abs(mix[:n]).max()), *(float(np.abs(x[:n]).max()) for x in (*ours.values(),
                                                                                        *theirs.values())))
        gain = min(1.0, 0.98 / max(peak, 1e-9))
        o = args.out / safe(d.name)
        o.mkdir(exist_ok=True)
        write(o / f"mix.{fmt}", mix[:n] * gain, fmt)
        for lane in LANES:
            write(o / f"app_{lane}.{fmt}", theirs[lane][:n] * gain, fmt)
            write(o / f"v0_{lane}.{fmt}", ours[lane][:n] * gain, fmt)
        songs.append({"title": d.name, "dir": o.name, "seconds": round(n / RATE, 1)})
        print(f"{d.name}: {n / RATE:.0f} s, gain {20 * np.log10(gain):.1f} dB", flush=True)
    (args.out / "index.html").write_text(page(songs, fmt, args.label, args.splitter), encoding="utf-8")
    print(f"{len(songs)} songs -> {args.out / 'index.html'}")


def page(songs: list[dict], fmt: str, label: str, splitter: str) -> str:
    data = json.dumps({"songs": songs, "ext": fmt, "lanes": list(LANES)})
    return TEMPLATE.replace("__DATA__", data).replace("__LABEL__", html.escape(label)).replace(
        "__SPLITTER__", html.escape(splitter))


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Stems listening test</title>
<style>
:root { --bg: #f4f5f8; --card: #fff; --ink: #22252c; --dim: #5d6372; --line: #dfe2ea; --accent: #6b4fd8;
        --accent-ink: #fff; --b: #1f8a70; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #17191e; --card: #22252c; --ink: #e7eaf1; --dim: #a3a9b8; --line: #343844; --accent: #8b74ec;
          --accent-ink: #fff; --b: #3cc39d; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); font: 15px/1.45 system-ui, -apple-system, sans-serif; }
main { max-width: 880px; margin: 0 auto; padding: 24px 16px 64px; }
h1 { font-size: 22px; margin: 0 0 4px; }
.lede { color: var(--dim); margin: 0 0 20px; }
kbd { border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; font-size: 12px; }
.song { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 14px 16px; margin: 0 0 12px; }
.song.on { border-color: var(--accent); }
.head { display: flex; align-items: center; gap: 12px; }
.play { width: 38px; height: 38px; border-radius: 50%; border: 0; background: var(--accent); color: var(--accent-ink);
        font-size: 15px; cursor: pointer; flex: none; }
.title { font-weight: 600; overflow-wrap: anywhere; }
.time { color: var(--dim); font-variant-numeric: tabular-nums; margin-left: auto; flex: none; font-size: 13px; }
.row { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.row button { border: 1px solid var(--line); background: transparent; color: var(--ink); border-radius: 8px;
              padding: 5px 11px; cursor: pointer; font: inherit; font-size: 14px; }
.row button.sel { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
.ver button.sel.b { background: var(--b); border-color: var(--b); }
.ver { margin-left: auto; }
input[type=range] { width: 100%; margin: 10px 0 0; accent-color: var(--accent); }
</style>
</head>
<body>
<main>
<h1>Stems: the app today vs __LABEL__</h1>
<p class="lede">Each lane, flipped at the same moment. <b>App today</b> = StemgenRT + the shipped synth splitter
(__SPLITTER__). <b>__LABEL__</b> = the own clean-data model. Mono, same gain on both sides of a song.
Keys: <kbd>space</kbd> play/pause, <kbd>1</kbd>&ndash;<kbd>6</kbd> mix and lanes, <kbd>x</kbd> flip app / __LABEL__,
<kbd>&larr;</kbd><kbd>&rarr;</kbd> 5 s.</p>
<div id="songs"></div>
</main>
<script>
const D = __DATA__;
const LANE_NAMES = { mix: "Mix", vocals: "Vocals", synth: "Synth", other: "Other", bass: "Bass", drums: "Drums" };
const els = {};          // "song|lane|ver" -> <audio>
let cur = null;          // { s, lane, ver, card }
const fmt = t => `${Math.floor(t / 60)}:${String(Math.floor(t % 60)).padStart(2, "0")}`;

function src(s, lane, ver) { return `${encodeURIComponent(s.dir)}/${lane === "mix" ? "mix" : `${ver}_${lane}`}.${D.ext}`; }
function audio(s, lane, ver) {
  const k = `${s.dir}|${lane}|${lane === "mix" ? "-" : ver}`;
  if (!els[k]) { const a = new Audio(); a.preload = "auto"; a.src = src(s, lane, ver); els[k] = a; }
  return els[k];
}
function active() { return cur ? audio(cur.s, cur.lane, cur.ver) : null; }

function select(card, s, lane, ver) {
  const old = active();
  const t = old ? old.currentTime : 0;
  const playing = old && !old.paused;
  if (cur && cur.card !== card) { old && old.pause(); cur.card.classList.remove("on"); }
  cur = { s, lane, ver, card };
  card.classList.add("on");
  const a = active();
  if (a !== old) {
    if (old && old.dataset.song === s.dir) a.currentTime = t;
    a.dataset.song = s.dir;
    if (playing && old.dataset.song === s.dir) { a.play(); old.pause(); }
  }
  // warm the other version of this lane, so the flip is instant
  if (lane !== "mix") audio(s, lane, ver === "app" ? "v0" : "app");
  paint();
}

function paint() {
  document.querySelectorAll(".song").forEach(card => {
    const me = cur && cur.card === card;
    card.querySelectorAll("[data-lane]").forEach(b => b.classList.toggle("sel", me && b.dataset.lane === cur.lane));
    card.querySelectorAll("[data-ver]").forEach(b => b.classList.toggle("sel", !!(me ? b.dataset.ver === cur.ver : b.dataset.ver === "app")));
    const a = me ? active() : null;
    card.querySelector(".play").textContent = a && !a.paused ? "\\u275a\\u275a" : "\\u25b6";
  });
}

function toggle() { const a = active(); if (!a) return; a.paused ? a.play() : a.pause(); }

D.songs.forEach(s => {
  const card = document.createElement("section");
  card.className = "song";
  card.innerHTML = `<div class="head"><button class="play" aria-label="Play or pause">\\u25b6</button>
    <div class="title"></div><div class="time">0:00 / ${fmt(s.seconds)}</div></div>
    <div class="row"><span class="lanes"></span><span class="ver row" style="margin-top:0">
      <button data-ver="app">App today</button><button data-ver="v0" class="b">__LABEL__</button></span></div>
    <input type="range" min="0" max="${s.seconds}" step="0.1" value="0" aria-label="Position">`;
  card.querySelector(".title").textContent = s.title;
  const lanes = card.querySelector(".lanes");
  lanes.className = "row"; lanes.style.marginTop = "0";
  ["mix", ...D.lanes].forEach(l => {
    const b = document.createElement("button"); b.dataset.lane = l; b.textContent = LANE_NAMES[l];
    b.onclick = () => select(card, s, l, cur && cur.card === card ? cur.ver : "app"); lanes.appendChild(b);
  });
  card.querySelectorAll("[data-ver]").forEach(b => b.onclick = () =>
    select(card, s, cur && cur.card === card ? cur.lane : "mix", b.dataset.ver));
  card.querySelector(".play").onclick = () => { if (!cur || cur.card !== card) select(card, s, "mix", "app"); toggle(); paint(); };
  card.querySelector("input").oninput = e => { if (!cur || cur.card !== card) select(card, s, "mix", "app"); active().currentTime = +e.target.value; };
  document.getElementById("songs").appendChild(card);
});

paint();
setInterval(() => {
  const a = active(); if (!a) return;
  cur.card.querySelector(".time").textContent = `${fmt(a.currentTime)} / ${fmt(cur.s.seconds)}`;
  const r = cur.card.querySelector("input"); if (document.activeElement !== r) r.value = a.currentTime;
  paint();
}, 250);

document.addEventListener("keydown", e => {
  if (!cur || e.metaKey || e.ctrlKey || e.altKey) return;
  const lanes = ["mix", ...D.lanes];
  if (e.key === " ") { e.preventDefault(); toggle(); }
  else if (e.key >= "1" && e.key <= "6") select(cur.card, cur.s, lanes[+e.key - 1], cur.ver);
  else if (e.key === "x") select(cur.card, cur.s, cur.lane, cur.ver === "app" ? "v0" : "app");
  else if (e.key === "ArrowLeft") active().currentTime -= 5;
  else if (e.key === "ArrowRight") active().currentTime += 5;
  else return;
  paint();
});
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()

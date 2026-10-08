# Changelog

Every release of Spectra, newest first. Versions are MAJOR.MINOR.PATCH — MAJOR
when something people rely on changes or goes away, MINOR for something new,
PATCH for a fix — and each is a git tag (`vX.Y.Z`) on the commit that
shipped. `tools/release` in Claudes Projects writes these entries.

## 1.5.0 — 2026-10-08

Synth lane on the Stems page: synth leads and pads in a lane of their own, tuned for hardstyle and electronic music

- Test the synth model the app ships: loads, carries state, masks are gains
- Ship the hardstyle-tuned synth model: fourth-hardstyle, last checkpoint
- Contract fixture with a conv front; model provenance credits MUSDB18-HQ and the made-up data
- Made-up hardstyle songs: tuned distorted kicks, reverse bass, breakdowns, ducked leads
- tools/render_many.py: a folder of songs through the stem model once and several splitters
- Loader: keep at most 96 songs memory-mapped per worker; remix vocals from the drawn corpora only
- Optional conv front for the splitter: the same filters across every band
- Apply the random EQ on the GPU, a batch at once: it was most of the loader's time
- train_after: start training at normal priority so its loader beats data prep to the CPU
- Hold out 23 MoisesDB songs, not 9: the artist split was lumpy
- Fit the corpus in a night: real music first, MoisesDB windows, fewer made-up songs
- Slakh and MoisesDB prep: hold a song's kept stretch, not every stem of it
- train_after: a failed step or run lets the queue move on instead of waiting forever
- Queue training behind any prep step; MoisesDB prep waits for its zip and takes its own worker count
- MoisesDB straight from moisesdb.zip: no 149 GB unpack, and the real folder layout
- fetch_parallel: take a signed link from a file (@file), off the command line
- fetch_parallel: carry on after an expired link (--resume), size from the server, zip CRC check
- train_after: wait on whole log lines, not substrings its own log line contains
- End train.py with TerminateProcess on Windows: os._exit still ran the crashing DLL hooks
- Windows: UTF-8 logs between the pipeline's scripts, and leave train.py without teardown
- Close the training loader before exit: Windows crashed tearing it down after CUDA
- Chain a training run after the corpus: train, score, export, render
- Cut the no-synth examples from stretches where the synth is really silent
- Keep OpenBLAS to one thread in every worker: Windows ran out of commit
- Document the desktop run: paths, the Windows traps, and what run 2 taught
- Make up new synth patches for the training corpus, and score silent crops apart
- Fetch Slakh and MUSDB on Windows: unpack in Python, download straight to a file
- Fix the synth labels and the overfitting the first training run showed
- Make the synth splitter's training run on the Mac overnight
- Add the training pipeline for the synth splitter
- Add a synth lane to the Stems page, split out of vocals and other
- Check the mix-format fallback on real Windows too, and document system audio
- Let the Windows build capture what is playing, not just the microphone

## 1.4.2 — 2026-10-06

Same look as the n3d sites: flat violet buttons and slider fills, drawn line icons, buttons that press in without the jelly bounce

- Match the sites' redesign: flat violet fills, line icons, no jelly press

## 1.4.1 — 2026-10-05

Settings scrolls smoothly again: it was redrawing ~150 MB of shadows every frame; back now returns from Settings

- Make the back gesture close Settings instead of the app
- Stop the settings page re-uploading its shadows on every frame

## 1.4.0 — 2026-09-29

Oscilloscope page: X-Y for oscilloscope music and a triggered Y-T sweep, drawn like an analog tube

- Add an Oscilloscope page that draws the capture like an analog tube

## 1.3.2 — 2026-09-28

Uses less power while open: the notification graph pauses while the app is in front

- Stop redrawing the notification and widget while the app is in front

## 1.3.1 — 2026-09-28

Stems keep up better: the model stays on the fastest core, and a skip no longer wipes its memory

- Pin the stem worker to the phone's fastest core
- Keep the stem model's memory across a skip, and allocate its tensors once

## 1.3.0 — 2026-09-28

Choose which Stems lanes hold still; only bass is held by default

- Let each Stems lane be held still or scroll; only bass by default

## 1.2.1 — 2026-09-23

Sliders no longer change when a scroll passes over them; double-tap one to reset it.

- Stop sliders changing when a scroll passes over them

## 1.2.0 — 2026-09-23

Stems page: vocals, other, bass and drums separated live on the phone, each held still like a chiptune channel scope. Waveform page: Hold still and NES 2A03 triangle modes.

- Add a live Stems page and held-still scopes to the phone
- Add the MIT license ahead of going public
- Add a changelog; versions start at v1.1.0

## 1.1.0 — 2026-09-19 · versionCode 2

As published on n3d-store.com/apps (the app shows it as "1.1"). Settings can
now check n3d-store.com for a new version and install it itself. That is the
only reason this version asks for internet access; no audio and nothing
measured from it ever leaves the phone.

Versions start here; everything earlier is in `git log`.

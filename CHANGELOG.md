# Changelog

Every release of Spectra, newest first. Versions are MAJOR.MINOR.PATCH — MAJOR
when something people rely on changes or goes away, MINOR for something new,
PATCH for a fix — and each is a git tag (`vX.Y.Z`) on the commit that
shipped. `tools/release` in Claudes Projects writes these entries.

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

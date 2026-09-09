# Spectra

A real-time audio analyser, in the Nebula 3D neumorphic look. Two products, one
analysis engine:

- **`app/`** — Android. Kotlin + Jetpack Compose, no native code, no third-party
  libraries beyond AndroidX. Built for the Galaxy S24 Ultra but nothing in it is
  device-specific.
- **`desktop/`** — Windows. Kotlin + Swing/Java2D, one dependency (the Kotlin
  stdlib), shipped as a self-contained `.exe`. It carries one feature the phone
  build does not: [a waveform mode for the NES 2A03's triangle
  channel](#the-nes-2a03-triangle-mode-windows-only).

The desktop module compiles the whole `com.n3d.spectra.dsp` package, `Settings`
and `Palette` straight out of `app/src/main/java` rather than copying them, so
the two can never disagree about what a dB, an LUFS or an accent colour is.

---

## Build and install

This project was written on a Mac with **no Android SDK**, so it has never been
compiled. Expect to fix a stray import or two on the first build; the logic is
what took the time, not the ceremony.

1. Unzip anywhere, then in Android Studio: **File → Open** and pick the
   `Spectra` folder (the one with `settings.gradle.kts`).
2. Let it sync. It targets **AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21**,
   `compileSdk 35`, `minSdk 29`, JDK 17.
3. Plug in the phone with USB debugging on, pick it in the device dropdown, hit
   **Run**.

From the command line instead:

```bash
./gradlew installDebug
```

`minSdk` is 29 because that is the floor for `AudioPlaybackCapture`.

### Permissions it will ask for

| Permission | When | Why |
|---|---|---|
| Microphone | First **Start** | Required for *both* sources — the permission gates the capture API, not the hardware |
| Notifications | First launch | Without it the foreground-service notification is invisible, which is the entire lock-screen feature |
| Screen capture consent | Each time device audio starts | How `MediaProjection` works. Nothing is written to disk |
| Display over other apps | Only if you enable the overlay | Settings screen, not a dialog |

---

## What it measures

Everything runs off one capture thread and one DSP pass, shared by every
surface.

**Spectrum** — windowed STFT, 512 to 16384 points, five windows (Hann, Hamming,
Blackman–Harris 4, flat-top, rectangular), 50/75/87.5 % overlap. Normalised so a
full-scale sine on a bin reads exactly 0 dBFS with any window. Log or linear
axis, adjustable range and floor, A/C/Z weighting, pink-noise slope tilt,
separate attack/release ballistics and peak hold with its own hold time and fall
rate.

**Bands** — seven independent meters (sub / bass / low-mid / mid / high-mid /
presence / air), every crossover editable, 12/24/48 dB/oct. These are built from
**real cascaded Butterworth band-pass filters in the time domain, not from
summing FFT bins**. Summing bins is cheaper and is what most phone analysers do,
but it inherits the transform's time resolution — at 4096 points a band reading
is smeared over 85 ms, which is longer than the entire attack of an 808. Filters
give sample-rate envelope resolution, so the sub meter actually snaps.

**Spectrogram** — scrolling waterfall, five colour maps, adjustable dB range.
Rows that span several bins take the maximum, not the mean: a narrow resonance
that averages away is exactly the thing you were looking for.

**Loudness** — ITU-R BS.1770-4. K-weighting re-derived for the actual sample
rate (not the published 48 kHz coefficients), 100 ms energy grains, momentary
(400 ms), short-term (3 s), gated integrated loudness, LRA, and true peak by
polyphase oversampling. Integrated loudness and LRA accumulate into histograms
rather than a growing block list, so it can run for hours without its memory
creeping.

**Stereo** — goniometer with adjustable persistence, phase correlation from
exponentially-decaying sums (so it settles instead of flickering), and stereo
width.

**Waveform** — decimated dual-channel scope.

---

## Where it runs

Four surfaces, one painter. The in-app UI, the notification, the overlay and the
widget all call the same `VizPainter` on a plain `Canvas` — reimplementing the
graphs per surface would have guaranteed they drifted apart.

### In the app
Full rate, capped in settings at 30/60/90/120 fps.

### Lock screen and notification shade
An ongoing foreground-service notification with a custom `RemoteViews` layout
carrying a rendered bitmap, plus real buttons for pause / cycle view / stop.

**Realistic rate: about 10 fps.** Notification updates are rate-limited by the
platform — push faster than roughly 10–12 per second and they are dropped, not
queued — and every frame is a bitmap crossing a process boundary. The bitmap is
drawn at full colour depth and copied down to RGB_565 with dithering before it
ships, which halves the bandwidth at the cost of a stipple you will not see at
52 dp tall.

For the notification to appear on the lock screen at all, the system setting for
showing notification content on the lock screen has to be on; the channel and the
notification both request `VISIBILITY_PUBLIC`.

### Floating overlay
A real `View` in this process, so it genuinely runs at 60 fps. Drag to move,
drag the bottom-right corner to resize, double-tap to collapse to a bar.

**It will not appear on the lock screen.** Android hides `TYPE_APPLICATION_OVERLAY`
windows on the keyguard, and there is no window type that gives both. The
overlay and the notification are two separate features on purpose.

### Home screen widget
Pushed frame by frame from the service, 1–4 fps. At that rate a level indicator
still reads correctly where a spectrum would just look broken, so it defaults to
the band meters.

### One UI 8 "Now Bar"
Not available. The Now Bar is built on Android 16 Live Updates, which is a
*progress*-style notification API — it can show a bar and a chip, not an
arbitrary spectrum. There is no third-party path to an Apple-Music-style live
chip with custom drawing.

---

## The device-audio limitation, in plain terms

`AudioPlaybackCapture` only receives audio from apps that permit it. Spotify,
YouTube, Netflix and essentially everything DRM-backed set
`allowAudioPlaybackCapture="false"` and are silently excluded from the mix. There
is no error and no callback — **the stream just contains digital silence**.

Only `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN` can be captured at all;
notification and call audio are excluded by the platform regardless of app
policy.

You chose explicit reporting over an automatic fallback, so after ~2.5 seconds of
digital silence the app says exactly that in a banner and leaves the source
alone. Switch to **Microphone** to measure a blocked app through the speaker.

Local players — Samsung Music, Poweramp, VLC, most browsers — generally work.

---

## Layout

```
app/src/main/java/com/n3d/spectra/
  MainActivity.kt          permissions, projection consent, Compose host
  SpectraApp.kt            notification channel
  audio/
    AudioEngine.kt         the one capture + DSP thread, StateFlow out
    AudioCapture.kt        source interface
    RecordCapture.kt       float-first format negotiation, 16-bit fallback
    MicCapture.kt          UNPROCESSED probing, AGC/NS/AEC explicitly disabled
    PlaybackCapture.kt     MediaProjection audio
    MonoRing.kt            capture chunks → overlapping FFT windows
  dsp/
    Fft.kt                 iterative radix-2, precomputed twiddles
    WindowFunction.kt      windows + coherent gain and ENBW, computed not tabled
    Biquad.kt              TDF-II, RBJ cookbook, K-weighting derivation
    SpectrumAnalyzer.kt    STFT, weighting, tilt, ballistics, spectrogram columns
    BandSplitter.kt        time-domain band-pass meters
    LoudnessMeter.kt       BS.1770-4 + polyphase true peak
    StereoAnalyzer.kt      correlation, width, goniometer cloud
    Ballistics.kt          attack/release, peak hold
    Histories.kt           lock-free ring buffers shared with the painters
    AnalysisFrame.kt       one immutable snapshot per hop
  paint/
    Palette.kt             the n3d design tokens as ints + colour maps
    Neu.kt                 neumorphic shadows on a raw Canvas
    VizPainter.kt          every graph, once
  service/
    AnalyzerService.kt     owns capture, notification, overlay, widget pushes
    NotificationRenderer.kt
    NotificationActionReceiver.kt
    OverlayController.kt
  widget/
  settings/                Settings data class + DataStore
  ui/                      Compose theme, neumorphic controls, screens
```

```
desktop/src/main/kotlin/com/n3d/spectra/desktop/
  Main.kt                  entry point
  audio/
    LineCapture.kt         javax.sound.sampled, format negotiation, device list
    SyntheticCapture.kt    a 2A03 rendered in software, as an input
    DesktopEngine.kt       the analysis loop — a port of AudioEngine
  nes/
    Nes2A03.kt             the chip as arithmetic: sequence, timers, clocks
    TriangleTracker.kt     period detection, timer quantisation, phase lock, fold
    PitchPreFilter.kt      the low-pass that keeps the melody out of the lock
  paint/
    G2.kt                  geometry and Graphics2D helpers
    Neu2D.kt               neumorphic shadows, blurred and cached
    VizPainter2D.kt        every graph, ported from VizPainter, plus the 2A03 page
  state/                   settings model + a properties file
  ui/                      the window, the custom-painted controls
  dev/                     harnesses: checks, headless renders, the icon
```

### Design system

Ported one-for-one from `public/css/style.css` on n3d-store.com: same neutrals
(`#22252c` / `#e7eaf1`), same shadow geometry (depth 4/7/12, blur twice the
depth, light fixed at the top-left), same radii, same easing curves including
the jelly overshoot on button release. Light and dark both follow the site.

Dark neumorphism sits on a mid grey rather than near-black for the reason the
site's stylesheet gives: with no headroom below the surface there is nothing for
the dark shadow to be darker than, and every control flattens out.

Android has no `box-shadow`, so each shadow is a blurred round-rect drawn behind
the shape (raised) or clipped inside it (inset). `BlurMaskFilter` is far too
expensive to run per frame, so every neumorphic frame is rendered to a bitmap
once per size change and blitted after that — in Compose via `drawWithCache`, in
the overlay via an explicit cache.

---

## Spectra for Windows

`desktop/` builds a self-contained Windows application: `Spectra.exe`, a jlinked
Java runtime and one jar. 49 MB unpacked, nothing to install on the target
machine.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
desktop/package-windows.sh
```

It cross-builds **from a Mac**, which needs three tricks and the script explains
each where it uses it:

- `jlink` will link a runtime image for another platform if it is given that
  platform's `jmods` and the versions match, so the script fetches a Temurin
  Windows JDK and links against it.
- `jpackage` will *not* cross-build — but the Windows `jpackage.exe` runs under
  Wine, and it is the only thing that stamps the icon and version resources into
  the launcher correctly. `rcedit` under Wine does not: Wine does not implement
  growing a PE resource section, and it fails silently and successfully.
- The `.ico` is rendered from `app/src/main/res/drawable/ic_launcher_*.xml` by
  `:desktop:appIcon`, so the Windows icon *is* the Android launcher icon rather
  than a redrawing of it. VectorDrawable `pathData` is SVG path syntax; the
  parser handles the subset the icon uses and throws on anything else.

To run it on the Mac during development: `./gradlew :desktop:runApp`.

Checks, none of which need a display:

| Task | What it does |
|---|---|
| `:desktop:trackerCheck` | Synthesises 2A03 audio and asserts the tracker's behaviour, including stationarity and the limits it cannot beat |
| `:desktop:uiShots` | Renders every page of the real window to PNGs |
| `:desktop:appIcon` | Rasterises the launcher icon into PNGs and an `.ico` |

`trackerCheck` also runs against the cross-built Windows runtime under Wine,
which is how the Windows build gets verified before it is on a Windows machine:

```bash
wine desktop/build/windows/image/Spectra/runtime/bin/java.exe \
  -cp desktop/build/libs/spectra-dev.jar \
  com.n3d.spectra.desktop.dev.TrackerCheckKt
```

### The NES 2A03 triangle mode (Windows only)

Waveform page → Mode → **NES 2A03 triangle**.

The NES triangle channel is not a triangle wave: it is a 4-bit DAC walking a
fixed 32-step sequence clocked straight off the CPU, so its output is a staircase
with sixteen levels and a flat top and bottom. This mode shows that staircase
rather than smoothing it away, and holds it still.

Three problems, solved in this order (`TriangleTracker`):

1. **What is the period?** An FFT-based normalised square difference (McLeod)
   over a copy of the input low-passed to just above the hunt range. The filter
   is what stops the pulse channels winning — two pulses a fourth apart share a
   common subharmonic, and the detector will find it, confidently and wrongly.
2. **What period would the chip have used?** The measurement is rounded to the
   nearest 11-bit timer value and the *exact* frequency of that timer drives the
   display. This is the single thing that makes the picture stand still: a
   measured period wanders by a fraction of a sample per frame and the wave
   crawls; a timer value is a constant while a note is held.
3. **Where does a period start?** The argument of one DFT bin at the fundamental,
   then a search against the ideal wave for the playback chain's phase shift and
   a polarity flip — and then a second correction taken from the *folded*
   period's own fundamental. That last step matters more than it sounds: one
   degree of error at the fundamental is forty degrees at the 31st harmonic,
   which is exactly where the staircase's corners live. Aligning on the
   fundamental alone scores 0.87 against the chip's wave; correcting from the
   fold scores 0.996, with every harmonic within a hundredth of a degree.

Whole periods are then folded together, which is a comb filter with teeth on the
note's harmonics: it cancels the pulse channels, noise and DMC while leaving the
staircase intact, because the staircase repeats exactly. Depth is capped at the
periods elapsed since the timer changed, so a moving bass line does not average
two notes into one picture.

**What it will not do, by construction:**

- A melody note that is an exact harmonic of the bass repeats at the bass period,
  so no period-domain method separates them. The display shows the sum, and says
  so.
- The staircase is a bass phenomenon. Each DAC step lasts `timer + 1` CPU cycles,
  so at 55 Hz a step covers 27 captured samples and every corner survives, while
  at 440 Hz it covers three and the whole thing arrives as a smooth triangle. The
  readout says how many samples a step is worth, and warns when it is too few.
- "It sits on the 2A03 grid" is not evidence in the bass: at 55 Hz consecutive
  timers are 1.7 cents apart, so any frequency lands on some timer. The grid step
  is reported next to the error so the claim cannot imply more than it has. The
  evidence that something is a 2A03 is the *shape* — the correlation with the
  chip's wave after the fundamental is removed, which a plain sine fails (a sine
  correlates 0.991 with a triangle before that subtraction, and −0.32 after).

---

## Known limits

- **`:app:lintDebug` fails on a pre-existing `MissingPermission` error** in
  `PlaybackCapture.kt`. It predates the desktop work; `assembleDebug` is clean.
- The Windows build cannot capture system audio on its own. The JDK has no
  WASAPI loopback, so it can only open the recording endpoints the driver
  exposes — "Stereo Mix" or a virtual cable. A small JNI DLL around `IAudioClient`
  would fix it and is not in this build; the app says so rather than sitting on a
  silent input.
- The Windows `.exe` is not code-signed, so SmartScreen warns on first run.
- **Never compiled on Android.** No Android SDK on the machine it was written on.
- Notification graphs are ~10 fps and cannot be faster. Platform limit.
- The overlay cannot appear on the lock screen. Platform limit.
- No Now Bar / Live Update integration. Platform limit.
- Apps that opt out of playback capture cannot be captured. Platform limit, and
  not one worth trying to route around.
- The spectrum is the mono sum of L+R. Per-channel spectra would double the
  transform cost to show two nearly identical curves.
- Changing the spectrogram dB range clears its history — columns are stored
  already mapped into that range.
- Band histories, the spectrogram and the loudness trace are shared ring buffers
  read without a lock. Worst case is one torn column in one frame; a lock there
  would put the audio thread at the mercy of a painter's scheduling.

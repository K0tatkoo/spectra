# Spectra

A real-time audio analyser for Android, in the Nebula 3D neumorphic look.

Kotlin + Jetpack Compose, no native code, no third-party libraries beyond
AndroidX. Built for the Galaxy S24 Ultra but nothing in it is device-specific.

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

## Known limits

- **Never compiled.** No Android SDK on the machine it was written on.
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

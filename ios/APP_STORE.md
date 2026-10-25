# Spectra on the App Store

What Apple asks for, what is already in the app, and what is still to do
before the first submission. Written 2026-10-24 against App Review
Guidelines as they stood then — re-read the guidelines before submitting.

## Already in the app

| Requirement | Where |
|---|---|
| Privacy policy reachable in the app (5.1.1) | Settings › About › Privacy policy, and the last page of the welcome tour |
| Support link | Settings › About › Support |
| Privacy manifest | `Spectra/PrivacyInfo.xcprivacy` (app) and `SpectraBroadcast/PrivacyInfo.xcprivacy` (extension): no tracking, no data collected; required-reason APIs declared — UserDefaults `CA92.1`, file timestamps `C617.1` (ONNX Runtime stats the model file, so the binary imports `stat`/`fstat`), system boot time `35F9.1` |
| Purpose string for every permission | `NSMicrophoneUsageDescription` in `project.yml` |
| Export compliance | `ITSAppUsesNonExemptEncryption = NO` — Spectra uses no encryption at all, so App Store Connect does not ask |
| Launch screen, icons | `UILaunchScreen`; AppIcon 1024 px light/dark/tinted, opaque |
| No permission primer that pushes "Allow" (5.1.1(iv)) | the tour only explains; its last button says Get Started and the microphone prompt is iOS's own, after the tour |

Check the manifest against the real binary after any new dependency:
`nm -u Spectra.app/Spectra | grep -E '^_(f?stat|lstat|getattrlist|mach_absolute_time|statv?fs)$'`
— every category that shows up must be declared.

## App Store Connect answers

- **Privacy Policy URL:** https://n3d-store.com/spectra-privacy
- **Support URL:** https://n3d-store.com/app-support
- **App Privacy ("nutrition label"):** *Data Not Collected.* Nothing leaves the
  device; Spectra makes no network connections.
- **Age rating:** every question "None" / "No" → 4+.
- **Category:** Music (secondary: Utilities).
- **Content rights:** no third-party content shown or streamed.
- **Name / subtitle (30 characters each):** Spectra Audio Analyzer ·
  Spectrum, loudness and scope
- **Keywords (100 characters):**
  `spectrum,analyzer,fft,lufs,loudness,oscilloscope,spectrogram,meter,stereo,stems,frequency,db,rta`
- **Review notes:**
  > Spectra is a real-time audio analyser. Everything runs on the device and
  > nothing is recorded or sent. To try it without speaking into the
  > microphone: tap the bar above the tabs, choose Audio file and pick any
  > audio file. "Other apps" uses a ReplayKit broadcast upload extension only
  > to receive the audio of other apps (Spotify, YouTube) for analysis: the
  > extension drops the screen frames unread and passes the audio to the app
  > through an App Group; it never records or transmits anything. The Stems
  > tab runs an ONNX model on the CPU. No account is needed.
- **Screenshots:** 6.9" iPhone (1320 × 2868) and 13" iPad (2064 × 2752): the
  project targets iPad (`TARGETED_DEVICE_FAMILY: "1,2"`), so the iPad set is
  mandatory and App Review may test on an iPad. Every page was checked on the
  iPad and iPhone Duo simulators on 2026-10-25. A debug build opens a page
  and plays the imported file for screenshots:
  `SIMCTL_CHILD_SPECTRA_PAGE=STEMS SIMCTL_CHILD_SPECTRA_SOURCE=FILE SIMCTL_CHILD_SPECTRA_PLAY=1 xcrun simctl launch <udid> com.n3d.spectra`.
  Build with Xcode 27.1 or later: on the iPhone Duo an older SDK leaves the
  app in a smaller window with horizontal bars.

## Before the first submission

1. **A paid Apple Developer Program membership** ($99/year). It needs the age
   of majority (18 in Czechia): wait, have a parent enrol, or enrol a company.
   An individual account shows the **legal full name** as the seller on the
   App Store; an organisation shows the company name (needs a D-U-N-S number).
2. **No "beta" on the App Store (2.2).** Betas belong in TestFlight. Submit a
   version whose name has no pre-release part — `tools/release` writes
   `APP_VERSION_NAME`, and the BETA badge disappears by itself when it is a
   plain number.
3. **The synth lane's weights are CC BY-NC-SA 4.0** and were trained on
   non-commercial datasets. A free app is fine; a paid app, a subscription or
   an in-app purchase is not, as things stand.
4. Bump `CURRENT_PROJECT_VERSION` for every upload (App Store Connect refuses
   a build number it has seen).

# Spectra for iPhone

The same analyser as the Android and Windows builds, rebuilt for iOS 26 and
later. The interface is iOS's own Liquid Glass, and the engine is a Swift port
of the shared Kotlin engine, checked against it.

```
ios/
├─ SpectraCore/      Swift package: the engine. Builds and tests on the Mac, no simulator.
│  ├─ Sources/SpectraCore    dsp/, dsp/nes, audio/, paint/, settings/: same files as the Kotlin
│  ├─ Sources/SpectraStems   stems/: the stem separator and synth splitter, on ONNX Runtime
│  └─ Tests/                 every class against the Kotlin engine's own results (fixtures)
├─ Spectra/          the app: capture, the analysis loop, the SwiftUI pages and painters
├─ SpectraBroadcast/ the screen-broadcast extension: iOS's only route to other apps' sound
├─ Shared/           the App Group ring the extension and the app share
├─ tools/            the app icon (from tools/launcher-icon.py) and the stem-model build phase
├─ project.yml       XcodeGen: `cd ios && xcodegen` regenerates Spectra.xcodeproj
└─ PORTING.md        the rules that keep the Swift engine and the Kotlin one in step
```

## Build and run

```bash
cd ios && xcodegen        # after editing project.yml (brew install xcodegen)
open Spectra.xcodeproj    # pick an iPhone simulator, ▶ Run
```

The engine on its own:

```bash
cd ios/SpectraCore && swift test
```

After any change to the Kotlin engine, regenerate the reference results and rerun:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :desktop:iosFixtures && (cd ios/SpectraCore && swift test)
```

## Where the sound comes from

- **Microphone.** Uses iOS's measurement mode (no gain control or noise
  suppression), and records stereo on iPhones whose built-in mics can.
- **Audio file.** Played through Spectra and heard at the output unit, so what
  is analysed is exactly what plays. This is the clean digital signal that
  Android's device-audio capture gives, and the one oscilloscope music needs.
- **Other apps.** iOS lets an app hear other apps only inside a
  screen-broadcast extension. `SpectraBroadcast` passes the app audio to the
  app through a file in the App Group. DRM-protected audio such as Apple Music
  arrives as silence. Broadcast extensions don't run in the simulator, so this
  path is only testable on a real iPhone.

iOS has no counterpart to the Android build's notification graph, floating
overlay or widget, and an iPhone app cannot update itself.

## On a real iPhone

```bash
ios/tools/run-on-iphone.sh
```

builds a release, signs it with the Apple ID's team, installs it on the iPhone
on the cable and starts it. Free Apple ID signing works, but an install stops
launching after 7 days: run the script again to renew it.

Once per Mac and Apple ID:

1. On the iPhone: Settings → Privacy & Security → Developer Mode on. The switch
   only appears after a Mac's developer tools have asked for it;
   `idevicedevmodectl reveal` (libimobiledevice) does that.
2. Xcode → Settings → Apple Accounts → Sign In…, then the Personal Team →
   Manage Certificates… → + → Apple Development. The script reads the team ID
   from that certificate, so it is never written into the project.
3. Register the App Group: open `Spectra.xcodeproj`, and on Signing &
   Capabilities pick the team for **both** targets. Xcode's editor registers
   `group.com.n3d.spectra`; `xcodebuild -allowProvisioningUpdates` alone makes
   profiles with an empty group list. Then `git checkout` the project file, which
   Xcode has just rewritten with the team ID in it.
4. After the first install: Settings → General → VPN & Device Management →
   the Apple ID → Trust.

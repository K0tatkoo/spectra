#!/usr/bin/env bash
#
# Builds Spectra for Windows — from a Mac.
#
# The result is a self-contained folder: Spectra.exe, a bundled Java runtime and
# the application jar. Nothing has to be installed on the target machine.
#
# Three things make cross-building possible, and each of them is the reason for a
# step below:
#
#   * jlink can produce a runtime image for another platform, as long as it is
#     given that platform's jmods and the versions match exactly. That is why a
#     Windows JDK is downloaded and why the local JDK is checked against it.
#   * jpackage cannot cross-build — but the *Windows* jpackage.exe runs under
#     Wine, and it is the only thing that stamps the icon and the version
#     resources into the launcher correctly. rcedit under Wine does not: Wine
#     does not implement growing a PE resource section, and it fails silently.
#   * Everything else — the jar, the icon — is plain Java and builds anywhere.
#
# Requirements: a JDK 21 on the PATH (or in JAVA_HOME), wine, curl, unzip.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
VERSION="${SPECTRA_VERSION:-1.0}"
WORK="${SPECTRA_BUILD_DIR:-$HERE/build/windows}"
CACHE="${SPECTRA_CACHE_DIR:-$HERE/build/cache}"
JDK_VERSION="21.0.12.1+1"
JDK_TAG="jdk-21.0.12.1%2B1"
JDK_FILE="OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/${JDK_TAG}/${JDK_FILE}"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31merror: %s\033[0m\n' "$*" >&2; exit 1; }

command -v wine >/dev/null || die "wine is not installed. brew install --cask wine-stable"
command -v curl >/dev/null || die "curl is required"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin}"
JAVA_BIN="${JAVA_BIN:-$(dirname "$(command -v java || true)")}"
[ -x "$JAVA_BIN/jlink" ] || die "no jlink found. Set JAVA_HOME to a JDK 21."

LOCAL_VERSION="$("$JAVA_BIN/java" -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
say "local JDK $LOCAL_VERSION, target $JDK_VERSION"
if [ "$LOCAL_VERSION" != "${JDK_VERSION%%+*}" ]; then
  # Not fatal — jlink usually tolerates a patch difference — but it is the first
  # thing to suspect if the link step fails with a module version complaint.
  printf '\033[33mwarning: local JDK is %s but the Windows jmods are %s\033[0m\n' \
    "$LOCAL_VERSION" "$JDK_VERSION"
fi

mkdir -p "$CACHE" "$WORK"

say "Windows JDK"
WIN_JDK="$CACHE/jdk-${JDK_VERSION//+/_}"
if [ ! -d "$WIN_JDK" ]; then
  [ -f "$CACHE/$JDK_FILE" ] || curl -fSL --progress-bar -o "$CACHE/$JDK_FILE" "$JDK_URL"
  ( cd "$CACHE" && unzip -q -o "$JDK_FILE" )
  mv "$CACHE/jdk-${JDK_VERSION}" "$WIN_JDK"
fi
echo "    $WIN_JDK"

say "application jar and icon"
( cd "$ROOT" && ./gradlew --console=plain -q :desktop:fatJar :desktop:appIcon )
JAR="$HERE/build/libs/spectra-all.jar"
ICO="$HERE/build/icon/spectra.ico"
[ -f "$JAR" ] || die "no jar at $JAR"
[ -f "$ICO" ] || die "no icon at $ICO"

say "Windows Java runtime (jlink, cross-target)"
RUNTIME="$WORK/runtime"
rm -rf "$RUNTIME"
# java.desktop pulls in java.datatransfer, java.xml and java.prefs; those five
# modules are the whole of what jdeps reports for the jar.
"$JAVA_BIN/jlink" \
  --module-path "$WIN_JDK/jmods" \
  --add-modules java.base,java.desktop \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$RUNTIME"
echo "    $(du -sh "$RUNTIME" | cut -f1)"

say "app image (Windows jpackage, under Wine)"
STAGE="$WORK/stage"
rm -rf "$STAGE" "$WORK/image"
mkdir -p "$STAGE/in" "$WORK/image"
cp "$JAR" "$STAGE/in/"
cp "$ICO" "$STAGE/spectra.ico"
(
  cd "$STAGE"
  WINEDEBUG=-all wine "$WIN_JDK/bin/jpackage.exe" \
    --type app-image \
    --name Spectra \
    --input in \
    --main-jar "$(basename "$JAR")" \
    --main-class com.n3d.spectra.desktop.MainKt \
    --icon spectra.ico \
    --app-version "$VERSION" \
    --vendor "Nebula 3D" \
    --copyright "Nebula 3D" \
    --description "Real-time audio analyser with an NES 2A03 triangle mode" \
    --java-options "-Dsun.java2d.d3d=false" \
    --runtime-image "$RUNTIME" \
    --dest "$WORK/image"
) 2>&1 | grep -viE 'mvk|vulkan|metal|GPU|extension|texture|tier|^\s+VK|supported|process group affinity|model:|type:|vendorID|deviceID|pipelineCache|^\s*$' || true

IMAGE="$WORK/image/Spectra"
[ -x "$IMAGE/Spectra.exe" ] || die "jpackage produced no launcher — is Wine working? try: wine $WIN_JDK/bin/java.exe -version"

# jpackage silently leaves the launcher iconless if the .ico could not be parsed,
# and an iconless build looks fine until it is on a taskbar. The resource section
# grows by roughly the size of the icon, so compare against the bare template.
cat > "$WORK/Rsrc.java" <<'JAVA'
import java.nio.*;
import java.nio.file.*;

/** Prints the size of a PE file's .rsrc section. Used only as a build assertion. */
public class Rsrc {
    public static void main(String[] args) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        int pe = b.getInt(0x3c);
        int sections = b.getShort(pe + 6) & 0xffff;
        int optionalHeader = b.getShort(pe + 20) & 0xffff;
        int table = pe + 24 + optionalHeader;
        int size = 0;
        for (int i = 0; i < sections; i++) {
            int o = table + i * 40;
            byte[] name = new byte[8];
            b.position(o);
            b.get(name);
            if (new String(name).replace("\0", "").trim().equals(".rsrc")) size = b.getInt(o + 16);
        }
        System.out.println(size);
    }
}
JAVA
RSRC=$("$JAVA_BIN/java" "$WORK/Rsrc.java" "$IMAGE/Spectra.exe")
[ "$RSRC" -gt 20000 ] || die "the launcher's .rsrc is only $RSRC bytes — the icon was not stamped"
echo "    icon stamped (.rsrc $RSRC bytes)"

say "package"
cp "$HERE/WINDOWS-README.txt" "$IMAGE/README.txt" 2>/dev/null || true
ZIP="$WORK/Spectra-$VERSION-windows-x64.zip"
rm -f "$ZIP"
( cd "$WORK/image" && zip -qr "$ZIP" Spectra )
echo "    $ZIP"
echo "    $(du -h "$ZIP" | cut -f1) zipped, $(du -sh "$IMAGE" | cut -f1) unpacked"

say "done"
echo "Unzip anywhere on a Windows machine and run Spectra.exe. No install, no JRE needed."

#!/bin/bash
# Puts the StemgenRT stem model into the app bundle (an Xcode build phase).
#
# The model is not in git: 37.5 MB, MIT, the exact file StemgenRT pins. The
# Android build's fetchStemModel task caches it in ~/.gradle/caches/spectra/;
# this uses that copy, or downloads the same pinned file into the same place,
# and refuses anything whose SHA-256 is not the pinned one.
set -euo pipefail

URL="https://media.githubusercontent.com/media/sweetspotsoundsystem/stemgen-rt/990df8ee5baa621f042d4534dacc65afee0a96ce/model/model.onnx"
SHA="08424ca91feae8d4746442a35ebf70489dea70ea6e81401b39483cf02d497748"
CACHE="$HOME/.gradle/caches/spectra/stemgen-rt-${SHA:0:16}.onnx"

verify() { [ -f "$1" ] && [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$SHA" ]; }

if ! verify "$CACHE"; then
    mkdir -p "$(dirname "$CACHE")"
    echo "Downloading the stem model (37.5 MB)…"
    curl -fL --retry 3 -o "$CACHE.part" "$URL"
    if ! verify "$CACHE.part"; then
        rm -f "$CACHE.part"
        echo "error: the downloaded stem model does not match its pinned SHA-256" >&2
        exit 1
    fi
    mv "$CACHE.part" "$CACHE"
fi

if [ -n "${TARGET_BUILD_DIR:-}" ]; then
    DEST="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/stemgen-rt-hop128.onnx"
    if ! cmp -s "$CACHE" "$DEST" 2>/dev/null; then cp "$CACHE" "$DEST"; fi
else
    echo "$CACHE"
fi

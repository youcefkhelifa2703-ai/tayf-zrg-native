#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.local/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [[ ! -f "$ANDROID_HOME/platforms/android-34/android.jar" ]]; then
    echo "Android SDK 34 is missing. Run: bash scripts/setup-android.sh" >&2
    exit 1
fi

options=()
# Google's Linux AAPT2 needs a Nix loader/library path in this environment.
# Patch a private copy rather than altering Gradle's downloaded cache.
if [[ -d /nix/store ]] && command -v patchelf >/dev/null; then
    original="$ANDROID_HOME/build-tools/34.0.0/aapt2"
    patched="$ANDROID_HOME/.replit-tools/aapt2"
    mkdir -p "$(dirname "$patched")"
    if [[ ! -f "$patched" || "$original" -nt "$patched" ]]; then
        cp "$original" "$patched"
        chmod u+w "$patched"
        loader="$(patchelf --print-interpreter "$(readlink -f "$(command -v java)")")"
        zlib="$(nix-instantiate --eval --raw -E '(import <nixpkgs> {}).zlib.outPath')"
        patchelf --set-interpreter "$loader" --set-rpath "$zlib/lib" "$patched"
    fi
    options+=("-Pandroid.aapt2FromMavenOverride=$patched")
fi

if [[ $# -eq 0 ]]; then
    set -- :app:assembleDebug
fi
./gradlew --no-daemon --max-workers=2 "${options[@]}" "$@"

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

command -v java >/dev/null || { echo "Install JDK 17 before continuing." >&2; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.local/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    archive="$(mktemp)"
    trap 'rm -f "$archive"' EXIT
    curl --fail --location --retry 2 \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
        --output "$archive"
    unzip -q "$archive" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

echo "Review and accept the Android SDK licenses when prompted."
"$SDKMANAGER" --licenses
"$SDKMANAGER" "platforms;android-34" "build-tools;34.0.0"
echo "Android SDK ready. Run: bash scripts/build-android.sh"

#!/usr/bin/env bash
# Sets up a self-contained Android build toolchain under ~/Android.
#
# No sudo, no system-wide changes. Everything lands in ~/Android and can be
# removed later with: rm -rf ~/Android
#
# Installs:
#   - JDK 17 (Eclipse Temurin)                  ~190 MB
#   - Android command-line tools (sdkmanager)   ~150 MB
#   - platform-tools (adb), platform 34,        ~200 MB
#     build-tools 34.0.0
#
# Idempotent-ish: re-running re-downloads and re-extracts. Safe to repeat.
set -euo pipefail

BASE="$HOME/Android"
SDK="$BASE/Sdk"
mkdir -p "$BASE" "$SDK"

if command -v curl >/dev/null; then DL=(curl -fL -o); elif command -v wget >/dev/null; then DL=(wget -O); else
  echo "ERROR: need curl or wget installed." >&2; exit 1
fi

echo ">> [1/4] JDK 17 (Temurin)..."
"${DL[@]}" /tmp/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
mkdir -p "$BASE/jdk17"
tar -xzf /tmp/jdk17.tar.gz -C "$BASE/jdk17" --strip-components=1
export JAVA_HOME="$BASE/jdk17"

echo ">> [2/4] Android command-line tools..."
"${DL[@]}" /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
rm -rf "$SDK/cmdline-tools/latest"
mkdir -p "$SDK/cmdline-tools"
python3 -m zipfile -e /tmp/cmdtools.zip "$SDK/cmdline-tools/"
mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
# python's zipfile does not preserve exec bits; restore them on the CLI tools.
chmod +x "$SDK/cmdline-tools/latest/bin/"*

export ANDROID_HOME="$SDK"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"

echo ">> [3/4] Accepting SDK licenses..."
yes | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true

echo ">> [4/4] platform-tools + platform 34 + build-tools 34.0.0..."
"$SDKM" --sdk_root="$SDK" "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 | tail -5

cat > "$BASE/env.sh" <<EOF
# Source this to put the toolchain on PATH: source ~/Android/env.sh
export JAVA_HOME="$BASE/jdk17"
export ANDROID_HOME="$SDK"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
EOF

echo
echo "=== installed ==="
"$BASE/jdk17/bin/java" -version 2>&1 | head -1
"$SDK/platform-tools/adb" version 2>&1 | head -1
echo "SETUP_DONE — env saved to $BASE/env.sh"

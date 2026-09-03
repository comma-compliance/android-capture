#!/usr/bin/env bash
#
# Userspace Android SDK installer for building this client.
#
# Idempotent and sudo-free: downloads the Android command-line tools into a
# per-user directory, installs the platform/build-tools needed to assemble the
# app, and accepts the SDK licenses non-interactively. Gradle itself is NOT
# installed here; the Gradle wrapper (./gradlew) downloads the pinned Gradle
# version on first use.
#
# PREREQUISITE (one-time, system-wide, must be done by a human with sudo):
#   apt-get install -y openjdk-17-jdk
# This script verifies JDK 17 is present and exits with guidance if not.
#
# After running, add the printed exports to your shell profile (or `source`
# this script's output) so ANDROID_HOME / ANDROID_SDK_ROOT and the tool dirs
# are on PATH for ./gradlew.
#
set -euo pipefail

# --- Tunables -----------------------------------------------------------------
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/sdk}}"
# Pinned versions kept in lockstep with app/build.gradle.kts (compileSdk 36).
PLATFORM_PKG="platforms;android-36"
BUILD_TOOLS_PKG="build-tools;36.0.0"
PLATFORM_TOOLS_PKG="platform-tools"
# Google-hosted command-line tools. Update the version + checksum together.
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"

# --- JDK 17 gate --------------------------------------------------------------
if ! command -v javac >/dev/null 2>&1; then
  echo "ERROR: javac not found. Install JDK 17 system-wide first:" >&2
  echo "  sudo apt-get install -y openjdk-17-jdk" >&2
  exit 1
fi
JAVAC_MAJOR="$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
if [ "$JAVAC_MAJOR" != "17" ]; then
  echo "ERROR: JDK 17 required, found javac major version '$JAVAC_MAJOR'." >&2
  echo "  Install with: sudo apt-get install -y openjdk-17-jdk" >&2
  echo "  and ensure it is the default javac on PATH." >&2
  exit 1
fi
echo "JDK 17 confirmed: $(javac -version 2>&1)"

# --- Install command-line tools ----------------------------------------------
# The SDK expects cmdline-tools under <root>/cmdline-tools/latest/.
CMDLINE_DIR="$SDK_ROOT/cmdline-tools/latest"
SDKMANAGER="$CMDLINE_DIR/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "Installing Android command-line tools into $SDK_ROOT ..."
  mkdir -p "$SDK_ROOT/cmdline-tools"
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  echo "Downloading $CMDLINE_TOOLS_URL ..."
  curl -fSL "$CMDLINE_TOOLS_URL" -o "$TMP_DIR/$CMDLINE_TOOLS_ZIP"
  unzip -q "$TMP_DIR/$CMDLINE_TOOLS_ZIP" -d "$TMP_DIR"
  # The zip extracts to a top-level "cmdline-tools" dir; relocate to ".../latest".
  rm -rf "$CMDLINE_DIR"
  mkdir -p "$CMDLINE_DIR"
  mv "$TMP_DIR/cmdline-tools/"* "$CMDLINE_DIR/"
  echo "Command-line tools installed."
else
  echo "Command-line tools already present at $CMDLINE_DIR."
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

# --- Accept licenses + install packages (idempotent) --------------------------
echo "Accepting SDK licenses ..."
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null

echo "Installing SDK packages ..."
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "$PLATFORM_TOOLS_PKG" \
  "$PLATFORM_PKG" \
  "$BUILD_TOOLS_PKG"

echo
echo "Android SDK ready at: $SDK_ROOT"
echo
echo "Add these to your shell profile (or eval them now) before running ./gradlew:"
echo "  export ANDROID_HOME=\"$SDK_ROOT\""
echo "  export ANDROID_SDK_ROOT=\"$SDK_ROOT\""
echo "  export PATH=\"\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\""

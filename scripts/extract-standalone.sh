#!/usr/bin/env bash
#
# extract-standalone.sh - produce the standalone, public source tree for the
# Comma Compliance Messages Archiver Android client.
#
# This client lives inside a larger monorepo during development. The public
# release is a self-contained repository that contains ONLY the Android client
# (with its own top-level CI), and excludes private operational material.
#
# What it does:
#   1. rsync the client tree into a target directory, excluding build output,
#      local-only files, signing material, and private docs.
#   2. Write standalone GitHub Actions workflows (ci.yml + release.yml) into
#      <target>/.github/workflows/, derived from the monorepo workflows but
#      rewritten for a repo whose root IS the client (no android-client/ path
#      prefixes, no working-directory defaults, no path filters). Signing-secret
#      handling is preserved exactly: signing material reaches Gradle only via
#      the environment, the keystore is a decoded temp file, and it is removed
#      afterward.
#
# The script is idempotent: re-running it overwrites the extracted tree to match
# the current source. It refuses to run if the target has uncommitted tracked
# changes that are NOT the result of a prior extraction (i.e. local edits that a
# re-extraction would silently clobber), so a human can review/commit first.
#
# Usage:
#   scripts/extract-standalone.sh [TARGET_DIR]
#
# TARGET_DIR defaults to ${ANDROID_ARCHIVE_TARGET:-$HOME/android-archive-client}.

set -euo pipefail

# --- locate the source tree (the android client root) ------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Default target: a sibling repo in the maintainer's home directory. Overridable
# via the first positional arg or the ANDROID_ARCHIVE_TARGET env var; no personal
# path is baked in so this script is safe to ship in the public tree.
TARGET_DIR="${1:-${ANDROID_ARCHIVE_TARGET:-$HOME/android-archive-client}}"

# --- rsync filter rules ------------------------------------------------------
# Order matters: the FIRST matching rule wins. We use --delete-excluded so that
# excluded paths are not merely skipped on copy but actively REMOVED from the
# target - otherwise a private doc, keystore, or stray build dir left by an
# earlier (or hand-mangled) extraction would survive in the public tree.
#
# Because --delete-excluded would also delete the target's OWN .git, we protect
# it first (a "P" rule shields a destination path from deletion) and also exclude
# .git from being copied out of the source (matches both a .git directory and a
# .git pointer-file, since the trailing slash is omitted).
#
# Excluded (and deleted from the target): build output, IDE/Gradle caches,
# machine-local config, signing material, packaged artifacts, the contract spike,
# and the private end-to-end archival runbook (operational, monorepo-only).
RSYNC_FILTERS=(
  # Protect the target's version-control metadata from --delete-excluded.
  --filter='P /.git/'
  --filter='P /.git'
  # Do not copy the source's VCS metadata into the target.
  --filter='- /.git/'
  --filter='- /.git'
  # Private / local / build / signing exclusions (also deleted from target).
  --filter='- docs/e2e-archival-runbook.md'
  --filter='- .gradle/'
  --filter='- **/build/'
  --filter='- .kotlin/'
  --filter='- local.properties'
  --filter='- *.keystore'
  --filter='- *.jks'
  --filter='- *.apk'
  --filter='- *.aab'
  --filter='- spike/'
)

# --- preflight ---------------------------------------------------------------
if [[ ! -d "$SOURCE_DIR/app" || ! -f "$SOURCE_DIR/settings.gradle.kts" ]]; then
  echo "ERROR: $SOURCE_DIR does not look like the android client root." >&2
  exit 1
fi

if ! command -v rsync >/dev/null 2>&1; then
  echo "ERROR: rsync is required but not found on PATH." >&2
  exit 1
fi

mkdir -p "$TARGET_DIR"

# The target must be a git repository so we can detect un-extraction-related
# local edits before clobbering them.
if [[ ! -d "$TARGET_DIR/.git" ]]; then
  echo "ERROR: $TARGET_DIR is not a git repository (expected an initialized" >&2
  echo "       target so a re-extraction cannot silently clobber local work)." >&2
  exit 1
fi

# Idempotency guard: refuse to run if the target has tracked changes that did
# NOT come from a prior extraction. A clean tree, or a tree whose only changes
# are this script's own output (i.e. a prior extraction the user has not yet
# committed), is fine - re-extraction reproduces it. But genuine local edits to
# already-committed files must be surfaced, not overwritten.
#
# Strategy: if the target has at least one commit, any committed file that is
# locally Modified, Deleted, or Type-changed (M/D/T) relative to HEAD is a local
# edit a re-run (rsync --delete-excluded or the workflow heredocs) would silently
# clobber -> stop. Additions/untracked files are expected output of an as-yet-
# uncommitted extraction and are safe to overwrite, so they are NOT flagged.
if git -C "$TARGET_DIR" rev-parse --verify HEAD >/dev/null 2>&1; then
  CLOBBERABLE="$(git -C "$TARGET_DIR" diff --name-only --diff-filter=MDT HEAD || true)"
  if [[ -n "$CLOBBERABLE" ]]; then
    echo "ERROR: $TARGET_DIR has local changes to committed files that a" >&2
    echo "       re-extraction would clobber. Commit or discard them first:" >&2
    echo "$CLOBBERABLE" | sed 's/^/         /' >&2
    exit 1
  fi
fi

# --- extract -----------------------------------------------------------------
echo "Extracting standalone tree:"
echo "  source: $SOURCE_DIR"
echo "  target: $TARGET_DIR"

# --delete keeps the target an exact mirror of the (filtered) source, so files
# removed upstream disappear from the public tree on re-extraction.
# --delete-excluded additionally purges excluded paths (private docs, signing
# material, build output) from the target; the target's own .git is shielded by
# the protect ("P") filters above.
rsync -a --delete --delete-excluded "${RSYNC_FILTERS[@]}" \
  "$SOURCE_DIR/" "$TARGET_DIR/"

# --- standalone CI workflows -------------------------------------------------
WORKFLOWS_DIR="$TARGET_DIR/.github/workflows"
mkdir -p "$WORKFLOWS_DIR"

# ci.yml: derived from the monorepo ci-android-client.yml. Differences from the
# monorepo original:
#   - the repo root IS the client, so there is no `working-directory: android-client`
#   - artifact paths drop the `android-client/` prefix
#   - push/pull_request path filters are removed (the whole repo is the client)
cat > "$WORKFLOWS_DIR/ci.yml" <<'CI_YML'
name: CI

on:
  push:
  pull_request:
  workflow_dispatch:

jobs:
  build:
    name: assembleDebug
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Build debug APK
        run: ./gradlew :app:assembleDebug --no-daemon --stacktrace

      - name: Run unit tests
        run: ./gradlew :app:testDebugUnitTest --no-daemon --stacktrace

      - name: Android Lint
        run: ./gradlew :app:lintDebug --no-daemon --stacktrace

      - name: Upload lint report
        if: ${{ !cancelled() }}
        uses: actions/upload-artifact@v4
        with:
          name: lint-results-debug
          path: app/build/reports/lint-results-debug.html
          if-no-files-found: warn

      - name: detekt (Kotlin static analysis)
        run: ./gradlew detekt --no-daemon --stacktrace

      - name: Upload detekt report
        if: ${{ !cancelled() }}
        uses: actions/upload-artifact@v4
        with:
          name: detekt-report
          path: build/reports/detekt/detekt.html
          if-no-files-found: warn

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
CI_YML

# release.yml: derived from the monorepo release-android-client.yml. Signing is
# identical in spirit and mechanics - signing material is supplied ONLY through
# GitHub Actions secrets, the keystore is decoded to a runner-local temp file,
# its path + passwords reach Gradle only through the environment (never as -P
# args), and the decoded keystore is removed afterward. Differences are purely
# structural: no working-directory default, and the artifact path drops the
# android-client/ prefix.
cat > "$WORKFLOWS_DIR/release.yml" <<'RELEASE_YML'
name: Release

# Builds a SIGNED Android App Bundle (.aab) for distribution via managed /
# private Google Play. Signing material is supplied ONLY through GitHub Actions
# secrets (never committed - .gitignore excludes *.keystore / *.jks / *.aab):
#
#   ANDROID_KEYSTORE_BASE64    base64 of the upload/release keystore (.jks)
#   ANDROID_KEYSTORE_PASSWORD  store password for that keystore
#   ANDROID_KEY_ALIAS          alias of the signing key inside the keystore
#   ANDROID_KEY_PASSWORD       password for that key alias
#
# To create the four secrets:
#   keytool -genkeypair -v -keystore release.jks -alias upload \
#     -keyalg RSA -keysize 2048 -validity 10000
#   base64 -w0 release.jks   # -> ANDROID_KEYSTORE_BASE64
# then add all four under Settings -> Secrets and variables -> Actions.
#
# The keystore is decoded to a runner-local temp file at build time and removed
# afterward. The path + passwords reach Gradle ONLY through the environment
# (app/build.gradle.kts reads them via System.getenv to assemble the release
# signingConfig) - never as -P command-line args, so secrets are not exposed in
# process arguments or --stacktrace output. Nothing is echoed to logs and no
# secret is written into the repo tree.

on:
  push:
    tags:
      - "v*"
  workflow_dispatch:

jobs:
  bundle:
    name: bundleRelease (signed AAB)
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Decode keystore
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          if [ -z "$ANDROID_KEYSTORE_BASE64" ]; then
            echo "::error::ANDROID_KEYSTORE_BASE64 secret is not set; cannot sign the release."
            exit 1
          fi
          KEYSTORE_PATH="$RUNNER_TEMP/release.jks"
          echo "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$KEYSTORE_PATH"
          chmod 600 "$KEYSTORE_PATH"
          echo "ANDROID_KEYSTORE_PATH=$KEYSTORE_PATH" >> "$GITHUB_ENV"

      - name: Build signed AAB
        env:
          # Passed via the environment (read by build.gradle.kts through
          # System.getenv), NOT as -P args, so signing secrets never appear in
          # process arguments or stacktrace output.
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          ./gradlew :app:bundleRelease --no-daemon --stacktrace

      - name: Remove decoded keystore
        if: always()
        run: rm -f "$ANDROID_KEYSTORE_PATH"

      - name: Upload AAB artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-release-aab
          path: app/build/outputs/bundle/release/app-release.aab
          if-no-files-found: error
RELEASE_YML

echo "Wrote standalone workflows:"
echo "  $WORKFLOWS_DIR/ci.yml"
echo "  $WORKFLOWS_DIR/release.yml"
echo "Done."

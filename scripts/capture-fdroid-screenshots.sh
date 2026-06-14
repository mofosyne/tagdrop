#!/usr/bin/env bash
#
# Captures F-Droid phone screenshots from a running emulator or connected
# device, using the same "Add Demo Collection" seed data that's already in
# the app (Main menu -> Add Demo Collection) so Collections/History/Map
# aren't empty.
#
# Requires: adb (Android SDK platform-tools) and python3 on PATH, plus a
# booted emulator or a device with USB debugging enabled. Run from anywhere;
# it cds to the repo root itself. On Windows, run via Git Bash or WSL.
#
# Usage:
#   scripts/capture-fdroid-screenshots.sh
#
# Output goes to fastlane/metadata/android/en-US/images/phoneScreenshots/ as
# descriptively-named PNGs. Review them, delete any you don't want, and
# rename the ones you're keeping to 1.png, 2.png, ... in the order they
# should appear on the store listing.

set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="com.github.mofosyne.tagdrop"
OUT_DIR="fastlane/metadata/android/en-US/images/phoneScreenshots"
HELPER="$(dirname "$0")/_ui_tap.py"

mkdir -p "$OUT_DIR"

echo "Waiting for a device..."
adb wait-for-device

echo "Building and installing debug APK..."
./gradlew installDebug

# Pre-grant runtime permissions so dialogs don't block automation.
adb shell pm grant "$APP_ID" android.permission.CAMERA >/dev/null 2>&1 || true
adb shell pm grant "$APP_ID" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
adb shell pm grant "$APP_ID" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true

adb shell am force-stop "$APP_ID"

tap_text() {
  echo "Tapping '$1'..."
  python3 "$HELPER" "$1"
}

screenshot() {
  echo "Capturing $1.png..."
  adb exec-out screencap -p > "$OUT_DIR/$1.png"
}

# --- Main screen: seed demo content, then capture each bottom-nav tab ---
adb shell am start -W -n "$APP_ID/.MainActivity" >/dev/null
sleep 3

# KEYCODE_MENU opens the toolbar's options menu even when there's no visible
# overflow icon — more reliable across devices than tapping "More options".
adb shell input keyevent KEYCODE_MENU
sleep 1
tap_text "Add Demo Collection"
sleep 2

screenshot "1-collections"

tap_text "History"
sleep 1
screenshot "2-history"

tap_text "Map"
sleep 3
screenshot "3-map"

tap_text "Collections"
sleep 1
tap_text "Demo Trail"
sleep 1
screenshot "4-collection-detail"

# --- Create screens (no seed data needed) ---
adb shell am start -W -n "$APP_ID/.CreateActivity" >/dev/null
sleep 1
screenshot "5-create"

adb shell am start -W -n "$APP_ID/.CreatePaperActivity" >/dev/null
sleep 1
screenshot "6-create-paper"

# --- Scanner screen ---
adb shell am start -W -n "$APP_ID/.ReceiveActivity" >/dev/null
sleep 2
screenshot "7-scan"

echo
echo "Done. Screenshots saved to $OUT_DIR/"
echo "Review, drop any you don't want, and rename the keepers to 1.png, 2.png, ..."

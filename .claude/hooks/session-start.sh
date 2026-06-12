#!/bin/bash
# Sets up an Android SDK and a Gradle-compatible Java truststore so that
# `./gradlew testDebugUnitTest` / `./gradlew assembleDebug` work in Claude Code
# on the web sessions (mirrors .github/workflows/ci.yml).
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_SDK_ROOT="/root/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ ! -d "$ANDROID_SDK_ROOT/platforms/android-34" ]; then
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  tmp_zip="$(mktemp)"
  curl -fsSL -o "$tmp_zip" "$CMDLINE_TOOLS_URL"
  unzip -q "$tmp_zip" -d "$ANDROID_SDK_ROOT/cmdline-tools"
  rm -f "$tmp_zip"
  [ -d "$ANDROID_SDK_ROOT/cmdline-tools/latest" ] || mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"

  yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses --sdk_root="$ANDROID_SDK_ROOT" >/dev/null 2>&1 || true
  "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1
fi

# local.properties is gitignored; points Gradle at the SDK above.
echo "sdk.dir=$ANDROID_SDK_ROOT" > "$CLAUDE_PROJECT_DIR/local.properties"

{
  echo "export ANDROID_HOME=$ANDROID_SDK_ROOT"
  echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
  # The JDK's default cacerts doesn't trust this sandbox's egress-gateway TLS
  # proxy, so Gradle's HTTPS dependency downloads fail PKIX validation. The
  # system Java truststore at /etc/ssl/certs/java/cacerts does trust it.
  echo 'export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"'
} >> "$CLAUDE_ENV_FILE"

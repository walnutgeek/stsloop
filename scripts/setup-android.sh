#!/usr/bin/env bash
# Install the Android build toolchain into $ANDROID_HOME (default ~/Android/Sdk).
# Idempotent: re-running with everything installed is a no-op. Needs no sudo.
# The JDK comes from mise.toml; run `mise install` first.
#
# Packages are installed with the Android CLI (`android sdk`), which replaced
# `sdkmanager` in cmdline-tools 23. It has no separate license-acceptance step:
# using it is subject to the Android SDK terms, https://developer.android.com/studio/terms
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip" # cmdline-tools 23.0
CMDLINE_TOOLS_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"

PACKAGES=( # `android sdk list --all` names
  "platform-tools"
  "platforms/android-37.0"
  "build-tools/37.0.0"
  "ndk/29.0.14206865" # r29
  "cmake/3.31.6"
)

say() { printf '==> %s\n' "$*"; }

if ! (cd "$REPO_ROOT" && mise exec -- java -version) >/dev/null 2>&1; then
  echo "JDK not available. Run 'mise install' in $REPO_ROOT first." >&2
  exit 1
fi
run() { (cd "$REPO_ROOT" && ANDROID_HOME="$ANDROID_HOME" mise exec -- "$@"); }

ANDROID_CLI="$ANDROID_HOME/cmdline-tools/latest/bin/android"
if [[ ! -x "$ANDROID_CLI" ]]; then
  say "Installing Android command-line tools into $ANDROID_HOME"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  echo "$CMDLINE_TOOLS_SHA1  $tmp/tools.zip" | sha1sum -c --quiet -
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

installed="$(run "$ANDROID_CLI" --no-metrics sdk list 2>/dev/null | awk '{print $1}')"
missing=()
for p in "${PACKAGES[@]}"; do
  grep -qxF "$p" <<<"$installed" || missing+=("$p")
done

if ((${#missing[@]} == 0)); then
  say "Android toolchain already installed"
else
  say "Installing: ${missing[*]}"
  run "$ANDROID_CLI" --no-metrics sdk install "${missing[@]}"
fi

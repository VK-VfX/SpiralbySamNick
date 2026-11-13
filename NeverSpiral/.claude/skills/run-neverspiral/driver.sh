#!/usr/bin/env bash
# Drives Niko's Cam through CI, the only place it can be compiled and run (no Android SDK here).
#   driver.sh status          latest Android Build / Android Emulator runs on the dev branch
#   driver.sh apk [outdir]    download the release APK and verify it against version.json
#   driver.sh results [dir]   fetch the emulator run's report, test results and pictures
set -euo pipefail
REPO=VK-VfX/SpiralbySamNick
BRANCH=claude/never-spiral-android-app-mlcvse
RAW=https://raw.githubusercontent.com/$REPO
cmd=${1:-status}; out=${2:-${TMPDIR:-/tmp}/nikocam-$cmd}
case $cmd in
status)
  gh api "repos/$REPO/actions/runs?branch=$BRANCH&per_page=6" \
    --jq '.workflow_runs[] | "\(.name)\t\(.status)/\(.conclusion)\t\(.head_sha[0:7])\t\(.display_title[0:60])"' ;;
apk)
  mkdir -p "$out"; cd "$out"
  curl -fsSL -O $RAW/never-spiral-apk-builds/niko-cam-release.apk
  curl -fsSL -O $RAW/never-spiral-apk-builds/version.json
  want=$(grep -o '"sha256": "[0-9a-f]*"' version.json | cut -d'"' -f4)
  have=$(sha256sum niko-cam-release.apk | cut -d' ' -f1)
  grep -o '"versionName": "[^"]*"' version.json
  [ "$want" = "$have" ] && echo "sha256 OK: $PWD/niko-cam-release.apk" || { echo "sha256 MISMATCH"; exit 1; } ;;
results)
  rm -rf "$out"; git clone -q --depth 1 -b never-spiral-emulator-results https://github.com/$REPO "$out"
  cd "$out"; cat RUN.txt
  for f in gradle/*.xml; do grep -o '<testsuite [^>]*>' "$f" | grep -o 'name="[^"]*" tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"'; grep -o '<testcase name="[^"]*" classname="[^"]*"' "$f"; done
  echo "--- emulator files (report.txt and pictures; empty means the run-as pull failed):"
  ls files/emulator 2>/dev/null || echo "none"
  [ -f files/emulator/report.txt ] && cat files/emulator/report.txt
  echo "--- OutOfMemory lines in logcat: $(grep -c OutOfMemoryError logcat.txt || true)" ;;
*) echo "usage: $0 status|apk|results [dir]"; exit 2 ;;
esac

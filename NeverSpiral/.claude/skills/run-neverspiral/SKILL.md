---
name: run-neverspiral
description: Build, run, test, screenshot or check Niko's Cam (NeverSpiral, com.nikoezveracamspiral), the Camera2 Android app. Use when asked to compile it, run it on a virtual phone, get the latest APK, or read emulator/CI results. Paths are relative to NeverSpiral/.
---

# Running Niko's Cam

Android app (Kotlin, Compose, Camera2). **There is no Android SDK or emulator in the agent container**, so it cannot be compiled or launched locally. GitHub Actions is the compiler and the virtual phone, and `driver.sh` is the handle on it. Only GitHub is reachable from the container.

## Agent path
```bash
D=.claude/skills/run-neverspiral/driver.sh
$D status          # latest 'Android Build' and 'Android Emulator' runs on the dev branch
$D apk [dir]       # downloads niko-cam-release.apk, checks sha256 against version.json
$D results [dir]   # clones branch never-spiral-emulator-results, lists tests, report, OOM count
```
Flow: push to `claude/never-spiral-android-app-mlcvse` (only when the user says so), which starts both workflows. `status` shows when they finish (about 6 min for the build, about 8 for the emulator), then run `apk` and send the file with SendUserFile. Compile errors: read the job log and grep the Kotlin `e:` lines (the log is one huge line).

What the emulator workflow runs (`.github/workflows/android-emulator.yml`, `ci/emulator-run.sh`): API 34 x86_64 Pixel 6, 3 GB RAM, virtual-scene camera. Tests are in `app/src/androidTest`: `AppSmokeTest` (launch, Portrait, volume-key shutter), `PortraitPipelineTest` (every style, editor re-render, 256 MB "Pixel 8a" heap), `WatermarkRenderTest`. Unit tests (`app/src/test`) run in the build workflow.

## Human path
Install the APK on a phone, or use the in-app update check (About). Useless headless.

## Gotchas
- `results` reports "none" for emulator files: the `run-as … tar` pull of `filesDir/emulator` (report.txt, portrait and watermark pictures) did not produce files on run 43394324946, so the pictures are not available yet. Test results and logcat do arrive. Don't claim you saw the images.
- `OutOfMemoryError` lines in logcat during `aPortraitOnAHeapTheSizeOfAnEightAs` are expected: the retry drops the working size to 576x768.
- Never commit user photos; the emulator test photo is fetched in CI (scikit-image v0.22.0).
- The APK sits on branch `never-spiral-apk-builds` (`niko-cam-release.apk`, `version.json`); a failed build leaves the previous APK there, so check `versionName` matches.

## Troubleshooting
- `gh api` fails: use the GitHub MCP tools (`actions_list`, `get_job_logs`) instead.
- `sha256 MISMATCH`: the branch was updated mid-download; re-run `apk`.

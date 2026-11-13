#!/usr/bin/env bash
# Run on the booted emulator by the "Android Emulator" workflow: runs the instrumented tests, then
# collects what they wrote (a report and pictures) and the end of the log, pass or fail.
set +e
mkdir -p ../emulator-results
./gradlew connectedDebugAndroidTest -PemulatorAbi=true --stacktrace
status=$?
adb logcat -d -t 8000 > ../emulator-results/logcat.txt
adb pull /sdcard/Android/data/com.nikoezveracamspiral/files/emulator ../emulator-results/files
exit $status

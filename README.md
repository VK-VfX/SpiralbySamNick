# Sam's Music Viz

Eleven audio-reactive visualizer modes for Android, styled as a modern dark mastering-suite
instrument panel. Captures whatever's playing system-wide (Spotify, YouTube Music, anything) with
no account or API key, and covers everything from a properly-ballistic analog VU meter and a
BS.1770-style LUFS loudness meter to FFT bar spectrums, a stereo goniometer, and a rainbow-lit
radial pulse ring. Not distributed through the Play Store, so it ships debug builds via GitHub
Releases with an in-app OTA update checker instead.

The app itself lives in [`NeverSpiral/`](NeverSpiral/) -- see
[`NeverSpiral/README.md`](NeverSpiral/README.md) for the full mode-by-mode breakdown, DSP/
rendering architecture notes, and build instructions. CI (`.github/workflows/android-build.yml`)
builds and tests every push, then publishes the debug APK as a tagged GitHub Release.

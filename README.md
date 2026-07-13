# Sam's Projects

Two small, self-contained projects living in this repo -- unrelated to each other, just sharing a
home.

## 🎵 [Sam's Music Viz](NeverSpiral/) -- Android

Eleven audio-reactive visualizer modes for Android, styled as a modern dark mastering-suite
instrument panel. Captures whatever's playing system-wide (Spotify, YouTube Music, anything) with
no account or API key, and covers everything from a properly-ballistic analog VU meter and a
BS.1770-style LUFS loudness meter to FFT bar spectrums, a stereo goniometer, and a rainbow-lit
radial pulse ring. Not distributed through the Play Store, so it ships debug builds via GitHub
Releases with an in-app OTA update checker instead.

See [`NeverSpiral/README.md`](NeverSpiral/README.md) for the full mode-by-mode breakdown, DSP/
rendering architecture notes, and build instructions. CI (`.github/workflows/android-build.yml`)
builds and tests every push, then publishes the debug APK as a tagged GitHub Release.

## 🎛️ Signal Stabilizer -- web

A minimalist, 100%-offline Web Audio tuning puzzle (`index.html` / `app.js` / `style.css`, no
build step, no dependencies). Drag the frequency slider to calm an erratic beat into a steady
1 Hz pulse, then tap the ring the instant it fills to the outer edge -- **PERFECT ALIGNMENT** locks
in a crisp haptic pulse and nudges a hidden fifth-and-octave harmony layer closer to pure,
zero-cents tuning as your streak climbs; **SIGNAL DRIFT** on a miss fires a buzz-pause-buzz pattern
instead (Vibration API, feature-detected and silently skipped where unsupported, e.g. iOS Safari).
No accounts, no network calls, no server -- just open `index.html` or serve the three files
statically.

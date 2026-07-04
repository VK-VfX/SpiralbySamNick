# VU Meter

An analog VU meter that reacts to whatever music is playing on the device -- Spotify,
YouTube Music, or anything else -- with correctly calibrated ballistics, not a fake wobble.

## How the meter works

- **Capture**: audio is captured via Android's `AudioPlaybackCaptureConfiguration` (Android 10+),
  which taps the device's internal audio mix rather than the microphone. That means it reacts the
  same way whether you're on speaker, wired headphones, or Bluetooth. It requires a `RECORD_AUDIO`
  grant plus a one-time system "start recording or casting" consent screen -- that wording is a
  quirk of the underlying API; the app only ever reads audio, never video, and only while the
  visualizer is toggled on (shown by a persistent notification while it runs).
- **Ballistics**: a real VU meter is not a peak meter. ANSI C16.5-1942 defines its response as
  reaching 99% of a step change in 300ms, applied *symmetrically* on the way up and down (unlike a
  peak meter's fast-attack/slow-release). That's what makes the needle read average program energy
  and ignore brief transients, instead of jumping to every instantaneous sample. `VuMeterEngine`
  implements this as a single-pole exponential filter in the dB domain with `tau = 300ms / ln(100)`.
- **Calibration**: 0 dBVU is set to -18 dBFS, the standard professional reference level that leaves
  headroom above 0 for transients to peak into before the digital signal clips.
- **Scale layout**: tick marks (-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3) are spaced *evenly by
  position*, not by dB value -- matching a real VU meter's dial, where the wide -20-to-10 gap and
  the narrow 0-to-1 gap take up roughly the same arc. The needle interpolates smoothly between
  whichever two ticks bracket the current reading.

## Building

Open `NeverSpiral/` in Android Studio, or from the command line:

```
cd NeverSpiral
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android-build.yml`) builds the debug APK on every push touching this
directory and uploads it as a workflow artifact named `never-spiral-debug-apk`.

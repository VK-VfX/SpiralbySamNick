# Sam's Visualizer

Three audio-reactive visualizer modes -- tap anywhere on the screen to crossfade to the next one
-- all driven by whatever music is playing on the device: Spotify, YouTube Music, or anything else.

- **VU Meter**: an analog needle meter with correctly calibrated ballistics (not a fake wobble),
  plus a peak LED that pulses when the level hits the top of the scale.
- **Spectrum**: a real-time FFT bar spectrum, log-spaced across the audible range, with per-band
  peak-hold caps.
- **Oscilloscope**: a classic Y-T waveform trace -- amplitude on the vertical axis, time flowing
  left to right, like a benchtop scope's normal mode -- over a graticule grid.

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
- **Peak LED**: latches on the instant the needle hits the top of the scale and pulses for about
  a second, independent of the needle's own much slower ballistic fall.

## How the spectrum view works

`SpectrumAnalyzer` runs a plain iterative radix-2 FFT (1024-point, Hann-windowed) on the same
captured PCM buffers, buckets the result into 28 log-spaced bands from 40Hz to 16kHz, and
`SpectrumEngine` smooths each band with a fast rise / slower fall filter plus a peak-hold cap --
so bars react instantly to transients but settle down smoothly instead of jittering.

## How the oscilloscope works

`OscilloscopeEngine` copies a decimated 800-point window of the raw (mono-downmixed) waveform
every buffer -- no smoothing, since that would blur the actual waveform shape -- and
`OscilloscopeScreen` plots it left-to-right against a graticule grid, with a soft glow pass under
a bright core stroke to suggest phosphor persistence.

All three engines are stepped every frame regardless of which mode is showing, so tapping to
switch shows a live reading immediately instead of a frozen one.

## Building

Open `NeverSpiral/` in Android Studio, or from the command line:

```
cd NeverSpiral
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android-build.yml`) builds the debug APK on every push touching this
directory and uploads it as a workflow artifact named `never-spiral-debug-apk`.

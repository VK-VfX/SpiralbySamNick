# Sam's Visualizer

Eight audio-reactive visualizer modes, styled as a modern dark mastering-suite instrument panel --
flat near-black panels, thin hairline dividers, a cool desaturated accent, and red reserved strictly
for clip/overload warnings, the way studio metering looks. Tap anywhere on the screen to crossfade
to the next mode, all driven by whatever music is playing on the device: Spotify, YouTube Music, or
anything else.

- **VU Meter**: an analog needle meter with correctly calibrated ballistics (not a fake wobble), a
  digital dB readout alongside the needle, and a peak LED that hard-flashes to full brightness the
  instant it hits the top of the scale, then decays -- like a real hardware peak indicator, not a
  soft continuous pulse.
- **Spectrum**: a real-time FFT bar spectrum, log-spaced across the audible range, with a cool
  blue-to-white gradient (red reserved for the clip zone at the very top), a dB reference grid, and
  frequency labels for orientation across the range.
- **Oscilloscope**: a single continuous white line tracing the waveform envelope over a faint
  graticule grid, with a settings panel (Scale, Stroke Weight, Intensity, Afterglow) accessible via
  a gear icon.
- **Goniometer**: a stereo phase scope -- plots left/right on the mid/side axes, so mono content
  collapses to a vertical line and phase problems fan out sideways -- plus a running phase
  correlation readout.
- **Loudness**: a BS.1770-style LUFS meter (momentary, short-term, integrated, plus loudness
  range), the metric streaming platforms actually normalize to, alongside a scrolling history trend.
- **Graphic EQ**: a classic discrete-LED equalizer bank -- the kind of spectrum display built into
  receivers and separates -- with per-band peak-hold segments, built on the same FFT bands as
  Spectrum.
- **Peak / RMS**: a hardware-style dual bar meter (fast peak with a hold cap, next to RMS) with a
  crest-factor readout -- the gap between the two shows how dynamic or compressed a master is.
- **Tonal Balance**: a long-averaged spectral curve against a flat reference line, showing the
  overall EQ character of what's playing rather than instantaneous levels.

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
- **Peak LED**: a hard flash, not a gradual pulse -- brightness snaps to full the instant the
  needle hits the top of the scale, then decays smoothly on its own, independent of the needle's
  own much slower ballistic fall, so a single loud hit still reads as a crisp flash.

## How the spectrum view works

`SpectrumAnalyzer` runs a plain iterative radix-2 FFT (1024-point, Hann-windowed) on the same
captured PCM buffers, buckets the result into 28 log-spaced bands from 40Hz to 16kHz, and
`SpectrumEngine` smooths each band with a fast rise / slower fall filter plus a peak-hold cap --
so bars react instantly to transients but settle down smoothly instead of jittering. `SpectrumScreen`
colors each bar on a cool blue-to-cyan-to-white gradient with red reserved for the clip zone right
at the top (rather than a green-to-red gradient spread across the whole range), and draws a dB
reference grid plus frequency labels (60Hz, 250Hz, 1kHz, 4kHz, 16kHz) for orientation.

## How the oscilloscope works

`OscilloscopeEngine` builds a smoothly *scrolling* waveform the way real audio waveform displays
do: as raw mono samples arrive, each of 56 columns tracks the peak absolute amplitude seen in its
slice of a scrolling ~2.4 second window, and completed columns shift left as new ones fill in on
the right -- rather than replacing the whole trace every buffer, which is what makes it read as a
continuous, evolving wave instead of flickering. `OscilloscopeScreen` traces that envelope as a
single continuous white line -- one flowing "string" with quadratic midpoint smoothing between
points, not a mirrored top/bottom pair -- and renders it into a persistent off-screen bitmap that's
faded (not cleared) every frame, producing a trailing afterglow instead of the wave just popping in
and out. A gear icon shown only in oscilloscope mode opens a settings panel with Scale, Stroke
Weight, Intensity, and Afterglow sliders.

## How the newer instruments work

- **Goniometer**: plots left/right on mid/side axes -- `x = (L-R)/2`, `y = (L+R)/2` -- rather than
  raw L/R, matching the convention hardware phase scopes use, so mono content collapses to a
  vertical line instead of a diagonal one. `GoniometerEngine` also tracks a running phase
  correlation coefficient (a leaky-integrator Pearson correlation over L and R power/cross-power)
  from +1 (perfectly in phase) through 0 (uncorrelated/wide) to -1 (out of phase, will cancel when
  summed to mono).
- **Loudness**: `LoudnessEngine` is a practical real-time approximation of ITU-R BS.1770 / EBU
  R128, not a certified meter -- K-weighting is a proper high-pass + high-shelf biquad pair (RBJ
  cookbook DSP) tuned to the same intent as the standard's filters, and integrated-loudness gating
  uses a single-pass absolute-threshold approximation rather than BS.1770's two-pass absolute +
  relative block gating, since this runs continuously on a live stream rather than analyzing a
  fixed file. Loudness range (LRA) is the 95th-minus-10th-percentile spread of a rolling short-term
  history.
- **Graphic EQ**: `GraphicEqScreen` renders the same [SpectrumEngine] bands and peak-hold caps as
  the Spectrum view, but as a bank of discrete lit/unlit segments per band instead of continuous
  bars -- the classic look of a receiver's built-in spectrum display -- so it's a different
  rendering treatment of already-proven data rather than a new capture or DSP path.
- **Peak / RMS**: `PeakRmsEngine` gives peak a near-instant attack and a slower release (unlike the
  VU meter's symmetric ballistics), so it actually catches transients, plus a hold cap that latches
  and slowly falls. RMS uses the same ~300ms window as the VU meter. The gap between them, the
  crest factor, is a genuinely useful number: wide means dynamic, narrow means compressed/limited.
- **Tonal Balance**: `TonalBalanceEngine` smooths the same bands as Spectrum with a multi-second
  time constant instead of a fast one, so it settles into overall tonal character rather than
  reacting to transients.

Every engine is stepped every frame regardless of which mode is showing, so tapping to switch shows
a live reading immediately instead of a frozen one. The app also requests a 120Hz window refresh
rate on displays that support it (Android ties refresh rate to the whole window, not to individual
views, so this benefits every mode).

## Building

Open `NeverSpiral/` in Android Studio, or from the command line:

```
cd NeverSpiral
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android-build.yml`) builds the debug APK on every push touching this
directory and uploads it as a workflow artifact named `never-spiral-debug-apk`.

# Sam's Visualizer

Eight audio-reactive visualizer modes, styled as a modern dark mastering-suite instrument panel --
flat near-black panels, thin hairline dividers, a cool desaturated accent, and red reserved strictly
for clip/overload warnings, the way studio metering looks. All driven by whatever music is playing
on the device: Spotify, YouTube Music, or anything else.

## Navigation

- **Tap** anywhere on the visualization to crossfade to the next mode.
- **Swipe** left or right to move either direction, for when the mode you want is behind you.
- **Long-press** to open a picker grid and jump straight to any of the 8 modes.
- A small dot row along the bottom shows which of the 8 modes you're on.
- Five modes (VU Meter, Spectrum, Waveform, Goniometer, Loudness) have their own tunable
  settings behind a gear icon in the top-right corner; every setting persists across app restarts.
- A hamburger icon (top-right, above the visualizer) opens app-wide Settings -- see below.

- **VU Meter**: an analog needle meter with correctly calibrated ballistics (not a fake wobble), a
  digital dB readout alongside the needle, and a peak LED that hard-flashes to full brightness the
  instant it hits the top of the scale, then decays -- like a real hardware peak indicator, not a
  soft continuous pulse. *Settings: calibration reference (12-24 dBFS).*
- **Spectrum**: a real-time FFT bar spectrum, log-spaced across the audible range, with a dB
  reference grid and frequency labels for orientation across the range. *Settings: Cool (blue-to-
  white) or Classic (green-yellow-red) color scheme.*
- **Waveform**: a classic linear waveform -- the amplitude envelope mirrored symmetrically top and
  bottom around a horizontal centerline and filled solid in a warm cream tone, like a track
  waveform in an audio editor. *Settings: Scale, Stroke Weight, Intensity, Afterglow.*
- **Goniometer**: a stereo phase scope -- plots left/right on the mid/side axes, so mono content
  collapses to a vertical line and phase problems fan out sideways -- plus a running phase
  correlation readout. *Settings: trail persistence.*
- **Loudness**: a BS.1770-style LUFS meter (momentary, short-term, integrated, plus loudness
  range) against a selectable normalization target, alongside a scrolling history trend. *Settings:
  target standard (Streaming -14, Apple Music -16, EBU R128 -23).*
- **Graphic EQ**: a classic discrete-LED equalizer bank -- the kind of spectrum display built into
  receivers and separates -- with per-band peak-hold segments and the same dB/frequency axes as
  Spectrum, built on the same FFT bands.
- **Peak / RMS**: a hardware-style dual bar meter (fast peak with a hold cap, next to RMS) with a
  crest-factor readout -- the gap between the two shows how dynamic or compressed a master is.
- **Tonal Balance**: a long-averaged spectral curve against a dashed reference curve tracking the
  same bands with a much longer time constant, showing whether what's playing right now trends
  brighter/darker/bassier than the last minute or so, rather than a comparison to an arbitrary line.

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
colors each bar per the selected `SpectrumColorScheme` (a cool blue-to-cyan-to-white gradient, or a
classic green-to-red one -- red is always reserved for the clip zone right at the top rather than
spread across the whole range), and draws a dB reference grid plus frequency labels (60Hz, 250Hz,
1kHz, 4kHz, 16kHz) for orientation.

## How the waveform view works

`WaveformEngine` builds a *static* amplitude envelope snapshot the way a track-overview waveform
looks, not a scrolling scope trace: each of 40 columns tracks the peak absolute amplitude seen in
its slice of a fixed ~2 second window, but instead of shifting older columns left as new ones
arrive, a full window's worth of columns is accumulated silently off to the side and the whole
visible shape is swapped in at once when it's ready -- so it holds still and only jumps to a new
still shape periodically, rather than continuously scrolling. Each committed column is also
normalized against a slowly-decaying recent-peak reference (instant attack, ~3.5s release), so
typical, consistently loud passages read as modest levels and only genuine accents approach full
height. `WaveformScreen` renders this as a discrete, static audio-progress-bar -- not a continuous
line: each column is its own isolated diamond, only drawn at all once its level clears a visibility
threshold, sitting on a flat baseline with a visible gap to its neighbors on either side, the way a
podcast or voice-message scrubber looks, rather than one smoothed path stitched across every
column. A star-shaped playhead sweeps left to right across whichever static map is currently
showing (driven by how far into the *next*, still-accumulating window capture has gotten, so it
resets right as a new map commits), recoloring the diamonds it has passed gold and leaving the rest
a faint beige. Diamonds and the playhead are rendered into a persistent off-screen bitmap that's
faded (not cleared) every frame, which drives both the soft glow around each shape (a blurred
duplicate drawn first) and the fade between one static map and the next. A gear icon shown only in
waveform mode opens a settings panel with Scale, Stroke Weight, Intensity, and Afterglow sliders.

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

Every engine is stepped every frame regardless of which mode is showing, so switching among most
modes shows a live reading immediately instead of a frozen one -- the two exceptions are Loudness
(two IIR K-weighting filters run over every sample in every buffer) and Goniometer (a per-sample
correlation sum), the heaviest per-sample work in the app, which only run while their own screen is
actually visible; both settle back to a live reading within their own ballistic time constant
(under a second) after switching back. The app also requests a 120Hz window refresh rate on
displays that support it (Android ties refresh rate to the whole window, not to individual views,
so this benefits every mode).

## App Settings

Distinct from each visualizer mode's own gear-icon tuning panel, the hamburger icon opens an
app-wide settings screen:

- **Players**: Sam's Visualizer already captures whatever's playing system-wide -- Spotify,
  YouTube Music, Tidal, anything -- with no account, API key, or per-app setup. This section is
  just quick-launch shortcuts to jump straight to those apps (or their Play Store listing if not
  installed); it deliberately does *not* do OAuth account linking, since that wouldn't improve the
  visualizer and would mean embedding API credentials in the app for no real benefit.
- **Display**: a "Keep Screen On" toggle. Android doesn't let third-party apps change the system
  screen-timeout duration directly (that needs the sensitive `WRITE_SETTINGS` permission), so this
  uses `View.keepScreenOn` -- the standard, non-invasive way to prevent sleep while the app is open.
- **Updates**: a GitHub-Releases-based OTA update path, the same pattern F-Droid-style apps use
  outside the Play Store. `UpdateChecker` queries the repo's latest release via GitHub's public
  REST API, and "Download & Install" fetches the attached APK through `DownloadManager` and hands
  it to the system installer (prompting for the one-time "install unknown apps" permission if not
  already granted). This only works while the repo is public -- an unauthenticated request to a
  private repo's releases API returns 404/403, so a failed check just says so rather than crashing.
  An "Check Automatically" toggle runs the same check silently once when Settings opens.
- **About**: version number and "vibe coded with love by Samuel Nicholas Salvador/Veera Krishnan."

## Building

Open `NeverSpiral/` in Android Studio, or from the command line:

```
cd NeverSpiral
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android-build.yml`) builds the debug APK on every push touching this
directory and uploads it as a workflow artifact named `never-spiral-debug-apk`.

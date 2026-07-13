# Sam's Visualizer

Nine audio-reactive visualizer modes, styled as a modern dark mastering-suite instrument panel --
flat near-black panels, thin hairline dividers, a cool desaturated accent, and red reserved strictly
for clip/overload warnings, the way studio metering looks. All driven by whatever music is playing
on the device: Spotify, YouTube Music, or anything else.

## Navigation

- **Tap** anywhere on the visualization to crossfade to the next mode.
- **Swipe** left or right to move either direction, for when the mode you want is behind you.
- **Long-press** to open a picker grid and jump straight to any visible mode.
- A small dot row along the bottom shows which mode you're on, among however many are visible.
- Six modes (VU Meter, Spectrum, Goniometer, Loudness, Rainbow Spectrum, Neon Cyan Pulse) have
  their own tunable settings behind a gear icon in the top-right corner; every setting persists
  across app restarts.
- A hamburger icon (top-right, above the visualizer) opens app-wide Settings -- see below. Its
  **Modes** section lets you hide modes you don't use and reorder the rest; tap/swipe cycling, the
  long-press picker, and the dot row all follow that customized order (`ModePreferences`, backed
  by `SettingsStore`). There are nine modes total when nothing's hidden.

- **VU Meter**: an analog needle meter with correctly calibrated ballistics (not a fake wobble), a
  digital dB readout alongside the needle, and a peak LED that hard-flashes to full brightness the
  instant it hits the top of the scale, then decays -- like a real hardware peak indicator, not a
  soft continuous pulse. *Settings: calibration reference (12-24 dBFS).*
- **Spectrum**: a real-time FFT bar spectrum, log-spaced across the audible range, with a dB
  reference grid and frequency labels for orientation across the range. *Settings: Cool (blue-to-
  white) or Classic (green-yellow-red) color scheme.*
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
- **Rainbow Spectrum**: a mirrored FFT bar spectrum -- bars reflect top and bottom off a horizontal
  center axis instead of growing from the bottom only -- with a fixed horizontal rainbow gradient
  (blue/purple through magenta and orange to yellow) and a soft glow, on pure black. *Settings:
  Scale, Stroke Weight, Height.*
- **Neon Cyan Pulse**: the same mirrored-bar idea, denser and thinner, with a stronger glow for a
  nightclub LED-wall feel. Frequency-reactive color: each bar rests at cyan when quiet and blends
  toward a color keyed to its own frequency band as its level rises -- bass flashes red, low-mid
  orange, mids yellow-green, presence stays cyan, treble goes violet -- rather than one flat color
  across the whole row. *Settings: Scale, Stroke Weight, Height.*

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
- **Glow**: the needle gets a soft blurred glow behind it, drawn as a single blurred copy of just
  the needle composited before the crisp native needle/panel/ticks/readout -- the same
  single-bitmap-blur technique described under Rainbow Spectrum and Neon Cyan Pulse below.

## How the spectrum view works

`SpectrumAnalyzer` runs a plain iterative radix-2 FFT (1024-point, Hann-windowed) on the same
captured PCM buffers, buckets the result into 28 log-spaced bands from 40Hz to 16kHz, and
`SpectrumEngine` smooths each band with a fast rise / slower fall filter plus a peak-hold cap --
so bars react instantly to transients but settle down smoothly instead of jittering. `SpectrumScreen`
colors each bar per the selected `SpectrumColorScheme` (a cool blue-to-cyan-to-white gradient, or a
classic green-to-red one -- red is always reserved for the clip zone right at the top rather than
spread across the whole range), and draws a dB reference grid plus frequency labels (60Hz, 250Hz,
1kHz, 4kHz, 16kHz) for orientation. Bars get the same single-composited-bitmap blur glow described
under Rainbow Spectrum and Neon Cyan Pulse below, drawn once behind the crisp bars/grid/labels
rather than blurred individually.

FFT band levels are properly normalized by FFT size before being converted to dB (see
`SpectrumAnalyzer.computeBands`) -- without that division, raw magnitude scales with FFT size, so
every band read pinned near the 0dB ceiling regardless of what was actually playing. That bug had
been present since Spectrum was first built; it just never produced an obviously-wrong picture
until other modes started depending on genuine per-band contrast to work at all.

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
  rendering treatment of already-proven data rather than a new capture or DSP path. Lit segments
  (unlit ones are skipped) get the same single-composited-bitmap blur glow as the other bar-based
  modes.
- **Peak / RMS**: `PeakRmsEngine` gives peak a near-instant attack and a slower release (unlike the
  VU meter's symmetric ballistics), so it actually catches transients, plus a hold cap that latches
  and slowly falls. RMS uses the same ~300ms window as the VU meter. The gap between them, the
  crest factor, is a genuinely useful number: wide means dynamic, narrow means compressed/limited.
- **Tonal Balance**: `TonalBalanceEngine` smooths the same bands as Spectrum with a multi-second
  time constant instead of a fast one, so it settles into overall tonal character rather than
  reacting to transients.
- **Rainbow Spectrum** and **Neon Cyan Pulse**: `RainbowSpectrumScreen` and `NeonCyanPulseScreen`
  both render the same [SpectrumEngine] bands directly, with no engine or DSP of their own -- the
  mirrored top/bottom reflection, colors, bar density, and glow are pure rendering choices on
  already-smoothed data, tunable through their own `BarSpectrumSettings` (Scale, Stroke Weight,
  Height). Neon Cyan Pulse's denser row comes from linearly interpolating between adjacent bands
  rather than a higher-resolution FFT. Its color is frequency-reactive rather than fixed: a bar's
  position in the row stands in for its frequency band (bands are laid out log-spaced low-to-high),
  so `frequencyZoneColor` (in `GradientColors.kt`) maps that position to a color -- red for bass
  through violet for treble -- and each bar blends from resting cyan toward its zone color as its
  own level rises, rather than every bar sharing one flat gradient. Both modes draw all bars solid
  into one bitmap, then blur *that
  whole composited layer once* for the glow, rather than blurring each bar individually --
  `BlurMaskFilter`'s cost is dominated by per-call overhead, so one blur pass over the full row is
  far cheaper than 28-56 separate ones while looking effectively identical, which is what keeps
  both modes smooth on mid-range devices. (The bitmap itself, cleared rather than faded each frame,
  exists purely so `BlurMaskFilter` has a software canvas to blur against -- Android silently
  ignores mask filters on Compose's hardware-accelerated canvas, the same reason Goniometer's
  trail goes through a bitmap.) Bar count, sensitivity gamma, and glow radius/alpha for both are
  named constants at the top of each file.

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
- **Modes**: hide modes you don't use, and reorder the rest via up/down arrows next to each one
  (drag-to-reorder felt riskier on a touchscreen than arrows for a list this short). At least one
  mode always stays visible. Persisted through `ModePreferences` as an ordered mode-name list plus
  a hidden set, so a future app update that adds a new mode just appends it to the end rather than
  losing the user's customization.
- **Updates**: a GitHub-Releases-based OTA update path, the same pattern F-Droid-style apps use
  outside the Play Store. `UpdateChecker` queries the repo's latest release via GitHub's public
  REST API, and "Download & Install" fetches the attached APK through `DownloadManager` and hands
  it to the system installer (prompting for the one-time "install unknown apps" permission if not
  already granted). This only works while the repo is public -- an unauthenticated request to a
  private repo's releases API returns 404/403, so a failed check just says so rather than crashing.
  An "Check Automatically" toggle runs the same check silently once when Settings opens.
- **About**: version number and "vibe coded with love by Samuel Nicholas Salvador/Veera Krishnan."

## App icon

A proper Android adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`, layering
`drawable/ic_launcher_background` and `ic_launcher_foreground`) rather than a single flat drawable
-- a plain flat icon gets wrapped in a launcher-synthesized white circle with the whole icon shrunk
to fit inside it, which is why it used to show up as a small square VU gauge floating in an
unrelated white circle. The foreground is a circular gauge face (ticks, needle, pivot) sized to sit
within the adaptive icon's 66dp safe zone, so it reads as an intentional circular badge and isn't
clipped regardless of which mask shape (circle, squircle, rounded square) a given launcher uses.
`mipmap/ic_launcher.xml` (no density qualifier) is a flattened fallback for API 24-25, which can't
load the adaptive icon format.

## Testing

Every engine (`VuMeterEngine`, `SpectrumEngine`, `SpectrumAnalyzer`, `PeakRmsEngine`,
`GoniometerEngine`, `LoudnessEngine`, `TonalBalanceEngine`) is plain Kotlin with no Android
framework calls, so their ballistics/DSP math has plain-JVM JUnit coverage under
`app/src/test/java/` -- no Robolectric or emulator needed. `SpectrumAnalyzerTest` in particular is
a regression guard for the FFT-magnitude-normalization bug described above: without dividing raw
FFT magnitude back down by `FFT_SIZE` before the dB conversion, every band reads pinned near the
ceiling regardless of what's actually playing.

```
cd NeverSpiral
./gradlew testDebugUnitTest
```

## Building

Open `NeverSpiral/` in Android Studio, or from the command line:

```
cd NeverSpiral
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android-build.yml`) runs the unit tests and builds the debug APK on every
push touching this directory, uploading it as a workflow artifact named `never-spiral-debug-apk`
and publishing it as a GitHub Release and to the `never-spiral-apk-builds` branch. It intentionally
does *not* also trigger on `pull_request` -- since this branch is developed via an always-open PR,
a `pull_request` trigger would fire a second, redundant build for the same commit as the `push`
trigger on every push, doubling CI time and racing the two runs to force-push the same APK to
`never-spiral-apk-builds`.

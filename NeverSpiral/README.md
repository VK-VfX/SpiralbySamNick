# Sam's Music Viz

Eleven audio-reactive visualizer modes, styled as a modern dark mastering-suite instrument panel --
flat near-black panels, thin hairline dividers, a cool desaturated accent, and red reserved strictly
for clip/overload warnings, the way studio metering looks. All driven by whatever music is playing
on the device: Spotify, YouTube Music, or anything else.

## Navigation

- A persistent, horizontally-scrollable strip of mode chips below the visualizer is the primary
  way to switch -- tap the specific mode you want directly, rather than repeatedly tapping the
  canvas to cycle through them one at a time. The strip auto-scrolls to keep the current mode's
  chip in view.
- **Swipe** left or right on the visualization to move either direction, for quick cycling without
  looking down at the strip.
- Nine modes (VU Meter, Spectrum, Goniometer, Loudness, Graphic EQ, Rainbow Spectrum, Neon Cyan
  Pulse, Circular Spectrum, Radial Pulse Ring) have their own tunable settings behind a gear icon
  in the top-right corner; every setting persists across app restarts.
- A hamburger icon (top-right, above the visualizer) opens app-wide Settings -- see below. Its
  **Modes** section lets you hide modes you don't use and reorder the rest; both the mode strip and
  swipe cycling follow that customized order (`ModePreferences`, backed by `SettingsStore`). There
  are eleven modes total when nothing's hidden.
- **Landscape** only works for Spectrum, Rainbow Spectrum, and Neon Cyan Pulse -- the modes that
  actually gain something from the extra width. Every other mode (a circular gauge, a radial
  layout, a scrolling history trend, and so on) is locked back to portrait the instant it's
  selected, via `Activity.requestedOrientation` set per mode in `MainScreen`, not a single
  manifest-wide lock. The manifest's `configChanges="orientation|screenSize"` means switching
  never recreates the Activity or interrupts capture, it just physically rotates the display.

- **VU Meter**: an analog needle meter with correctly calibrated ballistics (not a fake wobble), a
  digital dB readout alongside the needle, and a peak LED that hard-flashes to full brightness the
  instant it hits the top of the scale, then decays -- like a real hardware peak indicator, not a
  soft continuous pulse. *Settings: calibration reference (12-24 dBFS).*
- **Spectrum**: a real-time FFT bar spectrum, log-spaced across the audible range, with a dB
  reference grid and frequency labels for orientation across the range. *Settings: Cool (blue-to-
  white), Classic (green-yellow-red), or Frequency (Neon Cyan Pulse's frequency-reactive coloring,
  applied to Spectrum's own upward bars) color scheme.*
- **Goniometer**: a stereo phase scope -- plots left/right on the mid/side axes, so mono content
  collapses to a vertical line and phase problems fan out sideways -- plus a running phase
  correlation readout. *Settings: trail persistence.*
- **Loudness**: a BS.1770-style LUFS meter (momentary, short-term, integrated, plus loudness
  range) against a selectable normalization target, alongside a scrolling history trend. *Settings:
  target standard (Streaming -14, Apple Music -16, EBU R128 -23).*
- **Graphic EQ**: a classic discrete-LED equalizer bank -- the kind of spectrum display built into
  receivers and separates -- with per-band peak-hold segments and the same dB/frequency axes as
  Spectrum, built on the same FFT bands. *Settings: Classic (green/amber/red-by-height) or
  Frequency (same frequency-reactive coloring as Spectrum/Neon Cyan Pulse, with the top segment
  always flashing red as a clip warning regardless of scheme) color scheme.*
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
- **Circular Spectrum**: Rainbow Spectrum bent into a ring -- the same bands, the same fixed
  rainbow gradient and glow, but bars grow radially outward from a circle instead of reflecting
  top/bottom off a horizontal axis. Band 0 (bass) starts at 12 o'clock and sweeps clockwise through
  to the highest band. Portrait only -- a circular layout doesn't gain anything from extra width.
  *Settings: Scale, Stroke Weight, Height.*
- **Radial Pulse Ring**: a Specterr-style sunburst -- a thin, perfectly circular ring stays fixed
  at the center (audio never deforms it), while thin needle spikes shoot straight outward from its
  edge, one per angular position, each independently driven by that position's own FFT band. Quiet
  bands barely show past the ring edge; loud ones spike out sharply. The whole spike pattern spins
  slowly and continuously around the fixed ring (a constant degrees-per-second rotation, not
  audio-driven), and a small static music-note glyph sits anchored in the open center, never moving
  or reacting itself. A true 360-degree rainbow hue sweep runs around the circumference (not the
  app's usual non-looping rainbow, which would show a seam on a closed shape) -- ring and spikes
  share one shader, so each spike's color always matches its current position on the wheel as it
  rotates through it. Rendered as a glowing stroked outline with a bright hot edge and a softer
  outer bloom falloff. Portrait only, same reasoning as Circular Spectrum. *Settings: Scale, Stroke
  Weight, Height.*

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
  whichever two ticks bracket the current reading. Tick label font size scales with the meter's own
  rendered height (tracked via `Modifier.onSizeChanged`) rather than staying a fixed sp value --
  the meter renders much smaller in landscape (and on some narrower portrait screens), and a fixed
  font size didn't shrink with it, so the widest labels ("-20", "-10") could crowd or overlap at
  smaller sizes. The scaling fraction is derived from the actual tick-label arc geometry (see
  `VuMeterScreen.kt`'s `TICK_FONT_HEIGHT_FRACTION` doc comment), not just eyeballed.
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
  modes. Its `GraphicEqSettings.colorScheme` picks between the classic height-based coloring and
  the shared frequency-reactive treatment (Neon Cyan Pulse, Spectrum's Frequency scheme) -- in
  that mode the top segment still hard-flashes red as a clip warning regardless of frequency zone.
- **Peak / RMS**: `PeakRmsEngine` gives peak a near-instant attack and a slower release (unlike the
  VU meter's symmetric ballistics), so it actually catches transients, plus a hold cap that latches
  and slowly falls. RMS uses the same ~300ms window as the VU meter. The gap between them, the
  crest factor, is a genuinely useful number: wide means dynamic, narrow means compressed/limited.
- **Tonal Balance**: `TonalBalanceEngine` smooths the same bands as Spectrum with a multi-second
  time constant instead of a fast one, so it settles into overall tonal character rather than
  reacting to transients.
- **Rainbow Spectrum**, **Neon Cyan Pulse**, and **Circular Spectrum**: `RainbowSpectrumScreen`,
  `NeonCyanPulseScreen`, and `CircularSpectrumScreen` all render the same [SpectrumEngine] bands
  directly, with no engine or DSP of their own -- the mirrored/radial layout, colors, bar density,
  and glow are pure rendering choices on already-smoothed data, tunable through their own
  `BarSpectrumSettings` (Scale, Stroke Weight, Height). Neon Cyan Pulse's denser row comes from
  linearly interpolating between adjacent bands rather than a higher-resolution FFT. Neon Cyan
  Pulse's color is frequency-reactive rather than fixed: a bar's position in the row stands in for
  its frequency band (bands are laid out log-spaced low-to-high), so `frequencyZoneColor` (in
  `GradientColors.kt`) maps that position to a color -- red for bass through violet for treble --
  and each bar blends from resting cyan toward its zone color as its own level rises, rather than
  every bar sharing one flat gradient. Circular Spectrum instead reuses Rainbow Spectrum's fixed
  `rainbowColor` gradient exactly, just mapped around the ring by angular position (band 0 at 12
  o'clock, sweeping clockwise) instead of left-to-right. All three draw their bars solid into one
  bitmap, then blur *that
  whole composited layer once* for the glow, rather than blurring each bar individually --
  `BlurMaskFilter`'s cost is dominated by per-call overhead, so one blur pass over the full row is
  far cheaper than 28-56 separate ones while looking effectively identical, which is what keeps
  all three modes smooth on mid-range devices. (The bitmap itself, cleared rather than faded each
  frame, exists purely so `BlurMaskFilter` has a software canvas to blur against -- Android
  silently ignores mask filters on Compose's hardware-accelerated canvas, the same reason
  Goniometer's trail goes through a bitmap.) Bar count, sensitivity gamma, and glow radius/alpha
  for all three are named constants at the top of each file.
- **Radial Pulse Ring**: `RadialPulseRingScreen` also reads [SpectrumEngine]'s bands directly, but
  with its own second smoothing pass on top -- a per-point "push level" (`FloatArray(POINT_COUNT)`)
  that's independently exponentially smoothed frame to frame with a fast attack and a slower decay,
  both expressed as time constants (`ATTACK_TAU_SECONDS`, `DECAY_TAU_SECONDS`) converted through
  real delta time exactly like every engine's own `step(dt, ...)`. This is deliberate: how punchy
  *the spikes themselves* feel is a property of this mode's rendering, distinct from the shared band
  smoothing underneath it that every other mode also reads. `circularInterpolatedBand` maps
  [SpectrumEngine]'s bands onto `POINT_COUNT` angular positions with wraparound interpolation
  (unlike Neon Cyan Pulse's denser row, a closed ring has no start/end edge to clamp against). The
  base circle is drawn once as its own plain `drawCircle` call at a fixed radius that audio data
  never reaches -- it's the one shape in the mode with no per-frame variation at all. Each spike is
  a separate straight `drawLine` from the base circle's edge outward to `baseRadius + pushLevel *
  maxPush`, not a curve blended into a single outline, so quiet bands read as bare ring edge and
  loud ones as a sharp needle rather than a smooth bump. A slow constant rotation
  (`ROTATION_DEGREES_PER_SECOND`, converted through delta time like everything else) is added to
  every spike's angle each frame -- since color is a true closed 360-degree hue wheel
  (`fullHueSweepColors` in `GradientColors.kt`, built from `android.graphics.Color.HSVToColor` at
  even hue steps) applied as one `android.graphics.SweepGradient` shared by the base circle and
  every spike, color is keyed to canvas-space angle rather than band index, so the rotation alone
  makes each spike's hue visibly drift without touching the color logic at all. Unlike
  `RAINBOW_STOPS`, which is a deliberately non-looping gradient tuned for a straight bar row, a
  closed ring needs a gradient that wraps back to its own start with no visible seam. The glow is
  two separate blurred copies of the solid ring+spikes layer composited underneath the crisp one --
  a wide, low-alpha outer pass and a tight, high-alpha inner pass -- for a bright hot edge with a
  softer outer falloff, rather than one uniform blur radius. A small static music-note glyph (a
  filled head, a stem, and a bezier flag, all Compose `drawCircle`/`drawLine`/`drawPath` calls) is
  drawn last, on top of everything else -- it's the one thing in the mode that doesn't touch the
  bitmap/blur pipeline at all, since it never moves and never needs to blur. Base radius, point
  count, amplitude sensitivity gamma, both smoothing time constants, rotation rate, stroke width,
  glow radius/alpha, and the note's size are all named constants at the top of the file.

Every engine is stepped every frame regardless of which mode is showing, so switching among most
modes shows a live reading immediately instead of a frozen one -- the two exceptions are Loudness
(two IIR K-weighting filters run over every sample in every buffer) and Goniometer (a per-sample
correlation sum), the heaviest per-sample work in the app, which only run while their own screen is
actually visible; both settle back to a live reading within their own ballistic time constant
(under a second) after switching back.

### Refresh rate handling

The app reads the display's actual supported modes and requests whichever one has the highest
refresh rate -- the device's true native max (90Hz, 120Hz, 144Hz, whatever it happens to support)
rather than a fixed number that would cap a faster display or do nothing useful on a slower one
(Android ties refresh rate to the whole window, not to individual views, so this benefits every
mode). That request alone is only half the story on a phone that does adaptive/variable refresh
rate -- many drop from, say, 120Hz to 90Hz or 60Hz mid-session to save battery, and the system is
free to override a one-time preference at any point. `MainActivity` registers a
`DisplayManager.DisplayListener` (in `onStart`/`onStop`) that re-requests the fastest mode whenever
the system reports the display actually changed, instead of silently staying stuck at whatever rate
it settled on afterward.

None of the actual animation, smoothing, or decay math anywhere in the app assumes a fixed frame
rate. `MainScreen`'s shared frame loop reads real per-frame delta time from
`withFrameNanos { frameNanos -> ... }` -- the actual Choreographer timestamp, not a fixed step --
and every engine's `step(dtSeconds, ...)` converts that into an exponential-smoothing rate via
`alpha = 1f - exp(-dt / tauSeconds)`, where every `tauSeconds` constant (VU ballistics, spectrum
rise/fall, loudness integration windows, Radial Pulse Ring's attack/decay, and so on) is documented
as a time-based rate, not a flat per-frame multiplier. That's what keeps motion speed and
smoothness consistent whether the device ends up running at 60Hz, 90Hz, or 120Hz -- a higher
refresh rate means more, smaller steps toward the same target over the same wall-clock time, not
faster-looking motion.

## App Settings

Distinct from each visualizer mode's own gear-icon tuning panel, the hamburger icon opens an
app-wide settings screen:

- **Players**: Sam's Music Viz already captures whatever's playing system-wide -- Spotify,
  YouTube Music, Tidal, anything -- with no account, API key, or per-app setup. This section is
  just quick-launch shortcuts to jump straight to those apps (or their Play Store listing if not
  installed); it deliberately does *not* do OAuth account linking, since that wouldn't improve the
  visualizer and would mean embedding API credentials in the app for no real benefit.
- **Display**: a "Keep Screen On" toggle (`View.keepScreenOn` -- Android doesn't let third-party
  apps change the system screen-timeout duration directly, that needs the sensitive
  `WRITE_SETTINGS` permission) and an "Immersive Mode" toggle that hides the status/navigation bars
  while the visualizer is running (`WindowInsetsControllerCompat`, with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` so a swipe from an edge still reveals them temporarily).
  The app also isn't locked to portrait anymore -- it follows whatever the device's own
  rotation-lock setting allows, rather than forcing one orientation.
- **Appearance**: a custom accent color, expressed as Hue/Saturation/Brightness sliders (via
  `android.graphics.Color.HSVToColor`) rather than RGB so three sliders cover the whole range, with
  a live preview swatch. `VisualizerTheme.ACCENT` is mutable Compose state rather than a fixed
  constant specifically so this can override it -- and since nearly every mode already reads
  `ACCENT` (chips, the VU needle's glow, Spectrum's Cool and Frequency schemes, Graphic EQ's lit
  segments, and more), one custom color cascades across the whole app instead of needing a picker
  per mode. `ACCENT_DIM` derives from `ACCENT` rather than being independent, so it stays coherent
  with whatever's picked.
- **Modes**: hide modes you don't use, and reorder the rest via up/down arrows next to each one
  (drag-to-reorder felt riskier on a touchscreen than arrows for a list this short). At least one
  mode always stays visible. Persisted through `ModePreferences` as an ordered mode-name list plus
  a hidden set, so a future app update that adds a new mode just appends it to the end rather than
  losing the user's customization.
- **Updates**: a GitHub-Releases-based OTA update path, the same pattern F-Droid-style apps use
  outside the Play Store. `UpdateChecker` queries the repo's latest release via GitHub's public
  REST API, and "Download & Install" fetches the attached APK through `DownloadManager` into the
  app's private external-files directory, then hands it to the system installer via a `FileProvider`
  content URI (declared in the manifest, paths in `res/xml/file_paths.xml`) -- `DownloadManager`'s
  own `getUriForDownloadedFile()` is built for the public Downloads collection and returns an
  unusable URI for a private-directory download, which is why installs could silently do nothing.
  The download wait is capped at two minutes rather than polling forever, a stale file from a
  previous attempt is cleared before retrying, and a failed/timed-out download surfaces a "Try
  Again" state instead of quietly resetting as if nothing happened. This only works while the repo
  is public -- an unauthenticated request to a private repo's releases API returns 404/403, so a
  failed check just says so rather than crashing. An "Check Automatically" toggle runs the same
  check silently once when Settings opens. `UpdateChecker.isNewerThanInstalled` compares the
  release's version against the installed app's `versionName` component-by-component, so a release
  that matches what's already installed correctly shows "You're on the latest version" instead of
  always offering to reinstall the same build -- that comparison only works because the CI workflow
  tags each release `v<versionName>` (extracted straight from `build.gradle.kts`) instead of the
  old run-number-based `build-<N>` tag, which had no relationship to the app's actual version at all.
  A downloaded update also has to actually be *installable* over the running app: Android refuses
  to install a package signed with a different key than the one already on the device. `app/build.gradle.kts`
  used to declare no explicit `signingConfigs`, so Gradle fell back to its own default debug config
  -- which auto-generates `~/.android/debug.keystore` the first time it's needed. GitHub Actions
  runners are fresh on every run with no such keystore lying around, so each CI build was getting
  signed with a brand-new, different key, and every OTA update silently failed to apply, leaving
  users to uninstall and reinstall manually. `app/debug.keystore` is now a stable keystore committed
  to the repo, referenced by an explicit `signingConfigs.debug` block, so every build (CI or local)
  signs with the same key from here on -- note this means the *first* update after this fix still
  needs a manual reinstall (the previously-installed build used a throwaway key), but every update
  after that installs in place normally.
- **Diagnostics**: the most recent uncaught exception, if any -- `VisualizerApplication` installs a
  custom `Thread.UncaughtExceptionHandler` that writes the crash's stack trace to a local file
  (`CrashLog`) before re-raising to the system default handler, so the app still crashes normally,
  it just leaves a note behind first. There's no crash-reporting backend, so during solo on-device
  testing this is the only way to see what actually broke after the app dies and relaunches.
- **About**: version number and "vibe coded with love by Samuel Nicholas Salvador/Veera Krishnan."

## Home screen widget & Quick Settings tile

Both are tap-to-open shortcuts rather than live mini-visualizers or remote controls, for the same
underlying reason: `RemoteViews` (what both widgets and notifications render through) can't host a
live Compose `Canvas`, and starting capture requires [`MediaProjection`](https://developer.android.com/reference/android/media/projection/MediaProjection)'s
system "start recording or casting" consent dialog, which needs a foreground `Activity` to show and
isn't persisted across app restarts -- so neither surface can silently start visualizing on its own.
- **Widget** (`VisualizerWidgetProvider`): a branded `RemoteViews` layout that opens the app on tap.
  The bar icon still animates, though: `widget_launcher.xml` uses a `ViewFlipper` with
  `autoStart="true"` cycling through four bar-height frames (`ic_widget_bars_1..4`). This isn't a
  push-updated bitmap or a background service -- once the widget host (the launcher) inflates the
  layout, `ViewFlipper` drives its own flip loop entirely inside the launcher's own process, so it
  keeps animating with zero ongoing cost to our app, whether or not it's even running.
- **Quick Settings tile** (`VisualizerTileService`): reflects whether capture is currently running
  via `AudioCaptureService.isRunning` (a small Compose-state flag set in `onStartCommand`/`onDestroy`
  specifically so the tile can read it without binding to the service), and opens the app on tap.
  Unlike the widget, the tile's icon can't animate -- `Tile.icon` is a single static
  `android.graphics.drawable.Icon`, and `TileService`'s API has no `RemoteViews`-style layout or
  frame-cycling mechanism to hook into; it's a fixed system-rendered icon/label/subtitle/state
  format, not an inflatable view hierarchy. There's no legitimate way around that within the public
  API, so the tile's icon just stays static.

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

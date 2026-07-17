# Sam's Music Viz

Ten audio-reactive visualizer modes, styled as a modern dark mastering-suite instrument panel --
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
- Every mode has its own tunable settings behind a gear icon in the top-right corner; every
  setting persists across app restarts. Eight modes share the same three underlying sliders
  (`BarSpectrumSettings`: amplitude scale, stroke/line weight, and a size/reach control), but each
  is labeled for what it actually does in that mode (e.g. "Sensitivity"/"Line Thickness"/"Max
  Amplitude" for Lava Waveform) rather than a generic "Scale/Stroke Weight/Height" everywhere --
  see `BarSpectrumSettingsPanel` in `MainScreen.kt`. The rest have their own controls entirely
  (VU Meter's calibration reference, Spectrum's color-scheme choice).
- A hamburger icon (top-right, above the visualizer) opens app-wide Settings -- see below. Its
  **Modes** section lets you hide modes you don't use and reorder the rest; both the mode strip and
  swipe cycling follow that customized order (`ModePreferences`, backed by `SettingsStore`). There
  are ten modes total when nothing's hidden.
- **Landscape** works for Spectrum, Rainbow Spectrum, and Neon Cyan Pulse -- the modes that
  actually gain something from the extra width. Every other mode is a centered radial/point
  composition rather than a horizontal layout, so it's locked back to portrait the instant it's
  selected, via `Activity.requestedOrientation` set per mode in `MainScreen`, not a single
  manifest-wide lock. The manifest's `configChanges="orientation|screenSize"` means switching never
  recreates the Activity or interrupts capture, it just physically rotates the display.

- **VU Meter**: an analog needle meter with correctly calibrated ballistics (not a fake wobble), a
  digital dB readout alongside the needle, and a peak LED that hard-flashes to full brightness the
  instant it hits the top of the scale, then decays -- like a real hardware peak indicator, not a
  soft continuous pulse. *Settings: calibration reference (12-24 dBFS).*
- **Spectrum**: a real-time FFT bar spectrum, log-spaced across the audible range, with a dB
  reference grid and frequency labels for orientation across the range. *Settings: Cool (blue-to-
  white), Classic (green-yellow-red), or Frequency (Neon Cyan Pulse's frequency-reactive coloring,
  applied to Spectrum's own upward bars) color scheme.*
- **Rainbow Spectrum**: a mirrored FFT bar spectrum -- bars reflect top and bottom off a horizontal
  center axis instead of growing from the bottom only -- with a fixed horizontal rainbow gradient
  (blue/purple through magenta and orange to yellow) and a soft glow, on pure black. *Settings:
  Sensitivity, Bar Thickness, Bar Height.*
- **Neon Cyan Pulse**: the same mirrored-bar idea, denser and thinner, with a stronger glow for a
  nightclub LED-wall feel. Frequency-reactive color: each bar rests at cyan when quiet and blends
  toward a color keyed to its own frequency band as its level rises -- bass flashes red, low-mid
  orange, mids yellow-green, presence stays cyan, treble goes violet -- rather than one flat color
  across the whole row. *Settings: Sensitivity, Bar Thickness, Bar Height.*
- **Kaleidoscope Bloom**: an ornamental, rotationally-symmetric mandala rather than a meter, a
  scatter, or a scrolling trend. One petal shape is built from the spectrum and mirrored across its
  own center line for a genuine "one wedge, reflected" kaleidoscope look, then that single symmetric
  petal is repeated 8 times around a slow, continuously rotating circle. Colored with a true closed
  360-degree rainbow hue wheel centered on the bloom, so the whole rainbow visibly rotates along with
  the pattern. *Settings: Sensitivity, Petal Thickness, Bloom Size.*
- **Lava Waveform**: a single jagged line tracing the raw captured audio waveform itself, the
  literal oscilloscope look, rather than a treatment of FFT band levels like most other modes.
  Colored with a full rainbow hue sweep along the line's length that continuously, slowly rotates
  over time, so the color genuinely flows like molten lava instead of sitting in fixed frequency
  zones -- louder moments along the trace glow hotter/brighter than quiet ones, rather than the
  whole line staying one flat brightness. *Settings: Sensitivity, Line Thickness, Max Amplitude.*
- **White Waveform**: the same raw waveform trace as Lava Waveform, but filled rather than
  stroked, and plain white rather than color-driven -- the region between the trace and its center
  axis is filled solid, reading as a soft glowing silhouette rather than a wire outline, with a
  thin crisp white edge on top of the fill. *Settings: Sensitivity, Outline Thickness, Max
  Amplitude.*
- **Shadow Waveform**: also the same raw waveform trace as Lava/White Waveform, but drawn twice --
  the live trace at full brightness, plus a second "shadow" trace holding whatever it looked like a
  moment ago, dimmer and drawn first so the live trace sits on top of it. Colored with a single
  user-picked hue (`ColorWheelPicker`, see below) rather than Lava Waveform's rotating rainbow.
  *Settings: Sensitivity, Line Thickness, Max Amplitude, plus a color wheel.*
- **Dot Spectrum**: a mirrored FFT bar spectrum where each band's level is a column of small
  stacked dots rather than one continuous bar -- a dot-matrix/LED-cluster texture. Also a single
  user-picked hue. *Settings: Sensitivity, Dot Size, Bar Height, plus a color wheel.*
- **Skyline Spectrum**: an FFT bar spectrum, growing from the bottom edge only (not mirrored),
  where each bar is its own vertical gradient -- the full picked color at the tip fading down to
  near-black at the base, a shaded-segment look rather than one flat fill. Unlike every other bar
  mode, quiet bands are never floored to a minimum height, so genuine gaps of silence appear on
  their own between bursts of activity. *Settings: Sensitivity, Bar Thickness, Bar Height, plus a
  color wheel.*

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

Every mode past Spectrum reads [SpectrumEngine]'s bands directly, with no dedicated engine or
capture path of its own -- each is a different rendering treatment of the exact same already-smoothed
data, tunable through its own `BarSpectrumSettings` (three underlying sliders, labeled per mode in
its gear panel rather than a generic "Scale/Stroke Weight/Height"). Lava Waveform, White Waveform,
and Shadow Waveform are the exceptions -- see their own paragraph below. Shadow Waveform, Dot
Spectrum, and Skyline Spectrum also each pair a `BarSpectrumSettings` with a `CustomColorSettings`
for a user-picked fill color -- see "How the color wheel works" further down.

- **Rainbow Spectrum** and **Neon Cyan Pulse**: `RainbowSpectrumScreen` and `NeonCyanPulseScreen`
  are pure rendering choices on `SpectrumEngine`'s bands -- the mirrored layout, colors, bar
  density, and glow are the only differences between them. Neon Cyan Pulse's denser row comes from
  linearly interpolating between adjacent bands rather than a higher-resolution FFT. Its color is
  frequency-reactive rather than fixed: a bar's position in the row stands in for its frequency band
  (bands are laid out log-spaced low-to-high), so `frequencyZoneColor` (in `GradientColors.kt`) maps
  that position to a color -- red for bass through violet for treble -- and each bar blends from
  resting cyan toward its zone color as its own level rises, rather than every bar sharing one flat
  gradient. Both draw their bars solid into one bitmap, then blur *that whole composited layer once*
  for the glow, rather than blurring each bar individually -- `BlurMaskFilter`'s cost is dominated by
  per-call overhead, so one blur pass over the full row is far cheaper than 28-56 separate ones while
  looking effectively identical. (The bitmap itself, cleared rather than faded each frame, exists
  purely so `BlurMaskFilter` has a software canvas to blur against -- Android silently ignores mask
  filters on Compose's hardware-accelerated canvas.) Bar count, sensitivity gamma, and glow
  radius/alpha are named constants at the top of each file.
- **Kaleidoscope Bloom**: `KaleidoscopeBloomScreen` builds one petal's worth of radius data each
  frame -- a straight sweep across the spectrum condensed into half a petal's angular width, each
  point independently smoothed with the usual fast-attack/slower-decay pattern -- then mirrors it
  across its own center line before drawing anything, so the petal is bilaterally symmetric by
  construction rather than needing separate left/right logic. That single symmetric petal's radius
  profile is computed once per frame and reused for all 8 rotated copies (`SYMMETRY_COUNT`), not
  recomputed per copy. A slow constant rotation (`ROTATION_DEGREES_PER_SECOND`, delta-time based
  like every rate in this app) is added to every copy's placement angle each frame. Color is one
  `SweepGradient` built from a true closed 360-degree hue wheel (`fullHueSweepColors` in
  `GradientColors.kt`, the same technique Radial Pulse Ring used earlier) centered on the bloom --
  since the shader is keyed to canvas-space angle rather than petal-local position, the whole
  rainbow visibly rotates along with the pattern with no color logic of its own to keep in sync.
- **Lava Waveform**, **White Waveform**, and **Shadow Waveform** all read raw PCM directly from
  `AudioAnalyzer.snapshots.value.waveform` rather than `SpectrumEngine`'s FFT bands -- a genuine
  time-domain trace instead of a frequency-domain one. That raw mono waveform has been captured and
  published by `AudioCaptureService` since the original Oscilloscope/Waveform Ribbon modes existed,
  but nothing consumed it after those were removed; Lava Waveform was the first live reader again.
  Each frame, all three modes decimate the freshest buffer to a fixed `POINT_COUNT` (160) display
  points via the shared `decimateWaveform` (`WaveformDecimation.kt`) by taking the signed sample of
  *largest magnitude* within each bucket, not an average -- an average would smear out exactly the
  sharp transients a waveform trace exists to show. Points are connected with straight segments and
  deliberately not smoothed frame-to-frame, unlike every band-driven mode's fast-attack/slow-decay
  filtering -- each frame's buffer is genuinely different audio content, not a continuous quantity
  that benefits from easing toward a new target.
  - **Lava Waveform** (`LavaWaveformScreen`): color is a full rainbow hue sweep along the line's
    length, continuously rotating over time (`HUE_ROTATION_DEGREES_PER_SECOND`, delta-time based
    like Kaleidoscope Bloom's rotation) via `android.graphics.Color.HSVToColor` computed per point
    rather than a fixed shader, with each point's own amplitude independently boosting its
    brightness -- louder moments glow hotter, and even silence still glows dimly
    (`LAVA_BASE_BRIGHTNESS`) rather than going fully dark, the way real lava never looks black.
  - **White Waveform** (`WhiteWaveformScreen`): filled rather than stroked, and plain white rather
    than color-driven. A single closed `android.graphics.Path` runs from `(0, centerY)` through
    every trace point and back to `(width, centerY)` -- Android's default nonzero winding fill rule
    renders that correctly as a series of filled humps above and below center even though the path
    crosses the axis many times, without needing separate top/bottom sub-paths. A thin white
    outline stroke of the same path on top keeps the silhouette's edge crisp rather than a soft
    blurred blob.
  - **Shadow Waveform** (`ShadowWaveformScreen`): draws the decimated points twice per frame rather
    than once -- the live trace at full brightness/alpha, and a second "shadow" trace holding
    whatever the live trace looked like `SHADOW_CAPTURE_INTERVAL_SECONDS` ago (a plain
    `FloatArray.copyInto` snapshot on a fixed timer, not a rolling multi-frame history), drawn first
    and dimmer so the live trace sits on top of it. Both traces share the same single user-picked
    hue via `ColorWheelPicker`, differing only in brightness and alpha, rather than Lava Waveform's
    rotating rainbow.
- **Dot Spectrum** and **Skyline Spectrum** are both single-hue variants of the FFT bar spectrum,
  colored via a user-picked `CustomColorSettings` rather than fixed or frequency-reactive -- see
  "How the color wheel works" below for the picker itself.
  - **Dot Spectrum** (`DotSpectrumScreen`): each band's level becomes a column of small filled
    circles marching outward from the mirror axis (mirrored top and bottom) rather than one
    continuous stroke -- a dot-matrix texture, spaced by a fixed `DOT_SPACING_FRACTION`. Color is
    the same picked hue for every dot; the column heights' own live variation, driven entirely by
    the real audio, is what gives the row its shape -- no artificial per-position fade layered on
    top.
  - **Skyline Spectrum** (`SkylineSpectrumScreen`): each bar is its own `LinearGradient`, the full
    picked color at the tip fading to `BASE_BRIGHTNESS_FRACTION` of it (near-black) at the base --
    a shaded-segment look per bar, rather than every mode's flat single-color fill. Unlike every
    other bar mode, a bar's height is never floored to a minimum -- a genuinely silent band draws
    at zero height and is skipped entirely, so real gaps of silence between bursts of activity
    appear on their own from the live audio, matching the reference this mode is based on, rather
    than a bar mass that never fully empties. Bars grow from the bottom edge only, like
    `SpectrumScreen`, not mirrored.

### How the color wheel works

`ColorWheelPicker` is a genuine circular hue/saturation picker (angle = hue, radial distance =
saturation), unlike `AppearanceSettings`'s three-slider accent/background picker, which was built
specifically to avoid needing one. The wheel is drawn as a `Brush.sweepGradient` hue circle with a
white-to-transparent `Brush.radialGradient` composited on top via normal alpha blending --
`result = white * (1-saturation) + hueColor * saturation` is exactly what SRC_OVER-compositing a
white circle at alpha `(1-saturation)` over the hue circle produces, so that single extra draw call
depicts the desaturation-toward-center falloff without computing per-pixel HSV. Dragging or tapping
anywhere on the wheel computes `hue = atan2(dy, dx)` and `saturation = distance / radius` from the
touch point relative to center. A brightness slider sits below the wheel, and a live preview swatch
above it shows the exact selected color (true `HSVToColor`, not the wheel's visual approximation).

`CustomColorSettings` (hue/saturation/value, mirroring `AppearanceSettings`'s own HSV storage) is a
small settings holder shared by Shadow Waveform, Dot Spectrum, and Skyline Spectrum -- kept
separate from `BarSpectrumSettings` rather than folded into it, since only these three modes need a
custom color and every other `BarSpectrumSettings` consumer would otherwise carry three unused
fields. Each mode still gets its own `BarSpectrumSettingsPanel` (Sensitivity/Thickness/Height)
directly above its `ColorWheelPicker` in the gear panel, persisted through `SettingsStore` the same
way every other per-mode setting is.

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
rise/fall, Kaleidoscope Bloom's rotation, and so on) is documented as a time-based rate, not a flat
per-frame multiplier. That's what keeps motion speed and smoothness consistent whether the device
ends up running at 60Hz, 90Hz, or 120Hz -- a higher refresh rate means more, smaller steps toward
the same target over the same wall-clock time, not faster-looking motion.

## App Settings

Distinct from each visualizer mode's own gear-icon tuning panel, the hamburger icon opens an
app-wide settings screen:

Sections run in the order settings that change how the app behaves or looks (Display, Appearance,
Modes), then launcher shortcuts to other apps that aren't really a Sam's Music Viz setting at all
(Players), then update/diagnostic/about administrivia last.

- **Display**: a "Keep Screen On" toggle (`View.keepScreenOn` -- Android doesn't let third-party
  apps change the system screen-timeout duration directly, that needs the sensitive
  `WRITE_SETTINGS` permission) and an "Immersive Mode" toggle that hides the status/navigation bars
  while the visualizer is running (`WindowInsetsControllerCompat`, with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` so a swipe from an edge still reveals them temporarily).
  The app also isn't locked to portrait anymore -- it follows whatever the device's own
  rotation-lock setting allows, rather than forcing one orientation.
- **Appearance**: a custom accent color and a custom canvas background color, both expressed as
  Hue/Saturation/Brightness sliders (via `android.graphics.Color.HSVToColor`) rather than RGB so
  three sliders cover the whole range, each with a live preview swatch. `VisualizerTheme.ACCENT`
  and `VisualizerTheme.CANVAS_BACKGROUND` are both mutable Compose state rather than fixed
  constants specifically so this section can override them -- and since every mode already reads
  `ACCENT` (chips, the VU needle's glow, Spectrum's Cool and Frequency schemes, and more) and draws
  `CANVAS_BACKGROUND` as its
  first draw call, one custom color each cascades across the whole app instead of needing a picker
  per mode. `ACCENT_DIM` derives from `ACCENT` rather than being independent, so it stays coherent
  with whatever's picked. The background picker caps Brightness at 0.4 (unlike the accent picker's
  wide range) -- a bright backdrop would wash out every mode's glow effects and make bars/lines hard
  to read, so the slider only offers shades that stay usably dark.
- **Modes**: hide modes you don't use, and reorder the rest via up/down arrows next to each one
  (drag-to-reorder felt riskier on a touchscreen than arrows for a list this short). At least one
  mode always stays visible. Persisted through `ModePreferences` as an ordered mode-name list plus
  a hidden set, so a future app update that adds a new mode just appends it to the end rather than
  losing the user's customization.
- **Players**: Sam's Music Viz already captures whatever's playing system-wide -- Spotify,
  YouTube Music, Tidal, anything -- with no account, API key, or per-app setup. This section is
  just quick-launch shortcuts to jump straight to those apps (or their Play Store listing if not
  installed); it deliberately does *not* do OAuth account linking, since that wouldn't improve the
  visualizer and would mean embedding API credentials in the app for no real benefit.
- **Updates**: a GitHub-Releases-based update check, the same pattern F-Droid-style apps use
  outside the Play Store. `UpdateChecker` queries the repo's latest release via GitHub's public
  REST API, and "Get Update" opens the release page in the browser for a manual download and
  install, rather than downloading and self-installing the APK in-app. That's a deliberate
  trade-off: self-installing needed the `REQUEST_INSTALL_PACKAGES` permission, which -- combined
  with this app's audio-capture permissions -- reads to Google Play Protect's heuristics almost
  exactly like a trojan dropper (an app that listens to audio *and* can silently install more
  software), triggering an "app may be unsafe" warning on every sideloaded install regardless of
  what the permission was actually used for. Routing through the browser avoids that permission
  entirely, at the cost of one extra manual tap per update. This only works while the repo is
  public -- an unauthenticated request to a private repo's releases API returns 404/403, so a
  failed check just says so rather than crashing. A "Check Automatically" toggle runs the same
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
  signs with the same key from here on.
- **Diagnostics**: the most recent uncaught exception, if any -- `VisualizerApplication` installs a
  custom `Thread.UncaughtExceptionHandler` that writes the crash's stack trace to a local file
  (`CrashLog`) before re-raising to the system default handler, so the app still crashes normally,
  it just leaves a note behind first. There's no crash-reporting backend, so during solo on-device
  testing this is the only way to see what actually broke after the app dies and relaunches.
- **About**: version number and "vibe coded with love by Samuel Nicholas Salvador/Veera Krishnan."

## Sharing a frame

The share icon over the visualizer (`FrameCapture`) draws the app's root view into a bitmap
(`View.draw(Canvas)`), crops it to the visualizer `Box`'s own on-screen rectangle -- tracked via
`Modifier.onGloballyPositioned` + `boundsInRoot()`, so the mode strip and gear icon around it
aren't included -- writes the crop to the app's cache, and hands it to the system share sheet via
a `FileProvider` content URI. Deliberately doesn't call `WallpaperManager.setBitmap()` directly:
Android's share sheet already surfaces "Set as Wallpaper" as one of its targets for image content,
complete with the system's own crop/preview step, so routing through the chooser gets both
wallpaper-setting and general sharing (Gallery, Messages, anything else) for free, without a
dedicated `SET_WALLPAPER` permission of its own.

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

`VuMeterEngine`, `SpectrumEngine`, and `SpectrumAnalyzer` are plain Kotlin with no Android
framework calls, so their ballistics/DSP math has plain-JVM JUnit coverage under
`app/src/test/java/` -- no Robolectric or emulator needed. `SpectrumAnalyzerTest` in particular is
a regression guard for the FFT-magnitude-normalization bug described above: without dividing raw
FFT magnitude back down by `FFT_SIZE` before the dB conversion, every band reads pinned near the
ceiling regardless of what's actually playing. Every visualizer mode past Spectrum is a pure
rendering treatment of already-tested `SpectrumEngine` data (or, for Lava Waveform, White Waveform,
and Shadow Waveform, of the raw PCM `AudioAnalyzer` already publishes) with no DSP of its own, so
none of them need a dedicated test file the way an engine with real math does.

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

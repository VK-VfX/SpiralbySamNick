# Never Spiral

A camera app built on Android's `android.hardware.camera2` API directly (not CameraX), because
Pro mode's manual controls and true RAW capture both need `CaptureRequest`-level access CameraX's
stable public API doesn't expose. Distributed as a sideloaded APK via GitHub Releases, the same
OTA pattern the author's other apps use -- not published on the Play Store.

## What it does (v1.1.0)

- **Photo**: tap-to-focus/meter, pinch-to-zoom, flash (off/on/torch), self-timer (off/3s/10s), a
  3x3 grid overlay, front/back camera switch.
- **Pro mode**: independently toggleable manual exposure (ISO + shutter speed, each picked from
  the sensor's real supported range, snapped to the same discrete stops a physical camera dial
  uses) and manual focus (a diopter slider from infinity to the lens's closest focus distance),
  plus white-balance presets (Auto/Daylight/Cloudy/Tungsten/Fluorescent) and EV compensation while
  exposure stays automatic.
- **RAW**: a one-tap toggle that captures a `.dng` alongside the JPEG on devices whose sensor
  advertises the RAW capability, written via `DngCreator` straight from the sensor's raw Bayer
  buffer -- not a post-processed approximation.
- **Video**: H.264/AAC MP4 recording through `MediaRecorder`, using the device's own best
  available `CamcorderProfile` (1080p, falling back to 720p or the device's default "high"
  profile).
- Every capture lands in the shared MediaStore `Pictures/Never Spiral` (and `.../RAW`,
  `Movies/Never Spiral`) collections, so it shows up in the system gallery immediately like any
  other camera app's output.

**Portrait background blur, panorama, slow-motion, and a color filter pack are mode entries
already in the carousel but not yet wired to a capture pipeline** -- see
[What's next](#whats-next) below. Shipping the core capture pipeline first and layering these in
mirrors how the author's other Android project (a music visualizer) grew mode by mode rather than
landing every feature in one pass.

## Why Camera2, not CameraX

CameraX's public API has no supported path to `ImageFormat.RAW_SENSOR` capture, and Pro mode's
manual sensor controls (`SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`, `LENS_FOCUS_DISTANCE`) are
`CaptureRequest` keys CameraX only exposes piecemeal through `Camera2Interop`, if at all. Since
both are core to this app rather than a nice-to-have, `CameraEngine` is built directly on
`CameraManager`/`CameraDevice`/`CameraCaptureSession` instead -- more boilerplate (manual
background-thread handling, manual output-size selection, a hand-rolled TextureView transform
matrix) in exchange for actual control over what the sensor does.

## Architecture

- **`CameraEngine`**: owns exactly one open `CameraDevice` at a time and everything that talks to
  it directly -- opening/closing, building capture sessions, still/video/RAW capture, zoom (via
  `SCALER_CROP_REGION`, which works uniformly from API 21 up rather than needing a
  `CONTROL_ZOOM_RATIO`-vs-crop-region version split), tap-to-focus region mapping, and applying
  Pro mode's manual controls to every repeating preview request so the viewfinder itself reflects
  manual exposure live, not just the next capture.

  Pro mode's manual exposure/focus controls lean on a specific, easy-to-get-wrong Camera2 detail:
  `CONTROL_MODE` stays `AUTO` throughout, with `CONTROL_AE_MODE` and `CONTROL_AF_MODE`
  independently switched to `OFF`. Setting `CONTROL_MODE` itself to `OFF` disables all three of
  AE/AF/AWB at once and would force manual color-correction math just to keep white balance
  working, which manual exposure or manual focus alone has no reason to need.

- **`CameraController`**: the Compose-observable orchestration layer `CameraScreen` reads and
  writes directly -- mode, Pro settings, zoom, flash, recording state, last capture URI -- the
  same "a plain class holding `mutableStateOf` properties" shape the visualizer's engines already
  used, rather than an `AndroidViewModel` this single-Activity app has no real need for. Camera2's
  callback-based APIs are bridged into suspend functions via `suspendCancellableCoroutine`, called
  from `CameraScreen` through `rememberCoroutineScope()`.

- **`CaptureStore`**: writes JPEG/DNG/MP4 output into the shared MediaStore collections. Every
  write sets `IS_PENDING` on API 29+ (scoped storage) so a half-written file never shows up as a
  zero-byte/corrupt entry to anything else watching the collection. `MediaRecorder` needs a real
  file path, not a MediaStore `Uri`, so video always records to a private cache file first and
  gets copied into the shared `Movies/Never Spiral` collection once recording stops.

- **`CameraScreen`**: full-bleed live preview (a `TextureView` wrapped in `AndroidView`, chosen
  over `SurfaceView` because its transform matrix is what lets the preview buffer be
  center-crop-scaled and rotated to fill a portrait view) with the mode carousel, shutter, and Pro
  mode's manual dials floating on top -- the same layout every modern flagship camera app uses,
  rather than letterboxing the preview behind a separate control bar.

### Preview orientation

The sensor always delivers preview frames in its own landscape-native orientation. This app locks
its single Activity to portrait (`android:screenOrientation="portrait"` in the manifest), so the
general device-rotation-aware transform math a rotatable Activity would need collapses to a fixed
case: a center-crop scale plus a 90-degree rotation (−90 for the front camera, to also account for
its horizontal mirroring), covering the two sensor orientations every phone camera actually ships.
JPEG/video orientation uses the same fixed-portrait assumption in `CameraEngine`'s
`sensorToJpegOrientation`, rather than reading a live `OrientationEventListener`.

This is the one area of the app that's genuinely hard to verify without a physical device --
there's no camera hardware in the sandboxed build environment this was developed in, so while the
transform follows the standard Camera2 sample pattern (Google's own `Camera2Basic` sample uses the
same `configureTransform` shape), **preview framing and front-camera mirroring are worth a visual
check on an actual device** before trusting them fully.

### Manual control ranges

Pro mode's ISO and shutter speed sliders snap to a fixed list of standard camera stops (ISO
50/100/200/400/.../25600; shutter 1/8000 down to 30") intersected with whatever range the
connected sensor's `CameraCharacteristics` actually reports, rather than a continuous slider over
raw sensor values nobody reads intuitively. `ProFormatting.kt` holds this as plain,
framework-independent Kotlin, so it's covered by a fast JVM unit test (`ProFormattingTest`) rather
than needing an on-device/Robolectric run -- the same "pure math gets a plain-Kotlin unit test"
split the visualizer's own DSP engines used.

## What's next

Queued as follow-up rounds rather than attempted in the first pass:

- **Filters**: a color-matrix filter pack (B&W, Vivid, Cool, Warm, Vintage) applied to the
  captured JPEG.
- **HDR**: a 3-shot AE-bracket capture (under/normal/over exposure) merged via exposure fusion.
- **Panorama**: swipe-capture multiple frames while panning, stitched with cross-fade blending.
- **Slow-motion**: high-frame-rate capture via `createConstrainedHighSpeedCaptureSession` on
  devices that support it, encoded with a retimed playback frame rate.
- **Portrait mode**: background blur via ML Kit's on-device Selfie Segmentation (a real, public,
  no-network-required Google library), rather than anything requiring calibrated depth hardware.

## Building

```
./gradlew assembleDebug
```

Signed with a committed debug keystore (`app/debug.keystore`) rather than Gradle's own
auto-generated one, so every build -- CI or local -- signs with the same key and OTA updates over
a previous install don't silently fail (Android refuses to install an update signed with a
different key than the one already installed).

## Package

`com.nikoezveracamspiral` -- a separate identity from the author's other apps on this account, so
installing this one never collides with or gets confused for anything else already on a device.

## v1.1.0 UI and preview

- Pixel-style layout: rounded 3:4 viewfinder (matches the saved photo), JPEG/RAW pill and flash top corners, zoom chips, mode pills, ringed shutter with thumbnail and flip, bottom row with settings, Photo/Video toggle, manual controls.
- Preview transform applies no rotation (the camera framework already delivers upright buffers); it only cover-crops and mirrors the front camera.
- Capture animation: shutter blink on the viewfinder, shutter disc press/busy shrink, haptic tick, thumbnail pop.

## v1.2.0

- After-capture pipeline (`PhotoProcessing.kt`): EXIF rotation, selfie mirroring, crop to the chosen ratio, single-frame HDR tone curve (0-5), light sharpening, color style, re-encode at quality 97. The camera itself is asked for high-quality noise reduction, edge and tone-map modes.
- Styles (Natural, Vivid, Portrait, Landscape, Moody, Mono) shown live on the preview and applied to the saved photo.
- Settings sheet (flash, timer, ratio 4:3/16:9/1:1/2:1, grid off/thirds/golden/square, format, selfie mirroring), format popup, control strip (style, EV, shutter, ISO, WB, focus), level indicator.
- Files are saved as `bynikezvera_<date>.jpeg` and `bynikezvera_<date>.dng` (DNG is the RAW container).

## v1.3.0

- Filters are independent of HDR: 30+ looks in six categories (Basic, Film, Cinema, Retro, Mono, Creative), each with its own strength slider, shown live on the preview and applied to the saved photo.
- Develop sliders (exposure, contrast, saturation, warmth, tint, highlights, shadows, vignette, grain, sharpness) apply before capture and in the editor.
- HDR is a real three-frame bracket (dark, normal, bright) fused with exposure fusion; strength 1-5. Skipped with flash or manual exposure.
- Post-capture editor: tap the thumbnail to edit the last photo (filters, adjust, crop/rotate/flip, hold to compare) and save a new copy.
- Deeper controls: flash Auto, timer up to 15 s, zoom slider, extra white-balance presets, JPEG quality.
- New minimalist icon.
- OTA updates: CI publishes `version.json` and the APK to the `never-spiral-apk-builds` branch; Settings has Check for updates, Download and install, and an automatic check at launch.

## v1.4.0

- Pixel-style capture: the shutter fires a quick burst and returns at once (no waiting); a background queue aligns, merges, tone-maps, applies the look and saves. The thumbnail shows a spinner while a photo is processing and several photos can queue.
- HDR+: four deliberately underexposed frames, aligned (coarse-to-fine global shift search), merged with per-pixel rejection of anything that moved, then tone-mapped (exposure restored, shadows lifted, highlights rolled off).
- Night Sight: nine long exposures (up to 1/6 s each) merged the same way with an automatic brightness target. Hold still while the bar fills.
- Portrait: on-device person segmentation (ML Kit selfie segmenter) and a feathered background blur; strength slider.
- Filters, develop sliders, zoom, white balance and the rest work in every mode. In video the colour part of the look (filter, exposure, contrast, saturation, warmth, tint) is rendered after recording by Media3 Transformer in the background.

## v1.5.0

- Custom LUTs: import 3D `.cube` files (Filters > My LUTs > Import). Saved photos and the editor use the exact table with trilinear interpolation and the strength slider; the live preview and video use a least-squares colour-matrix fit of the table. LUTs are kept in app storage and can be deleted from the same tab.

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

## v1.6.0 (Niko's Cam)

- Renamed to Niko's Cam, with the new glyph icon, a splash that zooms into the app, and fade/scale open and close animations.
- Every slider is now a ruler-style wheel picker with a haptic tick per step.
- Night Sight offers Wait 3 s or Wait 6 s and shows a "Hold your phone steady" toast.
- Focus ring is a circle that springs in; the capture snapshot flies into the thumbnail, which orbits with a comet ring while the shot processes.
- Tapping the picture (outside Pro) or the empty space closes the filter/control panel and returns to shooting; Back closes panels, then exits with an animation.
- Photos carry EXIF (camera, exposure, ISO, aperture, focal length, processing, JPEG quality) and the editor has a Details tab (file, resolution, size, quality, compression, camera, exposure, date). Location will come later.

## v2.0.0

Reliability: the camera's own JPEG is saved the instant the shutter finishes and replaced by the processed version in place; a foreground service keeps processing alive if you leave the app; the queue is capped. Photos and video use the phone's real orientation (landscape works) and icons turn upright. Only the camera permission is required. RAW toggle fixed. Settings are remembered. AE/AF lock on long-press; tap-to-focus fires a single AF trigger.

Quality and speed: pixel loops run on all cores; burst alignment is global plus per-tile refinement with ISO-aware rejection; real lens zoom (0.5x on multi-camera phones) through the zoom-ratio API; optical and electronic stabilisation; Night Sight previews brightened, detects a tripod and uses longer exposures; Portrait blooms bright highlights into bokeh.

Video: resolution (720p / 1080p / 4K), 30/60 fps, H.264 or HEVC; filters render after recording with progress, and imported LUTs are applied exactly.

Extras: live histogram, QR scanning, volume keys as shutter, editor undo/redo, "Report a problem" log share.

Build and distribution: CI builds a minified arm64 release APK (and keeps the debug APK as a fallback), publishes a SHA-256 the updater verifies, caches Gradle, and signs with a private key if the repository secrets KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD exist (otherwise the committed debug key, so existing installs update in place). Unit tests cover LUT parsing, tone curves and the burst merge.

Not built yet (needs on-device testing): a YUV/RAW burst with zero-shutter-lag, a live GPU pipeline, panorama, slow-motion, Ultra HDR and motion photos.

## v2.1.0

- Fixed: choosing 0.5x never switched to the ultra-wide lens, because the engine clamped zoom to a minimum of 1x. The lower bound is now the camera's own minimum.
- Macro mode (extreme close-up): uses the ultra-wide lens, close-focus AF where the camera offers it, and shows the live focus distance.
- Fixed: preview surface leak (a Surface is now reused per SurfaceTexture and released on close); the camera now finishes closing before its callback thread stops; the QR scanner is created once and paused instead of recreated, and scans every 1.5 s.
- The problem report now lists each physical lens with its focal length and closest focus distance.

## v2.2.0 (Pixel family)

- Zoom chips are built from each phone's own lenses: the widest zoom, 1x, the optical stop of every extra physical lens (5x on a Pixel 9 Pro XL, 4x on a 7 Pro, none on an 8a), a 2x crop and a doubled telephoto. Lens focal lengths are converted to 35 mm equivalents from the sensor size.
- A device profile (`DeviceProfile.kt`) sizes burst and single-frame processing from the phone's memory: High (10.5 GB and up: Pixel 9 Pro XL, 8 Pro), Mid (7 GB and up: Pixel 8a, 7, 6), Basic. Large frames are now scaled to the budget instead of being halved twice (a 16 MP frame stays near 13 MP).
- The control area scales with the screen height (6.1" to 6.8" and folds).
- Settings shows "This phone" with its memory, performance tier and lenses; the problem report adds the SoC-independent facts the next features need: largest JPEG/YUV sizes, frame-rate ranges, high-speed video sizes, supported camera extensions (night, HDR, bokeh) and each physical lens with its closest focus distance.

## v2.2.1

- Fixed: Night mode washed bright scenes out to white. The viewfinder boost is now +1 EV (not the camera's maximum), the capture is based on the scene's real exposure with the boost removed, and each frame keeps the auto exposure's total light but trades ISO for a longer shutter, so brightness stays right and noise drops. Dark scenes are lifted by the tone curve.
- Fixed: zoom chips read 4.5 / 9 / 18 on a Pixel 9 Pro XL. The same lens appeared under several ids and the telephoto ratio was not rounded the way phones label it; lenses are now de-duplicated by focal length and a 4.5 ratio shows as 5 (with 10 as its double).
- Fixed: viewfinder snapshots (filter thumbnails, histogram, QR scanner, the flying capture) failed with "Unable to draw content from GPU" whenever a filter was active. The layer paint is lifted for the instant of the read.

## v2.3.0

- Fixed a launch crash from the settings saver (a coroutine started on a background thread inside the controller's constructor read state objects that did not exist yet). Saving now runs from the viewfinder's own coroutine, and background coroutines report errors instead of crashing the app.
- Removed the "Processing N..." pill; the thumbnail's orbiting ring is the only processing cue.
- Zoom quality: from 2x up, a shot is a 5-frame merged burst with minimal in-camera noise reduction (Settings: Zoom enhance), followed by a wider-radius unsharp mask that grows with the zoom level. Each photo's description now records the zoom and which physical lens captured it, so a telephoto shot can be told from a cropped main-lens one.
- Predictive-back callback enabled for Android 13+.

## v2.9.0

- **Pixel looks.** A new **Pixel** tab in Filters with Pixel's four base styles (Original, Natural, Shadows, Vanilla) and six creative presets (Editorial, Velvet, Classic, Digi, Black Tie, Minimal). They apply to photos, the editor, Night Sight (Google engine too) and video. Red/green/blue-specific colour (Editorial's washed blues, Velvet's rich blues and reds with cool greens) is applied on the saved photo; the live preview shows the overall colour and tone. Minimal adds no extra sharpening.
- **Fine-tuning** in Adjust now reads in Pixel's units: Warmth -10..+10, Sharpness 0..15, Grain None/Weak/Moderate/Strong, plus a new Vibrance slider (lifts muted colours more than strong ones). Contrast, Highlights, Shadows and Tint were already there.
- **Always use selected look** (Photo settings): on keeps the look every launch, off starts plain.

## v2.8.0
- **Watermark (Settings, Display).** A custom "Shot on Niko's Cam" watermark with the app logo (the N and camera, white on black) on every saved photo. Choose the style (small **on the photo**, or a **frame bar** added under it, light or dark), the position (left, centre, right), your own text, and whether to show the logo, the phone's name and the shot details (lens, f-stop, shutter, ISO). A live preview shows exactly what will be stamped. It applies to photos only (not video or RAW), also to Google Night photos and to a Portrait you re-edit. The bar style makes the photo a little taller, so it drops the Ultra HDR extra for that photo.
- **Portrait has one look now.** The six styles are gone; the **Blur** slider (in the viewfinder and in the editor's Portrait tab) is the control. Photos taken as another style before still open and edit fine.
- **No more sharp patch of background next to the subject.** The sharp region may take in things nearer than the subject (fingers) but only a little of what is farther (a seat back behind a hand), and for something that isn't a person it can grow less far.
- **No more pale glow around the subject.** The widest blur layer underneath used to include the subject's own colours, which smeared outward as a glow; it is now made from the background only, and the edge-colour clean-up can darken an edge but only lighten it a little.
- **A more compact About page** (Settings, the gear inside the gear). The three long bars (Telegram profile, update check, problem report) are now small buttons: your photo and name at the top, "@xnzvix" and "Report a problem" side by side, and the updates in one small card with a single Check or Install button and an On/Off for checking at launch.
- Tests: the watermark's wording and the saved-Portrait record (including records from 2.7.0) are covered by unit tests, and the emulator job draws every watermark style with the real logo.

## v2.7.0
- **Change a Portrait's blur after the shot.** Every Portrait now keeps (privately, newest 40) the picture as it was before the blur and what Portrait worked out about it (the subject and the depth of everything). The editor gets a **Portrait** tab for those photos: pick another style, change the blur strength with a live preview, or **tap the photo to pick a different subject** (a face, a hand, an object: the blur is re-done around it). Apply, then Save copy. Deleting a photo from the app deletes its data.
- **The original is saved too.** Settings, Photo: "Portrait: also save the original" (on by default) saves the photo as it was before the blur as `bynikezvera_<date>_original.jpeg`.
- **Round bokeh.** The blur kernel is now a disc (an octagon built from a box blur and two diagonal ones) instead of a Gaussian-like triple box blur, so lights become evenly lit round discs like a real lens's, and background detail keeps its shape instead of going milky. It is also cheaper to compute.
- **Cleaner edges.** In the soft edge around the subject (hair, shoulders) the photo's own pixels still carry a tint of the old background; Portrait now swaps that for the subject's colour from just inside, which removes the halo.
- **Colour and sharpening happen before the blur**, so an edited Portrait and a fresh one are built from the same picture.
- **Portrait fits phones with a small memory limit, like the Pixel 8a** (working size follows the app heap: about 1024 px on 512 MB, about 768 px on 256 MB; cues are fused in a separate step so their maps are freed before the blur; if memory still runs out the blur is retried a size smaller).
- **No more square notches at the edge of hair.** The mask clean-up kept clearing a square around small pieces such as strands of hair; pieces that sit against the subject are now kept, and only pieces floating well away are dropped, with a thin rim.
- **Unit tests** now cover Portrait's maths (mask clean-up, working size, the disc blur, refocusing, the saved record), which CI runs on every build.

## v2.6.1 (folded into 2.7.0)
- **Portrait fits phones with a small memory limit, like the Pixel 8a.** The Portrait blur keeps dozens of working maps in memory, which fit a Pixel 9 Pro XL's 512 MB app heap but not an 8a's roughly 256 MB. The working size now follows the phone's real heap (about 1024 px on 512 MB, about 750 px on 256 MB, never below 576), the depth, lens and focus cues are fused in a separate step so their maps are freed before the blur starts, and if memory still runs out the whole blur is retried a size smaller instead of failing. The radii scale with the working size, so the look is the same on every phone, and the log shows the size used.
- **No more square notches at the edge of hair.** When the subject mask had small separate pieces (strands of hair, fringe), cleaning them up cleared a square margin around each, which cut rectangular bites out of the hair and shoulders. Pieces that sit against the subject are now kept, only pieces floating well away are dropped, and only with a thin rim.

## v2.6.0
- **Pixel-level Portrait on every phone.** Portrait now uses the same method everywhere: a learned depth map (MiDaS small, the same kind of network Pixel's Portrait relies on, 63 MB, downloaded once on Wi-Fi when you open Portrait and kept across updates) gives clean, smooth depth; the person model (or the object around your tap) sets the subject; the blur size follows the distance from the subject's depth; the background is blurred in layers, in linear light, and composited back over the sharp photo. The two-lens and focus-sweep cues now only refine the learned depth on phones that have them, and Portrait still works without the model (outline blur) until it has downloaded.
- **Stronger, smoother background blur** by default (radii about 2.4x the old ones), sized to look like Pixel's.
- **Same look on every phone:** only the working resolution of the blur changes with the phone's speed (768 / 896 / 1024 px), and the radii scale with it.
- **Tap sets the subject in Portrait,** and the log says which depth sources a photo used ("learned depth", "two-lens stereo", "focus sweep").
- Two-lens stereo now matches lenses by focal length (the Pixel 9 Pro XL lists its main lens under two ids), with a log line for every reason it is skipped.
- Blur is done in linear light, so highlights keep their size and brightness.

## v2.5.5
- **Two-lens stereo now actually runs on the Pixel 9 Pro XL.** The phone lists its main lens under two ids (2 and 5, both 6.9 mm), and the app threw away the ultra-wide frame whenever the camera reported the other id. Lenses are now compared by focal length. The log (Report a problem) says why stereo was skipped, if it still is: wrong lens, no frame in time, focal ratio, or not enough texture.
- **Tap to set the subject (Portrait).** Tapping the viewfinder sets focus as before and now also picks the subject: the person under your finger, or else the in-focus object around that spot (a patch around the touch if nothing stands out). That subject stays sharp and sets the focus plane; everything else is blurred around it. A hint pill shows until you tap; changing mode or zoom clears the tap.
- **Lower memory use in Portrait:** the working picture is capped at 1024 pixels on the long side (was 1280), which cuts the garbage-collection stalls seen in the log.

## v2.5.4
- **Portrait viewfinder works like Pixel's.** The live view stays sharp and smooth (no blur overlay, no lag); the blur is applied after the shot, where the depth cues (outline, two lenses, focus sweep) make it accurate. The live preview experiment from 2.5.3 is removed.
- **No more white blobs.** Bokeh used to swell every bright area before blurring, so a sheet of paper turned into a white smear. Now only small bright points (lights, reflections) swell into soft discs; big bright surfaces are left alone.
- **No stray sharp patches.** Pieces of wall, sleeve or pillow that the subject model half-believed in (and noisy depth readings) used to stay sharp or turn dark as floating islands. Now only the main subject stays sharp, small separate pieces are dropped with a margin, and everything outside the subject gets at least the background blur; only something clearly and confidently in front is blurred lightly. The depth cues now adjust the blur less and are smoothed more.
- **Report a problem in About**, below Updates (it is also still under Settings, Phone).
- **Safer subject growth.** The sharp region can only grow from the subject across nearby pixels at the same measured depth (fingers, a held object); noisy depth can't flood it into the background, and a runaway growth is discarded in favour of the outline.

## v2.5.3
- **Portrait without a downloaded AI model.** The 63 MB depth model is gone (the app is smaller and nothing is downloaded). Depth now comes from what the phone can measure itself:
  - **Subject outline plus distance ramp:** the person model (or the in-focus area for objects) is snapped to the picture's real edges and the blur deepens smoothly with distance from it.
  - **Stereo from two lenses (1x, back camera):** each Portrait shot also takes the ultra-wide lens's view. It is cropped and enlarged to the main lens's field of view and matched pixel by pixel along the line between the lenses; nearer things shift more.
  - **Depth from focus:** after the shot the lens steps from far to near (about 1.5 s, "Hold still") and the step at which each area looks sharpest says how far away it is.
  The cues are used only where the picture has enough detail to trust; everywhere else the outline ramp decides. The photo's description in Details says which cues were used.
- **Whole subject kept sharp:** the sharp region grows from the subject across connected pixels at the same measured depth (fingers, a held object), and is snapped to real edges.
- **Google/Apple-style blur:** everything behind the subject gets a clear minimum blur that deepens with distance; things in front are blurred far less.
- **Live preview:** each Portrait style (and the blur slider) shows in the viewfinder, using the person model on small frames about 10 times a second.
- **Update button back in About:** Settings, the gear inside the gear: updates and the auto-check live there, and the update pill opens it.

## v2.5.2
- **Portrait with real depth.** (Replaced in 2.5.3 by the on-phone cues above.) An AI model (Intel's MiDaS, 63 MB, downloaded once and kept) turns the photo into a depth map. Portrait then simulates a large-aperture lens: every pixel is blurred by how far it is from the focus plane, in front of it or behind it; the picture is split into layers by blur size and stacked far to near, so a sharp subject can't bleed into the blur and a blurred foreground softens what is behind it. The depth map is snapped to the photo's real edges. The model downloads on Wi-Fi when you open Portrait (or when you tap to allow it on mobile data); until then the subject-outline blur from 2.5.1 is used.

## v2.5.1
- **Portrait rebuilt.** The subject outline is snapped to the picture's real edges (guided filter) instead of floating next to them, and leans slightly outward so hair and fine edges stay sharp. The blur uses background colours only, so the glow around the subject is gone, and it grows with distance from the subject like a real lens. Grain is put back into the blur so it doesn't look plastic.
- **Works on objects too:** when no person is found, the part of the frame that is in focus becomes the subject.
- **Looks:** Natural, Studio, Soft, Bokeh, Stage (dark backdrop) and Mono (black-and-white backdrop), chosen above the shutter in Portrait mode. The blur strength control stays, and the default is subtler.

## v2.5.0
- **RAW at 0.5x:** with RAW on, the ultra-wide shot now takes its RAW from the ultra-wide lens itself (it used to quietly come from the main lens). If the phone refuses that setup the shot falls back to JPEG only, with a note.
- **Display P3:** photos are captured, processed and saved in the wider Display P3 colour range where the camera supports it.
- **Ultra HDR:** photos keep an HDR gain map where the camera offers it (Settings > Photo > Ultra HDR). Re-processed shots carry the camera's gain map over, rotated, cropped and mirrored to match.
- **Merged RAW:** with RAW on, multi-frame shots (HDR+, Night, zoom stacks) now capture several RAW frames and merge them in the RAW domain into one cleaner DNG. HDR+ DNGs are left underexposed on purpose (to keep highlight detail): lift the exposure in your RAW editor.
- **Night Sight (Google):** a "Google" pill next to the 3 s / 6 s pills in Night mode uses the system's own Night extension where the phone offers it.
- **Zoom dial:** long-press a zoom chip, then slide along the scale. A tick in your hand for every step, a firmer one at each lens.
- **Brightness slider:** after tapping to focus, a sun appears beside the ring; drag it up or down.
- **Smoother lens changes:** a blurred still of the old view fades out when the camera flips or jumps to another lens.
- **Shutter ripple** and a **glowing thumbnail** while a photo develops.
- **Settings** are grouped into Photo, Video, Display and Phone.
- The problem report lists Ultra HDR and colour-space support per camera and what the current session is using.

## v2.4.2
- Lens distortion correction is now always requested (best quality for photos, fast for the viewfinder and video) wherever the camera supports it. The Pixel 9 Pro XL's ultra-wide only straightens the lens curve when asked; Google's camera asks, this app did not. It is part of how photos are processed, not a setting.
- The problem report lists each camera's supported distortion correction modes.

## v2.4.1
- Fixed the JPEG / RAW switch in the viewfinder's format pill doing nothing: the popup closed the moment it was tapped and cancelled the switch half way. It now runs on the camera's own scope.
- Settings has a small gear in its header that opens an About page: developer photo, Nikoezvera, version and the Telegram contact (@xnzvix).
- The DNG preview thumbnail is now sized within the 256 px limit, so it is actually written.

## v2.4.0
- New app icon and splash: the N + camera glyph, white on black.
- Removed the QR scanner (and its barcode model) from the viewfinder and Settings.
- RAW: DNG files now carry the capture's rotation (they used to open sideways), request the lens shading map (fixes the colour cast on the ultrawide) and embed a preview thumbnail.
- Settings, reachable from every mode, now ends with an About section: developer Nikoezvera, Telegram @xnzvix.

## v2.3.3
- Fixed the camera freezing after the app was in the background: the camera is now released when the app stops (finishing any recording first) and reopened when you return.

## v2.3.2
- Videos are saved the instant recording stops, so they can no longer vanish. With a filter on, the filtered copy is rendered in the background and replaces the original only when it finishes.
- The thumbnail now opens recordings in a built-in player.

## v2.3.1

- Pixel 8a: the widest zoom chip (0.5x) was dropped because it was rounded down below the camera's real minimum (0.53x); the camera's own minimum is now used. With no telephoto, only the 2x crop is offered instead of an extra 5x.
- Macro is only offered where a lens can focus close (about 5 cm or nearer): the 9 Pro XL's ultra-wide can; the 8a's ultra-wide is fixed-focus.
- Processing budgets follow the app's real heap limit (about 16 bytes per merged pixel), so a Pixel 8a's smaller heap no longer risks running out of memory in a merge.
- A lens readout above the zoom chips shows which physical lens is shooting (for example "Telephoto 17.9 mm · 10x").
- The problem report lists the heap limit.

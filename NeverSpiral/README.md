# Never Spiral

An Android app that is just a spiral: it never stops rotating, never stops growing, and never
stops changing color. New waves of the spiral are continuously born small, grow far larger than
the screen, and crossfade into the next wave -- so there's no visible reset, just an endless loop.

The spiral has a personality driven entirely by touch:

- **Tap** -- each tap adds a jolt of energy. Tap fast and the spiral spins faster, its growth
  cycle shortens (more manic), and its colors cycle and saturate harder. Stop tapping and it
  settles back down to a calm drift.
- **Multiple fingers at once** -- a bigger jolt than a single tap.
- **Long-press** -- makes the spiral "breathe": a pulsing radius modulation.
- **Drag** -- flicks the spiral's rotation like a spun wheel, with friction bringing it back to
  its ambient spin.
- **Haptics** -- every tap gives a short vibration that scales in strength with the current
  energy level.
- **Idle "sleepy" state** -- go untouched for a while and the spiral eases further below its
  normal resting speed and saturation, instead of settling at a flat idle.

It also has a music visualizer mode ("Visualize music" button, Android 10+ only): it captures
whatever the device is currently playing via `AudioPlaybackCaptureConfiguration` (not the
microphone), so it reacts the same way whether you're on speaker, wired headphones, or Bluetooth.
Loudness drives the same energy value as touch (more arms, faster spin, faster color cycling on
louder passages), and detected beat onsets trigger the breathing pulse. This requires a one-time
`RECORD_AUDIO` permission grant and a system "start recording or casting" consent screen -- that
wording is a quirk of the underlying API; Spiral only ever reads the audio, never video, and only
while the visualizer is toggled on (shown by a persistent notification while it runs). Whether a
given player (e.g. Spotify, YouTube Music) allows its audio to be captured this way depends on
flags it sets internally, which can only be confirmed by testing that app.

## Building

Open `NeverSpiral/` in Android Studio, or from the command line:

```
cd NeverSpiral
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android-build.yml`) builds the debug APK on every push touching this
directory and uploads it as a workflow artifact named `never-spiral-debug-apk`.

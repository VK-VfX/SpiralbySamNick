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

## Building

Open `NeverSpiral/` in Android Studio, or from the command line:

```
cd NeverSpiral
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android-build.yml`) builds the debug APK on every push touching this
directory and uploads it as a workflow artifact named `never-spiral-debug-apk`.

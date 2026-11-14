# Spiral

A 3-lane endless runner for Android, built natively in Kotlin + Jetpack Compose (Canvas-based
rendering, no game engine, no network access at runtime).

## Running it

Open the project root in Android Studio (Koala or newer). Since it has no `gradlew` yet, Studio
will offer to generate the Gradle wrapper on import — accept that, then run the `app` module on
a device or emulator (minSdk 26).

If you'd rather generate the wrapper yourself first:

```
gradle wrapper --gradle-version 8.9 --distribution-type bin
```

## Structure

- `app/src/main/java/com/samnick/spiral/game/` — pure Kotlin simulation (`GameEngine`,
  `GameSnapshot`, obstacle/phase/energy/gap logic). No Android or Compose imports, so it's
  straightforward to unit test in isolation from rendering.
- `app/src/main/java/com/samnick/spiral/ui/` — Compose Canvas rendering: the perspective
  corridor (`Corridor`, `GameRenderer.kt`), the procedural run-cycle figure (`RunnerFigure.kt`),
  the HUD (`Hud.kt`), and the screen that ties input + the frame loop together
  (`SpiralGameScreen.kt`).

## Notes

- The runner figure is drawn via forward kinematics (hip/knee/ankle, shoulder/elbow/wrist) driven
  by a single accumulated `runCyclePhase` value from the game engine, scaled by current game
  speed and fed into sine waves per limb — see the doc comment atop `RunnerFigure.kt`.
- This was built and code-reviewed in a sandboxed environment without Android SDK or Google Maven
  access, so it hasn't been compiled/run on a device yet. Build in Android Studio to verify.

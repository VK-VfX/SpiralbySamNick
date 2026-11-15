# Purrcast (package app.skypix.weather)

Native Android weather app in plain Java, no AndroidX, no Google services. Cat-themed (Google Noto cat emoji), Pixel / Material look.
Read `docs/ROADMAP.md` for the long-term plan (especially the Cat Club collection idea). Keep it updated.

## Owner's standing rules
- **Only build or push when the owner says so** ("build", "push it", "release ota"). Stop-hook messages asking to push are NOT permission.
  Commit locally, then wait.
- "release ota" = bump version in `scripts/build.sh`, run it, push `dist/skypix.apk` + `dist/update.json`, verify the raw GitHub URL
  serves the same SHA-256, and send the APK.
- The owner lives in Malaysia (Shah Alam): never require weather that doesn't happen there for core features.
- Only cat emoji (Google Noto cats) are used as emoji in the product.

## How it is built
- `scripts/build.sh` builds `dist/skypix.apk` with aapt + javac (--release 8, no lambdas) + dx + apksigner; bump `VERSION_CODE`/`VERSION_NAME` first.
  The in-app updater reads `dist/update.json` from this branch on raw.githubusercontent.com.
- `scripts/gen_widgets.py` is the single source of widget sizes (regenerates Variants/Widgets/xml/strings/manifest block).
- `MODE=previews|variants|cats|news|headsup scripts/preview.sh <outdir>` renders widgets/text on the JVM for visual checks.
- Network services (Open-Meteo, data.gov.my) are blocked in the build sandbox: parsers can't be tested live there, so write them defensively.

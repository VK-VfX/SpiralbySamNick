# Purrcast roadmap and ideas (kept on purpose, do not lose)

## Cat Club: the collection game (idea approved by the owner; only the streak counter is built so far)

The owner lives in **Malaysia** (Shah Alam), so the collection must never require weather that doesn't happen there.
Snow / true freezing must stay out of reach of the normal set. Keep this design when building the rest.

### Cats and how to unlock them
Tropical-first set (all achievable in Malaysia):
- Sunny (smile), partly cloudy (grin), cloudy (plain), rain (sad), umbrella cat (sad under an umbrella),
  thunderstorm (weary + bolt), monsoon cat (very heavy rain, about 20 mm/h or more), hot (sweaty), heatwave (feels like 40 or more),
  UV (sunglasses), haze (mask, AQI 100 or more), rainbow cat (sun and rain within a couple of hours),
  sunrise, sunset, night (black cat), sleepy (calm).
Cold cats with realistic triggers:
- Scarf cat unlocks at cool temperatures (about 20 C or lower), so Cameron / Genting Highlands count.
- Snow, true freezing and strong wind become **legendary** so everything else can be completed first.
Ways to unlock without local weather:
- **World tour postcards**: a built-in list of about 30 cities (Tokyo, Oslo, Reykjavik ...). When it is snowing in one right now
  (real Open-Meteo data, only city coordinates sent) the user can "send a postcard" and unlock that cat.
- **Streaks and habits**: open the app 7 days in a row, read the morning briefing, find the secret taps (7 taps on the title) -> costumes
  and accessories (Santa hat ...).
- **Cat of the day**: a daily surprise cat, can also be a widget.

### Collection book
Locked cats show as silhouettes with a hint ("Somewhere it's snowing..."), a progress percentage, and common / rare / legendary tiers.

### Phases
1. Streak counter (DONE, v1.0.8). Next: collection book with tropical + highland unlocks, streak rewards, cat of the day.
2. World tour postcards (saved places and geocoding now exist, so this is mostly a city list plus unlock logic).

## Built so far (v1.0.8, uncommitted-to-release until built)
- Streak counter (app card: days in a row, last 7 days, cat that gets cooler as the streak grows).
- Rain in minutes (Nowcast from Open-Meteo minutely_15; note: outside Europe/N. America/Japan the 15-minute data is interpolated from hourly, so approximate) + "Rain soon" widget (2x2, 4x2) + "Rain in ~N min: grab the umbrella" alert.
- Official warnings: MET Malaysia open data (api.data.gov.my/weather/warning) matched against the user's city/state. Parser is defensive because the feed could not be tested from the build sandbox. "Danger/flood" wording uses the loud emergency channel.
- Saved places (search via Open-Meteo geocoding, or "save where I am"), and a per-widget place chooser that shows when a widget is added and on long-press reconfigure.
- Material You colours (Android 12+, opt-in in Settings): app palette and widget backgrounds follow the wallpaper.

## Clock widgets (decided by the owner)
Included as widgets: Clock (big thin time + weather), Day clock (cat walking along the day), Sky clock (live sky, sun/moon, stars),
Cat schedule (time + what the cat is doing), Flip clock (redesigned: glossy tiles, bevelled hinge with pins, colon dots, cat peeking over the minutes).
They tick every minute through ACTION_TIME_TICK in the foreground service (no exact-alarm permission needed); the service now also runs when any clock widget exists.
Rejected / archived designs (code still in git history, commits 4ada700 .. 61fee6e): analogue cat clock, rings, word, dot-matrix, pill, neon, poster, LCD, world clock.

## Widgets are now one resizable widget per design (v1.0.10)
- 18 resizable widgets (see UNIFIED in scripts/gen_widgets.py) with min/max sizes decided by a size stress test (`java -Dkinds=NOW,... Preview sizes <dir>`).
- The old fixed-size widgets are kept as hidden "legacy" providers (widgetCategory=keyguard) so widgets already on home screens keep working; the in-app picker skips them (Variants.LEGACY).
- Touch and hold a widget -> "Widget settings" (reconfigurable config activity PlaceConfigActivity): per-widget background Solid / Glass / Clear with a live preview, plus the place it shows. The Settings screen background choice is only the default.

## Other ideas not built yet
- Commute alerts (user sets leaving times; "rain at 5:30, leave early").
- Pollen / allergy levels, UV "safe sun time" timeline, moon phase widget.
- History graph of past temperatures and a weekly recap.
- Ongoing notification or lock-screen line, Quick Settings tile.
- Mascot widget that reacts (asleep at night, awake at sunrise, purrs when petted), seasonal costumes, shareable weather card.
- "What should I wear?" with a cat outfit suggestion.
- OTA polish: changelog screen, Wi-Fi only installs, rollback. Translations, settings backup/restore.
- Philippine (PAGASA) official warnings: no stable open API found yet.

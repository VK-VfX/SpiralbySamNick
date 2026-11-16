#!/usr/bin/env python3
"""Single source of truth for widget size variants.

Edit VARIANTS, run `python3 scripts/gen_widgets.py`, and it regenerates the receivers, provider XML,
strings, manifest block, the core Variants table and the Widgets.java classes (and removes stale ones).
Picker previews are rendered afterwards by `MODE=previews scripts/preview.sh app/src/main/res/drawable-nodpi`.
"""
import glob, os, re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
def P(*a): return os.path.join(ROOT, *a)

# key, Cards kind, cells wide, cells high, title, one-line description
VARIANTS = [
    ('now_2x2', 'NOW', 2, 2, 'Now', 'Temperature, condition and high/low.'),
    ('now_2x3', 'NOW', 2, 3, 'Now', 'Temperature, high/low and the next hours.'),
    ('now_2x4', 'NOW', 2, 4, 'Now', 'Temperature, high/low and a taller hourly list.'),
    ('now_4x2', 'NOW_WIDE', 4, 2, 'Now wide', 'Big temperature with humidity and condition.'),
    ('hourly_4x2', 'HOURLY', 4, 2, 'Hourly', 'The next hours with rain chances.'),
    ('hourly_2x3', 'HOURLY', 2, 3, 'Hourly', 'A vertical list of the next hours.'),
    ('hourly_2x4', 'HOURLY', 2, 4, 'Hourly', 'A tall list of the next hours.'),
    ('forecast_2x3', 'FORECAST', 2, 3, 'Forecast', 'Compact multi-day outlook.'),
    ('forecast_2x4', 'FORECAST', 2, 4, 'Forecast', 'Tall multi-day outlook.'),
    ('forecast_4x3', 'FORECAST', 4, 3, 'Forecast', 'Five-day outlook with range bars.'),
    ('forecast_4x4', 'FORECAST', 4, 4, 'Forecast', 'Seven-day outlook with range bars.'),
    ('details_2x2', 'DETAILS', 2, 2, 'Details', 'Feels like, humidity, wind and UV.'),
    ('details_2x3', 'DETAILS', 2, 3, 'Details', 'Six weather details at a glance.'),
    ('details_2x4', 'DETAILS', 2, 4, 'Details', 'Eight weather details at a glance.'),
    ('details_4x2', 'DETAILS', 4, 2, 'Details', 'Feels like, humidity, wind, UV, air and pressure.'),
    ('details_4x3', 'DETAILS', 4, 3, 'Details', 'Nine weather details at a glance.'),
    ('location_2x2', 'LOCATION', 2, 2, 'Location', 'Your exact place, coordinates and GPS accuracy.'),
    ('location_2x3', 'LOCATION', 2, 3, 'Location', 'Place, coordinates, altitude and accuracy.'),
    ('location_4x2', 'LOCATION', 4, 2, 'Location', 'Wide place card with live coordinates.'),
    ('icon_1x1', 'ICON', 1, 1, 'Icon', 'Just the weather icon and temperature, no background.'),
    ('icon_2x1', 'ICON', 2, 1, 'Icon', 'Weather icon beside the temperature, no background.'),
    ('icon_2x2', 'ICON', 2, 2, 'Icon', 'Big weather icon with temperature and condition, no background.'),
    ('sun_2x2', 'SUN', 2, 2, 'Sun', 'Sun arc with sunrise and sunset.'),
    ('sun_4x2', 'SUN', 4, 2, 'Sun', 'Sun arc with sunrise, sunset and daylight left.'),
    ('lineh_2x2', 'LINE_HOURLY', 2, 2, 'Line hourly', 'Temperature curve with a weather icon on every hour.'),
    ('lineh_4x2', 'LINE_HOURLY', 4, 2, 'Line hourly', 'Wide temperature curve for the next hours.'),
    ('lineh_2x3', 'LINE_HOURLY', 2, 3, 'Line hourly', 'Tall hourly curve with high and low.'),
    ('lined_2x2', 'LINE_DAILY', 2, 2, 'Line daily', 'Highs for the next days as a smooth curve.'),
    ('lined_4x2', 'LINE_DAILY', 4, 2, 'Line daily', 'Wide curve of the daily highs.'),
    ('big_2x2', 'BIG', 2, 2, 'Big number', 'A giant, light temperature on a soft gradient.'),
    ('big_4x2', 'BIG', 4, 2, 'Big number', 'Wide giant temperature on a soft gradient.'),
    ('big_2x3', 'BIG', 2, 3, 'Big number', 'Tall giant temperature on a soft gradient.'),
    ('classic_2x2', 'CLASSIC', 2, 2, 'Classic', 'Place, light temperature and low/high.'),
    ('classic_2x3', 'CLASSIC', 2, 3, 'Classic', 'Tall classic card with place, temperature and low/high.'),
    ('rain_2x2', 'RAIN_SOON', 2, 2, 'Rain soon', 'Will it rain in the next minutes? A 15-minute rain outlook.'),
    ('rain_4x2', 'RAIN_SOON', 4, 2, 'Rain soon', 'Wide 15-minute rain outlook for the next three hours.'),
    ('clock_4x2', 'CLOCK_BIG', 4, 2, 'Clock', 'Big thin time with the date and the weather.'),
    ('clock_2x2', 'CLOCK_BIG', 2, 2, 'Clock', 'Stacked hour and minutes with the date.'),
    ('flip_4x2', 'CLOCK_FLIP', 4, 2, 'Flip clock', 'Split-flap tiles with a cat peeking over the minutes.'),
    ('flip_2x2', 'CLOCK_FLIP', 2, 2, 'Flip clock', 'Compact flip tiles with a peeking cat.'),
    ('sky_4x2', 'CLOCK_SKY', 4, 2, 'Sky clock', 'A sky that follows the real time of day, with the sun or moon.'),
    ('sky_2x2', 'CLOCK_SKY', 2, 2, 'Sky clock', 'The time of day as a small sky.'),
    ('catclock_4x2', 'CLOCK_SCHEDULE', 4, 2, 'Cat schedule', 'The time, and what the cat is up to right now.'),
    ('dayclock_4x2', 'CLOCK_DAY', 4, 2, 'Day clock', 'A cat walking along the day, with a sunrise or sunset countdown.'),
    ('headsup_3x1', 'HEADSUP', 3, 1, 'Heads-up', 'The one thing worth knowing right now.'),
    ('headsup_4x1', 'HEADSUP', 4, 1, 'Heads-up', 'The one thing worth knowing right now, with temperature.'),
    ('headsup_2x2', 'HEADSUP', 2, 2, 'Heads-up', 'The one thing worth knowing, in a small square.'),
    ('headsup_2x3', 'HEADSUP', 2, 3, 'Heads-up', 'The one thing worth knowing, with room to read.'),
]

# Sized widgets from earlier releases. They keep working on home screens that already have them, but are hidden from the picker.
LEGACY_KEYS = set(v[0] for v in VARIANTS)

# One resizable widget per design: (key, kind, default cells w, h, title, description, min cells (w, h), max cells (w, h))
UNIFIED = [
    ('now', 'NOW', 2, 2, 'Now', 'Temperature, condition and high/low. Grows into hourly lists as you resize it.', (2, 2), (4, 4)),
    ('hourly', 'HOURLY', 4, 2, 'Hourly', 'The next hours with rain chances.', (2, 2), (4, 4)),
    ('forecast', 'FORECAST', 4, 3, 'Forecast', 'Multi-day outlook with range bars.', (2, 2), (4, 4)),
    ('details', 'DETAILS', 4, 2, 'Details', 'Feels like, humidity, wind, UV, air and pressure.', (2, 2), (4, 4)),
    ('location', 'LOCATION', 2, 2, 'Location', 'Your exact place, coordinates and accuracy.', (2, 2), (4, 4)),
    ('sun', 'SUN', 4, 2, 'Sun', 'Sun arc with sunrise and sunset.', (2, 2), (4, 4)),
    ('headsup', 'HEADSUP', 4, 1, 'Heads-up', 'The one thing worth knowing right now.', (2, 1), (4, 4)),
    ('icon', 'ICON', 1, 1, 'Weather icon', 'Just the weather cat and temperature, no background.', (1, 1), (4, 4)),
    ('lineh', 'LINE_HOURLY', 4, 2, 'Line hourly', 'Temperature curve with a weather cat on every hour.', (2, 2), (4, 4)),
    ('lined', 'LINE_DAILY', 4, 2, 'Line daily', 'Daily highs as a smooth curve.', (2, 2), (4, 4)),
    ('big', 'BIG', 2, 2, 'Big number', 'A giant, light temperature on a soft gradient.', (2, 2), (4, 4)),
    ('classic', 'CLASSIC', 2, 2, 'Classic', 'Place, light temperature and low/high.', (2, 2), (4, 4)),
    ('rain', 'RAIN_SOON', 4, 2, 'Rain soon', 'Will it rain in the next minutes? A 15-minute rain outlook.', (2, 2), (4, 4)),
    ('clock', 'CLOCK_BIG', 4, 2, 'Clock', 'Big thin time with the date and the weather.', (2, 2), (4, 4)),
    ('flip', 'CLOCK_FLIP', 4, 2, 'Flip clock', 'Split-flap tiles with a cat peeking over the minutes.', (2, 2), (4, 4)),
    ('sky', 'CLOCK_SKY', 4, 2, 'Sky clock', 'A sky that follows the real time of day, with the sun or moon.', (2, 2), (4, 4)),
    ('catclock', 'CLOCK_SCHEDULE', 4, 2, 'Cat schedule', 'The time, and what the cat is up to right now.', (4, 2), (4, 4)),
    ('dayclock', 'CLOCK_DAY', 4, 2, 'Day clock', 'A cat walking along the day, with a sunrise or sunset countdown.', (4, 2), (4, 4)),
]
EXTRA = {}
for u in UNIFIED:
    VARIANTS.append(u[:6])
    EXTRA[u[0]] = (u[6], u[7])

def dp(n): return 70 * n - 30
def cls(key):
    if '_' not in key:
        return key.capitalize()
    head, size = key.rsplit('_', 1)
    return ''.join(w.capitalize() for w in head.split('_')) + size
def w(path, txt):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w').write(txt)

# ---- Variants.java (core) ----
n = len(VARIANTS)
j = ['package app.skypix.core;', '', '/** GENERATED by scripts/gen_widgets.py - do not edit. */', 'public final class Variants {',
     '    private Variants() {}', '', '    public static final int COUNT = %d;' % n, '']
j.append('    public static final String[] KEY = {%s};' % ', '.join('"%s"' % v[0] for v in VARIANTS))
j.append('    public static final int[] KIND = {%s};' % ', '.join('Cards.' + v[1] for v in VARIANTS))
j.append('    public static final int[] CELLS_W = {%s};' % ', '.join(str(v[2]) for v in VARIANTS))
j.append('    public static final int[] CELLS_H = {%s};' % ', '.join(str(v[3]) for v in VARIANTS))
j.append('    public static final String[] TITLE = {%s};' % ', '.join('"%s"' % v[4] for v in VARIANTS))
j.append('    public static final boolean[] LEGACY = {%s};' % ', '.join('true' if v[0] in LEGACY_KEYS else 'false' for v in VARIANTS))
j.append('    public static final String[] BLURB = {%s};' % ', '.join('"%s"' % v[5] for v in VARIANTS))
j += ['', '    public static int widthDp(int i) { return 70 * CELLS_W[i] - 30; }', '    public static int heightDp(int i) { return 70 * CELLS_H[i] - 30; }',
      '    public static String size(int i) { return CELLS_W[i] + "\\u00d7" + CELLS_H[i]; }', '}', '']
w(P('app/src/main/java/app/skypix/core/Variants.java'), '\n'.join(j))

# ---- Widgets.java (android) ----
k = ['package app.skypix.android;', '', 'import android.appwidget.AppWidgetManager;', 'import android.appwidget.AppWidgetProvider;',
     'import android.content.Context;', 'import android.os.Bundle;', '', 'import app.skypix.core.Cards;', 'import app.skypix.core.Variants;', '',
     '/** GENERATED by scripts/gen_widgets.py - do not edit. One provider class per widget size; all drawing lives in {@link Cards}. */',
     'public final class Widgets {', '    private Widgets() {}', '',
     '    abstract static class Base extends AppWidgetProvider {',
     '        /** Index into {@link Variants}. */', '        abstract int variant();', '',
     '        @Override', '        public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {',
     '            for (int id : ids) WidgetUpdater.update(ctx, m, variant(), id, null);',
     '            Jobs.sync(ctx);', '            Jobs.kick(ctx);', '        }', '',
     '        @Override', '        public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle newOptions) {',
     '            WidgetUpdater.update(ctx, m, variant(), id, null);', '        }', '',
     '        @Override', '        public void onDeleted(Context ctx, int[] ids) {', '            for (int id : ids) Places.forgetWidget(ctx, id);', '        }', '',
     '        @Override', '        public void onEnabled(Context ctx) {', '            Jobs.sync(ctx);', '            Jobs.kick(ctx);', '        }', '',
     '        @Override', '        public void onDisabled(Context ctx) {', '            Jobs.sync(ctx);', '        }', '    }', '']
for i, v in enumerate(VARIANTS):
    k.append('    public static final class %s extends Base { @Override int variant() { return %d; } }' % (cls(v[0]), i))
k += ['', '    static final Class<?>[] ALL = {', '        ' + ',\n        '.join(cls(v[0]) + '.class' for v in VARIANTS), '    };', '}', '']
w(P('app/src/main/java/app/skypix/android/Widgets.java'), '\n'.join(k))

# ---- provider XML (remove stale first) ----
for f in glob.glob(P('app/src/main/res/xml/widget_*.xml')): os.remove(f)
for f in glob.glob(P('app/src/main/res/drawable-nodpi/preview_*.png')): os.remove(f)
for key, kind, cw, ch, title, desc in VARIANTS:
    legacy = key in LEGACY_KEYS
    if legacy:
        mn, mx = (cw, ch), None
    else:
        mn, mx = EXTRA[key]
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:minWidth="%ddp"' % dp(cw), '    android:minHeight="%ddp"' % dp(ch),
             '    android:minResizeWidth="%ddp"' % dp(mn[0]), '    android:minResizeHeight="%ddp"' % dp(mn[1])]
    if mx:
        lines += ['    android:maxResizeWidth="%ddp"' % dp(mx[0]), '    android:maxResizeHeight="%ddp"' % dp(mx[1])]
    lines += ['    android:targetCellWidth="%d"' % cw, '    android:targetCellHeight="%d"' % ch,
              '    android:resizeMode="horizontal|vertical"', '    android:updatePeriodMillis="1800000"',
              '    android:initialLayout="@layout/widget_frame"', '    android:previewImage="@drawable/preview_%s"' % key,
              '    android:description="@string/d_%s"' % key]
    if not legacy:
        # only the resizable widgets have "Widget settings"; the sized ones behave exactly as before
        lines += ['    android:configure="app.skypix.weather/app.skypix.android.PlaceConfigActivity"',
                  '    android:widgetFeatures="reconfigurable"']
    lines += ['    android:widgetCategory="home_screen" />', '']
    w(P('app/src/main/res/xml/widget_%s.xml' % key), '\n'.join(lines))

# ---- strings ----
s = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>', '    <string name="app_name">Purrcast</string>']
for key, kind, cw, ch, title, desc in VARIANTS:
    s.append('    <string name="w_%s">Purrcast · %s %d×%d</string>' % (key, title, cw, ch) if key in LEGACY_KEYS else '    <string name="w_%s">Purrcast · %s</string>' % (key, title))
    s.append('    <string name="d_%s">%s</string>' % (key, desc.replace("'", "\\'")))
s += ['</resources>', '']
w(P('app/src/main/res/values/strings.xml'), '\n'.join(s))

# ---- manifest ----
mp = P('app/src/main/AndroidManifest.xml')
m = open(mp).read()
block = ['        <!-- widgets:begin (generated by scripts/gen_widgets.py) -->']
for key, kind, cw, ch, title, desc in VARIANTS:
    block.append('''        <receiver android:name="app.skypix.android.Widgets$%s" android:exported="false" android:label="@string/w_%s">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_%s" />
        </receiver>''' % (cls(key), key, key))
block.append('        <!-- widgets:end -->')
new = '\n'.join(block)
if '<!-- widgets:begin' in m:
    m = re.sub(r'        <!-- widgets:begin.*?<!-- widgets:end -->', lambda _: new, m, flags=re.S)
else:
    a = m.index('        <receiver android:name="app.skypix.android.Widgets$Now"')
    last = m.rindex('app.skypix.android.Widgets$HeadsUp')
    b = m.index('</receiver>', last) + len('</receiver>')
    m = m[:a] + new + m[b:]
open(mp, 'w').write(m)
print('generated %d widget variants' % n)

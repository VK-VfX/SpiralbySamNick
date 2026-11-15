package app.skypix.android;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.SystemClock;

import app.skypix.core.Place;

import java.util.List;

/**
 * Gets the most accurate position the device can give, using only the platform LocationManager
 * (GPS + network providers). No Google Play services.
 */
final class LocationHelper {
    private LocationHelper() {}

    private static final float GOOD_ACCURACY_M = 25f;

    /** Blocking; call off the main thread. Returns null when no usable fix exists. */
    static Location acquire(Context ctx, long timeoutMs) {
        final boolean fine = Store.fine(ctx);
        if (!fine && !Store.coarse(ctx)) return null;
        if (!Store.visible && !Store.background(ctx)) return null;

        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;

        final Object lock = new Object();
        final Location[] best = new Location[1];
        final boolean[] done = new boolean[1];

        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) {
                synchronized (lock) {
                    if (better(loc, best[0])) best[0] = loc;
                    if (loc.hasAccuracy() && loc.getAccuracy() <= GOOD_ACCURACY_M
                            && LocationManager.GPS_PROVIDER.equals(loc.getProvider())) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        HandlerThread ht = new HandlerThread("skypix-location");
        ht.start();
        try {
            List<String> providers = lm.getProviders(true);
            for (String prov : providers) {
                if (LocationManager.PASSIVE_PROVIDER.equals(prov)) continue;
                if (!fine && LocationManager.GPS_PROVIDER.equals(prov)) continue;
                try {
                    Location last = lm.getLastKnownLocation(prov);
                    if (last != null && ageMs(last) < 2 * 60 * 1000L) {
                        synchronized (lock) {
                            if (better(last, best[0])) best[0] = last;
                        }
                    }
                    lm.requestLocationUpdates(prov, 0L, 0f, listener, ht.getLooper());
                } catch (SecurityException e) {
                    // permission revoked mid-flight
                } catch (IllegalArgumentException e) {
                    // provider vanished
                }
            }
            long end = SystemClock.elapsedRealtime() + timeoutMs;
            synchronized (lock) {
                while (!done[0]) {
                    long left = end - SystemClock.elapsedRealtime();
                    if (left <= 0) break;
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            try {
                lm.removeUpdates(listener);
            } catch (SecurityException ignored) {
            }
            ht.quitSafely();
        }

        synchronized (lock) {
            if (best[0] != null) return best[0];
        }
        return newestLastKnown(lm, fine, 15 * 60 * 1000L);
    }

    private static Location newestLastKnown(LocationManager lm, boolean fine, long maxAgeMs) {
        Location pick = null;
        try {
            for (String prov : lm.getProviders(true)) {
                if (!fine && LocationManager.GPS_PROVIDER.equals(prov)) continue;
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && ageMs(l) <= maxAgeMs && better(l, pick)) pick = l;
            }
        } catch (SecurityException ignored) {
        }
        return pick;
    }

    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L;
    }

    private static boolean better(Location loc, Location cur) {
        if (cur == null) return true;
        long dt = loc.getTime() - cur.getTime();
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : 1e9f;
        float cacc = cur.hasAccuracy() ? cur.getAccuracy() : 1e9f;
        if (dt > 30000) return acc <= cacc + 150;
        if (dt < -30000) return false;
        return acc < cacc || (dt > 0 && acc <= cacc * 1.2f);
    }

    static Place toPlace(Context ctx, Location l) {
        Place p = new Place();
        p.lat = l.getLatitude();
        p.lon = l.getLongitude();
        p.accuracyM = l.hasAccuracy() ? l.getAccuracy() : Float.NaN;
        p.altitudeM = l.hasAltitude() ? l.getAltitude() : Double.NaN;
        p.fixTimeMs = System.currentTimeMillis() - Math.max(0, ageMs(l));
        p.provider = l.getProvider() == null ? "" : l.getProvider();
        p.precise = Store.fine(ctx);
        return p;
    }
}

package com.nikoezveracamspiral

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Where the phone is, for tagging photos. Only runs while the camera is open and the user has turned
 * Location tagging on; it listens for GPS/network fixes so the shutter never waits for one, and a
 * photo is tagged with the latest fix (at most [MAX_AGE_MS] old). Nothing is kept or sent anywhere.
 */
object LocationTagger {
    private const val MAX_AGE_MS = 10 * 60 * 1000L
    private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    @Volatile
    private var latest: Location? = null
    private var listener: LocationListener? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        if (listener != null || !hasPermission(context)) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latest = location
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            for (p in PROVIDERS) {
                if (manager.isProviderEnabled(p)) manager.requestLocationUpdates(p, 5_000L, 5f, l, Looper.getMainLooper())
            }
            listener = l
        } catch (e: SecurityException) {
            android.util.Log.w("LocationTagger", "No location permission", e)
        } catch (e: Exception) {
            android.util.Log.w("LocationTagger", "Couldn't start location updates", e)
        }
    }

    fun stop(context: Context) {
        val l = listener ?: return
        listener = null
        try {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l)
        } catch (_: Exception) {
        }
    }

    /** The best recent position, or null when there is none (or the permission is missing). */
    fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val candidates = ArrayList<Location>()
        latest?.let { candidates.add(it) }
        if (manager != null) {
            for (p in PROVIDERS) {
                try {
                    manager.getLastKnownLocation(p)?.let { candidates.add(it) }
                } catch (_: SecurityException) {
                } catch (_: Exception) {
                }
            }
        }
        val now = System.currentTimeMillis()
        return candidates
            .filter { now - it.time <= MAX_AGE_MS }
            .maxByOrNull { it.time }
    }
}

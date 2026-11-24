package com.atothei.roadhouse.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * One-shot GPS only — never continuous tracking, never polling. Used
 * by road notes (coordinates at dictation time) and the proximity
 * check on the next user interaction. Tries a recent last-known fix
 * first (under 5 minutes old); otherwise one single update with a
 * 10-second timeout, then gives up and reports null.
 */
object LocationHelper {

    private const val MAX_LAST_KNOWN_AGE_MS = 5L * 60L * 1000L
    private const val TIMEOUT_MS = 10_000L

    fun hasLocationPermission(context: Context): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** One-shot fix, or null. Callback runs on the main thread. */
    fun oneShot(context: Context, callback: (Pair<Double, Double>?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (!hasLocationPermission(context)) {
            callback(null)
            return
        }
        val lm = try {
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (e: Exception) {
            null
        } ?: run {
            callback(null)
            return
        }
        var done = false
        val finish: (Pair<Double, Double>?) -> Unit = { result ->
            if (!done) {
                done = true
                mainHandler.post { callback(result) }
            }
        }
        mainHandler.postDelayed({ finish(null) }, TIMEOUT_MS)
        try {
            val now = System.currentTimeMillis()
            var best: Location? = null
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (now - loc.time > MAX_LAST_KNOWN_AGE_MS) continue
                    if (best == null || loc.accuracy < best!!.accuracy) best = loc
                } catch (e: SecurityException) {
                    // Permission revoked mid-call; fall through to null.
                }
            }
            if (best != null) {
                finish(best.latitude to best.longitude)
                return
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try {
                        lm.removeUpdates(this)
                    } catch (e: Exception) {
                    }
                    finish(location.latitude to location.longitude)
                }

                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                try {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                } catch (e2: Exception) {
                    finish(null)
                }
            }
        } catch (e: Exception) {
            finish(null)
        }
    }
}

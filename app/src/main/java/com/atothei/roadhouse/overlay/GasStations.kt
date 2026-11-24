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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nearest gas station by voice — free, no key, no signup.
 * - Location: one-shot current GPS (a fresh last-known fix first,
 *   otherwise one single update with an 8-second timeout). Never polls,
 *   never tracks in the background.
 * - Search: Overpass API (overpass-api.de) for nodes tagged
 *   amenity=fuel within 10 km of the fix. The closest 1–2 are spoken
 *   with their distance in miles.
 *
 * FAIL-SOFT, always: if there is no location fix, no network, or the
 * lookup fails for any reason, the callback gets null and the caller
 * says so plainly instead of guessing a station. A station is only
 * ever named when Overpass actually returned it.
 */
object GasStations {

    data class Station(val name: String, val miles: Double)

    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val USER_AGENT = "RoadHouseOverlay/1.0 (driver assistant)"
    private const val SEARCH_RADIUS_M = 10000
    private const val EARTH_RADIUS_MILES = 3958.8

    /** Closest stations (at most 2), or null when the lookup failed. Async. */
    fun findNearest(context: Context, callback: (List<Station>?) -> Unit) {
        Thread {
            val hasPermission = try {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
            if (!hasPermission) {
                callback.invoke(null)
                return@Thread
            }
            val latch = CountDownLatch(1)
            var latLon: Pair<Double, Double>? = null
            currentLatLon(context) { result ->
                latLon = result
                latch.countDown()
            }
            latch.await(12, TimeUnit.SECONDS)
            val fix = latLon
            if (fix == null) {
                callback.invoke(null)
                return@Thread
            }
            callback.invoke(queryOverpass(fix.first, fix.second))
        }.start()
    }

    /** Overpass query for fuel stations around a fix. Null on any failure. */
    private fun queryOverpass(lat: Double, lon: Double): List<Station>? {
        return try {
            val query = "[out:json][timeout:20];" +
                "node[\"amenity\"=\"fuel\"](around:$SEARCH_RADIUS_M,$lat,$lon);" +
                "out body 30;"
            val conn = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write("data=" + URLEncoder.encode(query, "UTF-8"))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            val elements = JSONObject(text).optJSONArray("elements") ?: return null
            val stations = mutableListOf<Station>()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val elLat = el.optDouble("lat", Double.NaN)
                val elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) continue
                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: tags?.optString("brand")?.takeIf { it.isNotBlank() }
                    ?: tags?.optString("operator")?.takeIf { it.isNotBlank() }
                    ?: "Gas station"
                stations.add(Station(name, haversineMiles(lat, lon, elLat, elLon)))
            }
            if (stations.isEmpty()) return emptyList()
            stations.sortedBy { it.miles }.take(2)
        } catch (e: Exception) {
            null
        }
    }

    /** Great-circle distance in miles. */
    fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_MILES * c
    }

    /**
     * One-shot current location: a fresh last-known fix first (under 30
     * minutes old), otherwise one single location update with an 8-second
     * timeout. Never polls, never tracks in the background. Calls back
     * with null when there is no permission, no provider, or no fix.
     */
    private fun currentLatLon(context: Context, callback: (Pair<Double, Double>?) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            callback(null)
            return
        }
        val now = System.currentTimeMillis()
        val freshEnoughMs = 30L * 60L * 1000L
        try {
            var best: Location? = null
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                val loc = try {
                    lm.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                } catch (e: Exception) {
                    null
                } ?: continue
                if (now - loc.time > freshEnoughMs) continue
                if (best == null || loc.time > best.time) best = loc
            }
            if (best != null) {
                callback(best.latitude to best.longitude)
                return
            }
        } catch (e: Exception) {
            callback(null)
            return
        }
        val mainHandler = Handler(Looper.getMainLooper())
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                try {
                    lm.removeUpdates(this)
                } catch (e: Exception) {
                }
                callback(location.latitude to location.longitude)
            }

            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}

            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val timeoutRunnable = Runnable {
            if (done) return@Runnable
            done = true
            try {
                lm.removeUpdates(listener)
            } catch (e: Exception) {
            }
            callback(null)
        }
        mainHandler.postDelayed(timeoutRunnable, 8000)
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            try {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            } catch (e2: Exception) {
                if (!done) {
                    done = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    callback(null)
                }
            }
        }
    }
}

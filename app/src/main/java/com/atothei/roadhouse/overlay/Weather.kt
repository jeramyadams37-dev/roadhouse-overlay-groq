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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Weather — free Open-Meteo, no key, fail-soft. Same pattern as the
 * Carpentry Jobs weather check: the city name is geocoded once with
 * Nominatim (and cached in Prefs so it is not looked up again), then
 * Open-Meteo returns the current conditions. Any failure — no network,
 * bad city, bad response — returns null and the caller says the weather
 * was unavailable instead of guessing.
 *
 * Two entry points: fetch() for the pre-shift check (saved city) and
 * fetchCurrent() for a standalone "what's the weather" (current GPS
 * location first, saved city as fallback). Both are one-shot only: no
 * polling, no background refresh, no background location.
 */
object Weather {
    private const val NOMINATIM_URL =
        "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
    private const val OPEN_METEO_URL =
        "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f" +
            "&current=temperature_2m,weather_code,wind_speed_10m,precipitation_probability" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph"
    private const val USER_AGENT = "RoadHouseOverlay/1.0 (driver assistant)"

    /** One-shot fetch on a background thread. Callback gets a short summary or null. */
    fun fetch(context: Context, callback: (String?) -> Unit) {
        Thread {
            callback.invoke(fetchForCity(context, Prefs.getWeatherCity(context)))
        }.start()
    }

    /**
     * Standalone weather, any time: current location first, saved city as
     * fallback. A denied or missing location permission falls back to the
     * saved city and the summary says so honestly; a granted permission
     * with no fix does the same. One-shot only.
     */
    fun fetchCurrent(context: Context, callback: (String?) -> Unit) {
        Thread {
            val hasPermission = try {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
            val city = Prefs.getWeatherCity(context)
            if (!hasPermission) {
                val base = fetchForCity(context, city)
                callback.invoke(
                    base?.let {
                        "$it — that's your saved city. Turn on location in Settings to use where you are right now."
                    }
                )
                return@Thread
            }
            val latch = CountDownLatch(1)
            var latLon: Pair<Double, Double>? = null
            currentLatLon(context) { result ->
                latLon = result
                latch.countDown()
            }
            latch.await(12, TimeUnit.SECONDS)
            val summary = if (latLon != null) {
                val label = reverseGeocode(latLon!!.first, latLon!!.second) ?: "where you are"
                fetchForLatLon(latLon!!.first, latLon!!.second, label)
            } else {
                val base = fetchForCity(context, city)
                base?.let {
                    "$it — I couldn't get a location fix, so that's your saved city."
                }
            }
            callback.invoke(summary)
        }.start()
    }

    /** Current conditions for a saved city (geocode cached in Prefs). */
    private fun fetchForCity(context: Context, city: String): String? {
        val latLon = Prefs.getCachedLatLon(context, city) ?: geocode(city)?.also {
            Prefs.setCachedLatLon(context, city, it.first, it.second)
        } ?: return null
        return fetchForLatLon(latLon.first, latLon.second, city)
    }

    /** Current conditions for one lat/lon, labelled with the place name. */
    private fun fetchForLatLon(lat: Double, lon: Double, place: String): String? {
        return try {
            val url = URL(String.format(Locale.US, OPEN_METEO_URL, lat, lon))
            val body = httpGet(url) ?: return null
            val current = JSONObject(body).optJSONObject("current") ?: return null
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            val wind = current.optDouble("wind_speed_10m", Double.NaN)
            val rain = current.optDouble("precipitation_probability", Double.NaN)
            if (temp.isNaN() || code < 0) return null
            val desc = weatherWords(code)
            val windPart = if (!wind.isNaN()) ", wind %.0f mph".format(Locale.US, wind) else ""
            val rainPart =
                if (!rain.isNaN() && rain > 0) ", %.0f%% chance of rain".format(Locale.US, rain) else ""
            "%.0f°F and %s in %s%s%s".format(Locale.US, temp, desc, place, windPart, rainPart)
        } catch (e: Exception) {
            null
        }
    }

    /** Nominatim reverse geocode: lat/lon -> "Town, ST", or null. Fail-soft. */
    private fun reverseGeocode(lat: Double, lon: Double): String? {
        return try {
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon"
            )
            val body = httpGet(url) ?: return null
            val address = JSONObject(body).optJSONObject("address") ?: return null
            val town = address.optString("city").ifBlank {
                address.optString("town").ifBlank {
                    address.optString("village").ifBlank { address.optString("hamlet") }
                }
            }
            if (town.isBlank()) return null
            val state = address.optString("state_code").ifBlank { address.optString("state") }
            if (state.isBlank()) town else "$town, $state"
        } catch (e: Exception) {
            null
        }
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

    /** Nominatim geocode: city name -> (latitude, longitude), or null. */
    private fun geocode(city: String): Pair<Double, Double>? {
        return try {
            val url = URL(NOMINATIM_URL + URLEncoder.encode(city, "UTF-8"))
            val body = httpGet(url) ?: return null
            val first = JSONArray(body).optJSONObject(0) ?: return null
            Pair(first.optDouble("lat", Double.NaN), first.optDouble("lon", Double.NaN)).let {
                if (it.first.isNaN() || it.second.isNaN()) null else it
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: URL): String? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            null
        }
    }

    /** WMO weather codes -> plain words. */
    private fun weatherWords(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light showers"
        81 -> "showers"
        82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "uncertain conditions"
    }
}

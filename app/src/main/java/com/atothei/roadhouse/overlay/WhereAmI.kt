package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * "Where am I" — speaks the current street/town from a one-shot GPS
 * fix + reverse geocode (Nominatim, free, no key). No tracking, no
 * background location: one fix, one lookup, on demand only. Fail-soft:
 * a failed fix or lookup says so plainly — a place is never invented.
 */
object WhereAmI {

    /**
     * Async: (street/town line, or null on any failure). The callback
     * runs on a background thread.
     */
    fun locate(context: Context, callback: (String?) -> Unit) {
        if (!LocationHelper.hasLocationPermission(context)) {
            callback(null)
            return
        }
        LocationHelper.oneShot(context) { fix ->
            if (fix == null) {
                callback(null)
                return@oneShot
            }
            Thread {
                callback(reverseGeocode(fix.first, fix.second))
            }.start()
        }
    }

    /** Nominatim reverse geocode: (lat, lon) -> "123 Main St, Petersburg, IL", or null. */
    private fun reverseGeocode(lat: Double, lon: Double): String? {
        return try {
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=18" +
                    "&lat=$lat&lon=$lon"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "RoadHouseOverlay/1.0 (driver assistant)")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            val address = JSONObject(body).optJSONObject("address") ?: return null
            val road = address.optString("road")
            val house = address.optString("house_number")
            val town = address.optString("town").ifBlank {
                address.optString("city").ifBlank {
                    address.optString("village").ifBlank { address.optString("county") }
                }
            }
            val state = address.optString("state_code").ifBlank { address.optString("state") }
            val street = listOf(house, road).filter { it.isNotBlank() }.joinToString(" ")
            val place = listOf(street, town, state).filter { it.isNotBlank() }.joinToString(", ")
            if (place.isBlank()) null else place
        } catch (e: Exception) {
            null
        }
    }

    fun text(place: String?): String {
        if (place.isNullOrBlank()) {
            return "I couldn't work out where you are right now — check your location and connection and try again. I won't guess."
        }
        return "You're around $place."
    }
}

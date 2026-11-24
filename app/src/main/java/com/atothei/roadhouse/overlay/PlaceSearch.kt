package com.atothei.roadhouse.overlay

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Voice place search — "nearest bathroom", "cheapest gas near me",
 * "food nearby". Free Overpass API (no key), same pattern as
 * GasStations.kt, around a one-shot GPS fix from LocationHelper — no
 * second location stack. Speaks the closest 1–2 with distance.
 * Fail-soft like the gas search: any lookup failure says so plainly
 * instead of guessing. Honest limit: Overpass has no live fuel
 * prices, so "cheapest gas" answers with the NEAREST gas stations
 * and says prices aren't available — it never invents a price.
 */
object PlaceSearch {

    enum class Kind(val label: String) {
        BATHROOM("bathroom"),
        GAS("gas station"),
        FOOD("food")
    }

    data class Place(val name: String, val miles: Double, val detail: String?)

    /** Which kind of place is this command asking for, or null. */
    fun kindFor(lower: String): Kind? {
        return when {
            lower.contains("bathroom") || lower.contains("restroom") ||
                lower.contains("toilet") || lower.contains("need to pee") -> Kind.BATHROOM
            lower.contains("cheapest gas") || lower.contains("cheap gas") ||
                lower.contains("gas near") || lower.contains("gas station") -> Kind.GAS
            lower.contains("food nearby") || lower.contains("food near") ||
                lower.contains("hungry") || lower.contains("restaurant") ||
                lower.contains("fast food") || lower.contains("place to eat") -> Kind.FOOD
            else -> null
        }
    }

    /**
     * Async search around the current one-shot fix. Callback gets the
     * closest places (up to 2), an empty list when none are nearby, or
     * null on any failure (no GPS, no network, bad response). The
     * callback runs on a background thread.
     */
    fun searchNearest(context: android.content.Context, kind: Kind, callback: (List<Place>?) -> Unit) {
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
                callback(queryOverpass(fix.first, fix.second, kind))
            }.start()
        }
    }

    private fun queryOverpass(lat: Double, lon: Double, kind: Kind): List<Place>? {
        return try {
            val filter = when (kind) {
                Kind.BATHROOM -> "node[\"amenity\"=\"toilets\"](around:10000,$lat,$lon);"
                Kind.GAS -> "node[\"amenity\"=\"fuel\"](around:10000,$lat,$lon);"
                Kind.FOOD -> "node[\"amenity\"~\"^(fast_food|restaurant|cafe)$\"](around:10000,$lat,$lon);"
            }
            val query = "[out:json][timeout:15];($filter);out 20;"
            val url = URL("https://overpass-api.de/api/interpreter")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "RoadHouseOverlay/1.0 (driver assistant)")
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
            }
            conn.outputStream.use {
                it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            val elements = JSONObject(body).optJSONArray("elements") ?: return null
            val found = mutableListOf<Place>()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val elLat = el.optDouble("lat", Double.NaN)
                val elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) continue
                val tags = el.optJSONObject("tags") ?: JSONObject()
                val name = tags.optString("name").ifBlank {
                    tags.optString("brand").ifBlank {
                        when (kind) {
                            Kind.BATHROOM -> "Public bathroom"
                            Kind.GAS -> "Gas station"
                            Kind.FOOD -> tags.optString("cuisine").ifBlank { "Food place" }
                        }
                    }
                }
                val miles = GasStations.haversineMiles(lat, lon, elLat, elLon)
                val detail = if (kind == Kind.GAS) tags.optString("brand").takeIf { it.isNotBlank() } else null
                found.add(Place(name, miles, detail))
            }
            found.sortedBy { it.miles }.take(2)
        } catch (e: Exception) {
            null
        }
    }

    fun text(kind: Kind, places: List<Place>?): String {
        if (places == null) {
            return "I couldn't look that up right now — check your connection and location and try again."
        }
        if (places.isEmpty()) {
            return "No ${kind.label} found within about 6 miles of you."
        }
        val lines = places.mapIndexed { i, p ->
            val name = if (p.detail != null && p.detail != p.name) "${p.name} (${p.detail})" else p.name
            "${if (i == 0) "Closest" else "Next"}: $name, about %.1f miles away".format(p.miles)
        }.joinToString(". ")
        val priceNote = if (kind == Kind.GAS) {
            " I can't see live fuel prices, so this is the nearest gas, not the cheapest by price."
        } else ""
        return "$lines.$priceNote"
    }
}

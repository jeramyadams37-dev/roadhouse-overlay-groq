package com.atothei.roadhouse.overlay

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real route miles — free, no key, no signup.
 * - Geocode: Nominatim (OpenStreetMap), 1 request per second max, so
 *   calls are throttled to one per 1.1 seconds and run on a background
 *   thread, on demand only.
 * - Route: OSRM demo server (router.project-osrm.org), driving profile.
 *   One call takes any number of waypoints and returns the total driving
 *   distance in metres.
 *
 * FAIL-SOFT, always: any failure — no network, bad address, bad
 * response, rate limit — returns null and the caller falls back
 * silently to the printed miles. A route lookup never blocks an offer
 * check (it runs async, after the verdict is already shown) and a
 * distance is never invented. The geocode cache is in-memory only
 * (cleared when the process dies) and holds coordinates, never anything
 * the driver typed.
 */
object RouteMiles {

    private const val NOMINATIM_URL =
        "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
    private const val OSRM_URL = "https://router.project-osrm.org/route/v1/driving/"
    private const val USER_AGENT = "RoadHouseOverlay/1.0 (driver assistant)"
    private const val MIN_GEOCODE_GAP_MS = 1100L
    private const val METRES_PER_MILE = 1609.34

    /** In-memory geocode cache only — nothing is written to disk. */
    private val geocodeCache = LinkedHashMap<String, Pair<Double, Double>>()

    @Volatile
    private var lastGeocodeMs: Long = 0L

    /**
     * Real driving miles between two addresses, or null when either
     * address can't be geocoded or the route lookup fails. Async; the
     * callback runs on a background thread.
     */
    fun routeMilesBetween(
        addressA: String,
        addressB: String,
        callback: (Double?) -> Unit
    ) {
        Thread {
            callback.invoke(routeMilesForAddresses(listOf(addressA, addressB)))
        }.start()
    }

    /**
     * Real driving miles through a list of addresses in order (first =
     * start, rest = stops), or null when any address can't be geocoded
     * or the route lookup fails. Async; the callback runs on a background
     * thread.
     */
    fun routeMilesForStops(
        addresses: List<String>,
        callback: (Double?) -> Unit
    ) {
        Thread {
            callback.invoke(routeMilesForAddresses(addresses))
        }.start()
    }

    /** Synchronous worker: geocode every address, then one OSRM call. Null on any failure. */
    private fun routeMilesForAddresses(addresses: List<String>): Double? {
        return try {
            if (addresses.size < 2) return null
            val coords = mutableListOf<Pair<Double, Double>>()
            for (address in addresses) {
                val coord = geocode(address) ?: return null
                coords.add(coord)
            }
            route(coords)
        } catch (e: Exception) {
            null
        }
    }

    /** One OSRM call for a list of (lat, lon) waypoints. Total miles, or null. */
    private fun route(coords: List<Pair<Double, Double>>): Double? {
        return try {
            // OSRM wants lon,lat per waypoint, semicolon-separated.
            val joined = coords.joinToString(";") { "${it.second},${it.first}" }
            val url = URL(OSRM_URL + joined + "?overview=false")
            val body = httpGet(url) ?: return null
            val routes = JSONObject(body).optJSONArray("routes") ?: return null
            val metres = routes.optJSONObject(0)?.optDouble("distance", Double.NaN)
                ?: return null
            if (metres.isNaN() || metres <= 0.0) return null
            metres / METRES_PER_MILE
        } catch (e: Exception) {
            null
        }
    }

    /** Nominatim geocode: address -> (lat, lon), or null. Throttled to 1 req/sec. */
    private fun geocode(address: String): Pair<Double, Double>? {
        val key = OfferParser.normalizeKey(address)
        if (key.isBlank()) return null
        synchronized(geocodeCache) {
            geocodeCache[key]?.let { return it }
        }
        // Respect Nominatim's 1 request per second usage policy.
        val waitMs = MIN_GEOCODE_GAP_MS - (System.currentTimeMillis() - lastGeocodeMs)
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return try {
            lastGeocodeMs = System.currentTimeMillis()
            val url = URL(NOMINATIM_URL + URLEncoder.encode(address, "UTF-8"))
            val body = httpGet(url) ?: return null
            val first = JSONArray(body).optJSONObject(0) ?: return null
            val lat = first.optDouble("lat", Double.NaN)
            val lon = first.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            val coord = lat to lon
            synchronized(geocodeCache) {
                if (geocodeCache.size > 200) geocodeCache.clear()
                geocodeCache[key] = coord
            }
            coord
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
}

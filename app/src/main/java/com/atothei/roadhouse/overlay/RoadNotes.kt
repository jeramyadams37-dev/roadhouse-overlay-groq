package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RoadNote(
    val id: String,
    val text: String,
    val lat: Double,
    val lon: Double,
    val date: String,
    val lastSpokenDate: String
)

/**
 * Road notes — location-tagged notes he dictates ("remember, this road
 * floods", "bad pothole on 5th"). Explicit-only, never auto-captured.
 * The note + coordinates are stored at dictation time from a one-shot
 * GPS fix (LocationHelper); a note without a fix is not stored, so a
 * road note never pretends to know where it is. When his GPS is near
 * a noted spot (within ~0.3 miles, checked on the next user
 * interaction — no tracking, no polling), the note is read back once,
 * then not again that day.
 */
object RoadNotes {
    private const val FILE_NAME = "road_notes.json"
    private const val RADIUS_MILES = 0.3

    fun add(context: Context, text: String, lat: Double, lon: Double): RoadNote? {
        return try {
            val clean = text.trim()
            if (clean.isEmpty()) return null
            val (date, _) = DeliveryLog.nowParts()
            val note = RoadNote(
                id = System.currentTimeMillis().toString(),
                text = clean.take(300),
                lat = lat,
                lon = lon,
                date = date,
                lastSpokenDate = ""
            )
            val arr = readAll(context)
            arr.put(toJson(note))
            while (arr.length() > 100) arr.remove(0)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            note
        } catch (e: Exception) {
            null
        }
    }

    /** Notes within radius of (lat, lon) that have not been spoken today, nearest first. */
    fun nearbyUnspoken(context: Context, lat: Double, lon: Double): List<RoadNote> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return notes(context)
            .filter { it.lastSpokenDate != today }
            .map { it to GasStations.haversineMiles(lat, lon, it.lat, it.lon) }
            .filter { it.second <= RADIUS_MILES }
            .sortedBy { it.second }
            .map { it.first }
    }

    fun markSpoken(context: Context, id: String) {
        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val arr = readAll(context)
            var changed = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") == id) {
                    obj.put("last_spoken_date", today)
                    changed = true
                    break
                }
            }
            if (changed) File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
        } catch (e: Exception) {
        }
    }

    fun notes(context: Context): List<RoadNote> {
        val out = mutableListOf<RoadNote>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                RoadNote(
                    id = obj.optString("id"),
                    text = obj.optString("text"),
                    lat = obj.optDouble("lat", Double.NaN),
                    lon = obj.optDouble("lon", Double.NaN),
                    date = obj.optString("date"),
                    lastSpokenDate = obj.optString("last_spoken_date", "")
                )
            )
        }
        return out
    }

    fun readAll(context: Context): JSONArray {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun count(context: Context): Int = readAll(context).length()

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }

    private fun toJson(n: RoadNote): JSONObject {
        return JSONObject().apply {
            put("id", n.id)
            put("text", n.text)
            put("lat", n.lat)
            put("lon", n.lon)
            put("date", n.date)
            put("last_spoken_date", n.lastSpokenDate)
        }
    }
}

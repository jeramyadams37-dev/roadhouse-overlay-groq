package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ParkingSpot(
    val id: String,
    val text: String,
    val date: String,
    val time: String
)

/**
 * Parking spot memory — explicit only, same notes pattern as
 * StoreNotes/AddressNotes. "Remember I parked in row C" saves it;
 * "where did I park" reads it back. The location is never
 * auto-captured: only what he dictated is stored, and only the latest
 * spot is kept (a new one replaces the old — he is parked in one place).
 */
object ParkingSpot {
    private const val FILE_NAME = "parking.json"

    fun save(context: Context, text: String): ParkingSpot? {
        return try {
            val clean = text.trim()
            if (clean.isEmpty()) return null
            val (date, time) = DeliveryLog.nowParts()
            val spot = ParkingSpot(
                id = System.currentTimeMillis().toString(),
                text = clean.take(200),
                date = date,
                time = time
            )
            val arr = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("id", spot.id)
                        put("text", spot.text)
                        put("date", spot.date)
                        put("time", spot.time)
                    }
                )
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            spot
        } catch (e: Exception) {
            null
        }
    }

    fun latest(context: Context): ParkingSpot? {
        return try {
            val arr = readAll(context)
            val obj = arr.optJSONObject(0) ?: return null
            ParkingSpot(
                id = obj.optString("id"),
                text = obj.optString("text"),
                date = obj.optString("date"),
                time = obj.optString("time")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun forget(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
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
}

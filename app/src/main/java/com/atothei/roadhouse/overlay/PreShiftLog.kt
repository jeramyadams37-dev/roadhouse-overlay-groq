package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PreShiftRecord(
    val date: String,
    val time: String,
    val vehicleOk: Boolean?,
    val fuel: String,
    val apps: List<String>,
    val weather: String?,
    val summary: String
)

/**
 * Local JSON log of pre-shift checks, kept in the app's private files dir.
 * On-device only. The apps-online answer is what the driver SAID — the
 * overlay cannot detect another app's online status from outside, and it
 * never pretends to.
 */
object PreShiftLog {
    private const val FILE_NAME = "preshift_log.json"
    private const val MAX_ENTRIES = 200

    fun append(context: Context, record: PreShiftRecord): Boolean {
        return try {
            val arr = readAll(context)
            arr.put(
                JSONObject().apply {
                    put("date", record.date)
                    put("time", record.time)
                    if (record.vehicleOk != null) put("vehicle_ok", record.vehicleOk) else put("vehicle_ok", JSONObject.NULL)
                    put("fuel", record.fuel)
                    put("apps", JSONArray(record.apps))
                    put("weather", record.weather ?: JSONObject.NULL)
                    put("summary", record.summary)
                }
            )
            while (arr.length() > MAX_ENTRIES) arr.remove(0)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
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

    fun last(context: Context): PreShiftRecord? {
        val arr = readAll(context)
        if (arr.length() == 0) return null
        val obj = arr.optJSONObject(arr.length() - 1) ?: return null
        val apps = mutableListOf<String>()
        val appsArr = obj.optJSONArray("apps")
        if (appsArr != null) {
            for (i in 0 until appsArr.length()) apps.add(appsArr.optString(i))
        }
        return PreShiftRecord(
            date = obj.optString("date"),
            time = obj.optString("time"),
            vehicleOk = if (obj.isNull("vehicle_ok")) null else obj.optBoolean("vehicle_ok"),
            fuel = obj.optString("fuel"),
            apps = apps,
            weather = if (obj.isNull("weather")) null else obj.optString("weather"),
            summary = obj.optString("summary")
        )
    }

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }
}

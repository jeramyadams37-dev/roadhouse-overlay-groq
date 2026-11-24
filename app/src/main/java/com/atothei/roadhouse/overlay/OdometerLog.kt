package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

data class OdometerReading(
    val date: String,
    val time: String,
    val kind: String, // "start" | "end"
    val miles: Double
)

/**
 * Odometer readings for the tax log (odometer.json). Start of shift
 * and end of shift, spoken prompt each once; voice-captures the number.
 * If he doesn't answer, it is simply skipped, never nagged. A reading
 * can also be logged directly: "log odometer 123456" stores a start
 * reading if none exists today, otherwise an end reading.
 */
object OdometerLog {
    private const val FILE_NAME = "odometer.json"

    private val numberPattern = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)")

    /** First plausible odometer number in the text (commas allowed), or null. */
    fun parseNumber(text: String): Double? {
        val m = numberPattern.matcher(text)
        if (!m.find()) return null
        val value = m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (value <= 0.0 || value > 999999.0) return null
        return value
    }

    fun add(context: Context, kind: String, miles: Double): Boolean {
        return try {
            val (date, time) = DeliveryLog.nowParts()
            val obj = JSONObject().apply {
                put("date", date)
                put("time", time)
                put("kind", kind)
                put("miles", miles)
            }
            val arr = readAll(context)
            arr.put(obj)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readingForDate(context: Context, date: String, kind: String): OdometerReading? {
        val arr = readAll(context)
        var found: OdometerReading? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") != date) continue
            if (obj.optString("kind") != kind) continue
            found = OdometerReading(
                date = obj.optString("date"),
                time = obj.optString("time"),
                kind = kind,
                miles = obj.optDouble("miles", 0.0)
            )
        }
        return found
    }

    fun todayText(context: Context): String {
        val today = DeliveryLog.nowParts().first
        val start = readingForDate(context, today, "start")
        val end = readingForDate(context, today, "end")
        return when {
            start == null && end == null ->
                "No odometer readings today yet. Say \"log odometer\" followed by the number to record one."
            start != null && end != null -> {
                val driven = end.miles - start.miles
                if (driven >= 0) {
                    "Odometer today: started at %.0f, ended at %.0f — %.1f miles on the odometer.".format(
                        start.miles, end.miles, driven
                    )
                } else {
                    "Odometer today: started at %.0f, ended at %.0f — the end reading is lower than the start, so double-check the numbers.".format(
                        start.miles, end.miles
                    )
                }
            }
            start != null -> "Odometer today: started at %.0f, no end reading yet.".format(start.miles)
            else -> "Odometer today: ended at %.0f, no start reading yet.".format(end!!.miles)
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

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }
}

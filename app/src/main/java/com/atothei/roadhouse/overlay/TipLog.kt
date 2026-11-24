package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

data class TipEntry(
    val date: String,
    val time: String,
    val amount: Double,
    val platform: String
)

/**
 * Tips logged separately from base pay (tips.json), because Spark tips
 * land later than the base pay. Voice: "log a $4 tip", optionally "for
 * Walmart" / "for Spark" / "for the last delivery". Base pay and tips
 * are kept separate in EVERY total that reports them — earnings and the
 * tax tally both say "base $X plus $Y in tips", never a merged number
 * presented as base pay.
 */
object TipLog {
    private const val FILE_NAME = "tips.json"

    private val amountPattern = Pattern.compile("\\\$?\\s*(\\d+(?:\\.\\d{1,2})?)")

    /** Parse "log a $4 tip" / "log $4 tip for Walmart" into (amount, platform tag). */
    fun parse(command: String): Pair<Double, String>? {
        val lower = command.lowercase(Locale.US)
        if (!lower.contains("tip")) return null
        val m = amountPattern.matcher(command)
        if (!m.find()) return null
        val amount = m.group(1)?.toDoubleOrNull() ?: return null
        if (amount <= 0.0 || amount > 10000.0) return null
        val platform = when {
            lower.contains("spark") || lower.contains("walmart") -> "spark"
            lower.contains("doordash") || lower.contains("dash") -> "doordash"
            lower.contains("instacart") -> "instacart"
            lower.contains("last delivery") -> "last"
            else -> "unknown"
        }
        return amount to platform
    }

    fun add(context: Context, amount: Double, platform: String): Boolean {
        return try {
            val (date, time) = DeliveryLog.nowParts()
            val obj = JSONObject().apply {
                put("date", date)
                put("time", time)
                put("amount", amount)
                put("platform", platform)
            }
            val arr = readAll(context)
            arr.put(obj)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun totalToday(context: Context): Double {
        val today = DeliveryLog.nowParts().first
        var total = 0.0
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") != today) continue
            total += obj.optDouble("amount", 0.0)
        }
        return total
    }

    fun totalYear(context: Context, year: Int): Double {
        val prefix = "$year-"
        var total = 0.0
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!obj.optString("date").startsWith(prefix)) continue
            total += obj.optDouble("amount", 0.0)
        }
        return total
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

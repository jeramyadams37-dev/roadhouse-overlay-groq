package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

data class ExpenseEntry(
    val date: String,
    val time: String,
    val amount: Double,
    val category: String
)

/**
 * Work expenses logged by voice ("log $12 gas", "log $8 car wash") in
 * expenses.json — a SEPARATE store from delivery_log.json, so the
 * delivery log keeps its RoadHouse-compatible shape. Expenses feed the
 * tax tally (deductible costs, reported separately from mileage) and
 * "expenses today" reports the total. A garbage amount is rejected and
 * nothing is stored.
 */
object ExpenseLog {
    private const val FILE_NAME = "expenses.json"

    private val amountPattern = Pattern.compile("\\\$?\\s*(\\d+(?:\\.\\d{1,2})?)")

    /**
     * Parse "log $12 gas" / "log 12 dollars gas" into (amount, category),
     * or null when there is no usable amount. The category is whatever
     * words follow the amount ("gas", "car wash"); blank means "expense".
     */
    fun parse(command: String): Pair<Double, String>? {
        val lower = command.lowercase(Locale.US)
        if (!lower.contains("log")) return null
        if (lower.contains("tip")) return null // Tips have their own store.
        if (lower.contains("delivery") || lower.contains("this delivery") ||
            lower.contains("odometer")
        ) return null
        val m = amountPattern.matcher(command)
        if (!m.find()) return null
        val amount = m.group(1)?.toDoubleOrNull() ?: return null
        if (amount <= 0.0 || amount > 10000.0) return null
        var rest = command.substring(m.end()).trim()
            .replace(Regex("(?i)^dollars?\\b"), "").trim()
            .replace(Regex("(?i)\\s+for\\s+.*$"), "").trim()
        if (rest.isBlank()) rest = "expense"
        return amount to rest.take(60)
    }

    fun add(context: Context, amount: Double, category: String): Boolean {
        return try {
            val (date, time) = DeliveryLog.nowParts()
            val obj = JSONObject().apply {
                put("date", date)
                put("time", time)
                put("amount", amount)
                put("category", category)
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
        return totalForDate(context, today)
    }

    fun totalForDate(context: Context, date: String): Double {
        var total = 0.0
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") != date) continue
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

    fun countToday(context: Context): Int {
        val today = DeliveryLog.nowParts().first
        var n = 0
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") == today) n++
        }
        return n
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

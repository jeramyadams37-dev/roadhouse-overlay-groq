package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DeliveryEntry(
    val date: String,
    val time: String,
    val platform: String,
    val pay: Double?,
    val miles: Double?,
    val perMile: Double?,
    val address: String?
)

/**
 * Local JSON log of logged deliveries, kept in the app's private files dir.
 * Shape is RoadHouse-compatible (date, platform, pay, miles, address) so
 * RoadHouse's mileage/tax tracking can import it later. Nothing leaves the
 * phone except when the user explicitly exports it from Settings.
 */
object DeliveryLog {
    private const val FILE_NAME = "delivery_log.json"

    fun append(context: Context, entry: DeliveryEntry): Boolean {
        return try {
            val arr = readAll(context)
            val obj = JSONObject().apply {
                put("date", entry.date)
                put("time", entry.time)
                put("platform", entry.platform)
                if (entry.pay != null) put("pay", entry.pay) else put("pay", JSONObject.NULL)
                if (entry.miles != null) put("miles", entry.miles) else put("miles", JSONObject.NULL)
                if (entry.perMile != null) put("per_mile", entry.perMile) else put("per_mile", JSONObject.NULL)
                put("address", entry.address ?: JSONObject.NULL)
                put("source", "roadhouse-overlay-v1")
                put("logged_at", isoNow())
            }
            arr.put(obj)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Result of a deduped append: LOGGED, ALREADY_LOGGED, or FAILED. */
    enum class LogResult { LOGGED, ALREADY_LOGGED, FAILED }

    /**
     * Append unless the same delivery (platform + pay + miles + address)
     * was already logged within the last windowMinutes. "Took it" and
     * "log this delivery" both go through here, so a delivery can never
     * be logged twice by accident.
     */
    fun appendIfNew(context: Context, entry: DeliveryEntry, windowMinutes: Int = 10): LogResult {
        return try {
            if (hasRecent(context, entry.platform, entry.pay, entry.miles, entry.address, windowMinutes)) {
                LogResult.ALREADY_LOGGED
            } else if (append(context, entry)) {
                LogResult.LOGGED
            } else {
                LogResult.FAILED
            }
        } catch (e: Exception) {
            LogResult.FAILED
        }
    }

    /** Same platform/pay/miles/address logged within the last windowMinutes? */
    fun hasRecent(
        context: Context,
        platform: String,
        pay: Double?,
        miles: Double?,
        address: String?,
        windowMinutes: Int = 10
    ): Boolean {
        return try {
            val now = Date().time
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val arr = readAll(context)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("platform") != platform) continue
                val ePay = if (obj.isNull("pay")) null else obj.optDouble("pay")
                val eMiles = if (obj.isNull("miles")) null else obj.optDouble("miles")
                if (ePay != pay || eMiles != miles) continue
                val eAddr = if (obj.isNull("address")) null else obj.optString("address")
                if (eAddr != address) continue
                val at = try {
                    fmt.parse("${obj.optString("date")} ${obj.optString("time")}")?.time
                } catch (e: Exception) {
                    null
                } ?: continue
                if (now - at in 0 until windowMinutes * 60L * 1000L) return true
            }
            false
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

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun nowParts(): Pair<String, String> {
        val now = Date()
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val t = SimpleDateFormat("HH:mm", Locale.US).format(now)
        return d to t
    }

    private fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date())
    }
}

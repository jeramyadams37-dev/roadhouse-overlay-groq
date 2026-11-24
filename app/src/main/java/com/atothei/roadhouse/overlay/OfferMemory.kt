package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Outcome the driver recorded for a checked offer. */
enum class OfferOutcome { TOOK, SKIPPED, UNKNOWN }

data class OfferMemoryRecord(
    val id: String,
    val date: String,
    val time: String,
    val platform: String, // stable tag: "spark" / "doordash" / "instacart" / "unknown"
    val packageName: String, // foreground app package this check came from
    val storeKey: String,
    val storeLabel: String,
    val pay: Double?,
    val miles: Double?,
    val perMile: Double?,
    val verdict: Verdict,
    val outcome: OfferOutcome,
    val stops: List<String>
)

/**
 * Local JSON history of every offer checked, kept in the app's private
 * files dir. On-device only — nothing leaves the phone except when the
 * driver explicitly exports it from Settings. A check is recorded with
 * outcome UNKNOWN; the driver fills it in afterwards with the "Took it" /
 * "Skipped it" card buttons or by saying "took it" / "skipped it".
 *
 * Matching is store-first: a new offer matches a remembered one when the
 * store key is the same and pay/miles are close. Same store, different
 * numbers is still reported as "seen this store before".
 */
object OfferMemory {
    private const val FILE_NAME = "offer_history.json"
    private const val MAX_ENTRIES = 500

    fun append(context: Context, record: OfferMemoryRecord): OfferMemoryRecord {
        try {
            val arr = readAll(context)
            arr.put(toJson(record))
            while (arr.length() > MAX_ENTRIES) arr.remove(0)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
        } catch (e: Exception) {
            // Best effort: a failed write must not break the offer check.
        }
        return record
    }

    /** Fill in the outcome (took / skipped) for a recorded check. */
    fun markOutcome(context: Context, id: String, outcome: OfferOutcome): Boolean {
        return try {
            val arr = readAll(context)
            var changed = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") == id) {
                    obj.put("outcome", outcome.name)
                    changed = true
                    break
                }
            }
            if (changed) File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            changed
        } catch (e: Exception) {
            false
        }
    }

    /** The most recent record for the same store AND similar pay/miles. */
    fun findMatch(context: Context, storeKey: String, pay: Double?, miles: Double?): OfferMemoryRecord? {
        if (storeKey.isBlank()) return null
        val all = records(context)
        for (i in all.size - 1 downTo 0) {
            val r = all[i]
            if (r.storeKey != storeKey) continue
            if (pay != null && r.pay != null) {
                val tol = maxOf(2.5, r.pay * 0.20)
                if (kotlin.math.abs(pay - r.pay) > tol) continue
            } else if ((pay == null) != (r.pay == null)) {
                continue
            }
            if (miles != null && r.miles != null) {
                val tol = maxOf(1.5, r.miles * 0.20)
                if (kotlin.math.abs(miles - r.miles) > tol) continue
            } else if ((miles == null) != (r.miles == null)) {
                continue
            }
            return r
        }
        return null
    }

    /** The most recent record for the same store, whatever the numbers were. */
    fun findStore(context: Context, storeKey: String): OfferMemoryRecord? {
        if (storeKey.isBlank()) return null
        return records(context).lastOrNull { it.storeKey == storeKey }
    }

    /**
     * The most recent still-UNKNOWN check from this package, taken within
     * the last windowMinutes — the accept-tap gate (feature 9). A tap in
     * another app, or on an older check, returns null and logs nothing.
     * Time comes from the record's date + time ("yyyy-MM-dd HH:mm").
     */
    fun latestFreshCheck(
        context: Context,
        packageName: String,
        windowMinutes: Int = 3
    ): OfferMemoryRecord? {
        if (packageName.isBlank()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val now = Date().time
        val all = records(context)
        for (i in all.size - 1 downTo 0) {
            val r = all[i]
            if (r.outcome != OfferOutcome.UNKNOWN) continue
            if (r.packageName.isNotBlank() && r.packageName != packageName) continue
            if (r.packageName.isBlank()) continue // No package recorded: never guess.
            val at = try {
                fmt.parse("${r.date} ${r.time}")?.time
            } catch (e: Exception) {
                null
            } ?: continue
            if (now - at in 0 until windowMinutes * 60L * 1000L) return r
            // Records are append-ordered, so an older one can't be fresher.
            if (now - at >= windowMinutes * 60L * 1000L) return null
        }
        return null
    }

    fun records(context: Context): List<OfferMemoryRecord> {
        val out = mutableListOf<OfferMemoryRecord>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(fromJson(obj))
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

    /** Short spoken line about a remembered offer, or null if nothing matches. */
    fun memoryLine(match: OfferMemoryRecord?, storeOnly: OfferMemoryRecord?): String? {
        val m = match
        if (m != null) {
            val payPart = if (m.pay != null) "$%.2f".format(Locale.US, m.pay) else "unknown pay"
            val milesPart = if (m.miles != null) "%.1f miles".format(Locale.US, m.miles) else "unknown miles"
            return when (m.outcome) {
                OfferOutcome.TOOK ->
                    "You took this offer before — $payPart for $milesPart."
                OfferOutcome.SKIPPED ->
                    "You skipped this offer last time — $payPart for $milesPart."
                OfferOutcome.UNKNOWN ->
                    "You checked this offer before — $payPart for $milesPart — but didn't record took or skipped."
            }
        }
        val s = storeOnly
        if (s != null) {
            val payPart = if (s.pay != null) "$%.2f".format(Locale.US, s.pay) else "unknown pay"
            val milesPart = if (s.miles != null) "%.1f miles".format(Locale.US, s.miles) else "unknown miles"
            return when (s.outcome) {
                OfferOutcome.TOOK ->
                    "You've seen this store before — last time you took a $payPart, $milesPart offer here."
                OfferOutcome.SKIPPED ->
                    "You've seen this store before — last time you skipped a $payPart, $milesPart offer here."
                OfferOutcome.UNKNOWN ->
                    "You've seen this store before — last time was $payPart for $milesPart."
            }
        }
        return null
    }

    fun newId(): String = isoNow()

    private fun toJson(r: OfferMemoryRecord): JSONObject {
        return JSONObject().apply {
            put("id", r.id)
            put("date", r.date)
            put("time", r.time)
            put("platform", r.platform)
            put("package_name", r.packageName)
            put("store_key", r.storeKey)
            put("store_label", r.storeLabel)
            if (r.pay != null) put("pay", r.pay) else put("pay", JSONObject.NULL)
            if (r.miles != null) put("miles", r.miles) else put("miles", JSONObject.NULL)
            if (r.perMile != null) put("per_mile", r.perMile) else put("per_mile", JSONObject.NULL)
            put("verdict", r.verdict.name)
            put("outcome", r.outcome.name)
            put("stops", JSONArray(r.stops))
            put("logged_at", r.id)
        }
    }

    private fun fromJson(obj: JSONObject): OfferMemoryRecord {
        val stops = mutableListOf<String>()
        val arr = obj.optJSONArray("stops")
        if (arr != null) {
            for (i in 0 until arr.length()) stops.add(arr.optString(i))
        }
        return OfferMemoryRecord(
            id = obj.optString("id"),
            date = obj.optString("date"),
            time = obj.optString("time"),
            platform = Platforms.tagFromDisplay(obj.optString("platform")),
            packageName = obj.optString("package_name", ""),
            storeKey = obj.optString("store_key"),
            storeLabel = obj.optString("store_label"),
            pay = if (obj.isNull("pay")) null else obj.optDouble("pay"),
            miles = if (obj.isNull("miles")) null else obj.optDouble("miles"),
            perMile = if (obj.isNull("per_mile")) null else obj.optDouble("per_mile"),
            verdict = try {
                Verdict.valueOf(obj.optString("verdict", "UNKNOWN"))
            } catch (e: Exception) {
                Verdict.UNKNOWN
            },
            outcome = try {
                OfferOutcome.valueOf(obj.optString("outcome", "UNKNOWN"))
            } catch (e: Exception) {
                OfferOutcome.UNKNOWN
            },
            stops = stops
        )
    }

    private fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date())
    }
}

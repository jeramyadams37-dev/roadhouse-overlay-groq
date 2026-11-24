package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ShiftSummary(
    val label: String,
    val count: Int,
    val totalPay: Double,
    val totalMiles: Double,
    val avgPerMile: Double?,
    val bestPay: Double?,
    val bestLabel: String?,
    val worstPay: Double?,
    val worstLabel: String?,
    val perPlatform: List<PlatformSlice>
)

data class PlatformSlice(
    val tag: String,
    val count: Int,
    val pay: Double,
    val miles: Double
)

/**
 * End-of-shift summary, computed IN CODE from the local delivery log —
 * never by the model. Today only. Per-platform split uses the stable
 * platform tags ("spark" / "doordash" / "instacart" / "unknown"); older
 * log rows that stored the display name ("Spark") are normalised to the
 * same tags so the split stays correct across versions.
 *
 * Best / worst delivery are by pay. A delivery with unknown pay can be
 * neither — it is counted, but never named best or worst.
 */
object ShiftSummary {

    fun summarize(context: Context): ShiftSummary {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return summarizeForDate(context, today)
    }

    fun summarizeForDate(context: Context, date: String): ShiftSummary {
        var count = 0
        var pay = 0.0
        var miles = 0.0
        var bestPay: Double? = null
        var bestLabel: String? = null
        var worstPay: Double? = null
        var worstLabel: String? = null
        val byPlatform = linkedMapOf<String, MutablePlatformAcc>()

        val arr: JSONArray = DeliveryLog.readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") != date) continue
            count++
            val entryPay = if (obj.isNull("pay")) null else obj.optDouble("pay")
            val entryMiles = if (obj.isNull("miles")) null else obj.optDouble("miles")
            if (entryPay != null) pay += entryPay
            if (entryMiles != null) miles += entryMiles
            val tag = Platforms.tagFromDisplay(obj.optString("platform"))
            val acc = byPlatform.getOrPut(tag) { MutablePlatformAcc() }
            acc.count++
            if (entryPay != null) acc.pay += entryPay
            if (entryMiles != null) acc.miles += entryMiles
            if (entryPay != null) {
                val label = describe(obj.optString("platform"), entryPay, entryMiles)
                if (bestPay == null || entryPay > bestPay!!) {
                    bestPay = entryPay
                    bestLabel = label
                }
                if (worstPay == null || entryPay < worstPay!!) {
                    worstPay = entryPay
                    worstLabel = label
                }
            }
        }
        val avg = if (miles > 0) pay / miles else null
        val slices = byPlatform.entries.map {
            PlatformSlice(it.key, it.value.count, it.value.pay, it.value.miles)
        }
        return ShiftSummary(
            label = "Today",
            count = count,
            totalPay = pay,
            totalMiles = miles,
            avgPerMile = avg,
            bestPay = bestPay,
            bestLabel = bestLabel,
            worstPay = worstPay,
            worstLabel = worstLabel,
            perPlatform = slices
        )
    }

    private fun describe(platform: String, pay: Double, miles: Double?): String {
        val name = Platforms.displayFromTag(Platforms.tagFromDisplay(platform))
        val milesPart = if (miles != null) " for %.1f miles".format(Locale.US, miles) else ""
        return "$name — $%.2f$ milesPart".format(Locale.US, pay)
    }

    /** Short spoken + card text. */
    fun text(s: ShiftSummary): String {
        if (s.count == 0) {
            return "Nothing logged today yet, so there is no shift to sum up. Log a delivery first."
        }
        val noun = if (s.count == 1) "delivery" else "deliveries"
        val avgPart = if (s.avgPerMile != null) {
            ", $%.2f a mile".format(Locale.US, s.avgPerMile)
        } else {
            ""
        }
        val sb = StringBuilder()
        sb.append(
            "End of shift: ${s.count} $noun, $%.2f for %.1f miles$avgPart.".format(
                Locale.US, s.totalPay, s.totalMiles
            )
        )
        if (s.perPlatform.size > 1) {
            val parts = s.perPlatform.map {
                val n = if (it.count == 1) "delivery" else "deliveries"
                "${Platforms.displayFromTag(it.tag)}: ${it.count} $n, $%.2f".format(Locale.US, it.pay)
            }
            sb.append(" By platform — ${parts.joinToString("; ")}.")
        }
        if (s.bestLabel != null && s.worstLabel != null && s.count > 1) {
            sb.append(" Best: ${s.bestLabel}. Worst: ${s.worstLabel}.")
        } else if (s.bestLabel != null) {
            sb.append(" Best: ${s.bestLabel}.")
        }
        return sb.toString()
    }

    private class MutablePlatformAcc {
        var count = 0
        var pay = 0.0
        var miles = 0.0
    }
}

package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

data class AppHourSlice(
    val tag: String,
    val count: Int,
    val avgPay: Double,
    val avgPerMile: Double?
)

data class BestAppResult(
    val hour: Int,
    val slices: List<AppHourSlice>,
    val totalInHour: Int
)

/**
 * Best app RIGHT NOW — from his own log, per hour of day. Compares
 * average pay per delivery per platform for the current hour, code math
 * only. A platform needs at least 2 deliveries in that hour to count:
 * with fewer than 2 per platform the answer says the sample is too
 * small instead of guessing which app is paying better.
 */
object BestApp {

    fun analyze(context: Context, hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): BestAppResult {
        val byPlatform = linkedMapOf<String, MutableAcc>()
        var total = 0
        val arr: JSONArray = DeliveryLog.readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pay = if (obj.isNull("pay")) null else obj.optDouble("pay")
            val miles = if (obj.isNull("miles")) null else obj.optDouble("miles")
            val time = obj.optString("time")
            val entryHour = time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            if (entryHour != hour || pay == null) continue
            total++
            val tag = Platforms.tagFromDisplay(obj.optString("platform"))
            val acc = byPlatform.getOrPut(tag) { MutableAcc() }
            acc.count++
            acc.pay += pay
            if (miles != null) acc.miles += miles
        }
        val slices = byPlatform.entries.map { (tag, acc) ->
            AppHourSlice(
                tag = tag,
                count = acc.count,
                avgPay = acc.pay / acc.count,
                avgPerMile = if (acc.miles > 0) acc.pay / acc.miles else null
            )
        }.sortedByDescending { it.avgPay }
        return BestAppResult(hour = hour, slices = slices, totalInHour = total)
    }

    fun text(r: BestAppResult): String {
        val hourLabel = BestHours.hourLabel(r.hour)
        if (r.totalInHour == 0) {
            return "No deliveries logged around $hourLabel yet, so I can't say which app is paying better this hour. Log a few deliveries first."
        }
        val ranked = r.slices.filter { it.count >= 2 }
        if (ranked.size < 2) {
            val detail = r.slices.joinToString("; ") {
                "${Platforms.displayFromTag(it.tag)}: ${it.count} ${if (it.count == 1) "delivery" else "deliveries"}"
            }
            return "Sample too small to call it — around $hourLabel your log has $detail. " +
                "I need at least 2 deliveries per app in this hour before I'll compare them."
        }
        val best = ranked[0]
        val second = ranked[1]
        val bestPerMile = if (best.avgPerMile != null) {
            ", $%.2f a mile".format(Locale.US, best.avgPerMile)
        } else ""
        return "Around $hourLabel, ${Platforms.displayFromTag(best.tag)} is paying better than " +
            "${Platforms.displayFromTag(second.tag)} — averaging $%.2f a delivery$bestPerMile, " +
            "against $%.2f for ${Platforms.displayFromTag(second.tag)}. " +
            "That's from your own log: ${best.count} ${Platforms.displayFromTag(best.tag)} deliveries " +
            "and ${second.count} ${Platforms.displayFromTag(second.tag)} deliveries in this hour.".format(
                Locale.US, best.avgPay, second.avgPay
            )
    }

    private class MutableAcc {
        var count = 0
        var pay = 0.0
        var miles = 0.0
    }
}

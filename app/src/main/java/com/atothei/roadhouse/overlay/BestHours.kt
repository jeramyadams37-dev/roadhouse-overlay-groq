package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class HourSlice(
    val hour: Int,
    val count: Int,
    val totalPay: Double,
    val avgPay: Double,
    val avgPerMile: Double?
)

data class BestHoursResult(
    val totalCount: Int,
    val bestHours: List<HourSlice>,
    val bestDay: String?,
    val bestDayAvg: Double?
)

/**
 * Best earning hours from the driver's OWN log — code math only, never
 * the model. Groups logged deliveries by hour of day (from the log's
 * HH:mm time) and by day of week (from the yyyy-MM-dd date). Ranked by
 * average pay per delivery; an hour needs at least 2 deliveries to lead
 * the list, so one lucky delivery cannot crown an hour. With fewer than
 * 3 deliveries total the text says the sample is too small instead of
 * dressing up a guess as a pattern.
 */
object BestHours {

    fun analyze(context: Context): BestHoursResult {
        val byHour = mutableMapOf<Int, MutableAcc>()
        val byDay = mutableMapOf<Int, MutableAcc>()
        var total = 0
        val arr: JSONArray = DeliveryLog.readAll(context)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pay = if (obj.isNull("pay")) null else obj.optDouble("pay")
            val miles = if (obj.isNull("miles")) null else obj.optDouble("miles")
            val time = obj.optString("time")
            val hour = time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            if (pay == null) continue
            total++
            val hAcc = byHour.getOrPut(hour) { MutableAcc() }
            hAcc.count++
            hAcc.pay += pay
            if (miles != null) hAcc.miles += miles
            val date = try {
                dateFmt.parse(obj.optString("date"))
            } catch (e: Exception) {
                null
            }
            if (date != null) {
                val cal = Calendar.getInstance()
                cal.time = date
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val dAcc = byDay.getOrPut(dow) { MutableAcc() }
                dAcc.count++
                dAcc.pay += pay
                if (miles != null) dAcc.miles += miles
            }
        }
        val slices = byHour.entries
            .filter { it.value.count >= 2 }
            .map { (hour, acc) ->
                HourSlice(
                    hour = hour,
                    count = acc.count,
                    totalPay = acc.pay,
                    avgPay = acc.pay / acc.count,
                    avgPerMile = if (acc.miles > 0) acc.pay / acc.miles else null
                )
            }
            .sortedByDescending { it.avgPay }
            .take(3)
        // If no hour has 2+ deliveries, fall back to the single best hour
        // with 1 delivery — labelled as a thin sample by the text below.
        val bestHours = if (slices.isNotEmpty()) {
            slices
        } else {
            byHour.entries
                .map { (hour, acc) ->
                    HourSlice(
                        hour = hour,
                        count = acc.count,
                        totalPay = acc.pay,
                        avgPay = acc.pay / acc.count,
                        avgPerMile = if (acc.miles > 0) acc.pay / acc.miles else null
                    )
                }
                .sortedByDescending { it.avgPay }
                .take(1)
        }
        val bestDayEntry = byDay.entries
            .filter { it.value.count >= 2 }
            .maxByOrNull { it.value.pay / it.value.count }
        val dayNames = arrayOf(
            "", "Sunday", "Monday", "Tuesday", "Wednesday",
            "Thursday", "Friday", "Saturday"
        )
        return BestHoursResult(
            totalCount = total,
            bestHours = bestHours,
            bestDay = bestDayEntry?.let { dayNames.getOrNull(it.key) },
            bestDayAvg = bestDayEntry?.let { it.value.pay / it.value.count }
        )
    }

    fun text(r: BestHoursResult): String {
        if (r.totalCount == 0) {
            return "No deliveries with pay in your log yet, so I can't work out your best hours. Log a few deliveries first."
        }
        if (r.totalCount < 3) {
            return "Only ${r.totalCount} ${if (r.totalCount == 1) "delivery" else "deliveries"} in your log so far — too small a sample to call any hour your best. Log a few more days and ask again."
        }
        if (r.bestHours.isEmpty()) {
            return "Not enough logged deliveries to rank your hours yet. Log a few more days and ask again."
        }
        val sb = StringBuilder()
        sb.append("Your best hours, from your own log: ")
        sb.append(
            r.bestHours.joinToString("; ") { h ->
                val n = if (h.count == 1) "delivery" else "deliveries"
                "${hourLabel(h.hour)} averages $%.2f over ${h.count} $n".format(Locale.US, h.avgPay)
            }
        )
        sb.append(".")
        if (r.bestDay != null && r.bestDayAvg != null) {
            sb.append(
                " Best day: ${r.bestDay}, averaging $%.2f a delivery.".format(Locale.US, r.bestDayAvg)
            )
        }
        if (r.bestHours.any { it.count < 2 }) {
            sb.append(" Thin sample so far — the top hour has only one delivery behind it.")
        }
        return sb.toString()
    }

    /** 14 -> "2 PM", 9 -> "9 AM", 0 -> "12 AM". */
    fun hourLabel(hour: Int): String {
        return when {
            hour == 0 -> "12 AM"
            hour < 12 -> "$hour AM"
            hour == 12 -> "12 PM"
            else -> "${hour - 12} PM"
        }
    }

    private class MutableAcc {
        var count = 0
        var pay = 0.0
        var miles = 0.0
    }
}

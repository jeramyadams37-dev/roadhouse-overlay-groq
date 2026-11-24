package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class EarningsRange { TODAY, WEEK, MONTH }

data class EarningsTally(
    val range: EarningsRange,
    val label: String,
    val count: Int,
    val totalPay: Double,
    val totalMiles: Double,
    val avgPerMile: Double?
)

/**
 * Earnings tally, computed IN CODE from the local delivery log — never by
 * the model. Today / this week (Monday–Sunday) / this month. An empty range
 * says so plainly instead of inventing numbers.
 */
object Earnings {

    fun tally(context: Context, range: EarningsRange): EarningsTally {
        val (from, to, label) = rangeBounds(range)
        var count = 0
        var pay = 0.0
        var miles = 0.0
        val arr: JSONArray = DeliveryLog.readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val date = obj.optString("date")
            if (date < from || date > to) continue
            count++
            if (!obj.isNull("pay")) pay += obj.optDouble("pay")
            if (!obj.isNull("miles")) miles += obj.optDouble("miles")
        }
        val avg = if (miles > 0) pay / miles else null
        return EarningsTally(range, label, count, pay, miles, avg)
    }

    /** Short spoken + card text for a tally. */
    fun text(tally: EarningsTally): String {
        if (tally.count == 0) {
            return "Nothing logged ${tally.label.lowercase()} yet. Log a delivery first and I'll tally it."
        }
        val payStr = "$%.2f".format(Locale.US, tally.totalPay)
        val milesStr = "%.1f".format(Locale.US, tally.totalMiles)
        val avgPart = if (tally.avgPerMile != null) {
            ", $%.2f a mile".format(Locale.US, tally.avgPerMile)
        } else {
            ""
        }
        val noun = if (tally.count == 1) "delivery" else "deliveries"
        return "${tally.label}: ${tally.count} $noun, $payStr for $milesStr miles$avgPart."
    }

    /** (fromDate, toDate, label) for a range, dates as yyyy-MM-dd. */
    private fun rangeBounds(range: EarningsRange): Triple<String, String, String> {
        val today = todayStr()
        return when (range) {
            EarningsRange.TODAY -> Triple(today, today, "Today")
            EarningsRange.WEEK -> {
                val cal = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY }
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val monday = fmt(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 6)
                Triple(monday, fmt(cal.time), "This week")
            }
            EarningsRange.MONTH -> {
                val prefix = today.substring(0, 7) // yyyy-MM
                Triple("$prefix-01", "$prefix-31", "This month")
            }
        }
    }

    private fun todayStr(): String = fmt(Date())

    private fun fmt(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
}

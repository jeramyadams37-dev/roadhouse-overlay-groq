package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class BeatYesterdayResult(
    val todayPay: Double,
    val yesterdayPay: Double?,
    val hasYesterday: Boolean,
    val bestDayPay: Double?,
    val bestDayDate: String?,
    val isNewBestDay: Boolean
)

/**
 * Beat yesterday — daily streaks from the delivery log, code math.
 * Reports the numbers, never shames a slow day: "you're $12 ahead of
 * yesterday" is a fact, "you're behind yesterday" is the same fact the
 * other way round, with both totals named. "New best day" only when
 * today has passed every previous day's total. First logged day (no
 * yesterday in the log) says so plainly.
 */
object BeatYesterday {

    fun compare(context: Context): BeatYesterdayResult {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val byDay = mutableMapOf<String, Double>()
        val arr: JSONArray = DeliveryLog.readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.isNull("pay")) continue
            val date = obj.optString("date")
            byDay[date] = (byDay[date] ?: 0.0) + obj.optDouble("pay")
        }
        val todayPay = byDay[today] ?: 0.0
        val yesterdayPay = byDay[yesterday]
        var bestPay: Double? = null
        var bestDate: String? = null
        for ((date, pay) in byDay) {
            if (date == today) continue
            if (bestPay == null || pay > bestPay!!) {
                bestPay = pay
                bestDate = date
            }
        }
        val isNewBest = bestPay != null && todayPay > bestPay!! && todayPay > 0.0
        return BeatYesterdayResult(
            todayPay = todayPay,
            yesterdayPay = yesterdayPay,
            hasYesterday = yesterdayPay != null,
            bestDayPay = bestPay,
            bestDayDate = bestDate,
            isNewBestDay = isNewBest
        )
    }

    fun text(r: BeatYesterdayResult): String {
        if (r.todayPay <= 0.0) {
            return "Nothing logged today yet, so there's nothing to compare with yesterday."
        }
        val sb = StringBuilder()
        if (!r.hasYesterday) {
            sb.append("No yesterday in your log to compare with — today is your first logged day, $%.2f so far.".format(Locale.US, r.todayPay))
        } else {
            val diff = r.todayPay - r.yesterdayPay!!
            when {
                diff > 0.0 -> sb.append(
                    "You're $%.2f ahead of yesterday — $%.2f today against $%.2f yesterday.".format(
                        Locale.US, diff, r.todayPay, r.yesterdayPay
                    )
                )
                diff < 0.0 -> sb.append(
                    "You're $%.2f behind yesterday — $%.2f today against $%.2f yesterday. Slow days happen; the numbers are what they are.".format(
                        Locale.US, -diff, r.todayPay, r.yesterdayPay
                    )
                )
                else -> sb.append(
                    "Even with yesterday — $%.2f today and $%.2f yesterday.".format(
                        Locale.US, r.todayPay, r.yesterdayPay
                    )
                )
            }
        }
        if (r.isNewBestDay) {
            sb.append(
                " New best day — today has passed your previous best of $%.2f on ${r.bestDayDate}.".format(
                    Locale.US, r.bestDayPay!!
                )
            )
        }
        return sb.toString()
    }
}

package com.atothei.roadhouse.overlay

import android.content.Context
import java.util.Calendar
import java.util.Locale

data class WeeklyPaceResult(
    val earnedSoFar: Double,
    val elapsedDays: Int,
    val projectedWeek: Double,
    val count: Int
)

/**
 * Weekly pay pace — code math from the log. Method, stated in the
 * spoken text every time: this week's earnings so far, divided by the
 * elapsed days of the week (Monday = day 1), times 7. A pace is a
 * projection from his own week so far, never a promise.
 */
object WeeklyPace {

    fun pace(context: Context): WeeklyPaceResult {
        val tally = Earnings.tally(context, EarningsRange.WEEK)
        val cal = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY }
        // Monday = 1 … Sunday = 7 elapsed days of this week so far.
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val elapsed = when (dow) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }
        val projected = if (elapsed > 0) tally.totalPay / elapsed * 7.0 else 0.0
        return WeeklyPaceResult(
            earnedSoFar = tally.totalPay,
            elapsedDays = elapsed,
            projectedWeek = projected,
            count = tally.count
        )
    }

    fun text(r: WeeklyPaceResult): String {
        if (r.count == 0) {
            return "Nothing logged this week yet, so there is no pace to project. Log a delivery first."
        }
        val dayWord = if (r.elapsedDays == 1) "day" else "days"
        return "At this rate you'll make about $%.2f this week. " +
            "That's $%.2f earned so far over ${r.elapsedDays} $dayWord of the week, " +
            "divided by ${r.elapsedDays} and times 7 — a projection from your week so far, not a promise.".format(
                Locale.US, r.projectedWeek, r.earnedSoFar
            )
    }
}

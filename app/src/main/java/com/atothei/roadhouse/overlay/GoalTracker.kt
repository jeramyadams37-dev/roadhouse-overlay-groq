package com.atothei.roadhouse.overlay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Daily earnings goal. Set by voice ("set my goal to $150") or in
 * Settings; 0 (or unset) means no goal. Progress is reported only when
 * the driver asks ("how am I doing on my goal"). The goal-reached nudge
 * is spoken at most once per day, and only right after a delivery is
 * logged — never unprompted, never repeated, never nagging.
 */
object GoalTracker {

    private val moneyPattern = Pattern.compile("\\\$?\\s*(\\d+(?:\\.\\d{1,2})?)")

    /** Parse a dollar amount out of "set my goal to $150" / "my goal is 200". */
    fun parseGoalAmount(text: String): Double? {
        val m = moneyPattern.matcher(text)
        if (!m.find()) return null
        return m.group(1)?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10000.0 }
    }

    /** "You're $X toward your $Y goal today — Z% there, $W to go." */
    fun progressText(context: Context): String {
        val goal = Prefs.getDailyGoal(context)
        if (goal <= 0.0) {
            return "You haven't set a daily earnings goal yet. Say \"set my goal to 150 dollars\" or set it in Settings."
        }
        val tally = Earnings.tally(context, EarningsRange.TODAY)
        val pay = tally.totalPay
        return if (pay >= goal) {
            "Goal reached. You've made $%.2f today against your $%.2f goal.".format(
                Locale.US, pay, goal
            )
        } else {
            val pct = ((pay / goal) * 100).toInt()
            "You're $pct%% of the way to your goal — $%.2f of $%.2f today, $%.2f to go.".format(
                Locale.US, pay, goal, goal - pay
            )
        }
    }

    /**
     * If a goal is set, today's pay has reached it, and the nudge has not
     * already been spoken today, return the one-line nudge and mark it
     * announced. Otherwise return null. Callers invoke this only right
     * after a delivery is logged.
     */
    fun checkReached(context: Context): String? {
        val goal = Prefs.getDailyGoal(context)
        if (goal <= 0.0) return null
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (Prefs.getGoalAnnouncedDate(context) == today) return null
        val tally = Earnings.tally(context, EarningsRange.TODAY)
        if (tally.totalPay < goal) return null
        Prefs.setGoalAnnouncedDate(context, today)
        return "Daily goal reached — $%.2f today against your $%.2f goal.".format(
            Locale.US, tally.totalPay, goal
        )
    }
}

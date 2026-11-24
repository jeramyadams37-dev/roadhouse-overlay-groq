package com.atothei.roadhouse.overlay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shift lifecycle state, kept in Prefs (survives the process dying).
 * A shift starts on the first offer of the day (first offer check, or
 * the pre-shift check finishing) — see VoiceCommandHandler.maybeShiftStart.
 * The end-of-shift summary is OFFERED, never forced: on the next user
 * interaction after the last logged activity is more than 2 hours old
 * AND it is evening (5 PM or later), the handler offers it once.
 * Mechanism limit, stated honestly: there is no GPS home check here —
 * evening + 2 hours of quiet is a proxy for "probably home", so the
 * offer can fire on a slow evening even if he is still out. It is one
 * spoken question, a "no" or any other command ends it, and it never
 * repeats that day.
 */
object ShiftState {

    private const val QUIET_MS = 2L * 60L * 60L * 1000L
    private const val EVENING_HOUR = 17

    fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** True when no shift has started today yet. */
    fun isNewShift(context: Context): Boolean =
        Prefs.getShiftDate(context) != todayStr()

    /** Mark the shift started today. Returns true only on a genuinely new shift. */
    fun startShift(context: Context): Boolean {
        val today = todayStr()
        if (Prefs.getShiftDate(context) == today) return false
        Prefs.setShiftDate(context, today)
        Prefs.setBatteryWarnedDate(context, "")
        Prefs.setEncouragedDate(context, "")
        Prefs.setSummaryOfferedDate(context, "")
        Prefs.setBreakScheduledDate(context, "")
        touchActivity(context)
        return true
    }

    fun touchActivity(context: Context) {
        Prefs.setLastActivityMs(context, System.currentTimeMillis())
    }

    /** Should the end-of-shift summary be offered on this interaction? */
    fun shouldOfferSummary(context: Context): Boolean {
        val today = todayStr()
        if (Prefs.getShiftDate(context) != today) return false
        if (Prefs.getSummaryOfferedDate(context) == today) return false
        val last = Prefs.getLastActivityMs(context)
        if (last <= 0L) return false
        if (System.currentTimeMillis() - last < QUIET_MS) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < EVENING_HOUR) return false
        return true
    }

    fun markSummaryOffered(context: Context) {
        Prefs.setSummaryOfferedDate(context, todayStr())
    }

    /** Shift-start encouragement: due once per shift, never repeated. */
    fun encouragementDue(context: Context): Boolean {
        val today = todayStr()
        if (Prefs.getShiftDate(context) != today) return false
        if (Prefs.getEncouragedDate(context) == today) return false
        Prefs.setEncouragedDate(context, today)
        return true
    }
}

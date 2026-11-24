package com.atothei.roadhouse.overlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Break reminder — after 3 hours of continuous driving, ONE short
 * spoken nudge to take a breather; never nags repeatedly (at most once
 * per shift).
 *
 * MECHANISM: a single one-shot AlarmManager alarm set at shift start
 * (+3h), firing ReminderReceiver.ACTION_BREAK. No polling, no timer,
 * no background receiver running constantly — the alarm is the whole
 * mechanism. If the phone reboots mid-shift the alarm is lost (alarms
 * do not survive reboot); that is the known limit.
 */
object BreakReminder {

    private const val BREAK_AFTER_MS = 3L * 60L * 60L * 1000L

    /** Arm the +3h break alarm for this shift, once per shift only. */
    fun scheduleIfNeeded(context: Context) {
        try {
            val today = ShiftState.todayStr()
            if (Prefs.getBreakScheduledDate(context) == today) return
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = System.currentTimeMillis() + BREAK_AFTER_MS
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_BREAK
            }
            val pi = PendingIntent.getBroadcast(
                context,
                ReminderReceiver.BREAK_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Prefs.setBreakScheduledDate(context, today)
        } catch (e: Exception) {
        }
    }

    fun breakMessage(): String =
        "You've been driving about 3 hours — take a breather when you can. Stretch, drink some water, then carry on."
}

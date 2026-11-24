package com.atothei.roadhouse.overlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Locale
import java.util.regex.Pattern

/**
 * Voice timer — "set a 10 minute timer" (any minutes) for waiting on
 * a pickup. On-device AlarmManager only, same pattern as Reminders:
 * one alarm, fires once, speaks + posts a notification through
 * ReminderReceiver.ACTION_TIMER, then never repeats. A new timer
 * replaces the one before it.
 */
object VoiceTimer {

    private val timerPattern = Pattern.compile(
        "(?:set|start)\\s+(?:a\\s+)?(\\d+)\\s*(?:minutes?|mins?)\\s*(?:timer)?",
        Pattern.CASE_INSENSITIVE
    )
    private val secondsPattern = Pattern.compile(
        "(?:set|start)\\s+(?:a\\s+)?(\\d+)\\s*(?:seconds?|secs?)\\s*(?:timer)?",
        Pattern.CASE_INSENSITIVE
    )

    /** Minutes in "set a 10 minute timer", or null when it isn't a timer command. */
    fun parseMinutes(command: String): Int? {
        val lower = command.lowercase(Locale.US)
        if (!lower.contains("timer")) return null
        val m = timerPattern.matcher(command)
        if (m.find()) {
            val minutes = m.group(1)?.toIntOrNull() ?: return null
            if (minutes in 1..240) return minutes
            return null
        }
        val s = secondsPattern.matcher(command)
        if (s.find()) {
            val seconds = s.group(1)?.toIntOrNull() ?: return null
            if (seconds in 10..14400) return (seconds + 59) / 60
            return null
        }
        return null
    }

    /** Arm the one-shot timer alarm. Returns the trigger time, or null. */
    fun schedule(context: Context, minutes: Int, label: String = "Timer"): Long? {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return null
            val triggerAt = System.currentTimeMillis() + minutes * 60L * 1000L
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_TIMER
                putExtra(ReminderReceiver.EXTRA_TEXT, label)
                putExtra(ReminderReceiver.EXTRA_MINUTES, minutes)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                ReminderReceiver.TIMER_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            triggerAt
        } catch (e: Exception) {
            null
        }
    }

    fun confirmText(minutes: Int): String {
        val noun = if (minutes == 1) "minute" else "minutes"
        return "Timer set for $minutes $noun. I'll tell you when it's up."
    }

    fun firedText(minutes: Int): String {
        val noun = if (minutes == 1) "minute" else "minutes"
        return "Timer up — your $minutes $noun are done."
    }
}

package com.atothei.roadhouse.overlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class Reminder(
    val id: String,
    val triggerAtMs: Long,
    val text: String,
    val createdAtMs: Long,
    val fired: Boolean
)

/**
 * Voice reminders, on-device only — no server, no polling. Set by voice
 * ("remind me in 30 minutes to call dispatch", "remind me at 5pm to
 * check the fuel") and fired by AlarmManager, which wakes the
 * ReminderReceiver at the time; the receiver speaks the reminder and
 * shows a notification. Reminders are stored in reminders.json so a
 * reboot can re-schedule the ones still in the future (BootReceiver
 * calls rescheduleAll). An exact alarm is attempted first; if the
 * system refuses it (exact-alarm permission), a best-effort inexact
 * alarm is used instead and the voice confirmation says the time is
 * approximate.
 */
object Reminders {
    private const val FILE_NAME = "reminders.json"
    private const val MAX_REMINDERS = 100

    private val inPattern = Pattern.compile(
        "remind me in\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\s*(?:to\\s+)?(.*)",
        Pattern.CASE_INSENSITIVE
    )
    private val atPattern = Pattern.compile(
        "remind me at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*(?:to\\s+)?(.*)",
        Pattern.CASE_INSENSITIVE
    )

    data class ParsedReminder(val triggerAtMs: Long, val text: String)

    /** Parse a voice command into a trigger time + text, or null if it isn't a reminder. */
    fun parse(command: String): ParsedReminder? {
        val lower = command.lowercase(Locale.US)
        if (!lower.contains("remind me")) return null
        val inMatch = inPattern.matcher(command)
        if (inMatch.find()) {
            val amount = inMatch.group(1)?.toIntOrNull() ?: return null
            val unit = inMatch.group(2)?.lowercase(Locale.US) ?: return null
            val text = inMatch.group(3)?.trim().orEmpty()
            if (text.isBlank()) return null
            if (amount <= 0 || amount > 24 * 60) return null
            val delayMs = if (unit.startsWith("hour") || unit.startsWith("hr")) {
                amount * 60L * 60L * 1000L
            } else {
                amount * 60L * 1000L
            }
            return ParsedReminder(System.currentTimeMillis() + delayMs, text)
        }
        val atMatch = atPattern.matcher(command)
        if (atMatch.find()) {
            var hour = atMatch.group(1)?.toIntOrNull() ?: return null
            val minute = atMatch.group(2)?.toIntOrNull() ?: 0
            val ampm = atMatch.group(3)?.lowercase(Locale.US)
            val text = atMatch.group(4)?.trim().orEmpty()
            if (text.isBlank()) return null
            if (hour < 1 || hour > 12 || minute < 0 || minute > 59) return null
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (ampm == null && hour < 12) {
                // No am/pm: "remind me at 5 to …" means 5 PM if 5 AM has
                // already passed today, else 5 AM — never a time in the past.
                val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour <= nowHour) hour += 12
            }
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return ParsedReminder(cal.timeInMillis, text)
        }
        return null
    }

    /** Store the reminder and arm its alarm. Returns the reminder, or null. */
    fun add(context: Context, triggerAtMs: Long, text: String): Reminder? {
        return try {
            val reminder = Reminder(
                id = System.currentTimeMillis().toString(),
                triggerAtMs = triggerAtMs,
                text = text.trim().take(500),
                createdAtMs = System.currentTimeMillis(),
                fired = false
            )
            val arr = readAll(context)
            arr.put(toJson(reminder))
            while (arr.length() > MAX_REMINDERS) arr.remove(0)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            scheduleAlarm(context, reminder)
            reminder
        } catch (e: Exception) {
            null
        }
    }

    /** Arm the AlarmManager alarm for one reminder. Exact first, inexact fallback. */
    fun scheduleAlarm(context: Context, reminder: Reminder) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_REMIND
                putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
                putExtra(ReminderReceiver.EXTRA_TEXT, reminder.text)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                reminder.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pi)
            } catch (e: SecurityException) {
                // Exact alarms not permitted: best-effort inexact alarm.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pi)
            }
        } catch (e: Exception) {
            // Best effort: a failed alarm must not break the voice reply.
        }
    }

    /** Re-arm alarms for every unfired future reminder (after a reboot). */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (r in reminders(context)) {
            if (!r.fired && r.triggerAtMs > now) scheduleAlarm(context, r)
        }
    }

    /** Mark a reminder fired so it is never spoken twice. */
    fun markFired(context: Context, id: String): Boolean {
        return try {
            val arr = readAll(context)
            var changed = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") == id) {
                    obj.put("fired", true)
                    changed = true
                    break
                }
            }
            if (changed) File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            changed
        } catch (e: Exception) {
            false
        }
    }

    /** Reminders still waiting, soonest first. */
    fun pending(context: Context): List<Reminder> {
        val now = System.currentTimeMillis()
        return reminders(context)
            .filter { !it.fired && it.triggerAtMs > now }
            .sortedBy { it.triggerAtMs }
    }

    fun reminders(context: Context): List<Reminder> {
        val out = mutableListOf<Reminder>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(fromJson(obj))
        }
        return out
    }

    fun readAll(context: Context): JSONArray {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }

    /** "in 30 minutes" / "at 5:00 PM" — plain words for the confirmation. */
    fun whenWords(triggerAtMs: Long): String {
        val now = System.currentTimeMillis()
        val diffMin = ((triggerAtMs - now) / 60000L).toInt()
        if (diffMin < 1) return "in less than a minute"
        if (diffMin < 60) return "in $diffMin ${if (diffMin == 1) "minute" else "minutes"}"
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        return "at ${fmt.format(Date(triggerAtMs))}"
    }

    private fun toJson(r: Reminder): JSONObject {
        return JSONObject().apply {
            put("id", r.id)
            put("trigger_at_ms", r.triggerAtMs)
            put("text", r.text)
            put("created_at_ms", r.createdAtMs)
            put("fired", r.fired)
        }
    }

    private fun fromJson(obj: JSONObject): Reminder {
        return Reminder(
            id = obj.optString("id"),
            triggerAtMs = obj.optLong("trigger_at_ms", 0L),
            text = obj.optString("text"),
            createdAtMs = obj.optLong("created_at_ms", 0L),
            fired = obj.optBoolean("fired", false)
        )
    }
}

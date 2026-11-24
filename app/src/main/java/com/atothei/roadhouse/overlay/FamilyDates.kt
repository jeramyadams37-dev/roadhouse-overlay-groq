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

data class FamilyDate(
    val id: String,
    val name: String,
    val occasion: String,
    val month: Int,
    val day: Int
)

/**
 * Family date reminders — dates he adds by voice ("remind me Ilah's
 * birthday is June 3"). NEVER invents a date: only ones he gave it
 * are stored (family_dates.json). Checked once per day by a single
 * 8:00 AM alarm (FamilyDates.scheduleDailyCheck, re-armed after boot
 * and after every check); speaks only when a stored date matches
 * today, otherwise stays silent.
 */
object FamilyDates {
    private const val FILE_NAME = "family_dates.json"
    private const val CHECK_HOUR = 8

    private val months = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4,
        "may" to 5, "june" to 6, "july" to 7, "august" to 8,
        "september" to 9, "october" to 10, "november" to 11, "december" to 12
    )

    private val addPattern = Pattern.compile(
        "(?:remind me|remember)\\s+(?:that\\s+)?(.+?)'s\\s+(birthday|anniversary)\\s+is\\s+([A-Za-z]+)\\s+(\\d{1,2})",
        Pattern.CASE_INSENSITIVE
    )

    /** Parse "remind me Ilah's birthday is June 3" into (name, occasion, month, day). */
    fun parse(command: String): FamilyDate? {
        val m = addPattern.matcher(command.trim())
        if (!m.find()) return null
        val name = m.group(1)?.trim().orEmpty()
        val occasion = m.group(2)?.lowercase(Locale.US).orEmpty()
        val month = months[m.group(3)?.lowercase(Locale.US)] ?: return null
        val day = m.group(4)?.toIntOrNull() ?: return null
        if (name.isBlank() || day < 1 || day > 31) return null
        return FamilyDate(
            id = System.currentTimeMillis().toString(),
            name = name,
            occasion = occasion,
            month = month,
            day = day
        )
    }

    fun add(context: Context, date: FamilyDate): Boolean {
        return try {
            val arr = readAll(context)
            // Same person + occasion replaces the old date, never duplicates.
            for (i in arr.length() - 1 downTo 0) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("name").equals(date.name, ignoreCase = true) &&
                    obj.optString("occasion") == date.occasion
                ) {
                    arr.remove(i)
                }
            }
            arr.put(
                JSONObject().apply {
                    put("id", date.id)
                    put("name", date.name)
                    put("occasion", date.occasion)
                    put("month", date.month)
                    put("day", date.day)
                }
            )
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Stored dates matching today (month + day), in the order added. */
    fun todayMatches(context: Context): List<FamilyDate> {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return dates(context).filter { it.month == month && it.day == day }
    }

    fun dates(context: Context): List<FamilyDate> {
        val out = mutableListOf<FamilyDate>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                FamilyDate(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    occasion = obj.optString("occasion"),
                    month = obj.optInt("month", 0),
                    day = obj.optInt("day", 0)
                )
            )
        }
        return out
    }

    fun textForToday(matches: List<FamilyDate>): String {
        val parts = matches.map {
            val what = if (it.occasion == "anniversary") "anniversary" else "birthday"
            "Today is ${it.name}'s $what"
        }
        return parts.joinToString(". ") + ". Don't forget to wish them well."
    }

    /** Arm the one daily 8:00 AM check alarm (next occurrence). */
    fun scheduleDailyCheck(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, CHECK_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_FAMILY_DATE
            }
            val pi = PendingIntent.getBroadcast(
                context,
                ReminderReceiver.FAMILY_DATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: Exception) {
        }
    }

    fun monthName(month: Int): String {
        return months.entries.firstOrNull { it.value == month }?.key
            ?.replaceFirstChar { it.uppercaseChar() } ?: "month $month"
    }

    fun readAll(context: Context): JSONArray {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun count(context: Context): Int = readAll(context).length()

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }
}

package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

data class JournalEntry(
    val date: String,
    val time: String,
    val text: String
)

/**
 * Voice journal — an end-of-shift voice note to himself, saved on
 * device as TRANSCRIBED TEXT in journal.json (not audio: text reads
 * back cleanly the next morning and needs no microphone file handling
 * or extra storage). Explicit-only: records only when he asks it to
 * ("journal", "record a journal note"). Read back on request
 * ("read my journal") — today's entries first, otherwise the most
 * recent day's.
 */
object VoiceJournal {
    private const val FILE_NAME = "journal.json"

    fun add(context: Context, text: String): Boolean {
        return try {
            val clean = text.trim()
            if (clean.isEmpty()) return false
            val (date, time) = DeliveryLog.nowParts()
            val obj = JSONObject().apply {
                put("date", date)
                put("time", time)
                put("text", clean.take(1000))
            }
            val arr = readAll(context)
            arr.put(obj)
            while (arr.length() > 500) arr.remove(0)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun entries(context: Context): List<JournalEntry> {
        val out = mutableListOf<JournalEntry>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                JournalEntry(
                    date = obj.optString("date"),
                    time = obj.optString("time"),
                    text = obj.optString("text")
                )
            )
        }
        return out
    }

    fun readBackText(context: Context): String {
        val all = entries(context)
        if (all.isEmpty()) {
            return "Your journal is empty. Say \"record a journal note\" at the end of a shift to leave yourself a note."
        }
        val today = DeliveryLog.nowParts().first
        val todays = all.filter { it.date == today }
        val (label, chosen) = if (todays.isNotEmpty()) {
            "today" to todays
        } else {
            val lastDate = all.last().date
            "from $lastDate" to all.filter { it.date == lastDate }
        }
        val lines = chosen.mapIndexed { i, e -> "${i + 1}. ${e.text}" }.joinToString("\n")
        return "Journal $label:\n$lines"
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

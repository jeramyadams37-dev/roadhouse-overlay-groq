package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoreNote(
    val id: String,
    val storeKey: String,
    val storeLabel: String,
    val text: String,
    val createdAt: String
)

/**
 * Per-store notes in the driver's own words, kept in a local JSON file in
 * the app's private files dir. On-device only. A note is tied to the store
 * key (store name + address) of the screen it was saved from, so it comes
 * back automatically the next time an offer from that store is checked.
 */
object StoreNotes {
    private const val FILE_NAME = "store_notes.json"
    private const val MAX_NOTES = 500

    fun add(context: Context, storeKey: String, storeLabel: String, text: String): StoreNote? {
        return try {
            val clean = text.trim()
            if (clean.isEmpty() || storeKey.isBlank()) return null
            val note = StoreNote(
                id = System.currentTimeMillis().toString(),
                storeKey = storeKey,
                storeLabel = storeLabel,
                text = clean.take(500),
                createdAt = nowStamp()
            )
            val arr = readAll(context)
            arr.put(toJson(note))
            while (arr.length() > MAX_NOTES) arr.remove(0)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            note
        } catch (e: Exception) {
            null
        }
    }

    /** All notes for one store, oldest first. */
    fun notesFor(context: Context, storeKey: String): List<StoreNote> {
        if (storeKey.isBlank()) return emptyList()
        return notes(context).filter { it.storeKey == storeKey }
    }

    /** Delete one note by id. */
    fun delete(context: Context, id: String): Boolean {
        return try {
            val arr = readAll(context)
            var changed = false
            for (i in arr.length() - 1 downTo 0) {
                if (arr.optJSONObject(i)?.optString("id") == id) {
                    arr.remove(i)
                    changed = true
                }
            }
            if (changed) File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            changed
        } catch (e: Exception) {
            false
        }
    }

    /** Delete every note for one store. Returns how many were deleted. */
    fun deleteAllFor(context: Context, storeKey: String): Int {
        if (storeKey.isBlank()) return 0
        return try {
            val arr = readAll(context)
            var deleted = 0
            for (i in arr.length() - 1 downTo 0) {
                if (arr.optJSONObject(i)?.optString("store_key") == storeKey) {
                    arr.remove(i)
                    deleted++
                }
            }
            if (deleted > 0) File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            deleted
        } catch (e: Exception) {
            0
        }
    }

    fun notes(context: Context): List<StoreNote> {
        val out = mutableListOf<StoreNote>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                StoreNote(
                    id = obj.optString("id"),
                    storeKey = obj.optString("store_key"),
                    storeLabel = obj.optString("store_label"),
                    text = obj.optString("text"),
                    createdAt = obj.optString("created_at")
                )
            )
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

    fun count(context: Context): Int = readAll(context).length()

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }

    /** One short spoken line for a store's notes, or null when there are none. */
    fun spokenLine(notes: List<StoreNote>): String? {
        if (notes.isEmpty()) return null
        return if (notes.size == 1) {
            "Note for this store: ${notes[0].text}"
        } else {
            "You have ${notes.size} notes for this store. First one: ${notes[0].text}"
        }
    }

    private fun toJson(n: StoreNote): JSONObject {
        return JSONObject().apply {
            put("id", n.id)
            put("store_key", n.storeKey)
            put("store_label", n.storeLabel)
            put("text", n.text)
            put("created_at", n.createdAt)
        }
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}

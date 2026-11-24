package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AddressNote(
    val id: String,
    val addressKey: String,
    val addressLabel: String,
    val text: String,
    val createdAt: String
)

/**
 * Per-delivery-address notes in the driver's own words, kept in a local
 * JSON file in the app's private files dir. On-device only.
 *
 * EXPLICIT ONLY: a note exists only because the driver told the overlay
 * to remember something for an address ("remember: gate code 1234 for
 * this address"). Nothing is ever auto-captured from the screen — no
 * address, gate code, or anything else is saved unless he said so.
 * A note is keyed by the normalized delivery address, so it comes back
 * automatically the next time an offer or delivery shows that address.
 * Recall follows the same rules as store notes: questions LIST, they
 * never save.
 */
object AddressNotes {
    private const val FILE_NAME = "address_notes.json"
    private const val MAX_NOTES = 500

    fun add(context: Context, addressKey: String, addressLabel: String, text: String): AddressNote? {
        return try {
            val clean = text.trim()
            if (clean.isEmpty() || addressKey.isBlank()) return null
            val note = AddressNote(
                id = System.currentTimeMillis().toString(),
                addressKey = addressKey,
                addressLabel = addressLabel,
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

    /** All notes for one address, oldest first. */
    fun notesFor(context: Context, addressKey: String): List<AddressNote> {
        if (addressKey.isBlank()) return emptyList()
        return notes(context).filter { it.addressKey == addressKey }
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

    /** Delete every note for one address. Returns how many were deleted. */
    fun deleteAllFor(context: Context, addressKey: String): Int {
        if (addressKey.isBlank()) return 0
        return try {
            val arr = readAll(context)
            var deleted = 0
            for (i in arr.length() - 1 downTo 0) {
                if (arr.optJSONObject(i)?.optString("address_key") == addressKey) {
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

    fun notes(context: Context): List<AddressNote> {
        val out = mutableListOf<AddressNote>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                AddressNote(
                    id = obj.optString("id"),
                    addressKey = obj.optString("address_key"),
                    addressLabel = obj.optString("address_label"),
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

    /** One short spoken line for an address's notes, or null when there are none. */
    fun spokenLine(notes: List<AddressNote>): String? {
        if (notes.isEmpty()) return null
        return if (notes.size == 1) {
            "Note for this address: ${notes[0].text}"
        } else {
            "You have ${notes.size} notes for this address. First one: ${notes[0].text}"
        }
    }

    private fun toJson(n: AddressNote): JSONObject {
        return JSONObject().apply {
            put("id", n.id)
            put("address_key", n.addressKey)
            put("address_label", n.addressLabel)
            put("text", n.text)
            put("created_at", n.createdAt)
        }
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}

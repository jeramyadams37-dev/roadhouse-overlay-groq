package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Store likes and avoids — explicit only, same notes pattern as
 * StoreNotes/AddressNotes. "Avoid this store" / "I like this store"
 * flags a store by its store key; the flag is stored per store and
 * read back at offer-check time, where an avoided store gets a plain
 * spoken warning. Nothing is ever flagged automatically.
 */
object StorePrefs {
    private const val FILE_NAME = "store_prefs.json"

    enum class Pref { LIKE, AVOID }

    fun set(context: Context, storeKey: String, storeLabel: String, pref: Pref): Boolean {
        return try {
            if (storeKey.isBlank()) return false
            val arr = readAll(context)
            var updated = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("store_key") == storeKey) {
                    obj.put("pref", pref.name)
                    obj.put("store_label", storeLabel)
                    updated = true
                    break
                }
            }
            if (!updated) {
                arr.put(
                    JSONObject().apply {
                        put("store_key", storeKey)
                        put("store_label", storeLabel)
                        put("pref", pref.name)
                    }
                )
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun get(context: Context, storeKey: String): Pref? {
        if (storeKey.isBlank()) return null
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("store_key") == storeKey) {
                return when (obj.optString("pref")) {
                    "LIKE" -> Pref.LIKE
                    "AVOID" -> Pref.AVOID
                    else -> null
                }
            }
        }
        return null
    }

    fun clear(context: Context, storeKey: String): Boolean {
        return try {
            val arr = readAll(context)
            var changed = false
            for (i in arr.length() - 1 downTo 0) {
                if (arr.optJSONObject(i)?.optString("store_key") == storeKey) {
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

    /** Plain warning for an avoided store, or null (liked / unflagged stores stay silent). */
    fun warningFor(context: Context, storeKey: String, storeLabel: String): String? {
        if (get(context, storeKey) != Pref.AVOID) return null
        val label = storeLabel.ifBlank { "this store" }
        return "Heads up: you've flagged $label as a store to avoid."
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

    fun clearAll(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }
}

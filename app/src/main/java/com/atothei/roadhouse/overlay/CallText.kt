package com.atothei.roadhouse.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Call and text hands-free. "Call Ella" dials by voice — he names the
 * person, it dials; it NEVER dials on its own. Calls use ACTION_DIAL
 * (opens the dialer with the number filled in — never ACTION_CALL, so
 * no call is placed without his explicit tap in the moment). "Read my
 * messages" reads unread texts aloud via the system SMS provider.
 * Permissions (CALL_PHONE / READ_SMS / READ_CONTACTS) are requested
 * when first used (Settings button); if denied, the answer is a plain
 * spoken message and nothing half-done happens.
 */
object CallText {

    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Resolve a spoken name to (display name, phone number) from contacts, or null. */
    fun findContact(context: Context, name: String): Pair<String, String>? {
        if (!hasContactsPermission(context)) return null
        return try {
            val clean = name.trim()
            if (clean.isBlank()) return null
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$clean%")
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val display = cursor.getString(0) ?: clean
                    val number = cursor.getString(1) ?: return null
                    if (number.isBlank()) null else display to number
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Dial [name]: returns (message to speak, dial Intent to fire or
     * null). Never returns an ACTION_CALL intent — ACTION_DIAL only.
     */
    fun dial(context: Context, name: String): Pair<String, Intent?> {
        if (!hasContactsPermission(context)) {
            return "I need contacts permission to find that number — allow it in Settings under \"Allow calls and messages\", then try again." to null
        }
        val found = findContact(context, name)
            ?: return "I couldn't find $name in your contacts. Check the name and try again." to null
        val (display, number) = found
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return "Dialing $display — the dialer is open with the number filled in. Tap call when you're ready." to intent
    }

    data class SmsMessage(val sender: String, val body: String, val dateMs: Long)

    /** Up to 3 unread texts, newest first. Empty list = none (or no permission — see hasSmsPermission). */
    fun unreadMessages(context: Context, limit: Int = 3): List<SmsMessage> {
        if (!hasSmsPermission(context)) return emptyList()
        val out = mutableListOf<SmsMessage>()
        return try {
            val uri = Uri.parse("content://sms/inbox")
            val projection = arrayOf("address", "body", "date")
            val selection = "read = 0"
            context.contentResolver.query(uri, projection, selection, null, "date DESC")?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit) {
                    val address = cursor.getString(0) ?: "Unknown"
                    val body = cursor.getString(1) ?: ""
                    val dateMs = cursor.getLong(2)
                    val sender = resolveSenderName(context, address) ?: address
                    out.add(SmsMessage(sender, body, dateMs))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolveSenderName(context: Context, number: String): String? {
        if (!hasContactsPermission(context)) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun unreadText(context: Context): String {
        if (!hasSmsPermission(context)) {
            return "I need message permission to read your texts — allow it in Settings under \"Allow calls and messages\", then try again."
        }
        val messages = unreadMessages(context)
        if (messages.isEmpty()) {
            return "No unread messages."
        }
        val noun = if (messages.size == 1) "message" else "messages"
        val sb = StringBuilder("${messages.size} unread $noun. ")
        messages.forEachIndexed { i, m ->
            val body = m.body.replace(Regex("\\s+"), " ").trim().take(200)
            sb.append("From ${m.sender}: $body. ")
        }
        return sb.toString().trim()
    }
}

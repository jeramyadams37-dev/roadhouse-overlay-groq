package com.atothei.roadhouse.overlay

/**
 * Customer message helper — drafts "I'm here" / "running 5 minutes
 * late" (and a custom one from his words) and READS IT OUT for him to
 * send. It NEVER sends anything itself: no SMS, no in-app send, no
 * intent that sends. It only speaks the draft and shows it on the
 * card; he copies it or reads it himself.
 */
object CustomerMessage {

    enum class Kind { HERE, LATE, CUSTOM }

    fun draft(kind: Kind, customText: String?, address: String?): String {
        return when (kind) {
            Kind.HERE -> {
                val where = if (!address.isNullOrBlank()) " at $address" else ""
                "Hi, it's your driver — I'm here with your delivery$where. " +
                    "I'll leave it where you asked, or just let me know."
            }
            Kind.LATE -> {
                "Hi, running about 5 minutes late — sorry about that. " +
                    "I'm on my way and I'll be there shortly."
            }
            Kind.CUSTOM -> {
                val words = customText?.trim().orEmpty()
                if (words.isBlank()) {
                    "Tell me what you want the message to say and I'll read the draft back to you."
                } else {
                    "Here's your draft: \"$words\""
                }
            }
        }
    }

    /** What kind of draft is this command asking for, or null. */
    fun kindFor(lower: String): Kind? {
        return when {
            lower.contains("running late") || lower.contains("running 5") ||
                lower.contains("i'm late") || lower.contains("i am late") ||
                lower.contains("going to be late") || lower.contains("be a few minutes late") -> Kind.LATE
            lower.contains("i'm here") || lower.contains("i am here") ||
                lower.contains("i've arrived") || lower.contains("i have arrived") ||
                lower.contains("tell the customer i'm here") ||
                lower.contains("message the customer") ||
                lower.contains("text the customer") -> Kind.HERE
            lower.contains("draft a message") || lower.contains("draft a text") ||
                lower.contains("write a message") || lower.contains("message for the customer") -> Kind.CUSTOM
            else -> null
        }
    }

    /** Custom message words after "draft a message …" / "tell the customer …". */
    fun customWords(command: String): String {
        var text = command.trim()
        for (prefix in listOf(
            "draft a message", "draft a text", "write a message",
            "tell the customer", "message for the customer"
        )) {
            if (text.lowercase().startsWith(prefix)) {
                text = text.substring(prefix.length).trim().trimStart(':', '-', ' ').trim()
                break
            }
        }
        return text
    }
}

package com.atothei.roadhouse.overlay

/**
 * Conversation mode (talk while driving) + dead-mile entertainment.
 *
 * Conversation opens ONLY when he starts it ("talk to me", "keep me
 * company") and ends on "stop talking" / "that's enough". While it is
 * on, ordinary talk goes to Groq through the existing GroqClient.ask
 * path — never unprompted chatter, and an offer event pauses it: the
 * offer wins, the conversation stops. Jokes / trivia / stories use the
 * same chat path.
 *
 * Dead-mile entertainment: on a long empty stretch (a dead-mile
 * warning is showing) the overlay may OFFER — never play — a joke,
 * short story, trivia, or music; it only plays if he says yes. One
 * offer per empty stretch at most, and never while an offer is on
 * screen (the offer check's own verdict always comes first).
 */
object Conversation {

    const val CHAT_SYSTEM_PROMPT = "You are RoadHouse, a friendly co-pilot talking " +
        "with a gig delivery driver while he drives. Keep replies short and spoken — " +
        "under 40 words unless he asks for detail. Be warm, plain, and human. " +
        "Never give driving directions, never tell him to accept or decline an " +
        "offer, never invent pay, miles, addresses, or dates. If you do not " +
        "know something, say so plainly."

    fun isChatStart(lower: String): Boolean =
        lower.contains("talk to me") || lower.contains("keep me company") ||
            lower.contains("chat with me") || lower.contains("let's talk") ||
            lower.contains("lets talk") || lower.contains("talk with me") ||
            lower.contains("what should i listen to") || lower.contains("suggest a song")

    fun isChatStop(lower: String): Boolean =
        lower.contains("stop talking") || lower.contains("that's enough") ||
            lower.contains("thats enough") || lower.contains("stop chatting") ||
            lower.contains("enough talking") || lower.contains("quiet") ||
            lower == "stop"

    fun isJoke(lower: String): Boolean =
        lower.contains("tell me a joke") || lower.contains("give me a joke") ||
            lower.contains("make me laugh") || lower.contains("a joke")

    fun isTrivia(lower: String): Boolean =
        lower.contains("trivia") || lower.contains("give me a fact") ||
            lower.contains("tell me a fact") || lower.contains("random fact")

    fun isStory(lower: String): Boolean =
        lower.contains("tell me a story") || lower.contains("a short story") ||
            lower.contains("tell a story")

    /** One offer per empty stretch: the stretch key is the last-dropoff address key. */
    fun entertainmentOfferText(): String =
        "Long empty stretch ahead. Want some company — a joke, a short story, " +
            "some trivia, or music? Just say which one."

    fun isEntertainmentPick(lower: String): Boolean =
        isJoke(lower) || isTrivia(lower) || isStory(lower) ||
            lower.contains("music") || lower.contains("play something") ||
            lower.contains("song")

    fun isYes(lower: String): Boolean {
        val t = lower.trim()
        return t == "yes" || t == "yeah" || t == "yep" || t == "sure" ||
            t == "ok" || t == "okay" || t.startsWith("yes ") ||
            t.startsWith("yeah ") || t.contains("yes please") || t.contains("go ahead")
    }

    fun isNo(lower: String): Boolean {
        val t = lower.trim()
        return t == "no" || t == "nope" || t == "nah" || t.startsWith("no ") ||
            t.contains("not now") || t.contains("maybe later") || t.contains("not right now")
    }
}

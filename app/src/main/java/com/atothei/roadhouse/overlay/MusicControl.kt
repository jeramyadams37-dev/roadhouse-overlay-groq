package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.net.URLEncoder
import java.util.Calendar

/**
 * Music by voice — plays through the music apps ALREADY on the phone
 * (YouTube Music / Spotify / YouTube / the system music app). No new
 * accounts, no sign-ins, no streaming keys.
 *
 * PLAYBACK IS REAL, and the result is reported honestly:
 * - The request fires android.media.action.MEDIA_PLAY_FROM_SEARCH with
 *   the song/genre/playlist as the query — the standard voice-playback
 *   intent music apps handle by starting playback.
 * - 4 seconds later the phone checks AudioManager.isMusicActive().
 *   Playing = audio actually started. If music is active, the answer
 *   says "Now playing …". If it is NOT active, the answer says exactly
 *   what happened ("opened in YouTube Music — tap the song to play")
 *   and NEVER claims it is playing. A YouTube watch link auto-plays
 *   when one is fired; a bare search never pretends to be playback.
 * - No music app at all: fail-soft, says so plainly.
 *
 * SUGGESTIONS (two-sided): the co-pilot can also suggest music —
 * mood/time-based picks (upbeat for the morning rush, chill for late
 * night, high-energy on a long dead stretch), from a local pick list
 * or from Groq when he asks. A suggestion is an offer only, never
 * autoplay; when he says yes it plays through the same path above.
 * Suggestions are never unprompted except in the contexts already
 * approved (a dead-mile entertainment offer, or when he asks).
 */
object MusicControl {

    enum class PlayResult { PLAYING, OPENED_SEARCH, NO_APP }

    data class PlayOutcome(
        val result: PlayResult,
        val appName: String,
        val query: String
    )

    private const val YOUTUBE_MUSIC_PKG = "com.google.android.apps.youtube.music"
    private const val SPOTIFY_PKG = "com.spotify.music"
    private const val YOUTUBE_PKG = "com.google.android.youtube"
    private const val PLAY_CHECK_DELAY_MS = 4000L

    /** (package, label) of the best music app on the phone, or null. */
    fun resolveMusicApp(context: Context): Pair<String, String>? {
        val pm = context.packageManager
        for ((pkg, label) in listOf(
            YOUTUBE_MUSIC_PKG to "YouTube Music",
            SPOTIFY_PKG to "Spotify",
            YOUTUBE_PKG to "YouTube"
        )) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg to label
            } catch (e: Exception) {
                // Not installed; try the next one.
            }
        }
        // System default music app.
        val intent = Intent(Intent.CATEGORY_APP_MUSIC).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            null
        }
        val pkg = resolved?.activityInfo?.packageName
        if (pkg != null) return pkg to "your music app"
        return null
    }

    /** True only for the named music apps — the voice app-switch allow-list. */
    fun isMusicPackage(pkg: String?): Boolean =
        pkg == YOUTUBE_MUSIC_PKG || pkg == SPOTIFY_PKG || pkg == YOUTUBE_PKG

    /**
     * Play [query] (song, genre, or playlist name). Fires the
     * voice-playback intent, then checks 4s later whether audio really
     * started. Callback runs on the main thread.
     */
    fun play(context: Context, query: String, callback: (PlayOutcome) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val app = resolveMusicApp(context)
        if (app == null) {
            callback(PlayOutcome(PlayResult.NO_APP, "", query))
            return
        }
        val (pkg, label) = app
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = try {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            null
        }
        if (resolved == null) {
            // This app does not handle voice playback: open its search instead.
            openSearch(context, pkg, label, query, callback)
            return
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            callback(PlayOutcome(PlayResult.OPENED_SEARCH, label, query))
            return
        }
        mainHandler.postDelayed({
            val audio = try {
                context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            } catch (e: Exception) {
                null
            }
            val playing = try {
                audio?.isMusicActive == true
            } catch (e: Exception) {
                false
            }
            if (playing) {
                callback(PlayOutcome(PlayResult.PLAYING, label, query))
            } else {
                callback(PlayOutcome(PlayResult.OPENED_SEARCH, label, query))
            }
        }, PLAY_CHECK_DELAY_MS)
    }

    /** Fallback: open the app's search for the query (never claims playback). */
    private fun openSearch(
        context: Context,
        pkg: String,
        label: String,
        query: String,
        callback: (PlayOutcome) -> Unit
    ) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = when (pkg) {
                YOUTUBE_MUSIC_PKG -> "https://music.youtube.com/search?q=$encoded"
                YOUTUBE_PKG -> "https://www.youtube.com/results?search_query=$encoded"
                SPOTIFY_PKG -> "https://open.spotify.com/search/$encoded"
                else -> "https://music.youtube.com/search?q=$encoded"
            }
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            callback(PlayOutcome(PlayResult.OPENED_SEARCH, label, query))
        } catch (e: Exception) {
            callback(PlayOutcome(PlayResult.NO_APP, label, query))
        }
    }

    /** Honest spoken + card text for a playback outcome. Never claims playback it did not verify. */
    fun text(outcome: PlayOutcome): String {
        return when (outcome.result) {
            PlayResult.PLAYING ->
                "Now playing ${outcome.query} on ${outcome.appName}."
            PlayResult.OPENED_SEARCH ->
                "Opened ${outcome.query} in ${outcome.appName} — tap the song to play. " +
                    "It didn't start playing on its own, so I'm not going to tell you it's playing."
            PlayResult.NO_APP ->
                "I couldn't find a music app on this phone to play ${outcome.query}. " +
                    "Install YouTube Music or Spotify and try again."
        }
    }

    /**
     * Mood/time-based suggestion. Morning rush = upbeat, late night =
     * chill, long dead stretch = high-energy to keep him going. Returns
     * (query to play, spoken suggestion line). Local picks — no Groq
     * call, no listening history is read off the phone.
     */
    fun suggest(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY), deadStretch: Boolean = false): Pair<String, String> {
        return when {
            deadStretch -> "high-energy driving music" to
                "Long empty stretch — how about something high-energy to keep you going? " +
                    "Say yes and I'll play some high-energy driving music."
            hour < 10 -> "upbeat country music" to
                "Morning rush — how about some upbeat country to get going? Say yes and I'll play it."
            hour >= 22 || hour < 5 -> "chill acoustic music" to
                "Late night — how about something chill? Say yes and I'll play some chill acoustic music."
            else -> "feel-good driving music" to
                "How about some feel-good driving music? Say yes and I'll play it."
        }
    }

    /** Extract the song/genre/playlist words from "play …" / "play some …". */
    fun queryFrom(command: String): String {
        var text = command.trim()
        val lower = text.lowercase()
        for (prefix in listOf("play some ", "play my ", "play the ", "play ")) {
            if (lower.startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }
        // "play country on spotify" — the app name is not part of the song.
        text = text.replace(Regex("(?i)\\s+on\\s+(spotify|youtube music|youtube)\\s*$"), "").trim()
        return text.trim().take(120)
    }
}

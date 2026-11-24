package com.atothei.roadhouse.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Fires a voice reminder at its AlarmManager time. Speaks the reminder
 * with its own one-shot TextToSpeech (the overlay service may not be
 * running) and posts a notification with the same words, so the reminder
 * is not lost if speech is off or TTS fails. The reminder is marked
 * fired before speaking so it can never fire twice.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.atothei.roadhouse.overlay.groq.REMIND"
        const val ACTION_BREAK = "com.atothei.roadhouse.overlay.groq.BREAK"
        const val ACTION_FAMILY_DATE = "com.atothei.roadhouse.overlay.groq.FAMILY_DATE"
        const val ACTION_TIMER = "com.atothei.roadhouse.overlay.groq.TIMER"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TEXT = "reminder_text"
        const val EXTRA_MINUTES = "timer_minutes"
        const val BREAK_REQUEST_CODE = 3001
        const val FAMILY_DATE_REQUEST_CODE = 3002
        const val TIMER_REQUEST_CODE = 3003
        private const val CHANNEL_ID = "roadhouse_reminders"
        private const val NOTIF_ID_BASE = 2000
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_BREAK -> {
                val spoken = BreakReminder.breakMessage()
                postNotification(context, spoken, "break")
                speakOnce(context.applicationContext, spoken)
            }
            ACTION_FAMILY_DATE -> {
                // Re-arm tomorrow's 8 AM check first, so a crash below cannot lose it.
                FamilyDates.scheduleDailyCheck(context)
                val matches = FamilyDates.todayMatches(context)
                if (matches.isEmpty()) return // Silent on days with no family date.
                val spoken = FamilyDates.textForToday(matches)
                postNotification(context, spoken, "family")
                speakOnce(context.applicationContext, spoken)
            }
            ACTION_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                val spoken = VoiceTimer.firedText(minutes)
                postNotification(context, spoken, "timer")
                speakOnce(context.applicationContext, spoken)
            }
            ACTION_REMIND -> onRemind(context, intent)
            else -> return
        }
    }

    private fun onRemind(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        Reminders.markFired(context, id)
        val spoken = "Reminder: $text"
        postNotification(context, spoken, id)
        speakOnce(context.applicationContext, spoken)
    }

    private fun postNotification(context: Context, text: String, id: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "RoadHouse reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Spoken reminders set by voice in RoadHouse Overlay Groq."
                }
                nm.createNotificationChannel(channel)
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("RoadHouse reminder")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_mic)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_BASE + (id.hashCode() and 0xFFFF), notif)
        } catch (e: Exception) {
            // Best effort: speech below is the primary channel.
        }
    }

    /**
     * One-shot TTS: speak, then shut down when the utterance finishes or
     * after 15 seconds, whichever comes first. Uses goAsync() so the
     * receiver is not killed mid-utterance.
     */
    private fun speakOnce(appContext: Context, text: String) {
        val pending = goAsync()
        val mainHandler = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var done = false
        val finish = {
            if (!done) {
                done = true
                try {
                    tts?.stop()
                    tts?.shutdown()
                } catch (e: Exception) {
                }
                pending.finish()
            }
        }
        mainHandler.postDelayed({ finish() }, 15000)
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    mainHandler.post { finish() }
                    return@TextToSpeech
                }
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(
                    object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            mainHandler.post { finish() }
                        }

                        override fun onError(utteranceId: String?) {
                            mainHandler.post { finish() }
                        }
                    }
                )
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rh-reminder")
            }
        } catch (e: Exception) {
            mainHandler.post { finish() }
        }
    }
}

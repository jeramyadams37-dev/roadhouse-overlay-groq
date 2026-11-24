package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Voice in (SpeechRecognizer) and voice out (TextToSpeech).
 * One listening session per tap: the driver taps the mic button, speaks a
 * command, gets a spoken answer. No always-on listening in v1 — that would
 * drain the battery and needs a hotword engine.
 */
class VoiceManager(private val context: Context) {

    interface Listener {
        fun onCommand(text: String)
        fun onPartial(text: String)
        fun onListenError(message: String)
    }

    var listener: Listener? = null

    var isListening = false
        private set

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Called when a spoken utterance finishes (or fails), for speak-then-listen flows. */
    private var speakDone: (() -> Unit)? = null

    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = runSpeakDone()
                    override fun onError(utteranceId: String?) = runSpeakDone()
                })
            }
        }
    }

    private fun runSpeakDone() {
        val cb = speakDone
        speakDone = null
        cb?.invoke()
    }

    fun speak(text: String) {
        speak(text, null)
    }

    /**
     * Speak, then run onDone when the utterance finishes. If TTS is
     * unavailable, onDone runs immediately so a guided flow never stalls.
     */
    fun speak(text: String, onDone: (() -> Unit)?) {
        if (!ttsReady) initTts()
        if (tts == null || !ttsReady) {
            onDone?.invoke()
            return
        }
        speakDone = onDone
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rh-utterance")
    }

    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w("VoiceManager", "stopSpeaking", e)
        }
    }

    fun startListening() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener?.onListenError(
                "Speech recognition is not available on this phone. " +
                    "Make sure the Google app is installed and updated."
            )
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    isListening = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "I didn't catch that. Tap the mic and try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "I didn't hear anything. Tap the mic and try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is off. Allow it in the app settings."
                        else ->
                            "Listening stopped (error $error). Tap the mic and try again."
                    }
                    listener?.onListenError(msg)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (heard.isBlank()) {
                        listener?.onListenError("I didn't catch that. Tap the mic and try again.")
                    } else {
                        listener?.onCommand(heard)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val heard = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (heard.isNotBlank()) listener?.onPartial(heard)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            listener?.onListenError("Could not start listening: ${e.message ?: "unknown error"}")
        }
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w("VoiceManager", "stopListening", e)
        }
        isListening = false
    }

    fun destroy() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w("VoiceManager", "destroy recognizer", e)
        }
        recognizer = null
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w("VoiceManager", "destroy tts", e)
        }
        tts = null
    }
}

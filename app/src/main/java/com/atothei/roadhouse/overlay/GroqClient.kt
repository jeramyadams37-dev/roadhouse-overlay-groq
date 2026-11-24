package com.atothei.roadhouse.overlay

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Groq REST client — plain HttpURLConnection, no extra dependency.
 * OpenAI-compatible chat completions endpoint:
 * POST https://api.groq.com/openai/v1/chat/completions
 *
 * Model is openai/gpt-oss-120b (Groq's current production flagship on the
 * free tier, verified Nov 2026). NOTE: llama-3.3-70b-versatile — the model
 * most tutorials still show — was shut down for free/developer accounts on
 * Aug 16, 2026; Groq's own deprecations page names openai/gpt-oss-120b as
 * its replacement. Free tier: 30 requests/min, 1,000/day, 8,000 tokens/min,
 * 200,000 tokens/day. No credit card needed; key is free at console.groq.com.
 *
 * The API key comes only from device storage (Prefs); it is never compiled
 * into the app. Screen text is sent to Groq's API only, and only as part
 * of a question the driver asked — it is never stored.
 */
object GroqClient {
    const val MODEL = "openai/gpt-oss-120b"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    const val SYSTEM_PROMPT = "You are RoadHouse, a voice-first driving assistant for a gig delivery " +
        "driver using Walmart Spark, DoorDash, or Instacart. You see the text of the delivery app " +
        "screen currently open on the driver's phone. Answer briefly, in plain spoken language, " +
        "under 40 words unless the driver asks for detail. Give facts from the screen: pay, miles, " +
        "address, customer notes, item counts. The app computes the dollars-per-mile verdict " +
        "separately, so do not recommend accepting or declining an offer — just report what the " +
        "screen says. If the screen does not contain the answer, say so plainly."

    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    /**
     * Conversational chat (talk while driving, jokes / trivia / stories,
     * music suggestions from Groq). Same endpoint and model as ask(),
     * but with a conversational system prompt and a short message
     * history instead of screen text. Called ONLY when he talks to it —
     * never unprompted, never for watching or parsing.
     */
    fun askChat(
        apiKey: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        callback: Callback
    ) {
        val body = JSONObject().apply {
            put("model", MODEL)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    for ((role, content) in messages) {
                        put(JSONObject().put("role", role).put("content", content))
                    }
                }
            )
            put("temperature", 0.7)
            put("max_tokens", 256)
        }
        post(apiKey, body, callback)
    }

    fun ask(apiKey: String, userPrompt: String, screenText: String, callback: Callback) {
        Thread {
            try {
                val prompt = buildString {
                    append("Screen text from the delivery app:\n")
                    append(screenText.take(8000))
                    append("\n\nDriver question: ")
                    append(userPrompt)
                }
                val body = JSONObject().apply {
                    put("model", MODEL)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                            put(JSONObject().put("role", "user").put("content", prompt))
                        }
                    )
                    put("temperature", 0.3)
                    put("max_tokens", 512)
                }
                postOnThread(apiKey, body, callback)
            } catch (e: Exception) {
                callback.onError("Could not reach Groq: ${e.message ?: "network error"}")
            }
        }.start()
    }

    private fun post(apiKey: String, body: JSONObject, callback: Callback) {
        Thread {
            postOnThread(apiKey, body, callback)
        }.start()
    }

    private fun postOnThread(apiKey: String, body: JSONObject, callback: Callback) {
        try {
            val url = URL(ENDPOINT)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) {
                callback.onError("Groq error $code: ${friendlyError(code, text)}")
                return
            }
            val answer = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim().orEmpty()
            if (answer.isBlank()) callback.onError("Groq returned an empty answer.")
            else callback.onResult(answer)
        } catch (e: Exception) {
            callback.onError("Could not reach Groq: ${e.message ?: "network error"}")
        }
    }

    /** Plain-language error; the key is never echoed back. */
    private fun friendlyError(code: Int, body: String): String {
        if (code == 401) return "Groq key not valid — check the key in Settings."
        if (code == 429) return "Groq free-tier rate limit — wait a minute and retry."
        return extractError(body)
    }

    private fun extractError(body: String): String {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message", body.take(200))
                ?: body.take(200)
        } catch (e: Exception) {
            body.take(200)
        }
    }
}

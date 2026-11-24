package com.atothei.roadhouse.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Foreground service that owns the floating mic button and the answer card.
 * - Tap the button: start a voice listening session.
 * - Long-press the button: instant offer check, no talking needed.
 * - Drag the button: move it anywhere on screen.
 * The answer card is a WebView (file:///android_asset/overlay.html) with the
 * RoadHouse JS bridge; it is focusable while shown so its buttons work, and
 * non-focusable while hidden so the delivery app underneath stays usable.
 */
class OverlayService : Service(), VoiceManager.Listener, VoiceCommandHandler.Output {

    companion object {
        const val ACTION_START = "com.atothei.roadhouse.overlay.groq.START"
        const val ACTION_STOP = "com.atothei.roadhouse.overlay.groq.STOP"
        private const val CHANNEL_ID = "roadhouse_overlay_groq"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning = false
            private set

        fun start(context: android.content.Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var buttonView: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var cardView: WebView? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var cardReady = false
    private var pendingCard: Triple<String, String, List<String>>? = null
    private lateinit var voice: VoiceManager
    private lateinit var commands: VoiceCommandHandler

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        voice = VoiceManager(this).also {
            it.listener = this
            it.initTts()
        }
        commands = VoiceCommandHandler(this).also { it.output = this }
        // Opt-in "speak new offers" watch: the accessibility service calls
        // back here when a fresh offer appears; we speak it (local math
        // only — never a model call) and remember the check so "took it"
        // works hands-free. An offer always wins over a conversation:
        // chatting pauses and the offer is spoken instead.
        ScreenReaderService.onNewOffer = { spoken, record ->
            mainHandler.post {
                commands.pauseConversation()
                speak(spoken)
                commands.noteCheckedOffer(record)
            }
        }
        // Arrival notes: when the delivery app shows arrival at an address
        // with stored notes, speak them (explicit notes only).
        ScreenReaderService.onArrivalNotes = { message ->
            mainHandler.post { speak(message) }
        }
        // Take-me-home arrival: offer the end-of-shift summary, never end it.
        ScreenReaderService.onHomeArrival = {
            mainHandler.post { commands.offerShiftSummary() }
        }
        // Accept-tap auto-logging: when the driver taps Accept on a fresh
        // checked offer, the delivery is logged; say so once.
        ScreenReaderService.onAcceptTapLogged = { message ->
            mainHandler.post {
                speak(message)
                showCard("Delivery logged", message, listOf("hide"))
            }
        }
        ScreenReaderService.setSpeakOffers(Prefs.isSpeakNewOffers(this))
        ScreenReaderService.setAcceptTapLogging(Prefs.isAcceptTapLogging(this))
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        addFloatingButton()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenReaderService.onNewOffer = null
        ScreenReaderService.onArrivalNotes = null
        ScreenReaderService.onHomeArrival = null
        ScreenReaderService.onAcceptTapLogged = null
        ScreenReaderService.setSpeakOffers(false)
        ScreenReaderService.setAcceptTapLogging(false)
        removeViews()
        voice.destroy()
        isRunning = false
    }

    // ---- foreground notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RoadHouse Overlay Groq",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the RoadHouse Overlay Groq button alive while you drive."
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoadHouse Overlay Groq is on")
            .setContentText("Tap the mic button over any app to talk to RoadHouse.")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    // ---- floating button ----

    private fun buttonSizePx(): Int {
        val dp = when (Prefs.getOverlaySize(this)) {
            "small" -> 56
            "large" -> 88
            else -> 72
        }
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun addFloatingButton() {
        val size = buttonSizePx()
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = getString(R.string.overlay_button_description)
            setBackgroundResource(R.drawable.bg_fab)
            setOnClickListener { onMicTapped() }
            setOnLongClickListener {
                commands.handle("check this offer")
                true
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 32
            y = resources.displayMetrics.heightPixels / 3
        }
        makeDraggable(button, params)
        buttonView = button
        buttonParams = params
        windowManager.addView(button, params)
    }

    /**
     * Drag to move; a tap (under 10px of movement) still fires the click.
     * The touch listener must return false on ACTION_UP for a tap, or the
     * View never sees the UP and performClick() never runs — that was the
     * bug: taps did nothing (no listening) while a lingering tap tripped
     * the long-press offer check instead. UP is consumed only after a real
     * drag, so dragging never fires a click; a drag also cancels any
     * pending long-press so a slow drag can't trigger the offer check.
     */
    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        view.cancelLongPress()
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Consume UP only after a drag (suppresses the click);
                    // on a tap let it through so performClick() fires.
                    moved
                }
                else -> false
            }
        }
    }

    private fun onMicTapped() {
        if (voice.isListening) {
            voice.stopListening()
            return
        }
        startListening()
    }

    // ---- answer card (WebView) ----

    private fun ensureCard() {
        if (cardView != null) return
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            setBackgroundColor(0x00000000)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    cardReady = true
                    pendingCard?.let {
                        pendingCard = null
                        deliverCard(it)
                    }
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AgentBridge(this@OverlayService), "RoadHouse")
            loadUrl("file:///android_asset/overlay.html")
        }
        val height = (resources.displayMetrics.heightPixels * 0.45).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        cardView = web
        cardParams = params
    }

    private fun deliverCard(card: Triple<String, String, List<String>>) {
        val web = cardView ?: return
        val (title, body, actions) = card
        val js = "showCard(" +
            JSONObject.quote(title) + ", " +
            JSONObject.quote(body) + ", " +
            JSONArray(actions).toString() + ")"
        web.evaluateJavascript(js, null)
    }

    override fun showCard(title: String, body: String, actions: List<String>) {
        mainHandler.post {
            ensureCard()
            val web = cardView ?: return@post
            val params = cardParams ?: return@post
            if (web.parent == null) windowManager.addView(web, params)
            web.visibility = View.VISIBLE
            // Focusable while shown so the card's buttons receive taps.
            params.flags = params.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(web, params)
            val payload = Triple(title, body, actions)
            if (cardReady) deliverCard(payload) else pendingCard = payload
        }
    }

    override fun hideCard() {
        mainHandler.post {
            val web = cardView ?: return@post
            val params = cardParams ?: return@post
            web.visibility = View.GONE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            try {
                windowManager.updateViewLayout(web, params)
            } catch (e: Exception) {
                // Card was already removed; nothing to do.
            }
        }
    }

    /**
     * "Reading…" state: shown the moment a screen read starts, so the
     * card never sits on the previous answer looking current while the
     * fresh read is in flight. The real answer replaces it as soon as
     * the read (and any Groq call) returns.
     */
    override fun showReading() {
        showCard("Reading…", "Reading the screen…", listOf("hide"))
    }

    private fun removeViews() {
        try {
            buttonView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
        }
        try {
            cardView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
        }
        buttonView = null
        cardView = null
    }

    // ---- voice (VoiceManager.Listener) ----

    fun startListening() {
        startListening(showListeningCard = true)
    }

    fun startListening(showListeningCard: Boolean) {
        mainHandler.post {
            if (!Prefs.isVoiceEnabled(this)) {
                showCard(
                    "Voice is off",
                    "Voice answers are off in Settings. Tap Check offer for a silent answer.",
                    listOf("hide")
                )
                return@post
            }
            if (showListeningCard) showCard("Listening…", "Speak your command…", listOf("hide"))
            voice.startListening()
        }
    }

    /**
     * Speak a question, then start listening once the speech finishes, so
     * the recognizer does not hear the tail of our own voice. Used by the
     * guided pre-shift check. With voice off, the question card is the
     * prompt and typed answers work instead.
     */
    override fun speakAndListen(text: String) {
        if (!Prefs.isVoiceEnabled(this)) return
        voice.speak(text) { startListening(showListeningCard = false) }
    }

    override fun onCommand(text: String) {
        mainHandler.post { commands.handle(text) }
    }

    override fun onPartial(text: String) {
        mainHandler.post {
            cardView?.evaluateJavascript(
                "setListeningText(" + JSONObject.quote(text) + ")", null
            )
        }
    }

    override fun onListenError(message: String) {
        mainHandler.post {
            speak(message)
            showCard("Couldn't hear you", message, listOf("hide"))
        }
    }

    // ---- VoiceCommandHandler.Output ----

    override fun speak(text: String) {
        if (Prefs.isVoiceEnabled(this)) voice.speak(text)
    }

    override fun navigateTo(address: String) {
        mainHandler.post { commands.openMaps(address) }
    }

    // ---- called from the JS bridge ----

    fun askGroqFromCard(question: String) {
        mainHandler.post { commands.handle(question) }
    }

    /** Remember a check the card ran, so "Took it" / "Skipped it" works. */
    fun noteCheckedOffer(record: OfferMemoryRecord) {
        commands.noteCheckedOffer(record)
        commands.maybeShiftStart()
    }

    /** Offer the end-of-shift summary (voice path for take-me-home arrival). */
    fun offerShiftSummaryFromService() {
        mainHandler.post { commands.offerShiftSummary() }
    }

    /** Record took / skipped for the most recent check; returns the message. */
    fun recordOfferOutcome(outcome: OfferOutcome): String {
        return commands.recordOutcomeMessage(outcome)
    }

    /** Ask the weather from a card button (normal command path). */
    fun weatherFromCard() {
        mainHandler.post { commands.handle("what's the weather") }
    }

    fun startPreShift() {
        mainHandler.post { commands.startPreShift() }
    }

    /** Earnings tally JSON for a card button; pure code math, no read needed. */
    fun earningsSummary(range: String): String {
        return commands.earningsSummary(range)
    }

    /** End-of-shift summary JSON for a card button; pure code math, no read needed. */
    fun shiftSummary(): String {
        return commands.shiftSummaryJson()
    }

    fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

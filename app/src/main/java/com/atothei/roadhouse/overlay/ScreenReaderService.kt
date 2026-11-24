package com.atothei.roadhouse.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Accessibility service that reads the foreground app's screen text
 * ON DEMAND — only when the driver asks (tap Ask, voice command,
 * long-press offer check). It never polls, never runs a timer, and never
 * walks the node tree on accessibility events: the service stays passive
 * until asked, so it costs no battery, data, or Groq calls in use.
 *
 * One read per request. A read taken within the last few seconds is reused
 * for an immediate follow-up instead of walking the tree again — but ONLY
 * when the foreground app has not changed since that read was taken. The
 * cached read is tagged with the package it came from (lastReadPackage);
 * if the driver switched apps, the cache is ignored and the new app's
 * screen is walked fresh, so an answer can never describe the wrong app
 * with old values. A failed read returns a plain failure instead of
 * stale text.
 * Anything older is re-read on demand; a failed read returns a plain
 * failure instead of stale text.
 *
 * SystemUI (notification shade / status bar) and the overlay's own windows
 * are always skipped — a read only ever sees the foreground app's window.
 * It only READS. It never clicks, types, or acts in any app.
 *
 * Two opt-in exceptions, both OFF by default:
 * - the "speak new offers" watch (Prefs.isSpeakNewOffers): when on, the
 *   service subscribes to window-content events in delivery apps and
 *   speaks a short verdict from LOCAL parsing only — pay/miles/floor math
 *   in code, never a model call — when a fresh offer appears. When the
 *   watch is off, content events are never subscribed and detection does
 *   nothing at all.
 * - accept-tap auto-logging (Prefs.isAcceptTapLogging): when on, the
 *   service subscribes to view-clicked events in delivery apps; a tap on
 *   an Accept button logs the fresh checked offer (same app, within ~3
 *   minutes) to the delivery log. When off, click events are never
 *   subscribed and taps do nothing at all. Neither exception ever
 *   auto-accepts or auto-declines anything.
 */
class ScreenReaderService : AccessibilityService() {

    /** Outcome of an on-demand read. */
    enum class ReadStatus {
        OK,
        SERVICE_NOT_RUNNING,
        SHADE_DOWN,
        NO_APP_WINDOW,
        NO_TEXT
    }

    data class ScreenRead(
        val status: ReadStatus,
        val text: String,
        val packageName: String,
        val readAtMs: Long
    ) {
        val isOk: Boolean get() = status == ReadStatus.OK

        /** Honest age label for the answer card: "read just now" / "read 4s ago". */
        fun ageLabel(): String {
            val ageSec = ((System.currentTimeMillis() - readAtMs) / 1000).toInt()
            return if (ageSec < 2) "read just now" else "read ${ageSec}s ago"
        }

        /** Plain-language message for a failed read. Never answers from old text. */
        fun failureMessage(): String = when (status) {
            ReadStatus.SERVICE_NOT_RUNNING ->
                "The screen reader isn't running. Open RoadHouse Overlay Groq and turn on the accessibility service."
            ReadStatus.SHADE_DOWN ->
                "I can't see the app right now — close the notification shade and ask again."
            ReadStatus.NO_APP_WINDOW ->
                "I can't see any app screen right now. Open your delivery app first, then ask again."
            ReadStatus.NO_TEXT ->
                "I can see the app but there's no readable text on this screen right now."
            ReadStatus.OK -> ""
        }
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val OWN_PACKAGE = "com.atothei.roadhouse.overlay.groq"
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val MAX_CHARS = 20000

        /**
         * A read this young may be reused for an immediate follow-up
         * instead of walking the tree again. One read per request;
         * anything older is re-read on demand.
         */
        private const val FRESH_WINDOW_MS = 5000L

        @Volatile
        private var instance: ScreenReaderService? = null

        /** Last on-demand read, if any. Updated only when a read is taken. */
        @Volatile
        var currentScreenText: String = ""
            private set

        /** Package the last on-demand read came from. */
        @Volatile
        var foregroundPackage: String = ""
            private set

        /**
         * Package the CACHED read (currentScreenText) came from. The cache
         * is reusable only while this still equals foregroundPackage —
         * that is the stale-screen guard: switching apps invalidates the
         * cache even inside the fresh window.
         */
        @Volatile
        private var lastReadPackage: String = ""

        @Volatile
        var lastUpdatedMs: Long = 0L
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Opt-in "speak new offers" watch. OFF by default; the driver turns
         * it on in Settings. When off, no content events are subscribed and
         * detection below is never reached.
         */
        @Volatile
        var speakOffersOn = false
            private set

        /** Called by OverlayService when a fresh offer is detected. */
        @Volatile
        var onNewOffer: ((String, OfferMemoryRecord) -> Unit)? = null

        /** Called by OverlayService when arrival notes are read aloud. */
        @Volatile
        var onArrivalNotes: ((String) -> Unit)? = null

        /** Called by OverlayService when an accept-tap auto-logs a delivery. */
        @Volatile
        var onAcceptTapLogged: ((String) -> Unit)? = null

        /** Called by OverlayService when a "take me home" navigation arrives. */
        @Volatile
        var onHomeArrival: (() -> Unit)? = null

        @Volatile
        private var lastDetectAttemptMs: Long = 0

        @Volatile
        private var lastSpokenMs: Long = 0

        @Volatile
        private var lastSpokenSignature: String = ""

        /** Last address whose arrival notes were spoken, to avoid repeats. */
        @Volatile
        private var lastArrivalKey: String = ""

        @Volatile
        private var lastArrivalMs: Long = 0

        @Volatile
        private var lastArrivalCheckMs: Long = 0

        private const val DETECT_THROTTLE_MS = 10_000L
        private const val SPOKEN_COOLDOWN_MS = 30_000L
        private const val ARRIVAL_CHECK_THROTTLE_MS = 30_000L
        private const val ARRIVAL_REPEAT_MS = 30L * 60L * 1000L

        /** Set the watch on/off and re-apply the event subscription. Safe when not connected. */
        fun setSpeakOffers(enabled: Boolean) {
            speakOffersOn = enabled
            refreshEventTypes()
        }

        /**
         * Opt-in accept-tap auto-logging. OFF by default; the driver turns
         * it on in Settings. When off, click events are never subscribed
         * and accept taps do nothing at all.
         */
        @Volatile
        var acceptTapOn = false
            private set

        /** Set accept-tap logging on/off and re-apply the event subscription. */
        fun setAcceptTapLogging(enabled: Boolean) {
            acceptTapOn = enabled
            refreshEventTypes()
        }

        /** Re-apply event types: content events only while the watch is on. */
        fun refreshEventTypes() {
            val inst = instance ?: return
            val desired = desiredEventTypes()
            val info = inst.serviceInfo ?: AccessibilityServiceInfo()
            if (info.eventTypes != desired) {
                info.eventTypes = desired
                inst.serviceInfo = info
            }
        }

        private fun desiredEventTypes(): Int {
            var types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            if (speakOffersOn) types = types or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            if (acceptTapOn) types = types or AccessibilityEvent.TYPE_VIEW_CLICKED
            return types
        }

        /**
         * The read for a user request. Reuses a read taken within the last
         * few seconds (immediate follow-up) — but ONLY if the foreground
         * app is still the app the cached read came from. If the driver
         * switched apps since that read, the cache is stale for the new
         * app and a fresh walk is forced, so the answer never describes
         * the wrong app. A failed walk returns a failure status instead
         * of old text.
         */
        fun readFresh(): ScreenRead {
            val now = System.currentTimeMillis()
            if (currentScreenText.isNotEmpty() && now - lastUpdatedMs < FRESH_WINDOW_MS &&
                lastReadPackage.isNotEmpty() && lastReadPackage == foregroundPackage
            ) {
                return ScreenRead(ReadStatus.OK, currentScreenText, foregroundPackage, lastUpdatedMs)
            }
            return instance?.readNow()
                ?: ScreenRead(ReadStatus.SERVICE_NOT_RUNNING, "", foregroundPackage, now)
        }

        private fun isSkippedPackage(pkg: String?): Boolean =
            pkg == null || pkg == SYSTEM_UI_PACKAGE || pkg == OWN_PACKAGE
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        val info = serviceInfo ?: AccessibilityServiceInfo()
        // Window-state changes always: enough to know which app is in front.
        // Content events only while the opt-in "speak new offers" watch is
        // on — otherwise the service must not wake up (or walk the tree)
        // for every screen change. It reads only when the driver asks.
        speakOffersOn = Prefs.isSpeakNewOffers(this)
        acceptTapOn = Prefs.isAcceptTapLogging(this)
        info.eventTypes = desiredEventTypes()
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 500
        serviceInfo = info
    }

    /**
     * Event handler: tracks the foreground package only. No node-tree walk
     * happens here — reading is strictly on demand (see readFresh()) —
     * except the two opt-in, event-driven detections below, which read
     * only when their toggle is on and only in a delivery app.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            refreshEventTypes()
            val pkg = event.packageName?.toString() ?: return
            if (!isSkippedPackage(pkg)) foregroundPackage = pkg
            maybeDetectOffer(event)
            maybeDetectArrival(event)
            maybeDetectHomeArrival(event)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            maybeDetectOffer(event)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            onAcceptTap(event)
        }
    }

    /**
     * Arrival detection (feature 7): when the delivery app moves to a new
     * screen that says the driver has arrived, speak the stored
     * instructions/notes for that address — notes the driver explicitly
     * asked to be remembered, never anything auto-captured. Event-driven
     * only (window-state changes), throttled, once per address per 30
     * minutes, and only when there are notes to speak. No polling, no
     * location tracking.
     */
    private fun maybeDetectArrival(event: AccessibilityEvent) {
        val hook = onArrivalNotes ?: return
        if (!OverlayService.isRunning) return
        val ctx = instance ?: return
        if (!Prefs.isVoiceEnabled(ctx)) return
        val pkg = event.packageName?.toString() ?: return
        if (!Platforms.isDeliveryApp(pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastArrivalCheckMs < ARRIVAL_CHECK_THROTTLE_MS) return
        lastArrivalCheckMs = now
        val read = readFresh()
        if (!read.isOk) return
        val lower = read.text.lowercase()
        val arrived = lower.contains("arrived") || lower.contains("you've arrived") ||
            lower.contains("you have arrived") || lower.contains("you're here") ||
            lower.contains("you are here") || lower.contains("reached the destination") ||
            lower.contains("at the store") || lower.contains("at the customer") ||
            lower.contains("delivery location")
        if (!arrived) return
        val address = OfferParser.parseAddress(read.text.replace('\n', ' '))
            ?: return
        val key = OfferParser.normalizeKey(address)
        if (key.isBlank()) return
        if (key == lastArrivalKey && now - lastArrivalMs < ARRIVAL_REPEAT_MS) return
        val notes = AddressNotes.notesFor(ctx, key)
        if (notes.isEmpty()) return
        lastArrivalKey = key
        lastArrivalMs = now
        val line = AddressNotes.spokenLine(notes) ?: return
        hook("You've arrived at $address. $line")
    }

    /**
     * Take-me-home arrival: while a "take me home" navigation is active
     * (Prefs.isHomeNavActive) and Google Maps is in front saying the
     * driver has arrived, fire onHomeArrival once — the overlay then
     * OFFERS the end-of-shift summary, never ends the shift itself.
     * Same foreground-wording approach as arrival notes; a silent miss
     * just means no offer that time. No polling, no location tracking.
     */
    private fun maybeDetectHomeArrival(event: AccessibilityEvent) {
        val hook = onHomeArrival ?: return
        if (!OverlayService.isRunning) return
        val ctx = instance ?: return
        if (!Prefs.isHomeNavActive(ctx)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != MAPS_PACKAGE) return
        val read = readFresh()
        if (!read.isOk) return
        val lower = read.text.lowercase()
        val arrived = lower.contains("you have arrived") || lower.contains("you've arrived") ||
            lower.contains("arrived") || lower.contains("destination") ||
            lower.contains("you're here") || lower.contains("you are here")
        if (!arrived) return
        Prefs.setHomeNavActive(ctx, false)
        hook()
    }

    /**
     * Accept-tap auto-logging (feature 9): when the driver taps an Accept
     * button in a delivery app AND there is a fresh checked offer (same
     * app, within ~3 minutes), log that offer to the delivery log. The
     * dedupe (same platform/pay/miles/address within 10 minutes) means a
     * second tap never duplicates. If no fresh offer matches, log
     * NOTHING — never guess. Event-driven only; runs only while the
     * accept-tap toggle is on.
     */
    private fun onAcceptTap(event: AccessibilityEvent) {
        if (!acceptTapOn) return
        if (!OverlayService.isRunning) return
        val ctx = instance ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!Platforms.isDeliveryApp(pkg)) return
        val label = buildString {
            for (t in event.text) append(t?.toString() ?: "").append(' ')
            val desc = event.contentDescription?.toString()
            if (!desc.isNullOrBlank()) append(desc)
        }.lowercase()
        if (!label.contains("accept")) return
        val checked = OfferMemory.latestFreshCheck(ctx, pkg, 3) ?: return
        val hook = onAcceptTapLogged
        val tag = Platforms.tagFromDisplay(checked.platform)
        val address = checked.stops.firstOrNull()
        val (date, time) = DeliveryLog.nowParts()
        val entry = DeliveryEntry(date, time, tag, checked.pay, checked.miles, checked.perMile, address)
        when (DeliveryLog.appendIfNew(ctx, entry)) {
            DeliveryLog.LogResult.LOGGED -> {
                OfferMemory.markOutcome(ctx, checked.id, OfferOutcome.TOOK)
                SeenOffers.markTakenForCheck(ctx, checked)
                SeenOffers.resolveByDelivery(ctx, entry)
                val payPart = if (checked.pay != null) "$%.2f".format(checked.pay) else "unknown pay"
                var msg = "Logged — you took the $payPart offer."
                GoalTracker.checkReached(ctx)?.let { msg += " $it" }
                hook?.invoke(msg)
            }
            DeliveryLog.LogResult.ALREADY_LOGGED -> {
                hook?.invoke("Already in your delivery log.")
            }
            DeliveryLog.LogResult.FAILED -> {
                hook?.invoke("I couldn't save that delivery. Try logging it by voice.")
            }
        }
    }

    /**
     * Opt-in new-offer watch: when a fresh offer appears in a delivery
     * app, speak the short local verdict (code math only — no model call)
     * and remember the check so "took it" works hands-free. Runs at most
     * once per 10 seconds, speaks at most once per 30 seconds, and only
     * for an offer signature (pay + miles + address) it has not spoken
     * before. Does nothing at all when the watch is off, the overlay is
     * stopped, or voice is off.
     */
    private fun maybeDetectOffer(event: AccessibilityEvent) {
        if (!speakOffersOn) return
        if (!OverlayService.isRunning) return
        val hook = onNewOffer ?: return
        val ctx = instance ?: return
        if (!Prefs.isVoiceEnabled(ctx)) return
        val pkg = event.packageName?.toString() ?: return
        if (!Platforms.isDeliveryApp(pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastDetectAttemptMs < DETECT_THROTTLE_MS) return
        lastDetectAttemptMs = now
        if (now - lastSpokenMs < SPOKEN_COOLDOWN_MS) return
        val read = readFresh()
        if (!read.isOk) return
        val floor = Prefs.getFloor(ctx)
        val result = OfferParser.checkBatch(read.text, floor)
        if (result.pay == null || result.miles == null) return
        val signature = "${result.pay}|${result.miles}|${result.address ?: ""}"
        if (signature == lastSpokenSignature) return
        val display = Platforms.displayName(read.packageName)
        val tag = Platforms.tag(read.packageName)
        val key = OfferParser.storeKey(read.text, display)
        val label = OfferParser.storeLabel(read.text, display)
        val match = OfferMemory.findMatch(ctx, key, result.pay, result.miles)
        val storeOnly = if (match == null) OfferMemory.findStore(ctx, key) else null
        val memoryLine = OfferMemory.memoryLine(match, storeOnly)
        val notes = StoreNotes.notesFor(ctx, key)
        val noteLine = StoreNotes.spokenLine(notes)
        val addressNotes = if (result.address.isNullOrBlank()) emptyList() else
            AddressNotes.notesFor(ctx, OfferParser.normalizeKey(result.address))
        val addressLine = AddressNotes.spokenLine(addressNotes)
        val spoken = buildString {
            append(OfferParser.speakBatchText(result, display))
            if (memoryLine != null) append(" $memoryLine")
            if (noteLine != null) append(" $noteLine")
            if (addressLine != null) append(" $addressLine")
        }.toString()
        val (date, time) = DeliveryLog.nowParts()
        val record = OfferMemoryRecord(
            id = OfferMemory.newId(),
            date = date,
            time = time,
            platform = tag,
            packageName = read.packageName,
            storeKey = key,
            storeLabel = label,
            pay = result.pay,
            miles = result.miles,
            perMile = result.perMile,
            verdict = result.verdict,
            outcome = OfferOutcome.UNKNOWN,
            stops = result.stops.map { it.address }
        )
        OfferMemory.append(ctx, record)
        SeenOffers.record(ctx, tag, key, label, result.pay, result.miles, result.perMile, result.address, "watch")
        lastSpokenSignature = signature
        lastSpokenMs = now
        hook(spoken, record)
    }

    /**
     * Walks the foreground app's window right now. Called only from
     * readFresh(), i.e. only for a user request. getWindows() is a binder
     * call, safe from any thread; node reads below are IPC-backed too.
     */
    private fun readNow(): ScreenRead {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        val seen = LinkedHashSet<String>()
        var appPackage = ""
        var systemUiActive = false
        var focusedAppRoot: AccessibilityNodeInfo? = null
        var firstAppRoot: AccessibilityNodeInfo? = null
        try {
            val windows = windows
                ?: return ScreenRead(ReadStatus.NO_APP_WINDOW, "", foregroundPackage, now)
            for (window in windows) {
                if (window == null) continue
                val root = try {
                    window.root
                } catch (e: Exception) {
                    null
                }
                val pkg = root?.packageName?.toString()
                // Shade / status bar pulled down or being touched: say so
                // plainly instead of reading the app underneath it.
                if (pkg == SYSTEM_UI_PACKAGE && (window.isFocused || window.isActive)) {
                    systemUiActive = true
                }
                // Only the foreground app's own window: no SystemUI, no
                // input method, no overlay — and never our own card/button.
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    isSkippedPackage(pkg)
                ) {
                    try {
                        root?.recycle()
                    } catch (e: Exception) {
                    }
                    continue
                }
                if (firstAppRoot == null) {
                    firstAppRoot = root
                    appPackage = pkg ?: ""
                }
                if (window.isFocused && focusedAppRoot == null) {
                    focusedAppRoot = root
                    appPackage = pkg ?: ""
                }
                if (focusedAppRoot != null && firstAppRoot != null) break
                // An extra app window we do not need (split screen / PiP):
                // recycle its root now instead of leaking it.
                if (root !== firstAppRoot && root !== focusedAppRoot) {
                    try {
                        root?.recycle()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            return ScreenRead(ReadStatus.NO_APP_WINDOW, "", foregroundPackage, now)
        }
        if (systemUiActive) {
            try {
                focusedAppRoot?.recycle()
            } catch (e: Exception) {
            }
            if (focusedAppRoot !== firstAppRoot) {
                try {
                    firstAppRoot?.recycle()
                } catch (e: Exception) {
                }
            }
            return ScreenRead(ReadStatus.SHADE_DOWN, "", foregroundPackage, now)
        }
        // Prefer the focused app window; fall back to the topmost app window.
        val root = focusedAppRoot ?: firstAppRoot
        if (root == null || appPackage.isEmpty()) {
            return ScreenRead(ReadStatus.NO_APP_WINDOW, "", foregroundPackage, now)
        }
        try {
            extractText(root, sb, seen)
        } finally {
            try {
                root.recycle()
            } catch (e: Exception) {
                // Best effort; a stale node must not kill the service.
            }
            // Only when both roots exist and differ is there a second node
            // to recycle; otherwise root above was already the first one.
            if (focusedAppRoot != null && focusedAppRoot !== firstAppRoot &&
                firstAppRoot != null
            ) {
                try {
                    firstAppRoot.recycle()
                } catch (e: Exception) {
                }
            }
        }
        val text = sb.toString().take(MAX_CHARS)
        if (text.isBlank()) {
            return ScreenRead(ReadStatus.NO_TEXT, "", appPackage, now)
        }
        currentScreenText = text
        foregroundPackage = appPackage
        lastReadPackage = appPackage
        lastUpdatedMs = now
        return ScreenRead(ReadStatus.OK, text, appPackage, now)
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder, seen: LinkedHashSet<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val line = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }
        if (line.isNotEmpty() && seen.add(line)) {
            sb.append(line).append('\n')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                extractText(child, sb, seen)
            } finally {
                try {
                    child?.recycle()
                } catch (e: Exception) {
                    // Best effort.
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRunning = false
    }
}

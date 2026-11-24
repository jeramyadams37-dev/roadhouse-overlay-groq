package com.atothei.roadhouse.overlay

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS bridge for the overlay card WebView (window.RoadHouse).
 * Same shape as the proven AI Overlay APK bridge (getScreenContext,
 * launchApp-style helpers), extended for the driver use case: offer checks
 * computed in code, Groq questions, navigation, delivery logging,
 * offer memory, store notes, earnings tallies, pre-shift checks.
 */
class AgentBridge(private val service: OverlayService) {

    /** Fresh read for this request (a few-second-old read is reused). */
    private fun freshPackage(): String {
        val read = ScreenReaderService.readFresh()
        return if (read.isOk) read.packageName else ScreenReaderService.foregroundPackage
    }

    @JavascriptInterface
    fun getScreenContext(): String {
        val read = ScreenReaderService.readFresh()
        return if (read.isOk) read.text else ""
    }

    @JavascriptInterface
    fun getForegroundApp(): String = Platforms.displayName(freshPackage())

    @JavascriptInterface
    fun isDeliveryApp(): Boolean = Platforms.isDeliveryApp(freshPackage())

    @JavascriptInterface
    fun getFloor(): Double = Prefs.getFloor(service)

    @JavascriptInterface
    fun isVoiceEnabled(): Boolean = Prefs.isVoiceEnabled(service)

    /**
     * Offer check with the $/mile math done in code. Returns JSON with
     * "ok": true on success, or "ok": false + "error" when the screen
     * could not be read right now — never a verdict from stale text.
     * Includes batch stops, offer-memory and store-note lines when present.
     */
    @JavascriptInterface
    fun checkOffer(): String {
        val read = ScreenReaderService.readFresh()
        val floor = Prefs.getFloor(service)
        if (!read.isOk) {
            return JSONObject().apply {
                put("ok", false)
                put("error", read.failureMessage())
                put("platform", Platforms.displayName(ScreenReaderService.foregroundPackage))
                put("pay", JSONObject.NULL)
                put("miles", JSONObject.NULL)
                put("perMile", JSONObject.NULL)
                put("floor", floor)
                put("address", JSONObject.NULL)
                put("verdict", Verdict.UNKNOWN.name)
                put("spoken", read.failureMessage())
                put("note", read.failureMessage())
                put("readAge", read.ageLabel())
                put("isBatch", false)
                put("stops", JSONArray())
                put("memory", JSONObject.NULL)
                put("notes", JSONObject.NULL)
            }.toString()
        }
        val platform = Platforms.displayName(read.packageName)
        val tag = Platforms.tag(read.packageName)
        val result = OfferParser.checkBatch(read.text, floor)
        val key = OfferParser.storeKey(read.text, platform)
        val label = OfferParser.storeLabel(read.text, platform)

        // Look up memory BEFORE recording this check, or it would match itself.
        val match = OfferMemory.findMatch(service, key, result.pay, result.miles)
        val storeOnly = if (match == null) OfferMemory.findStore(service, key) else null
        val memoryLine = OfferMemory.memoryLine(match, storeOnly)
        val notes = StoreNotes.notesFor(service, key)
        val noteLine = StoreNotes.spokenLine(notes)
        val allAddressNotes = AddressNotes.notes(service)
        val addressNotes = if (result.address.isNullOrBlank()) emptyList() else
            allAddressNotes.filter { it.addressKey == OfferParser.normalizeKey(result.address) }
        val addressNoteLine = AddressNotes.spokenLine(addressNotes)

        // Remember this check; the driver fills in took/skipped afterwards.
        // Stored platform is the stable tag; speech uses the display name.
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
        OfferMemory.append(service, record)
        SeenOffers.record(service, tag, key, label, result.pay, result.miles, result.perMile, result.address, "check")
        service.noteCheckedOffer(record)

        val spoken = buildString {
            append(OfferParser.speakBatchText(result, platform))
            if (memoryLine != null) append(" $memoryLine")
            if (noteLine != null) append(" $noteLine")
            if (addressNoteLine != null) append(" $addressNoteLine")
            val avoidWarning = StorePrefs.warningFor(service, key, label)
            if (avoidWarning != null) append(" $avoidWarning")
        }.toString()
        val stopsJson = JSONArray().apply {
            for (stop in result.stops) {
                val stopNotes = allAddressNotes.filter {
                    it.addressKey == OfferParser.normalizeKey(stop.address)
                }
                put(JSONObject().apply {
                    put("address", stop.address)
                    if (stop.miles != null) put("miles", stop.miles) else put("miles", JSONObject.NULL)
                    if (stop.pay != null) put("pay", stop.pay) else put("pay", JSONObject.NULL)
                    if (stopNotes.isEmpty()) put("addressNotes", JSONObject.NULL)
                    else put("addressNotes", JSONArray(stopNotes.map { it.text }))
                })
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("platform", platform)
            put("pay", result.pay ?: JSONObject.NULL)
            put("miles", result.miles ?: JSONObject.NULL)
            put("perMile", result.perMile ?: JSONObject.NULL)
            put("floor", result.floor)
            put("address", result.address ?: JSONObject.NULL)
            put("verdict", result.verdict.name)
            put("spoken", spoken)
            put("note", result.note)
            put("readAge", read.ageLabel())
            put("isBatch", result.isBatch)
            put("stops", stopsJson)
            put("memory", memoryLine ?: JSONObject.NULL)
            put("notes", if (notes.isEmpty()) JSONObject.NULL else JSONArray(notes.map { it.text }))
            put("addressNotes", if (addressNotes.isEmpty()) JSONObject.NULL else JSONArray(addressNotes.map { it.text }))
        }.toString()
    }

    /** Record took / skipped for the most recent offer check. */
    @JavascriptInterface
    fun recordOfferOutcome(outcome: String): String {
        val parsed = if (outcome.lowercase().contains("took")) OfferOutcome.TOOK else OfferOutcome.SKIPPED
        val message = service.recordOfferOutcome(parsed)
        service.speak(message)
        return JSONObject().apply {
            put("ok", message.isNotBlank())
            put("message", message)
        }.toString()
    }

    /** Start the guided pre-shift check (card + voice). */
    @JavascriptInterface
    fun startPreShift() {
        service.startPreShift()
    }

    /** Earnings tally as JSON: {"ok":true,"label","count","totalPay","totalMiles","perMile","text"}. */
    @JavascriptInterface
    fun earningsSummary(range: String): String {
        return service.earningsSummary(range)
    }

    /** End-of-shift summary as JSON: {"ok":true,"count","totalPay","totalMiles","perMile","text"}. */
    @JavascriptInterface
    fun shiftSummary(): String {
        return service.shiftSummary()
    }

    @JavascriptInterface
    fun askGroq(question: String) {
        service.askGroqFromCard(question)
    }

    @JavascriptInterface
    fun startListening() {
        service.startListening()
    }

    @JavascriptInterface
    fun speak(text: String) {
        service.speak(text)
    }

    @JavascriptInterface
    fun navigate(address: String) {
        val target = address.ifBlank {
            val read = ScreenReaderService.readFresh()
            if (!read.isOk) {
                service.showCard(
                    "Can't see the screen",
                    read.failureMessage(),
                    listOf("hide")
                )
                return
            }
            OfferParser.parseAddress(read.text.replace('\n', ' '))
        } ?: ""
        if (target.isBlank()) {
            service.showCard(
                "Navigate",
                "I can't find an address on this screen.",
                listOf("hide")
            )
        } else {
            service.navigateTo(target)
        }
    }

    /** Logs the current screen's delivery. Returns {"ok":bool,"count":n}. */
    @JavascriptInterface
    fun logDelivery(): String {
        val read = ScreenReaderService.readFresh()
        if (!read.isOk) {
            return JSONObject().apply {
                put("ok", false)
                put("error", read.failureMessage())
                put("count", DeliveryLog.count(service))
            }.toString()
        }
        val platform = Platforms.displayName(read.packageName)
        val result = OfferParser.check(read.text, Prefs.getFloor(service))
        val (date, time) = DeliveryLog.nowParts()
        val entry = DeliveryEntry(
            date, time, Platforms.tag(read.packageName),
            result.pay, result.miles, result.perMile, result.address
        )
        val logged = DeliveryLog.appendIfNew(service, entry)
        if (logged == DeliveryLog.LogResult.LOGGED) {
            SeenOffers.resolveByDelivery(service, entry)
        }
        return JSONObject().apply {
            put("ok", logged != DeliveryLog.LogResult.FAILED)
            put("alreadyLogged", logged == DeliveryLog.LogResult.ALREADY_LOGGED)
            put(
                "error", when (logged) {
                    DeliveryLog.LogResult.LOGGED -> JSONObject.NULL
                    DeliveryLog.LogResult.ALREADY_LOGGED ->
                        "Already logged — this delivery is in your log."
                    DeliveryLog.LogResult.FAILED -> "Could not save that delivery. Try again."
                }
            )
            put("count", DeliveryLog.count(service))
        }.toString()
    }

    @JavascriptInterface
    fun weather() {
        service.weatherFromCard()
    }

    @JavascriptInterface
    fun hideCard() {
        service.hideCard()
    }

    @JavascriptInterface
    fun openSettings() {
        service.openSettings()
    }
}

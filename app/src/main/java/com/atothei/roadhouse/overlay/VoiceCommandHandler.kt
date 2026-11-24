package com.atothei.roadhouse.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Routes a heard voice command to the right action.
 * Offer verdicts come from OfferParser (code math vs the $/mile floor);
 * earnings tallies come from Earnings (code math on the delivery log);
 * open questions go to Groq with the current screen text. Nothing here
 * ever taps, accepts, or declines anything in a delivery app — the overlay
 * advises, the driver decides.
 *
 * Routing order matters: outcome recording, earnings, and pre-shift need
 * NO screen read, so they are routed before any readFresh() call. Only
 * navigate / log / offer check / notes / Groq questions read the screen,
 * one fresh read per request, on demand.
 */
class VoiceCommandHandler(private val context: Context) {

    interface Output {
        fun speak(text: String)
        /** Speak a question, then start listening when the speech finishes. */
        fun speakAndListen(text: String)
        fun showCard(title: String, body: String, actions: List<String>)
        fun hideCard()
        fun navigateTo(address: String)
        /** Show a "Reading…" card while a fresh screen read is in flight. */
        fun showReading()
    }

    var output: Output? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The most recent offer check (voice or card) — for "took it" / "skipped it". */
    @Volatile
    private var lastChecked: OfferMemoryRecord? = null

    /** Pre-shift conversation state; null when no check is running. */
    private var preShift: PreShiftFlow? = null

    /** Offer-review conversation state; null when no review is running. */
    private var review: ReviewFlow? = null

    private data class ReviewFlow(
        var items: List<SeenOffer>,
        var index: Int,
        var took: Int,
        var skipped: Int
    )

    private data class PreShiftFlow(
        var step: Int, // 0 vehicle, 1 fuel, 2 apps
        var vehicleOk: Boolean?,
        var fuel: String?,
        var apps: List<String>,
        var retries: Int
    )

    /** Conversation mode (talk while driving); false unless he started it. */
    private var chatting = false

    /** Short Groq chat history (last 3 turns), in-memory only. */
    private val chatHistory = mutableListOf<Pair<String, String>>()

    /** Dead-mile entertainment offer waiting on a yes (one per empty stretch). */
    private var entertainmentPending = false
    private var entertainmentStretchKey = ""

    /** End-of-shift summary offered, waiting on a yes/no. */
    private var summaryPending = false

    /** "Want me to play it?" — the suggested query waiting on a yes/no. */
    private var musicSuggestQuery: String? = null

    /** "Joke, story, trivia, or music?" — waiting on a pick. */
    private var entertainmentPickArmed = false

    /** Voice journal: next utterance is the note itself. */
    private var journalArmed = false

    /** Odometer capture: "start" or "end" when a reading is being asked for. */
    private var odometerKind: String? = null

    fun handle(command: String) {
        val lower = command.lowercase()

        // Guided flows own every answer until they end.
        if (preShift != null) {
            handlePreShiftAnswer(command, lower)
            return
        }
        if (review != null) {
            handleReviewAnswer(command, lower)
            return
        }
        if (journalArmed) {
            handleJournalAnswer(command, lower)
            return
        }
        if (odometerKind != null) {
            handleOdometerAnswer(command, lower)
            return
        }
        if (musicSuggestQuery != null && handleMusicSuggestAnswer(command, lower)) return
        if (entertainmentPickArmed && handleEntertainmentPickAnswer(command, lower)) return

        // Pending yes/no offers: a yes/no answers them; ANY other command
        // clears the pending offer and runs normally — a pending offer
        // never swallows a real command.
        if (summaryPending) {
            when {
                Conversation.isYes(lower) -> {
                    summaryPending = false
                    handleShiftSummary()
                    return
                }
                Conversation.isNo(lower) -> {
                    summaryPending = false
                    output?.speak("No problem.")
                    return
                }
                else -> summaryPending = false
            }
        }
        if (entertainmentPending) {
            when {
                Conversation.isYes(lower) -> {
                    entertainmentPending = false
                    askEntertainmentPick()
                    return
                }
                Conversation.isEntertainmentPick(lower) -> {
                    entertainmentPending = false
                    handleEntertainmentChoice(command, lower)
                    return
                }
                else -> entertainmentPending = false // "no" or any other command: offer lapses.
            }
        }
        if (chatting && Conversation.isChatStop(lower)) {
            chatting = false
            chatHistory.clear()
            val msg = "I'll be quiet. Say \"talk to me\" if you want company again."
            output?.speak(msg)
            output?.showCard("RoadHouse", msg, listOf("hide"))
            return
        }

        // Low-battery warning (once per shift): spoken FIRST, alone —
        // VoiceManager.speak uses QUEUE_FLUSH, so a second speak would
        // cut it off. The command itself runs 5 seconds later.
        val batteryWarn = BatteryCheck.checkAndWarn(context)
        if (batteryWarn != null) {
            output?.speak(batteryWarn)
            output?.showCard("Battery", batteryWarn, listOf("hide"))
            mainHandler.postDelayed({ handleAfterBattery(command, lower) }, 5000)
            return
        }
        handleAfterBattery(command, lower)
    }

    private fun handleAfterBattery(command: String, lower: String) {
        ShiftState.touchActivity(context)
        maybeRoadNoteProximity()
        val summaryDue = ShiftState.shouldOfferSummary(context) && !isShiftSummary(lower)
        if (summaryDue) ShiftState.markSummaryOffered(context)
        // An offer or any real command pauses a conversation; the offer wins.
        if (chatting && isRecognizedCommand(lower)) chatting = false
        handleInner(command, lower)
        // End-of-shift summary offer, spoken after the answer (delayed so
        // it cannot flush the answer mid-sentence — QUEUE_FLUSH again).
        if (summaryDue) {
            mainHandler.postDelayed({ offerShiftSummary() }, 8000)
        }
    }

    private fun handleInner(command: String, lower: String) {
        // These need no screen read — route them before readFresh().
        if (isOutcome(lower)) {
            recordOutcome(if (isTook(lower)) OfferOutcome.TOOK else OfferOutcome.SKIPPED)
            return
        }
        if (isTax(lower)) {
            handleTax()
            return
        }
        if (isShiftSummary(lower)) {
            handleShiftSummary()
            return
        }
        if (isGoalSet(lower)) {
            handleGoalSet(lower)
            return
        }
        if (isGoalProgress(lower)) {
            handleGoalProgress()
            return
        }
        if (isFuel(lower)) {
            handleFuel()
            return
        }
        if (isBestHours(lower)) {
            handleBestHours()
            return
        }
        if (isDeadMiles(lower)) {
            handleDeadMiles()
            return
        }
        if (isFamilyDateAdd(lower)) {
            handleFamilyDateAdd(command)
            return
        }
        if (isRoadNoteAdd(lower)) {
            handleRoadNoteAdd(command)
            return
        }
        if (isReminderSet(lower)) {
            handleReminderSet(command)
            return
        }
        if (isReminderList(lower)) {
            handleReminderList()
            return
        }
        if (isOfferReview(lower)) {
            startReview()
            return
        }
        if (isJournalStart(lower)) {
            startJournal()
            return
        }
        if (isJournalRead(lower)) {
            handleJournalRead()
            return
        }
        if (isCameraStop(lower)) {
            handleCameraStop()
            return
        }
        if (isCameraStart(lower)) {
            handleCameraStart()
            return
        }
        if (isCameraPhoto(lower)) {
            handleCameraPhoto()
            return
        }
        if (isTip(lower)) {
            handleTip(command)
            return
        }
        if (isTipsToday(lower)) {
            handleTipsToday()
            return
        }
        if (isExpense(lower)) {
            handleExpense(command)
            return
        }
        if (isExpensesToday(lower)) {
            handleExpensesToday()
            return
        }
        if (isBestApp(lower)) {
            handleBestApp()
            return
        }
        if (isWeeklyPace(lower)) {
            handleWeeklyPace()
            return
        }
        if (isDeadheadHome(lower)) {
            handleDeadheadHome()
            return
        }
        if (isParkingSave(lower)) {
            handleParkingSave(command)
            return
        }
        if (isParkingRecall(lower)) {
            handleParkingRecall()
            return
        }
        if (isParkingForget(lower)) {
            handleParkingForget()
            return
        }
        if (isFamilyDateList(lower)) {
            handleFamilyDateList()
            return
        }
        if (isCall(lower)) {
            handleCall(command)
            return
        }
        if (isReadMessages(lower)) {
            handleReadMessages()
            return
        }
        if (isTakeMeHome(lower)) {
            handleTakeMeHome()
            return
        }
        if (isBatchPrediction(lower)) {
            handleBatchPrediction(lower)
            return
        }
        if (isBeatYesterday(lower)) {
            handleBeatYesterday()
            return
        }
        if (isFloorSet(lower)) {
            handleFloorSet(lower)
            return
        }
        if (isOpenApp(lower)) {
            handleOpenApp(command)
            return
        }
        if (isPlaceSearch(lower)) {
            handlePlaceSearch(lower)
            return
        }
        if (isTimer(lower)) {
            handleTimer(command)
            return
        }
        if (isReadNotifications(lower)) {
            handleReadNotifications()
            return
        }
        if (isWhereAmI(lower)) {
            handleWhereAmI()
            return
        }
        if (isOdometer(lower)) {
            handleOdometer(command)
            return
        }
        if (isMusicPlay(lower)) {
            handleMusicPlay(command)
            return
        }
        if (isMusicSuggest(lower)) {
            handleMusicSuggest()
            return
        }
        if (isChatStart(lower)) {
            startChat(command)
            return
        }
        if (isJoke(lower) || isTrivia(lower) || isStory(lower)) {
            handleEntertainmentGroq(lower)
            return
        }
        if (isEarnings(lower)) {
            handleEarnings(lower)
            return
        }
        if (isPreShiftStart(lower)) {
            startPreShift()
            return
        }
        if (isWeather(lower)) {
            handleWeather()
            return
        }
        if (isGasStation(lower)) {
            handleGasStation()
            return
        }

        // Conversation mode: ordinary talk goes to Groq chat, no screen read.
        if (chatting) {
            handleChat(command)
            return
        }

        // Everything below reads the screen, one fresh read per request.
        // Show "Reading…" first so the card never sits on the previous
        // answer looking current while the read is in flight.
        output?.showReading()
        val read = ScreenReaderService.readFresh()
        if (!read.isOk) {
            val msg = read.failureMessage()
            output?.speak(msg)
            output?.showCard("Can't see the screen", msg, listOf("hide"))
            return
        }
        val screenText = read.text
        val platform = Platforms.displayName(read.packageName)

        when {
            isAddressNoteForget(lower) -> handleForgetAddressNote(screenText, platform, read)
            isAddressNoteRead(lower) -> handleReadAddressNotes(screenText, platform, read)
            isAddressNoteAdd(lower) -> handleAddAddressNote(command, screenText, platform)
            isNoteForget(lower) -> handleForgetNote(screenText, platform, read)
            isNoteRead(lower) -> handleReadNotes(screenText, platform, read)
            isNoteAdd(lower) -> handleAddNote(command, screenText, platform)
            isStorePrefForget(lower) -> handleStorePrefForget(screenText, platform)
            isStorePrefSet(lower) -> handleStorePrefSet(command, screenText, platform)
            isOfferCompare(lower) -> handleOfferCompare(screenText, platform, read)
            isCustomerMessage(lower) -> handleCustomerMessage(command, screenText, platform, read)
            isNavigate(lower) -> handleNavigate(command, screenText)
            isLog(lower) -> handleLog(screenText, platform, read)
            isOfferCheck(lower) -> handleOfferCheck(screenText, platform, read)
            else -> handleAsk(command, screenText, platform, read)
        }
    }

    /** "Read just now · Spark" — honest about how fresh the screen was. */
    private fun readFooter(read: ScreenReaderService.ScreenRead, platform: String): String =
        "\n\n${read.ageLabel().replaceFirstChar { it.uppercaseChar() }} · $platform"

    // ---- command matchers ----

    private fun isOutcome(lower: String) = isTook(lower) || isSkipped(lower)

    private fun isTook(lower: String) =
        lower.contains("took it") || lower.contains("i took") ||
            lower.contains("took this")

    private fun isSkipped(lower: String) =
        lower.contains("skipped it") || lower.contains("i skipped") ||
            lower.contains("skipped this") || lower.contains("skip it")

    private fun isEarnings(lower: String) =
        lower.contains("earning") || lower.contains("how much") &&
            (lower.contains("today") || lower.contains("this week") ||
                lower.contains("this month") || lower.contains("made") ||
                lower.contains("make")) ||
            lower.contains("how much did i make") ||
            lower.contains("how much have i made")

    private fun isPreShiftStart(lower: String) =
        lower.contains("pre-shift") || lower.contains("pre shift") ||
            lower.contains("preshift") || lower.contains("start my shift") ||
            lower.contains("start shift")

    /** Standalone weather: "what's the weather", "weather", "how's the weather". */
    private fun isWeather(lower: String) =
        lower.contains("weather") || lower.contains("forecast") ||
            lower.contains("temperature") || lower.contains("how hot") ||
            lower.contains("how cold") || lower.contains("is it raining") ||
            lower.contains("will it rain") || lower.contains("rain today")

    private fun isShiftSummary(lower: String) =
        lower.contains("end of shift") || lower.contains("shift summary") ||
            lower.contains("end my shift") || lower.contains("finish my shift") ||
            lower.contains("wrap up my shift") || lower.contains("shift wrap")

    private fun isTax(lower: String) =
        lower.contains("tax tally") || lower.contains("how much can i deduct") ||
            lower.contains("tax deduction") || lower.contains("deduction") ||
            (lower.contains("tax") && (lower.contains("tally") || lower.contains("deduct")))

    private fun isGoalSet(lower: String) =
        (lower.contains("set") && lower.contains("goal")) || lower.contains("my goal is")

    private fun isGoalProgress(lower: String) =
        lower.contains("goal") && (lower.contains("how am") || lower.contains("doing") ||
            lower.contains("progress") || lower.contains("am i") ||
            lower.contains("check my goal") || lower.contains("my goal"))

    private fun isFuel(lower: String) =
        lower.contains("fuel cost") || lower.contains("gas cost") ||
            (lower.contains("fuel") && lower.contains("cost")) || lower.contains("fuel today")

    private fun isBestHours(lower: String) =
        lower.contains("best hours") || lower.contains("best time") ||
            lower.contains("when do i earn") || lower.contains("when should i drive") ||
            lower.contains("best day to drive") || lower.contains("best hours to drive")

    private fun isDeadMiles(lower: String) =
        lower.contains("dead mile") || lower.contains("empty mile")

    /**
     * "Remind me in … / at … to …". A question about remembered things
     * ("remind me what did I ask you to remember") is NOT a reminder —
     * it stays a notes-recall question.
     */
    private fun isReminderSet(lower: String) =
        lower.contains("remind me") && !lower.contains("what did") &&
            !lower.contains("what do") && !lower.endsWith("?")

    private fun isReminderList(lower: String) =
        lower.contains("my reminders") || lower.contains("list reminders") ||
            lower.contains("what reminders") || lower.contains("any reminders")

    private fun isOfferReview(lower: String) =
        lower.contains("review my offers") || lower.contains("review offers") ||
            lower.contains("offers to review")

    private fun isGasStation(lower: String) =
        lower.contains("nearest gas") || lower.contains("closest gas") ||
            lower.contains("gas station") || lower.contains("where is gas") ||
            lower.contains("need gas") || lower.contains("find gas")

    // ---- v1.7 matchers (16 + 9 + music + 8 + camera) ----

    private fun isExpense(lower: String) =
        lower.contains("log") && ExpenseLog.parse(lower) != null

    private fun isExpensesToday(lower: String) =
        lower.contains("expenses today") || lower.contains("expenses this week") ||
            lower.contains("how much did i spend") || lower.contains("what did i spend")

    private fun isTip(lower: String) =
        lower.contains("tip") && TipLog.parse(lower) != null

    private fun isTipsToday(lower: String) =
        lower.contains("tips today") || lower.contains("tips this week") ||
            (lower.contains("tips") && lower.contains("how much"))

    private fun isBestApp(lower: String) =
        lower.contains("best app") || lower.contains("which app is paying") ||
            lower.contains("which app pays") || lower.contains("paying better") ||
            lower.contains("which app is better")

    private fun isWeeklyPace(lower: String) =
        lower.contains("weekly pace") || lower.contains("at this rate") ||
            lower.contains("how much will i make this week") ||
            lower.contains("projected this week")

    private fun isDeadheadHome(lower: String) =
        lower.contains("deadhead home") || lower.contains("how far home") ||
            lower.contains("how far from home") || lower.contains("miles from home") ||
            lower.contains("how far is home")

    private fun isParkingSave(lower: String) =
        lower.contains("parked") && (lower.contains("remember") || lower.contains("note"))

    private fun isParkingRecall(lower: String) =
        lower.contains("where did i park") || lower.contains("where's my car") ||
            lower.contains("where is my car") || lower.contains("where did i leave my car") ||
            lower.contains("where did i leave the car")

    private fun isParkingForget(lower: String) =
        lower.contains("forget where i parked") || lower.contains("forget my parking")

    private fun isFamilyDateAdd(lower: String) =
        (lower.contains("birthday") || lower.contains("anniversary")) &&
            (lower.contains("remind me") || lower.contains("remember")) &&
            FamilyDates.parse(lower) != null

    private fun isFamilyDateList(lower: String) =
        lower.contains("family dates") || lower.contains("birthdays") ||
            lower.contains("what birthdays") || lower.contains("list my family dates")

    private fun isRoadNoteAdd(lower: String): Boolean {
        val t = lower.trim()
        if (!t.startsWith("remember") && !t.startsWith("note")) return false
        val roadWords = listOf(
            "road", "street", "pothole", "flood", "bridge", "intersection",
            "highway", "avenue", "lane", "drive", "underpass", "overpass"
        )
        return roadWords.any { t.contains(it) }
    }

    private fun isCall(lower: String) =
        lower.startsWith("call ") || lower.startsWith("dial ") || lower.startsWith("phone ")

    private fun isReadMessages(lower: String) =
        lower.contains("read my messages") || lower.contains("read my texts") ||
            lower.contains("any new messages") || lower.contains("check my messages") ||
            lower.contains("read my texts aloud")

    private fun isTakeMeHome(lower: String) =
        lower.contains("take me home") || lower.contains("navigate home") ||
            lower.contains("drive home") || lower.contains("go home") ||
            lower.contains("head home")

    private fun isBatchPrediction(lower: String) =
        lower.contains("next batch") || lower.contains("when do batches drop") ||
            lower.contains("when do offers drop") || lower.contains("when's the next") ||
            lower.contains("when is the next batch")

    private fun isBeatYesterday(lower: String) =
        lower.contains("beat yesterday") || lower.contains("beating yesterday") ||
            lower.contains("ahead of yesterday") || lower.contains("am i beating yesterday") ||
            lower.contains("how am i doing vs yesterday")

    private fun isFloorSet(lower: String) =
        lower.contains("floor") && (lower.contains("set") || lower.contains("change"))

    private fun isOpenApp(lower: String) =
        lower.contains("open spark") || lower.contains("open doordash") ||
            lower.contains("open dash") || lower.contains("open instacart") ||
            lower.contains("open music") || lower.contains("open spotify") ||
            lower.contains("launch spark") || lower.contains("launch doordash") ||
            lower.contains("launch instacart")

    private fun isPlaceSearch(lower: String) = PlaceSearch.kindFor(lower) != null

    private fun isTimer(lower: String) = VoiceTimer.parseMinutes(lower) != null

    private fun isReadNotifications(lower: String) =
        lower.contains("read my notifications") || lower.contains("read notifications") ||
            lower.contains("what notifications") || lower.contains("any notifications")

    private fun isWhereAmI(lower: String) =
        lower.contains("where am i") || lower.contains("what's my location") ||
            lower.contains("what is my location") || lower.contains("where are we")

    private fun isOdometer(lower: String) = lower.contains("odometer")

    private fun isMusicPlay(lower: String) =
        lower.startsWith("play ") || lower.startsWith("play some ") || lower.startsWith("play my ")

    private fun isMusicSuggest(lower: String) =
        lower.contains("suggest some music") || lower.contains("suggest music") ||
            lower.contains("what should i listen to") || lower.contains("recommend some music") ||
            lower.contains("pick some music") || lower.contains("suggest a song")

    private fun isChatStart(lower: String) = Conversation.isChatStart(lower)

    private fun isJoke(lower: String) = Conversation.isJoke(lower)

    private fun isTrivia(lower: String) = Conversation.isTrivia(lower)

    private fun isStory(lower: String) = Conversation.isStory(lower)

    private fun isJournalStart(lower: String) =
        lower.contains("journal") && (lower.contains("record") || lower.contains("note") ||
            lower.contains("add") || lower.trim() == "journal" ||
            lower.contains("voice journal") || lower.contains("start a journal"))

    private fun isJournalRead(lower: String) =
        lower.contains("read my journal") || lower.contains("read the journal") ||
            lower.contains("journal entries")

    private fun isCameraStart(lower: String): Boolean {
        val t = lower.trim()
        if (t.contains("journal") || t.contains("delivery")) return false
        return t == "record" || t.contains("start recording") || t.contains("start video") ||
            t.contains("record a video") || t.contains("record video")
    }

    private fun isCameraStop(lower: String) =
        lower.contains("stop recording") || lower.contains("stop the recording") ||
            lower.contains("stop video")

    private fun isCameraPhoto(lower: String) =
        lower.contains("take a photo") || lower.contains("take a picture") ||
            lower.contains("take photo") || lower.contains("snap a photo") ||
            lower.contains("take a pic")

    private fun isStorePrefSet(lower: String) =
        lower.contains("avoid this store") || lower.contains("avoid this place") ||
            lower.contains("i like this store") || lower.contains("like this store") ||
            lower.contains("don't like this store") || lower.contains("dont like this store") ||
            lower.contains("flag this store")

    private fun isStorePrefForget(lower: String) =
        lower.contains("remove store flag") || lower.contains("clear store flag") ||
            lower.contains("unflag this store")

    private fun isOfferCompare(lower: String) =
        lower.contains("which is better") || lower.contains("which offer is better") ||
            lower.contains("compare offers") || lower.contains("compare these offers") ||
            lower.contains("which one is better")

    private fun isCustomerMessage(lower: String) = CustomerMessage.kindFor(lower) != null

    /** Everything the handler recognises as a command (pauses a conversation). */
    private fun isRecognizedCommand(lower: String): Boolean {
        return isOutcome(lower) || isTax(lower) || isShiftSummary(lower) ||
            isGoalSet(lower) || isGoalProgress(lower) || isFuel(lower) ||
            isBestHours(lower) || isDeadMiles(lower) || isReminderSet(lower) ||
            isReminderList(lower) || isOfferReview(lower) || isEarnings(lower) ||
            isPreShiftStart(lower) || isWeather(lower) || isGasStation(lower) ||
            isExpense(lower) || isExpensesToday(lower) || isTip(lower) ||
            isTipsToday(lower) || isBestApp(lower) || isWeeklyPace(lower) ||
            isDeadheadHome(lower) || isParkingSave(lower) || isParkingRecall(lower) ||
            isParkingForget(lower) || isFamilyDateAdd(lower) || isFamilyDateList(lower) ||
            isRoadNoteAdd(lower) || isCall(lower) || isReadMessages(lower) ||
            isTakeMeHome(lower) || isBatchPrediction(lower) || isBeatYesterday(lower) ||
            isFloorSet(lower) || isOpenApp(lower) || isPlaceSearch(lower) ||
            isTimer(lower) || isReadNotifications(lower) || isWhereAmI(lower) ||
            isOdometer(lower) || isMusicPlay(lower) || isMusicSuggest(lower) ||
            isJoke(lower) || isTrivia(lower) || isStory(lower) ||
            isJournalStart(lower) || isJournalRead(lower) ||
            isCameraStart(lower) || isCameraStop(lower) || isCameraPhoto(lower) ||
            isStorePrefSet(lower) || isStorePrefForget(lower) || isOfferCompare(lower) ||
            isCustomerMessage(lower) || isOfferCheck(lower) || isLog(lower) ||
            isNavigate(lower) || isNoteAdd(lower) || isNoteRead(lower) ||
            isNoteForget(lower) || isAddressNoteAdd(lower) || isAddressNoteRead(lower) ||
            isAddressNoteForget(lower)
    }

    /**
     * Address notes: same directive-only rule as store notes, but only
     * when the command is about an ADDRESS (address / gate / drop-off
     * words). "Remember: gate code 1234 for this address" saves an
     * address note; "remember: pickup is at the back" stays a store note.
     * Questions never save — they list.
     */
    private fun isAddressNoteAdd(lower: String): Boolean {
        val t = lower.trim()
        if (isAddressNoteRead(t) || t.endsWith("?")) return false
        val addressy = t.contains("address") || t.contains("gate") ||
            t.contains("drop-off") || t.contains("dropoff") ||
            t.contains("delivery address") || t.contains("for this address") ||
            t.contains("this delivery")
        if (!addressy) return false
        return t.startsWith("remember:") || t.startsWith("remember that") ||
            t.startsWith("remember this") || t.startsWith("remember to") ||
            t.startsWith("remember ") || t.startsWith("note:") ||
            t.startsWith("note that") || t.startsWith("note this") ||
            t.startsWith("note ") || t.startsWith("add a note") ||
            t.startsWith("save a note")
    }

    /** Recall for an address: any question or list request mentioning address/gate. */
    private fun isAddressNoteRead(lower: String): Boolean {
        val t = lower.trim()
        // A directive to save is never a recall, even when it says "gate code".
        if (isSaveDirective(t)) return false
        val addressy = t.contains("address") || t.contains("gate")
        if (!addressy) return false
        if (t.contains("what did i ask") || t.contains("what have i asked") ||
            t.contains("what do i know") || t.contains("what do you know") ||
            t.contains("what notes") || t.contains("show my notes") ||
            t.contains("show me my notes") || t.contains("list my notes") ||
            t.contains("list the notes") || t.contains("notes for this address") ||
            t.contains("gate code") || t.contains("what's the gate code") ||
            t.contains("what is the gate code") || t.contains("any notes")
        ) return true
        if (t.startsWith("what") && t.contains("note")) return true
        if ((t.startsWith("show") || t.startsWith("list")) && t.contains("note")) return true
        if (t.startsWith("tell me") && t.contains("note")) return true
        if (t.endsWith("?") && t.contains("note")) return true
        return false
    }

    private fun isAddressNoteForget(lower: String) =
        (lower.contains("forget") || lower.contains("delete")) &&
            (lower.contains("address note") || lower.contains("gate") ||
                lower.contains("address"))

    /**
     * Save a note ONLY on a directive ("remember: …", "remember that …",
     * "note: …", "add a note …"). A question about remembered things is
     * never a save, even when it contains the word "remember" — "what did
     * I ask you to remember" must recall, not save a new note.
     */
    private fun isNoteAdd(lower: String): Boolean {
        val t = lower.trim()
        // Questions about memory never save.
        if (isNoteRead(t) || t.endsWith("?")) return false
        if (t == "remember" || t == "note") return true // prompt for the note text
        return t.startsWith("remember:") || t.startsWith("remember that") ||
            t.startsWith("remember this") || t.startsWith("remember to") ||
            t.startsWith("remember ") || t.startsWith("note:") ||
            t.startsWith("note that") || t.startsWith("note this") ||
            t.startsWith("note ") || t.startsWith("add a note") ||
            t.startsWith("save a note")
    }

    /** Recall: any question or request to list what was remembered. */
    private fun isNoteRead(lower: String): Boolean {
        val t = lower.trim()
        // A directive to save is never a recall, even when it names a note.
        if (isSaveDirective(t)) return false
        if (t.contains("what did i ask") || t.contains("what have i asked") ||
            t.contains("what did i tell you to remember") ||
            t.contains("what do i know") || t.contains("what do you know") ||
            t.contains("any notes") || t.contains("read my notes") ||
            t.contains("store notes") || t.contains("notes for this store") ||
            t.contains("what notes") || t.contains("show my notes") ||
            t.contains("show me my notes") || t.contains("list my notes") ||
            t.contains("list the notes")
        ) return true
        // Generic question forms about notes/memory.
        if (t.startsWith("what") && (t.contains("remember") || t.contains("note"))) return true
        if ((t.startsWith("show") || t.startsWith("list")) && t.contains("note")) return true
        if (t.startsWith("tell me") && (t.contains("remember") || t.contains("note"))) return true
        if (t.endsWith("?") && (t.contains("remember") || t.contains("note"))) return true
        return false
    }

    private fun isNoteForget(lower: String) =
        lower.contains("forget that note") || lower.contains("forget the note") ||
            lower.contains("delete note") || lower.contains("delete that note") ||
            lower.contains("forget my notes") || lower.contains("forget this note")

    /** A save directive ("remember: …", "note that …") — never a recall question. */
    private fun isSaveDirective(t: String): Boolean {
        val s = t.trim()
        if (s == "remember" || s == "note") return true
        return s.startsWith("remember:") || s.startsWith("remember that") ||
            s.startsWith("remember this") || s.startsWith("remember to") ||
            s.startsWith("remember ") || s.startsWith("note:") ||
            s.startsWith("note that") || s.startsWith("note this") ||
            s.startsWith("note ") || s.startsWith("add a note") ||
            s.startsWith("save a note")
    }

    private fun isNavigate(lower: String) =
        lower.contains("navigate") || lower.contains("directions") ||
            lower.contains("take me") || lower.contains("drive to")

    private fun isLog(lower: String) =
        lower.contains("log") || lower.contains("save this") ||
            lower.contains("record this") || lower.contains("record delivery")

    private fun isOfferCheck(lower: String) =
        lower.contains("check") || lower.contains("offer") || lower.contains("mile") ||
            lower.contains("worth") || lower.contains("how much") || lower.contains("pay")

    // ---- offer outcome recording ----

    /** Voice path: record the outcome, speak it, show it. */
    fun recordOutcome(outcome: OfferOutcome) {
        val msg = recordOutcomeMessage(outcome)
        output?.speak(msg)
        output?.showCard("Offer recorded", msg, listOf("hide"))
    }

    /**
     * Card-button path (via AgentBridge): just the message; the bridge speaks it.
     * "Took it" also logs the delivery automatically (same shape as "log
     * this delivery") — deduped, so a second tap says it is already logged
     * instead of duplicating. "Skipped it" never logs.
     */
    fun recordOutcomeMessage(outcome: OfferOutcome): String {
        maybeShiftStart()
        val checked = lastChecked
        if (checked == null) {
            return "I don't have a recent offer check to record. Check an offer first, then say took it or skipped it."
        }
        val ok = OfferMemory.markOutcome(context, checked.id, outcome)
        lastChecked = checked.copy(outcome = outcome)
        val label = if (checked.storeLabel.isNotBlank()) checked.storeLabel
            else Platforms.displayFromTag(checked.platform)
        if (!ok) return "I couldn't save that. Try again."
        if (outcome != OfferOutcome.TOOK) return "Recorded: skipped it — $label."
        val address = checked.stops.firstOrNull()
        val (date, time) = DeliveryLog.nowParts()
        val entry = DeliveryEntry(
            date, time, Platforms.tagFromDisplay(checked.platform),
            checked.pay, checked.miles, checked.perMile, address
        )
        val logged = DeliveryLog.appendIfNew(context, entry)
        if (logged == DeliveryLog.LogResult.LOGGED) {
            SeenOffers.markTakenForCheck(context, checked)
            SeenOffers.resolveByDelivery(context, entry)
        }
        val nudge = if (logged == DeliveryLog.LogResult.LOGGED) {
            GoalTracker.checkReached(context)?.let { " $it" } ?: ""
        } else ""
        return when (logged) {
            DeliveryLog.LogResult.LOGGED ->
                "Recorded: took it — $label. Logged to your delivery log.$nudge"
            DeliveryLog.LogResult.ALREADY_LOGGED ->
                "Recorded: took it — $label. Already in your delivery log."
            DeliveryLog.LogResult.FAILED ->
                "Recorded: took it — $label."
        }
    }

    /** Remember a check the card ran (AgentBridge.checkOffer) so outcome recording works. */
    fun noteCheckedOffer(record: OfferMemoryRecord) {
        lastChecked = record
    }

    // ---- earnings tally (code math, no screen read, no Groq) ----

    private fun handleEarnings(lower: String) {
        val range = when {
            lower.contains("this week") -> EarningsRange.WEEK
            lower.contains("this month") -> EarningsRange.MONTH
            else -> EarningsRange.TODAY
        }
        val tally = Earnings.tally(context, range)
        var text = Earnings.text(tally)
        // Tips are reported separately from base pay, never merged into it.
        if (range == EarningsRange.TODAY) {
            val tips = TipLog.totalToday(context)
            if (tips > 0.0) {
                text += " Plus $%.2f in tips today, kept separate from base pay.".format(tips)
            }
        }
        output?.speak(text)
        output?.showCard("Earnings — ${tally.label}", text, listOf("hide"))
    }

    /** JSON tally for the card buttons. */
    fun earningsSummary(rangeText: String): String {
        val range = when (rangeText.lowercase()) {
            "week" -> EarningsRange.WEEK
            "month" -> EarningsRange.MONTH
            else -> EarningsRange.TODAY
        }
        val tally = Earnings.tally(context, range)
        return org.json.JSONObject().apply {
            put("ok", true)
            put("label", tally.label)
            put("count", tally.count)
            put("totalPay", tally.totalPay)
            put("totalMiles", tally.totalMiles)
            if (tally.avgPerMile != null) put("perMile", tally.avgPerMile) else put("perMile", org.json.JSONObject.NULL)
            put("text", Earnings.text(tally))
        }.toString()
    }

    // ---- end-of-shift summary (code math, no screen read, no Groq) ----

    private fun handleShiftSummary() {
        val summary = ShiftSummary.summarize(context)
        var text = ShiftSummary.text(summary)
        if (summary.count > 0) {
            text += " " + BeatYesterday.text(BeatYesterday.compare(context))
        }
        // End-of-shift odometer prompt, once per day: one combined
        // utterance (summary + question), so QUEUE_FLUSH cannot cut the
        // summary off — the answer starts listening when it finishes.
        val today = DeliveryLog.nowParts().first
        val askOdometer = OdometerLog.readingForDate(context, today, "end") == null &&
            Prefs.getOdometerEndAskedDate(context) != today
        if (askOdometer) {
            Prefs.setOdometerEndAskedDate(context, today)
            odometerKind = "end"
            output?.speakAndListen("$text One more — what's the ending odometer reading? Say skip if you'd rather not.")
            output?.showCard("End of shift", text, listOf("hide"))
        } else {
            output?.speak(text)
            output?.showCard("End of shift", text, listOf("hide"))
        }
    }

    /** JSON summary for the card button. */
    fun shiftSummaryJson(): String {
        val summary = ShiftSummary.summarize(context)
        var text = ShiftSummary.text(summary)
        if (summary.count > 0) {
            text += " " + BeatYesterday.text(BeatYesterday.compare(context))
        }
        return org.json.JSONObject().apply {
            put("ok", true)
            put("count", summary.count)
            put("totalPay", summary.totalPay)
            put("totalMiles", summary.totalMiles)
            if (summary.avgPerMile != null) put("perMile", summary.avgPerMile)
            else put("perMile", org.json.JSONObject.NULL)
            put("text", text)
        }.toString()
    }

    // ---- daily earnings goal (code math, no screen read) ----

    private fun handleGoalSet(lower: String) {
        val amount = GoalTracker.parseGoalAmount(lower)
        if (amount == null) {
            val msg = "I didn't catch a dollar amount for the goal. Say it like: set my goal to 150 dollars."
            output?.speak(msg)
            output?.showCard("Daily goal", msg, listOf("hide"))
            return
        }
        Prefs.setDailyGoal(context, amount)
        val msg = "Daily goal set to $%.2f. I'll tell you once when you reach it today — and only then.".format(amount)
        output?.speak(msg)
        output?.showCard("Daily goal", msg, listOf("hide"))
    }

    private fun handleGoalProgress() {
        val text = GoalTracker.progressText(context)
        output?.speak(text)
        output?.showCard("Daily goal", text, listOf("hide"))
    }

    // ---- fuel math (code math, no screen read) ----

    private fun handleFuel() {
        val tally = FuelMath.tallyToday(context)
        val text = FuelMath.text(context, tally)
        output?.speak(text)
        output?.showCard("Fuel cost today", text, listOf("hide"))
    }

    // ---- best hours (code math on the log, no screen read) ----

    private fun handleBestHours() {
        val result = BestHours.analyze(context)
        val text = BestHours.text(result)
        output?.speak(text)
        output?.showCard("Your best hours", text, listOf("hide"))
    }

    // ---- tax tally (code math, no screen read) ----

    private fun handleTax() {
        val year = java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
            .format(java.util.Date()).toInt()
        val tally = TaxTally.tallyYear(context, year)
        var text = TaxTally.text(year, tally)
        // Expenses and tips feed the tally, reported separately from mileage.
        val expenses = ExpenseLog.totalYear(context, year)
        if (expenses > 0.0) {
            text += " Plus $%.2f in logged work expenses this year (gas, car wash, and the rest) — keep the receipts; that's separate from the mileage deduction.".format(
                expenses
            )
        }
        val tips = TipLog.totalYear(context, year)
        if (tips > 0.0) {
            text += " Tips this year: $%.2f, on top of base pay — tips count as income too.".format(tips)
        }
        output?.speak(text)
        output?.showCard("Tax tally $year", text, listOf("hide"))
    }

    // ---- dead miles (code math, no screen read) ----

    private fun handleDeadMiles() {
        val summary = DeadMiles.summaryToday(context)
        val text = DeadMiles.text(summary)
        output?.speak(text)
        output?.showCard("Dead miles today", text, listOf("hide"))
        // Deadhead home: last dropoff -> home, real route miles + return
        // fuel cost (fail-soft: unknown distance is spoken as unknown).
        val lastDropoff = summary.lastDropoff
        if (lastDropoff != null) {
            DeadheadHome.deadheadHome(context, lastDropoff) { miles, fuelCost ->
                mainHandler.post {
                    val msg = DeadheadHome.text(lastDropoff, Prefs.getWeatherCity(context), miles, fuelCost)
                    output?.speak(msg)
                    output?.showCard("Deadhead home", msg, listOf("hide"))
                }
            }
        }
    }

    // ---- nearest gas station (free Overpass, one-shot GPS, fail-soft) ----

    private fun handleGasStation() {
        val hasPermission = try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        if (!hasPermission) {
            val msg = "Location isn't on — allow location for RoadHouse Overlay Groq in Settings so I can find gas near you."
            output?.speak(msg)
            output?.showCard("Gas station", msg, listOf("hide"))
            return
        }
        output?.showCard("Gas station", "Looking for gas stations near you…", listOf("hide"))
        GasStations.findNearest(context) { stations ->
            mainHandler.post {
                when {
                    stations == null -> {
                        val msg = "I couldn't look up gas stations right now. Check your connection and try again."
                        output?.speak(msg)
                        output?.showCard("Gas station", msg, listOf("hide"))
                    }
                    stations.isEmpty() -> {
                        val msg = "No gas stations found within about 6 miles of you."
                        output?.speak(msg)
                        output?.showCard("Gas station", msg, listOf("hide"))
                    }
                    else -> {
                        val lines = stations.mapIndexed { i, s ->
                            "${if (i == 0) "Closest" else "Next"}: ${s.name}, about %.1f miles away".format(
                                Locale.US, s.miles
                            )
                        }.joinToString(". ")
                        val msg = "$lines."
                        output?.speak(msg)
                        output?.showCard("Gas station", msg, listOf("hide"))
                    }
                }
            }
        }
    }

    // ---- v1.7: shift lifecycle ----

    /**
     * Mark the shift started (first offer of the day) and arm the +3h
     * break alarm. Pure state — callers do their own speaking, because
     * VoiceManager.speak uses QUEUE_FLUSH and a second speak would cut
     * off the answer already playing. Returns true only on a new shift.
     */
    fun maybeShiftStart(): Boolean {
        if (!ShiftState.startShift(context)) return false
        BreakReminder.scheduleIfNeeded(context)
        return true
    }

    /** Pause a conversation (an offer event always wins over chatting). */
    fun pauseConversation() {
        if (chatting) {
            chatting = false
            chatHistory.clear()
        }
    }

    /**
     * Offer the end-of-shift summary (shift auto-detect or take-me-home
     * arrival). One spoken question + card; a yes/no answers it, any
     * other command clears it and runs normally.
     */
    fun offerShiftSummary() {
        summaryPending = true
        val msg = "Want your shift summary? Say yes, or no if not."
        output?.speak(msg)
        output?.showCard("End of shift?", msg, listOf("hide"))
    }

    // ---- v1.7: expenses & tips ----

    private fun handleExpense(command: String) {
        val parsed = ExpenseLog.parse(command)
        if (parsed == null) {
            val msg = "Say it like: log 12 dollars gas, or log 8 dollars car wash."
            output?.speak(msg)
            output?.showCard("Expense", msg, listOf("hide"))
            return
        }
        val (amount, category) = parsed
        val ok = ExpenseLog.add(context, amount, category)
        val msg = if (ok) {
            "Logged expense: $%.2f for $category. That's $%.2f in expenses today.".format(
                amount, category, ExpenseLog.totalToday(context)
            )
        } else {
            "I couldn't save that expense. Try again."
        }
        output?.speak(msg)
        output?.showCard("Expense logged", msg, listOf("hide"))
    }

    private fun handleExpensesToday() {
        val total = ExpenseLog.totalToday(context)
        val count = ExpenseLog.countToday(context)
        val msg = if (count == 0) {
            "No expenses logged today yet. Say \"log 12 dollars gas\" to add one."
        } else {
            val noun = if (count == 1) "expense" else "expenses"
            "Expenses today: $%.2f across $count $noun.".format(total)
        }
        output?.speak(msg)
        output?.showCard("Expenses today", msg, listOf("hide"))
    }

    private fun handleTip(command: String) {
        val parsed = TipLog.parse(command)
        if (parsed == null) {
            val msg = "Say it like: log a 4 dollar tip, or log a 4 dollar tip for Walmart."
            output?.speak(msg)
            output?.showCard("Tip", msg, listOf("hide"))
            return
        }
        val (amount, platform) = parsed
        val ok = TipLog.add(context, amount, platform)
        val platformPart = when (platform) {
            "last" -> " for your last delivery"
            "unknown" -> ""
            else -> " for ${Platforms.displayFromTag(platform)}"
        }
        val msg = if (ok) {
            "Logged tip: $%.2f$platformPart. That's $%.2f in tips today, kept separate from base pay.".format(
                amount, TipLog.totalToday(context)
            )
        } else {
            "I couldn't save that tip. Try again."
        }
        output?.speak(msg)
        output?.showCard("Tip logged", msg, listOf("hide"))
    }

    private fun handleTipsToday() {
        val total = TipLog.totalToday(context)
        val msg = if (total <= 0.0) {
            "No tips logged today yet. Say \"log a 4 dollar tip\" to add one."
        } else {
            "Tips today: $%.2f, kept separate from base pay.".format(total)
        }
        output?.speak(msg)
        output?.showCard("Tips today", msg, listOf("hide"))
    }

    // ---- v1.7: best app / weekly pace / deadhead home / beat yesterday ----

    private fun handleBestApp() {
        val result = BestApp.analyze(context)
        val text = BestApp.text(result)
        output?.speak(text)
        output?.showCard("Best app right now", text, listOf("hide"))
    }

    private fun handleWeeklyPace() {
        val result = WeeklyPace.pace(context)
        val text = WeeklyPace.text(result)
        output?.speak(text)
        output?.showCard("Weekly pace", text, listOf("hide"))
    }

    private fun handleDeadheadHome() {
        val lastDropoff = DeadMiles.lastDropoffToday(context)
        if (lastDropoff.isNullOrBlank()) {
            val msg = "No dropoff logged today yet, so there's no drive home to measure. Log a delivery first."
            output?.speak(msg)
            output?.showCard("Deadhead home", msg, listOf("hide"))
            return
        }
        output?.showCard("Deadhead home", "Working out the drive home…", listOf("hide"))
        DeadheadHome.deadheadHome(context, lastDropoff) { miles, fuelCost ->
            mainHandler.post {
                val msg = DeadheadHome.text(lastDropoff, Prefs.getWeatherCity(context), miles, fuelCost)
                output?.speak(msg)
                output?.showCard("Deadhead home", msg, listOf("hide"))
            }
        }
    }

    private fun handleBeatYesterday() {
        val result = BeatYesterday.compare(context)
        val text = BeatYesterday.text(result)
        output?.speak(text)
        output?.showCard("Beat yesterday", text, listOf("hide"))
    }

    // ---- v1.7: parking spot ----

    private fun handleParkingSave(command: String) {
        val lower = command.lowercase()
        var text = command
        for (prefix in listOf("remember i parked", "remember that i parked", "remember i parked in", "note i parked", "remember parked")) {
            if (lower.contains(prefix)) {
                val idx = lower.indexOf(prefix)
                text = command.substring(idx + prefix.length)
                break
            }
        }
        // Fall back: strip a leading "remember" / "note".
        if (text == command) {
            for (prefix in listOf("remember that", "remember:", "remember", "note that", "note:", "note")) {
                if (lower.startsWith(prefix)) {
                    text = command.substring(prefix.length)
                    break
                }
            }
        }
        text = text.trim().trimStart(':', '-', ' ').trim()
        // "I parked in row C" -> "row C".
        text = text.replace(Regex("(?i)^i parked (in |at )?"), "").trim()
        text = text.replace(Regex("(?i)^(in |at )"), "").trim()
        if (text.isBlank()) {
            val msg = "Where did you park? Say it like: remember I parked in row C."
            output?.speak(msg)
            output?.showCard("Parking spot", msg, listOf("hide"))
            return
        }
        val spot = ParkingSpot.save(context, text)
        val msg = if (spot != null) "Got it — parked in $text. I'll remember that until you park somewhere else."
            else "I couldn't save that. Try again."
        output?.speak(msg)
        output?.showCard("Parking spot", msg, listOf("hide"))
    }

    private fun handleParkingRecall() {
        val spot = ParkingSpot.latest(context)
        val msg = if (spot == null) {
            "You haven't told me where you parked. Say \"remember I parked in row C\" and I'll remember it."
        } else {
            "You parked in ${spot.text}."
        }
        output?.speak(msg)
        output?.showCard("Parking spot", msg, listOf("hide"))
    }

    private fun handleParkingForget() {
        val ok = ParkingSpot.forget(context)
        val msg = if (ok) "Forgot where you parked." else "Nothing saved about parking."
        output?.speak(msg)
        output?.showCard("Parking spot", msg, listOf("hide"))
    }

    // ---- v1.7: family dates ----

    private fun handleFamilyDateAdd(command: String) {
        val parsed = FamilyDates.parse(command)
        if (parsed == null) {
            val msg = "Say it like: remind me Ilah's birthday is June 3."
            output?.speak(msg)
            output?.showCard("Family date", msg, listOf("hide"))
            return
        }
        val ok = FamilyDates.add(context, parsed)
        if (ok) FamilyDates.scheduleDailyCheck(context)
        val msg = if (ok) {
            "Got it — ${parsed.name}'s ${parsed.occasion} is ${FamilyDates.monthName(parsed.month)} ${parsed.day}. " +
                "I'll say so on the day, at 8 in the morning."
        } else {
            "I couldn't save that date. Try again."
        }
        output?.speak(msg)
        output?.showCard("Family date", msg, listOf("hide"))
    }

    private fun handleFamilyDateList() {
        val dates = FamilyDates.dates(context)
        val msg = if (dates.isEmpty()) {
            "No family dates saved yet. Say \"remind me Ilah's birthday is June 3\" to add one."
        } else {
            val lines = dates.mapIndexed { i, d ->
                "${i + 1}. ${d.name}'s ${d.occasion} — ${FamilyDates.monthName(d.month)} ${d.day}"
            }.joinToString("\n")
            "Family dates:\n$lines"
        }
        output?.speak(msg)
        output?.showCard("Family dates", msg, listOf("hide"))
    }

    // ---- v1.7: road notes ----

    private fun handleRoadNoteAdd(command: String) {
        val lower = command.lowercase()
        var text = command
        for (prefix in listOf("remember that", "remember this", "remember:", "remember", "note that", "note this", "note:", "note")) {
            if (lower.startsWith(prefix)) {
                text = command.substring(prefix.length)
                break
            }
        }
        text = text.trim().trimStart(':', '-', ' ').trim()
        if (text.isBlank()) {
            val msg = "What should I remember about this road? Say it after \"remember\"."
            output?.speak(msg)
            output?.showCard("Road note", msg, listOf("hide"))
            return
        }
        if (!LocationHelper.hasLocationPermission(context)) {
            val msg = "Location isn't on — allow location for RoadHouse Overlay Groq in Settings so I can pin this road note to this spot."
            output?.speak(msg)
            output?.showCard("Road note", msg, listOf("hide"))
            return
        }
        output?.showCard("Road note", "Getting your location to pin this note…", listOf("hide"))
        LocationHelper.oneShot(context) { fix ->
            if (fix == null) {
                val msg = "I couldn't get your location right now, so I didn't save the road note — try again when GPS is up."
                output?.speak(msg)
                output?.showCard("Road note", msg, listOf("hide"))
                return@oneShot
            }
            val note = RoadNotes.add(context, text, fix.first, fix.second)
            val msg = if (note != null) "Saved road note at this spot: $text. I'll read it back when you're near here again."
                else "I couldn't save that road note. Try again."
            output?.speak(msg)
            output?.showCard("Road note", msg, listOf("hide"))
        }
    }

    /** Proximity read-back, on the next user interaction only (no polling). */
    private fun maybeRoadNoteProximity() {
        if (RoadNotes.count(context) == 0) return
        if (!LocationHelper.hasLocationPermission(context)) return
        LocationHelper.oneShot(context) { fix ->
            if (fix == null) return@oneShot
            val near = RoadNotes.nearbyUnspoken(context, fix.first, fix.second)
            if (near.isEmpty()) return@oneShot
            val note = near[0]
            RoadNotes.markSpoken(context, note.id)
            // Delayed so it cannot flush the answer to this interaction (QUEUE_FLUSH).
            mainHandler.postDelayed({
                val msg = "Heads up — road note near here: ${note.text}"
                output?.speak(msg)
                output?.showCard("Road note", msg, listOf("hide"))
            }, 6000)
        }
    }

    // ---- v1.7: call / text / take me home / batch prediction ----

    private fun handleCall(command: String) {
        val lower = command.lowercase()
        var name = command.trim()
        for (prefix in listOf("call ", "dial ", "phone ")) {
            if (lower.startsWith(prefix)) {
                name = command.substring(prefix.length).trim()
                break
            }
        }
        name = name.replace(Regex("(?i)\\s+please\\s*$"), "").trim()
        if (name.isBlank()) {
            val msg = "Who should I call? Say it like: call Ella."
            output?.speak(msg)
            output?.showCard("Call", msg, listOf("hide"))
            return
        }
        val (msg, intent) = CallText.dial(context, name)
        output?.speak(msg)
        output?.showCard("Call", msg, listOf("hide"))
        if (intent != null) {
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                output?.speak("I couldn't open the dialer.")
            }
        }
    }

    private fun handleReadMessages() {
        val text = CallText.unreadText(context)
        output?.speak(text)
        output?.showCard("Messages", text, listOf("hide"))
    }

    private fun handleTakeMeHome() {
        val home = Prefs.getWeatherCity(context)
        Prefs.setHomeNavActive(context, true)
        val msg = "Navigating home to $home. I'll offer your shift summary when you get there."
        output?.speak(msg)
        output?.showCard("Take me home", msg, listOf("hide"))
        output?.navigateTo(home)
    }

    private fun handleBatchPrediction(lower: String) {
        val platformTag = when {
            lower.contains("spark") -> "spark"
            lower.contains("doordash") || lower.contains("dash") -> "doordash"
            lower.contains("instacart") -> "instacart"
            else -> null
        }
        val result = BatchPrediction.analyze(context, platformTag)
        val text = BatchPrediction.text(result)
        output?.speak(text)
        output?.showCard("Next batch", text, listOf("hide"))
    }

    // ---- v1.7: floor / app switching / places / timer / notifications / where am I ----

    private fun handleFloorSet(lower: String) {
        val amount = GoalTracker.parseGoalAmount(lower)
        if (amount == null) {
            val msg = "I didn't catch a dollar amount for the floor. Say it like: set my floor to 2 dollars a mile."
            output?.speak(msg)
            output?.showCard("Offer floor", msg, listOf("hide"))
            return
        }
        if (amount <= 0.0 || amount > 10.0) {
            val msg = "A floor has to be between 1 cent and 10 dollars a mile — I won't set it to zero, or every offer would pass. Say a number in that range."
            output?.speak(msg)
            output?.showCard("Offer floor", msg, listOf("hide"))
            return
        }
        Prefs.setFloor(context, amount)
        val msg = "Offer floor set to $%.2f a mile. Offers under that will read as skips from now on.".format(amount)
        output?.speak(msg)
        output?.showCard("Offer floor", msg, listOf("hide"))
    }

    private fun handleOpenApp(command: String) {
        val (intent, msg) = AppSwitcher.launchIntent(context, command)
        output?.speak(msg)
        output?.showCard("Open app", msg, listOf("hide"))
        if (intent != null) {
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                output?.speak("I couldn't open that app.")
            }
        }
    }

    private fun handlePlaceSearch(lower: String) {
        val kind = PlaceSearch.kindFor(lower) ?: return
        if (!LocationHelper.hasLocationPermission(context)) {
            val msg = "Location isn't on — allow location for RoadHouse Overlay Groq in Settings so I can search near you."
            output?.speak(msg)
            output?.showCard("Nearby", msg, listOf("hide"))
            return
        }
        output?.showCard("Nearby", "Looking for ${kind.label} near you…", listOf("hide"))
        PlaceSearch.searchNearest(context, kind) { places ->
            mainHandler.post {
                val text = PlaceSearch.text(kind, places)
                output?.speak(text)
                output?.showCard("Nearby — ${kind.label}", text, listOf("hide"))
            }
        }
    }

    private fun handleTimer(command: String) {
        val minutes = VoiceTimer.parseMinutes(command)
        if (minutes == null) {
            val msg = "Say it like: set a 10 minute timer."
            output?.speak(msg)
            output?.showCard("Timer", msg, listOf("hide"))
            return
        }
        val scheduled = VoiceTimer.schedule(context, minutes)
        val msg = if (scheduled != null) VoiceTimer.confirmText(minutes)
            else "I couldn't set that timer. Try again."
        output?.speak(msg)
        output?.showCard("Timer", msg, listOf("hide"))
    }

    private fun handleReadNotifications() {
        val text = NotificationReaderService.readAloudText(context)
        output?.speak(text)
        output?.showCard("Notifications", text, listOf("hide"))
        if (!NotificationReaderService.isEnabled(context)) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // The spoken message already explains how to enable it.
            }
        }
    }

    private fun handleWhereAmI() {
        output?.showCard("Where am I", "Finding you…", listOf("hide"))
        WhereAmI.locate(context) { place ->
            mainHandler.post {
                val text = WhereAmI.text(place)
                output?.speak(text)
                output?.showCard("Where am I", text, listOf("hide"))
            }
        }
    }

    // ---- v1.7: odometer ----

    private fun handleOdometer(command: String) {
        val number = OdometerLog.parseNumber(command)
        if (number == null) {
            val text = OdometerLog.todayText(context)
            output?.speak(text)
            output?.showCard("Odometer", text, listOf("hide"))
            return
        }
        val today = DeliveryLog.nowParts().first
        val kind = if (OdometerLog.readingForDate(context, today, "start") == null) "start" else "end"
        val ok = OdometerLog.add(context, kind, number)
        val msg = if (ok) "Logged odometer $kind: %.0f.".format(number)
            else "I couldn't save that odometer reading. Try again."
        output?.speak(msg)
        output?.showCard("Odometer", msg, listOf("hide"))
    }

    private fun handleOdometerAnswer(command: String, lower: String) {
        val kind = odometerKind ?: return
        odometerKind = null
        if (lower.contains("skip") || lower.contains("cancel") || lower.contains("never mind") ||
            lower.contains("not now") || lower.contains("forget it")
        ) {
            output?.speak("Skipped — no odometer reading saved.")
            return
        }
        val number = OdometerLog.parseNumber(command)
        if (number == null) {
            output?.speak("No number heard — skipping the odometer.")
            return
        }
        val ok = OdometerLog.add(context, kind, number)
        val msg = if (ok) "Got it — odometer $kind: %.0f.".format(number)
            else "I couldn't save that odometer reading."
        output?.speak(msg)
        output?.showCard("Odometer", msg, listOf("hide"))
    }

    // ---- v1.7: music ----

    private fun handleMusicPlay(command: String) {
        val query = MusicControl.queryFrom(command)
        if (query.isBlank()) {
            val msg = "What do you want to hear? Say it like: play some country."
            output?.speak(msg)
            output?.showCard("Music", msg, listOf("hide"))
            return
        }
        output?.showCard("Music", "Asking ${query} to play…", listOf("hide"))
        MusicControl.play(context, query) { outcome ->
            mainHandler.post {
                val text = MusicControl.text(outcome)
                output?.speak(text)
                output?.showCard("Music", text, listOf("hide"))
            }
        }
    }

    private fun handleMusicSuggest() {
        val (query, line) = MusicControl.suggest()
        musicSuggestQuery = query
        output?.showCard("Music", line, listOf("hide"))
        output?.speakAndListen(line)
    }

    private fun handleMusicSuggestAnswer(command: String, lower: String): Boolean {
        val query = musicSuggestQuery ?: return false
        return when {
            Conversation.isYes(lower) -> {
                musicSuggestQuery = null
                handleMusicPlay("play $query")
                true
            }
            Conversation.isNo(lower) -> {
                musicSuggestQuery = null
                output?.speak("No problem — say \"play something\" whenever you want music.")
                true
            }
            else -> {
                musicSuggestQuery = null
                false // Not an answer to the suggestion: run it as a normal command.
            }
        }
    }

    // ---- v1.7: conversation / entertainment (Groq chat path) ----

    private fun startChat(command: String) {
        val key = Prefs.getApiKey(context)
        if (key.isBlank()) {
            val msg = "No Groq key set yet. Open Settings in the RoadHouse Overlay Groq app and paste your free key from console.groq.com."
            output?.speak(msg)
            output?.showCard("Groq key needed", msg, listOf("hide"))
            return
        }
        chatting = true
        chatHistory.clear()
        handleChat(command)
    }

    private fun handleChat(command: String) {
        val key = Prefs.getApiKey(context)
        if (key.isBlank()) {
            chatting = false
            val msg = "No Groq key set yet. Open Settings in the RoadHouse Overlay Groq app and paste your free key from console.groq.com."
            output?.speak(msg)
            output?.showCard("Groq key needed", msg, listOf("hide"))
            return
        }
        val messages = (chatHistory + ("user" to command)).takeLast(6)
        output?.showCard("RoadHouse", "Thinking…", listOf("hide"))
        GroqClient.askChat(key, Conversation.CHAT_SYSTEM_PROMPT, messages, object : GroqClient.Callback {
            override fun onResult(text: String) {
                chatHistory.add("user" to command)
                chatHistory.add("assistant" to text)
                while (chatHistory.size > 6) chatHistory.removeAt(0)
                output?.speak(text)
                output?.showCard("RoadHouse", text, listOf("hide"))
            }

            override fun onError(message: String) {
                output?.speak(message)
                output?.showCard("Groq error", message, listOf("hide"))
            }
        })
    }

    /** One-shot joke / trivia / story via the same Groq chat path (no chat mode entered). */
    private fun handleEntertainmentGroq(lower: String) {
        val key = Prefs.getApiKey(context)
        if (key.isBlank()) {
            val msg = "No Groq key set yet. Open Settings in the RoadHouse Overlay Groq app and paste your free key from console.groq.com."
            output?.speak(msg)
            output?.showCard("Groq key needed", msg, listOf("hide"))
            return
        }
        val prompt = when {
            isJoke(lower) -> "Tell me a short, clean joke a delivery driver would laugh at. One joke only."
            isTrivia(lower) -> "Tell me one short, surprising trivia fact. One fact only."
            else -> "Tell me a very short story, under 100 words, with a warm ending."
        }
        output?.showCard("RoadHouse", "Thinking…", listOf("hide"))
        GroqClient.askChat(key, Conversation.CHAT_SYSTEM_PROMPT, listOf("user" to prompt), object : GroqClient.Callback {
            override fun onResult(text: String) {
                output?.speak(text)
                output?.showCard("RoadHouse", text, listOf("hide"))
            }

            override fun onError(message: String) {
                output?.speak(message)
                output?.showCard("Groq error", message, listOf("hide"))
            }
        })
    }

    private fun askEntertainmentPick() {
        entertainmentPickArmed = true
        val question = "Joke, story, trivia, or music?"
        output?.showCard("Company?", question, listOf("hide"))
        output?.speakAndListen(question)
    }

    private fun handleEntertainmentPickAnswer(command: String, lower: String): Boolean {
        return when {
            isJoke(lower) || isTrivia(lower) || isStory(lower) -> {
                entertainmentPickArmed = false
                handleEntertainmentGroq(lower)
                true
            }
            lower.contains("music") || lower.contains("song") || lower.contains("play something") -> {
                entertainmentPickArmed = false
                val (query, _) = MusicControl.suggest(deadStretch = true)
                handleMusicPlay("play $query")
                true
            }
            Conversation.isYes(lower) -> {
                entertainmentPickArmed = false
                askEntertainmentPick()
                true
            }
            Conversation.isNo(lower) || lower.contains("cancel") || lower.contains("never mind") -> {
                entertainmentPickArmed = false
                output?.speak("No problem.")
                true
            }
            else -> {
                entertainmentPickArmed = false
                false // Not a pick: run it as a normal command.
            }
        }
    }

    private fun handleEntertainmentChoice(command: String, lower: String) {
        when {
            isJoke(lower) || isTrivia(lower) || isStory(lower) -> handleEntertainmentGroq(lower)
            lower.contains("music") || lower.contains("song") || lower.contains("play something") -> {
                val (query, _) = MusicControl.suggest(deadStretch = true)
                handleMusicPlay("play $query")
            }
            else -> askEntertainmentPick()
        }
    }

    // ---- v1.7: voice journal ----

    private fun startJournal() {
        journalArmed = true
        val question = "What's the note for your journal?"
        output?.showCard("Journal", question, listOf("hide"))
        output?.speakAndListen(question)
    }

    private fun handleJournalAnswer(command: String, lower: String) {
        journalArmed = false
        if (lower.contains("cancel") || lower.contains("never mind") || lower.contains("forget it")) {
            output?.speak("Journal note cancelled.")
            return
        }
        val ok = VoiceJournal.add(context, command)
        val msg = if (ok) "Saved to your journal." else "I couldn't save that note. Try again."
        output?.speak(msg)
        output?.showCard("Journal", msg, listOf("hide"))
    }

    private fun handleJournalRead() {
        val text = VoiceJournal.readBackText(context)
        output?.speak(text)
        output?.showCard("Journal", text, listOf("hide"))
    }

    // ---- v1.7: camera ----

    private fun handleCameraStart() {
        if (CameraRecorder.isRecording) {
            val msg = "Already recording."
            output?.speak(msg)
            output?.showCard("Camera", msg, listOf("hide"))
            return
        }
        val hasPermission = try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        if (!hasPermission) {
            val msg = "Camera permission isn't on yet — I'll open the camera so you can allow it, then say \"record\" again."
            output?.speak(msg)
            output?.showCard("Camera", msg, listOf("hide"))
            CameraRecorder.openForPermission(context)
            return
        }
        CameraRecorder.openVideo(context)
        val msg = "Recording started — rear camera, saving to Movies. Say \"stop recording\" or tap the red Stop button to stop."
        output?.speak(msg)
        output?.showCard("Camera", msg, listOf("hide"))
    }

    private fun handleCameraStop() {
        if (CameraRecorder.requestStop()) {
            val msg = "Stopping the recording — saving the clip."
            output?.speak(msg)
            output?.showCard("Camera", msg, listOf("hide"))
        } else {
            val msg = "Nothing is recording right now."
            output?.speak(msg)
            output?.showCard("Camera", msg, listOf("hide"))
        }
    }

    private fun handleCameraPhoto() {
        if (CameraRecorder.isRecording) {
            val msg = "You're recording right now — stop the recording first, then take a photo."
            output?.speak(msg)
            output?.showCard("Camera", msg, listOf("hide"))
            return
        }
        val hasPermission = try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        if (!hasPermission) {
            val msg = "Camera permission isn't on yet — I'll open the camera so you can allow it, then say \"take a photo\" again."
            output?.speak(msg)
            output?.showCard("Camera", msg, listOf("hide"))
            CameraRecorder.openForPermission(context)
            return
        }
        CameraRecorder.openPhoto(context)
        val msg = "Taking a photo — saving to your photos."
        output?.speak(msg)
        output?.showCard("Camera", msg, listOf("hide"))
    }

    // ---- voice reminders (on-device alarms, no screen read) ----

    private fun handleReminderSet(command: String) {
        val parsed = Reminders.parse(command)
        if (parsed == null) {
            val msg = "Say it like: remind me in 30 minutes to call dispatch, or remind me at 5pm to check the fuel."
            output?.speak(msg)
            output?.showCard("Reminder", msg, listOf("hide"))
            return
        }
        val reminder = Reminders.add(context, parsed.triggerAtMs, parsed.text)
        val msg = if (reminder != null) {
            "Got it — I'll remind you to ${parsed.text} ${Reminders.whenWords(parsed.triggerAtMs)}."
        } else {
            "I couldn't set that reminder. Try again."
        }
        output?.speak(msg)
        output?.showCard("Reminder", msg, listOf("hide"))
    }

    private fun handleReminderList() {
        val pending = Reminders.pending(context)
        val msg = if (pending.isEmpty()) {
            "No reminders waiting."
        } else {
            val lines = pending.mapIndexed { i, r ->
                "${i + 1}. ${r.text} — ${Reminders.whenWords(r.triggerAtMs)}"
            }.joinToString("\n")
            "Reminders waiting:\n$lines"
        }
        output?.speak(msg)
        output?.showCard("Reminders", msg, listOf("hide"))
    }

    // ---- offer review (guided voice conversation, no screen read) ----

    private fun startReview() {
        val unresolved = SeenOffers.unresolved(context)
        if (unresolved.isEmpty()) {
            val msg = "Nothing to review — every seen offer is resolved."
            output?.speak(msg)
            output?.showCard("Review offers", msg, listOf("hide"))
            return
        }
        // Ambiguous (identical) offers first — those need his call most.
        val sorted = unresolved.sortedWith(
            compareByDescending<SeenOffer> { it.needsReview }.thenBy { it.createdAtMs }
        )
        review = ReviewFlow(items = sorted, index = 0, took = 0, skipped = 0)
        askReviewItem()
    }

    private fun askReviewItem() {
        val flow = review ?: return
        if (flow.index >= flow.items.size) {
            val msg = "Review done. Marked ${flow.took} took and ${flow.skipped} skipped."
            review = null
            output?.speak(msg)
            output?.showCard("Review offers", msg, listOf("hide"))
            return
        }
        val offer = flow.items[flow.index]
        val platform = Platforms.displayFromTag(offer.platform)
        val payPart = if (offer.pay != null) "$%.2f".format(offer.pay) else "unknown pay"
        val milesPart = if (offer.miles != null) "%.1f miles".format(offer.miles) else "unknown miles"
        val label = offer.storeLabel.ifBlank { platform }
        val flag = if (offer.needsReview) {
            " This one is identical to another offer, so only you can tell them apart."
        } else ""
        val question = "Offer ${flow.index + 1} of ${flow.items.size}: $platform — $label, $payPart for $milesPart, ${offer.date} ${offer.time}.$flag " +
            "Did you take it or skip it? Say took it, skipped it, next, or done."
        output?.showCard(
            "Review offers",
            question,
            listOf("hide")
        )
        output?.speakAndListen(question)
    }

    private fun handleReviewAnswer(command: String, lower: String) {
        val flow = review ?: return
        if (lower.contains("cancel") || lower == "stop" || lower.contains("never mind") ||
            lower.contains("quit") || lower.contains("done") || lower.contains("that's all") ||
            lower.contains("finish")
        ) {
            val msg = "Review stopped. Marked ${flow.took} took and ${flow.skipped} skipped so far."
            review = null
            output?.speak(msg)
            output?.showCard("Review offers", msg, listOf("hide"))
            return
        }
        val offer = flow.items.getOrNull(flow.index) ?: run {
            review = null
            return
        }
        when {
            isTook(lower) -> {
                SeenOffers.markOutcome(context, offer.id, OfferOutcome.TOOK)
                flow.took++
                flow.index++
                askReviewItem()
            }
            isSkipped(lower) -> {
                SeenOffers.markOutcome(context, offer.id, OfferOutcome.SKIPPED)
                flow.skipped++
                flow.index++
                askReviewItem()
            }
            lower.contains("next") || lower.contains("skip") -> {
                flow.index++
                askReviewItem()
            }
            else -> {
                output?.speakAndListen(
                    "Say took it, skipped it, next, or done for this offer."
                )
            }
        }
    }

    // ---- pre-shift check (guided voice conversation, no screen read) ----

    fun startPreShift() {
        preShift = PreShiftFlow(step = 0, vehicleOk = null, fuel = null, apps = emptyList(), retries = 0)
        askPreShiftStep()
    }

    private fun askPreShiftStep() {
        val flow = preShift ?: return
        val question = when (flow.step) {
            0 -> "Pre-shift check, step 1 of 3. Is the vehicle OK? Say yes or no."
            1 -> "Step 2 of 3. What is the fuel level? Say full, half, quarter, or low."
            else -> "Step 3 of 3. Which apps are you running today? Say Spark, DoorDash, Instacart, or all."
        }
        output?.showCard(
            "Pre-shift check",
            question + "\n\nSay \"cancel\" to stop.",
            listOf("hide")
        )
        output?.speakAndListen(question)
    }

    private fun handlePreShiftAnswer(command: String, lower: String) {
        val flow = preShift ?: return
        if (lower.contains("cancel") || lower == "stop" || lower.contains("never mind") ||
            lower.contains("quit") || lower.contains("forget it")
        ) {
            preShift = null
            val msg = "Pre-shift check cancelled."
            output?.speak(msg)
            output?.showCard("Pre-shift check", msg, listOf("hide"))
            return
        }
        when (flow.step) {
            0 -> {
                val yes = lower.contains("yes") || lower.contains("yeah") || lower.contains("yep") ||
                    lower.contains("all good") || lower.contains("good") || lower == "ok" ||
                    lower.contains("okay") || lower.contains("fine")
                val no = lower.contains("no") || lower.contains("nope") || lower.contains("not ok") ||
                    lower.contains("needs work") || lower.contains("broken")
                if (yes && !no) {
                    flow.vehicleOk = true
                    flow.step = 1
                    flow.retries = 0
                    askPreShiftStep()
                } else if (no) {
                    flow.vehicleOk = false
                    flow.step = 1
                    flow.retries = 0
                    askPreShiftStep()
                } else if (flow.retries < 1) {
                    flow.retries++
                    output?.speakAndListen("I didn't catch that — is the vehicle OK, yes or no?")
                } else {
                    flow.vehicleOk = null
                    flow.step = 1
                    flow.retries = 0
                    askPreShiftStep()
                }
            }
            1 -> {
                flow.fuel = parseFuel(lower)
                flow.step = 2
                flow.retries = 0
                askPreShiftStep()
            }
            else -> {
                val apps = parseApps(lower)
                if (apps.isEmpty() && flow.retries < 1) {
                    flow.retries++
                    output?.speakAndListen("Which apps — Spark, DoorDash, Instacart, or all?")
                } else {
                    flow.apps = apps
                    finishPreShift(flow)
                }
            }
        }
    }

    private fun parseFuel(lower: String): String {
        return when {
            lower.contains("full") -> "Full"
            lower.contains("three quarter") || lower.contains("3/4") || lower.contains("3 quarters") -> "Three quarters"
            lower.contains("half") || lower.contains("1/2") -> "Half"
            lower.contains("quarter") || lower.contains("1/4") -> "Quarter"
            lower.contains("low") || lower.contains("empty") || lower.contains("almost out") -> "Low"
            else -> lower.trim().take(40).ifBlank { "Not recorded" }
        }
    }

    private fun parseApps(lower: String): List<String> {
        if (lower.contains("all") || lower.contains("everything") || lower.contains("all three")) {
            return listOf("Spark", "DoorDash", "Instacart")
        }
        val apps = mutableListOf<String>()
        if (lower.contains("spark")) apps.add("Spark")
        if (lower.contains("doordash") || lower.contains("dash")) apps.add("DoorDash")
        if (lower.contains("instacart")) apps.add("Instacart")
        if (lower.contains("both") && apps.size < 2) {
            // "Both" on its own: the two he drives most.
            if (!apps.contains("Spark")) apps.add("Spark")
            if (!apps.contains("DoorDash")) apps.add("DoorDash")
        }
        return apps
    }

    private fun finishPreShift(flow: PreShiftFlow) {
        preShift = null
        output?.showCard("Pre-shift check", "Checking the weather…", listOf("hide"))
        Weather.fetch(context) { weather ->
            mainHandler.post {
                val vehiclePart = when (flow.vehicleOk) {
                    true -> "Vehicle OK."
                    false -> "Vehicle NOT OK — check it before you drive."
                    null -> "Vehicle not confirmed."
                }
                val appsPart = if (flow.apps.isEmpty()) "No apps named." else "Apps: ${flow.apps.joinToString(", ")}."
                val weatherPart = if (weather != null) "Weather: $weather." else "Weather unavailable."
                val summary = "$vehiclePart Fuel: ${flow.fuel ?: "Not recorded"}. $appsPart $weatherPart"
                val (date, time) = DeliveryLog.nowParts()
                PreShiftLog.append(
                    context,
                    PreShiftRecord(date, time, flow.vehicleOk, flow.fuel ?: "Not recorded", flow.apps, weather, summary)
                )
                val done = "Pre-shift done. $summary"
                val newShift = maybeShiftStart()
                if (newShift) {
                    // Shift starts here too: one combined utterance
                    // (summary + encouragement + odometer question).
                    val encouragement = if (ShiftState.encouragementDue(context)) {
                        "Good luck out there today — hope the offers are kind. You've got this. "
                    } else ""
                    odometerKind = "start"
                    output?.speakAndListen("$done $encouragement Quick one before you go — what's the odometer reading? Say skip if you'd rather not.")
                    output?.showCard("Pre-shift check", done, listOf("hide"))
                } else {
                    output?.speak(done)
                    output?.showCard("Pre-shift check", done, listOf("hide"))
                }
            }
        }
    }

    // ---- standalone weather (no screen read; current location first) ----

    private fun handleWeather() {
        output?.showCard("Weather", "Checking the weather…", listOf("hide"))
        Weather.fetchCurrent(context) { summary ->
            mainHandler.post {
                if (summary != null) {
                    output?.speak(summary)
                    output?.showCard("Weather", summary, listOf("hide"))
                } else {
                    val msg = "I couldn't get the weather right now. Check your connection and try again."
                    output?.speak(msg)
                    output?.showCard("Weather", msg, listOf("hide"))
                }
            }
        }
    }

    // ---- address notes (explicit only; same recall rules as store notes) ----

    private fun handleAddAddressNote(command: String, screenText: String, platform: String) {
        val address = OfferParser.parseAddress(screenText.replace('\n', ' '))
        if (address.isNullOrBlank()) {
            val msg = "I can't see a delivery address on this screen. Open the order details first, then save the note."
            output?.speak(msg)
            output?.showCard("Address note", msg, listOf("hide"))
            return
        }
        val lower = command.lowercase()
        var text = command
        for (prefix in listOf("remember that", "remember this", "remember:", "remember", "note that", "note this", "note:", "note", "add a note", "save a note")) {
            if (lower.startsWith(prefix)) {
                text = command.substring(prefix.length)
                break
            }
        }
        text = text.trim().trimStart(':', '-', ' ').trim()
        // "… for this address" / "… for this delivery" is the target, not the note.
        text = text.replace(Regex("(?i)\\s+for this (address|delivery)\\s*$"), "").trim()
        if (text.isBlank()) {
            val msg = "What should I remember about $address? Say it after \"remember\"."
            output?.speak(msg)
            output?.showCard("Address note", msg, listOf("hide"))
            return
        }
        val note = AddressNotes.add(context, OfferParser.normalizeKey(address), address, text)
        val msg = if (note != null) "Saved for $address: $text" else "I couldn't save that note. Try again."
        output?.speak(msg)
        output?.showCard("Address note", msg, listOf("hide"))
    }

    private fun handleReadAddressNotes(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val address = OfferParser.parseAddress(screenText.replace('\n', ' '))
        if (address.isNullOrBlank()) {
            val msg = "I can't see a delivery address on this screen. Open the order details first."
            output?.speak(msg)
            output?.showCard("Address notes", msg, listOf("hide"))
            return
        }
        val notes = AddressNotes.notesFor(context, OfferParser.normalizeKey(address))
        val msg = if (notes.isEmpty()) {
            "You haven't asked me to remember anything about $address yet."
        } else {
            val numbered = notes.mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString("\n")
            val countWord = if (notes.size == 1) "thing" else "things"
            "For $address, you asked me to remember ${notes.size} $countWord:\n$numbered"
        }
        output?.speak(msg)
        output?.showCard("Address notes — $address", msg + readFooter(read, platform), listOf("hide"))
    }

    private fun handleForgetAddressNote(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val address = OfferParser.parseAddress(screenText.replace('\n', ' '))
        if (address.isNullOrBlank()) {
            val msg = "I can't tell which address this is. Open the order details first."
            output?.speak(msg)
            output?.showCard("Address notes", msg, listOf("hide"))
            return
        }
        val deleted = AddressNotes.deleteAllFor(context, OfferParser.normalizeKey(address))
        val msg = if (deleted > 0) {
            "Forgot $deleted note${if (deleted == 1) "" else "s"} for $address."
        } else {
            "There are no notes saved for $address."
        }
        output?.speak(msg)
        output?.showCard("Address notes", msg + readFooter(read, platform), listOf("hide"))
    }

    // ---- store notes (need the current store from the screen) ----

    private fun handleAddNote(command: String, screenText: String, platform: String) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't tell which store this is. Open the store or offer screen first, then save the note."
            output?.speak(msg)
            output?.showCard("Store note", msg, listOf("hide"))
            return
        }
        val lower = command.lowercase()
        var text = command
        for (prefix in listOf("remember that", "remember this", "remember:", "remember", "note that", "note this", "note:", "note", "add a note", "save a note")) {
            if (lower.startsWith(prefix)) {
                text = command.substring(prefix.length)
                break
            }
        }
        text = text.trim().trimStart(':', '-', ' ').trim()
        if (text.isBlank()) {
            val msg = "What should I remember about $label? Say it after \"remember\"."
            output?.speak(msg)
            output?.showCard("Store note", msg, listOf("hide"))
            return
        }
        val note = StoreNotes.add(context, key, label, text)
        val msg = if (note != null) "Saved for $label: $text" else "I couldn't save that note. Try again."
        output?.speak(msg)
        output?.showCard("Store note", msg, listOf("hide"))
    }

    private fun handleReadNotes(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't see a store on this screen. Open the store or offer screen first."
            output?.speak(msg)
            output?.showCard("Store notes", msg, listOf("hide"))
            return
        }
        val notes = StoreNotes.notesFor(context, key)
        val msg = if (notes.isEmpty()) {
            "You haven't asked me to remember anything about $label yet."
        } else {
            val numbered = notes.mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString("\n")
            val countWord = if (notes.size == 1) "thing" else "things"
            "For $label, you asked me to remember ${notes.size} $countWord:\n$numbered"
        }
        output?.speak(msg)
        output?.showCard("Store notes — $label", msg + readFooter(read, platform), listOf("hide"))
    }

    private fun handleForgetNote(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't tell which store this is. Open the store or offer screen first."
            output?.speak(msg)
            output?.showCard("Store notes", msg, listOf("hide"))
            return
        }
        val deleted = StoreNotes.deleteAllFor(context, key)
        val msg = if (deleted > 0) {
            "Forgot $deleted note${if (deleted == 1) "" else "s"} for $label."
        } else {
            "There are no notes saved for $label."
        }
        output?.speak(msg)
        output?.showCard("Store notes", msg + readFooter(read, platform), listOf("hide"))
    }

    // ---- v1.7: store likes / avoids (explicit only) ----

    private fun handleStorePrefSet(command: String, screenText: String, platform: String) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't tell which store this is. Open the store or offer screen first, then flag it."
            output?.speak(msg)
            output?.showCard("Store flag", msg, listOf("hide"))
            return
        }
        val lower = command.lowercase()
        val pref = if (lower.contains("avoid") || lower.contains("don't like") || lower.contains("dont like")) {
            StorePrefs.Pref.AVOID
        } else {
            StorePrefs.Pref.LIKE
        }
        val ok = StorePrefs.set(context, key, label, pref)
        val msg = if (ok) {
            if (pref == StorePrefs.Pref.AVOID) "Got it — $label flagged as a store to avoid. I'll warn you when an offer comes from there."
            else "Got it — $label marked as a store you like."
        } else {
            "I couldn't save that. Try again."
        }
        output?.speak(msg)
        output?.showCard("Store flag", msg, listOf("hide"))
    }

    private fun handleStorePrefForget(screenText: String, platform: String) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't tell which store this is. Open the store or offer screen first."
            output?.speak(msg)
            output?.showCard("Store flag", msg, listOf("hide"))
            return
        }
        val cleared = StorePrefs.clear(context, key)
        val msg = if (cleared) "Cleared the flag for $label." else "No flag saved for $label."
        output?.speak(msg)
        output?.showCard("Store flag", msg, listOf("hide"))
    }

    // ---- v1.7: offer vs offer ----

    private fun handleOfferCompare(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val result = OfferCompare.compare(screenText)
        val text = OfferCompare.text(result)
        output?.speak(text)
        output?.showCard("Which is better?", text + readFooter(read, platform), listOf("hide"))
    }

    // ---- v1.7: customer message (draft only — never sends) ----

    private fun handleCustomerMessage(
        command: String,
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val lower = command.lowercase()
        val kind = CustomerMessage.kindFor(lower) ?: return
        val address = OfferParser.parseAddress(screenText.replace('\n', ' '))
        val custom = if (kind == CustomerMessage.Kind.CUSTOM) CustomerMessage.customWords(command) else null
        val draft = CustomerMessage.draft(kind, custom, address)
        val spoken = "Here's a message you can send — I won't send it myself: $draft"
        output?.speak(spoken)
        output?.showCard("Message draft", draft + readFooter(read, platform), listOf("hide"))
    }

    // ---- existing routes (navigate / log / offer / ask) ----

    private fun handleNavigate(command: String, screenText: String) {
        // A spoken address wins ("navigate to 123 Main St"); otherwise the
        // address on screen ("navigate to the pickup").
        val address = OfferParser.parseAddress(command)
            ?: OfferParser.parseAddress(screenText.replace('\n', ' '))
        if (address.isNullOrBlank()) {
            val msg = "I can't find an address on this screen. Open the order details first."
            output?.speak(msg)
            output?.showCard("Navigate", msg, listOf("hide"))
            return
        }
        output?.speak("Navigating to $address.")
        output?.navigateTo(address)
    }

    private fun handleLog(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        maybeShiftStart()
        val result = OfferParser.check(screenText, Prefs.getFloor(context))
        val (date, time) = DeliveryLog.nowParts()
        val entry = DeliveryEntry(
            date, time, Platforms.tag(read.packageName),
            result.pay, result.miles, result.perMile, result.address
        )
        val logged = DeliveryLog.appendIfNew(context, entry)
        if (logged == DeliveryLog.LogResult.LOGGED) {
            SeenOffers.resolveByDelivery(context, entry)
        }
        val nudge = if (logged == DeliveryLog.LogResult.LOGGED) {
            GoalTracker.checkReached(context)?.let { " $it" } ?: ""
        } else ""
        val msg = when (logged) {
            DeliveryLog.LogResult.LOGGED -> {
                val payPart = if (result.pay != null) "$%.2f".format(result.pay) else "unknown pay"
                val milesPart = if (result.miles != null) "%.1f miles".format(result.miles) else "unknown miles"
                "Logged. $platform, $payPart, $milesPart.$nudge"
            }
            DeliveryLog.LogResult.ALREADY_LOGGED ->
                "Already logged — this delivery is in your log."
            DeliveryLog.LogResult.FAILED ->
                "I couldn't save that delivery. Try again."
        }
        output?.speak(msg)
        output?.showCard("Delivery logged", msg + readFooter(read, platform), listOf("hide"))
    }

    private fun handleOfferCheck(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val floor = Prefs.getFloor(context)
        val result = OfferParser.checkBatch(screenText, floor)
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)

        // Look up memory BEFORE recording this check, or it would match itself.
        val match = OfferMemory.findMatch(context, key, result.pay, result.miles)
        val storeOnly = if (match == null) OfferMemory.findStore(context, key) else null
        val memoryLine = OfferMemory.memoryLine(match, storeOnly)

        // Remember this check; the driver fills in took/skipped afterwards.
        // Stored platform is the stable tag; speech uses the display name.
        val tag = Platforms.tag(read.packageName)
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
        OfferMemory.append(context, record)
        SeenOffers.record(context, tag, key, label, result.pay, result.miles, result.perMile, result.address, "check")
        lastChecked = record
        val deadWarning = DeadMiles.warningForOffer(context, result.address, label)
        // If a real route lookup is possible for the dead-mile leg (last
        // dropoff -> this offer's address), hold the vague warning: the
        // async lookup speaks the real distance, and falls back to this
        // warning only if geocoding/routing fails.
        val offerAddress = result.address ?: result.stops.firstOrNull()?.address
        val deadRoutePending = deadWarning != null &&
            DeadMiles.lastDropoffToday(context) != null && !offerAddress.isNullOrBlank()
        val spokenDeadWarning = if (deadRoutePending) null else deadWarning
        val notes = StoreNotes.notesFor(context, key)
        val noteLine = StoreNotes.spokenLine(notes)
        val allAddressNotes = AddressNotes.notes(context)
        val addressNotes = if (result.address.isNullOrBlank()) emptyList() else
            allAddressNotes.filter { it.addressKey == OfferParser.normalizeKey(result.address) }
        val addressNoteLine = AddressNotes.spokenLine(addressNotes)

        val newShift = maybeShiftStart()
        val encouragement = if (newShift && ShiftState.encouragementDue(context)) {
            "Good luck out there today — hope the offers are kind. You've got this. "
        } else ""
        val avoidWarning = StorePrefs.warningFor(context, key, label)
        // Dead-mile entertainment: one offer per empty stretch, never
        // while an offer is on screen — the offer rides in the SAME
        // utterance as the verdict (QUEUE_FLUSH would cut a second
        // speak off), and only plays if he says yes. Skipped when the
        // shift-start odometer prompt is armed this turn, so the next
        // answer is never ambiguous between the two questions.
        val stretchKey = OfferParser.normalizeKey(DeadMiles.lastDropoffToday(context) ?: "")
        val offerEntertainment = deadWarning != null && !newShift &&
            stretchKey.isNotBlank() && stretchKey != entertainmentStretchKey
        if (offerEntertainment) {
            entertainmentPending = true
            entertainmentStretchKey = stretchKey
        }
        val spoken = buildString {
            append(OfferParser.speakBatchText(result, platform))
            if (memoryLine != null) append(" $memoryLine")
            if (noteLine != null) append(" $noteLine")
            if (addressNoteLine != null) append(" $addressNoteLine")
            if (spokenDeadWarning != null) append(" $spokenDeadWarning")
            if (avoidWarning != null) append(" $avoidWarning")
            if (encouragement.isNotBlank()) append(" $encouragement")
            if (offerEntertainment) append(" ${Conversation.entertainmentOfferText()}")
        }.toString()
        if (newShift) {
            // Shift-start odometer prompt: one combined utterance
            // (verdict + question), listening starts when it finishes.
            odometerKind = "start"
            output?.speakAndListen("$spoken Quick one before you go — what's the odometer reading? Say skip if you'd rather not.")
        } else {
            output?.speak(spoken)
        }

        val body = buildString {
            if (result.isBatch) append("Batch: ${result.stops.size} stops\n")
            if (result.pay != null) append("Pay: $%.2f\n".format(result.pay))
            if (result.miles != null) append("Miles: %.1f\n".format(result.miles))
            if (result.perMile != null) append("Per mile: $%.2f\n".format(result.perMile))
            append("Floor: $%.2f / mile\n".format(result.floor))
            if (result.isBatch) {
                result.stops.forEachIndexed { i, stop ->
                    val milesPart = if (stop.miles != null) " — %.1f mi".format(stop.miles) else ""
                    val payPart = if (stop.pay != null) " — $%.2f".format(stop.pay) else ""
                    append("Stop ${i + 1}: ${stop.address}$milesPart$payPart\n")
                    val stopNotes = allAddressNotes.filter {
                        it.addressKey == OfferParser.normalizeKey(stop.address)
                    }
                    stopNotes.forEach { append("  Notes: ${it.text}\n") }
                }
            } else if (result.address != null) {
                append("Address: ${result.address}\n")
            }
            if (memoryLine != null) append("\n$memoryLine\n")
            if (notes.isNotEmpty()) {
                append("\nNotes for this store:\n")
                notes.forEach { append("• ${it.text}\n") }
            }
            if (!result.isBatch && addressNotes.isNotEmpty()) {
                append("\nNotes for this address:\n")
                addressNotes.forEach { append("• ${it.text}\n") }
            }
            if (spokenDeadWarning != null) append("\n$spokenDeadWarning\n")
            if (avoidWarning != null) append("\n$avoidWarning\n")
            append("\n$spoken")
            append(readFooter(read, platform))
        }
        output?.showCard(
            "Offer check — $platform",
            body.trim(),
            listOf("navigate", "log", "took", "skipped", "hide")
        )
        // Real route miles (free OSRM, fail-soft, async — never blocks
        // the verdict above). See maybeRouteCheck.
        maybeRouteCheck(result, deadWarning, offerAddress)
    }

    /**
     * Async real-route enhancements to an offer check. Both lookups run
     * on background threads and report back later; either can fail
     * silently (printed miles stand, nothing is invented).
     * (a) Dead-mile leg: last dropoff -> this offer's address. On success
     * the real unpaid distance is spoken; on failure the plain warning
     * (held back from the first verdict) is spoken instead.
     * (b) Batch route check: real driving distance through the addresses
     * on screen vs the printed miles. Spoken only when it differs
     * meaningfully (more than 2 miles AND more than 20%).
     */
    private fun maybeRouteCheck(
        result: BatchResult,
        deadWarning: String?,
        offerAddress: String?
    ) {
        if (deadWarning != null && !offerAddress.isNullOrBlank()) {
            val lastDropoff = DeadMiles.lastDropoffToday(context)
            if (lastDropoff != null) {
                RouteMiles.routeMilesBetween(lastDropoff, offerAddress) { miles ->
                    mainHandler.post {
                        if (miles != null) {
                            val msg = "Dead miles: the unpaid drive from your last dropoff to this pickup is about %.1f miles.".format(
                                Locale.US, miles
                            )
                            output?.speak(msg)
                            output?.showCard("Dead miles", msg, listOf("hide"))
                        } else {
                            output?.speak(deadWarning)
                            output?.showCard("Dead miles", deadWarning, listOf("hide"))
                        }
                    }
                }
            }
        }
        if (result.isBatch && result.stops.size >= 2 && result.miles != null) {
            val addresses = result.stops.map { it.address }
            RouteMiles.routeMilesForStops(addresses) { routeMiles ->
                mainHandler.post {
                    if (routeMiles == null) return@post // Silent fallback.
                    val printed = result.miles!!
                    val diff = kotlin.math.abs(routeMiles - printed)
                    if (diff <= 2.0 || diff <= printed * 0.20) return@post // Close enough.
                    val perMilePart = if (result.pay != null && routeMiles > 0) {
                        " Real $/mile is about $%.2f.".format(Locale.US, result.pay / routeMiles)
                    } else ""
                    val msg = "Route check: driving between the addresses on this screen is about %.1f miles — the screen printed %.1f miles.$perMilePart".format(
                        Locale.US, routeMiles, printed
                    )
                    output?.speak(msg)
                    output?.showCard("Route check", msg, listOf("hide"))
                }
            }
        }
    }

    private fun handleAsk(
        question: String,
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val key = Prefs.getApiKey(context)
        if (key.isBlank()) {
            val msg = "No Groq key set yet. Open Settings in the RoadHouse Overlay Groq app and paste your free key from console.groq.com."
            output?.speak(msg)
            output?.showCard("Groq key needed", msg, listOf("hide"))
            return
        }
        output?.showCard("Thinking…", "Asking Groq about this screen…", listOf("hide"))
        GroqClient.ask(key, question, screenText, object : GroqClient.Callback {
            override fun onResult(text: String) {
                output?.speak(text)
                output?.showCard("RoadHouse", text + readFooter(read, platform), listOf("hide"))
            }

            override fun onError(message: String) {
                output?.speak(message)
                output?.showCard("Groq error", message, listOf("hide"))
            }
        }, appName = platform)
    }

    /** Opens Google Maps navigation; falls back to the Maps website. */
    fun openMaps(address: String) {
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(address))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.maps")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(address))
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(web)
        }
    }
}

package com.atothei.roadhouse.overlay

import java.util.Locale

/** Verdict from the in-code $/mile math. The model never decides this. */
enum class Verdict { TAKE, SKIP, UNKNOWN }

data class OfferResult(
    val pay: Double?,
    val miles: Double?,
    val perMile: Double?,
    val address: String?,
    val verdict: Verdict,
    val floor: Double,
    val note: String
)

/** One stop of a batch: address plus whatever the screen gives for it. */
data class BatchStop(
    val address: String,
    val miles: Double?,
    val pay: Double?
)

/** Batch-aware result. A single-stop offer is a batch of one stop. */
data class BatchResult(
    val pay: Double?,
    val miles: Double?,
    val perMile: Double?,
    val address: String?,
    val verdict: Verdict,
    val floor: Double,
    val note: String,
    val isBatch: Boolean,
    val stops: List<BatchStop>
)

/**
 * Parses pay, miles and a delivery address out of raw screen text.
 * All math happens here, in code — never by the model — so the verdict is
 * exact and auditable. If the screen is ambiguous, the result says so
 * instead of guessing.
 */
object OfferParser {

    private val moneyRegex =
        Regex("""\$(\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""")
    private val milesRegex =
        Regex("""(\d+(?:\.\d+)?)\s*(?:mi|miles)\b""", RegexOption.IGNORE_CASE)
    private val payLabelRegex =
        Regex("""(total|pay|earn|payout|guaranteed|estimated)""", RegexOption.IGNORE_CASE)
    private val milesLabelRegex =
        Regex("""(total|distance|trip|drive)""", RegexOption.IGNORE_CASE)

    private val streetSuffix =
        """(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct|Way|Place|Pl|Highway|Hwy|Circle|Cir|Terrace|Ter|Parkway|Pkwy|Trail|Trl|Loop|Square|Sq)"""
    // House number must be followed by a capitalised word (street names are
    // capitalised; "32 min" or "5 miles" are not addresses). City is capped
    // at 3 words so the match cannot swallow the rest of the screen.
    private val addressRegex = Regex(
        """\b\d{1,6}\s+(?=[A-Z0-9])(?:[A-Za-z0-9.'-]+\s+){0,4}?$streetSuffix\b(?:\s*,?\s*[A-Za-z][A-Za-z.'-]*(?:\s+[A-Za-z][A-Za-z.'-]*){0,2})?(?:\s*,?\s*[A-Z]{2}\s*\d{5}(?:-\d{4})?)?"""
    )

    fun check(screenText: String, floor: Double): OfferResult {
        val pay = parsePay(screenText)
        val miles = parseMiles(screenText)
        val perMile = if (pay != null && miles != null && miles > 0) pay / miles else null
        val address = parseAddress(screenText.replace('\n', ' '))
        val verdict = when {
            perMile == null -> Verdict.UNKNOWN
            perMile >= floor -> Verdict.TAKE
            else -> Verdict.SKIP
        }
        val note = when {
            pay == null && miles == null ->
                "I can't see a pay amount or miles on this screen. Open the offer first, then check again."
            pay == null -> "I can see the miles but not the pay on this screen."
            miles == null -> "I can see the pay but not the miles on this screen."
            else -> ""
        }
        return OfferResult(pay, miles, perMile, address, verdict, floor, note)
    }

    /** Spoken verdict — one short sentence, safe to read aloud while driving. */
    fun speakText(result: OfferResult, platform: String): String {
        if (result.perMile == null) return result.note
        val payStr = "$%.2f".format(Locale.US, result.pay)
        val milesStr = "%.1f".format(Locale.US, result.miles)
        val perMileStr = "$%.2f".format(Locale.US, result.perMile)
        val floorStr = "$%.2f".format(Locale.US, result.floor)
        return when (result.verdict) {
            Verdict.TAKE ->
                "$platform offer: $payStr for $milesStr miles, $perMileStr a mile. Above your $floorStr floor. Take it."
            Verdict.SKIP ->
                "$platform offer: $payStr for $milesStr miles, $perMileStr a mile. Below your $floorStr floor. Skip it."
            Verdict.UNKNOWN -> result.note
        }
    }

    /** Spoken verdict for a batch check. Single-stop reads exactly like speakText. */
    fun speakBatchText(result: BatchResult, platform: String): String {
        if (result.perMile == null) return result.note
        if (!result.isBatch) {
            return speakText(
                OfferResult(result.pay, result.miles, result.perMile, result.address, result.verdict, result.floor, result.note),
                platform
            )
        }
        val payStr = "$%.2f".format(Locale.US, result.pay)
        val milesStr = "%.1f".format(Locale.US, result.miles)
        val perMileStr = "$%.2f".format(Locale.US, result.perMile)
        val floorStr = "$%.2f".format(Locale.US, result.floor)
        val stopsPart = "${result.stops.size} stops"
        val verdictPart = when (result.verdict) {
            Verdict.TAKE -> "Above your $floorStr floor. Take it."
            Verdict.SKIP -> "Below your $floorStr floor. Skip it."
            Verdict.UNKNOWN -> result.note
        }
        val milesDetail = result.stops.mapNotNull { it.miles }
        val detail = if (milesDetail.size == result.stops.size && milesDetail.size > 1) {
            val joined = milesDetail.dropLast(1).joinToString(", ") { "%.1f".format(Locale.US, it) } +
                " and " + "%.1f".format(Locale.US, milesDetail.last())
            " Stops run $joined miles."
        } else {
            ""
        }
        return "$platform batch: $stopsPart, $payStr for $milesStr miles, $perMileStr a mile. $verdictPart$detail"
    }

    // ---- store identity (offer memory + store notes share this key) ----

    private val knownStores = listOf(
        "Sam's Club", "Walmart", "Target", "Kroger", "Meijer", "Aldi",
        "Dollar General", "Dollar Tree", "CVS", "Walgreens", "Best Buy",
        "Lowe's", "Home Depot", "Menards", "Hy-Vee", "Casey's", "Schnucks",
        "County Market", "Jewel-Osco", "Mariano's", "Whole Foods",
        "Amazon Fresh", "Family Dollar", "Rural King", "Tractor Supply"
    )

    /** First known store chain named on the screen, or null. */
    fun parseStoreName(text: String): String? {
        for (line in text.lines()) {
            for (store in knownStores) {
                if (line.contains(store, ignoreCase = true)) return store
            }
        }
        return null
    }

    /** Lowercase, punctuation-free key for matching stores across checks. */
    fun normalizeKey(s: String): String =
        s.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Store key for memory + notes: "store name | street address",
     * whichever parts the screen gives. Empty when the screen names
     * neither a store nor an address — then nothing can match reliably.
     */
    fun storeKey(text: String, platform: String): String {
        val flat = text.replace('\n', ' ')
        val name = parseStoreName(text)
        val address = parseAddress(flat)
        val parts = listOfNotNull(name, address)
        if (parts.isEmpty()) return ""
        return normalizeKey(parts.joinToString(" | "))
    }

    /** Display label for a store: "Walmart — 1234 S 5th St, Springfield, IL" or what exists. */
    fun storeLabel(text: String, platform: String): String {
        val name = parseStoreName(text)
        val address = parseAddress(text.replace('\n', ' '))
        return when {
            name != null && address != null -> "$name — $address"
            name != null -> name
            address != null -> address
            else -> platform
        }
    }

    // ---- batch parsing ----

    private val stopMarkerRegex =
        Regex("""\b(stop|drop-?off|delivery)\s*#?\s*\d+\b""", RegexOption.IGNORE_CASE)

    /**
     * Every distinct street address on the screen, in order, at most 6,
     * each with the line it was found on. Matched PER LINE, not on the
     * flattened text: flattening glues "Stop 1" to the next line's address
     * and the regex then reads the stop number as the house number
     * ("1 1234 S 5th St"). One address per line is how the delivery apps
     * lay the stops out.
     */
    fun parseAddressesWithLines(text: String): List<Pair<Int, String>> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<Pair<Int, String>>()
        text.lines().forEachIndexed { idx, line ->
            val m = addressRegex.find(line) ?: return@forEachIndexed
            val a = m.value.trim().trimEnd(',', ' ').replace(Regex("\\s+"), " ")
            if (a.length >= 8 && seen.add(a)) out.add(idx to a)
            if (out.size >= 6) return out
        }
        return out
    }

    /** Every distinct street address on the screen, in order, at most 6. */
    fun parseAddresses(text: String): List<String> =
        parseAddressesWithLines(text).map { it.second }

    /** (line index, miles) for every miles figure on the screen. */
    fun parseMilesList(text: String): List<Pair<Int, Double>> {
        val out = mutableListOf<Pair<Int, Double>>()
        text.lines().forEachIndexed { idx, line ->
            val m = milesRegex.find(line) ?: return@forEachIndexed
            val v = m.groupValues[1].toDoubleOrNull() ?: return@forEachIndexed
            if (v > 0.0 && v <= 500.0) out.add(idx to v)
        }
        return out
    }

    /**
     * Batch-aware check. A batch is 2+ addresses, or 2+ numbered stops.
     * Totals come from the same labelled parsing as check() — the verdict
     * is always on the whole batch (total pay vs total miles vs floor).
     * Per-stop miles come from the miles figure nearest each address;
     * per-stop pay only when the screen prints a separate amount per stop.
     * Anything the screen does not show stays null — never invented.
     */
    fun checkBatch(screenText: String, floor: Double): BatchResult {
        val base = check(screenText, floor)
        val lines = screenText.lines()
        val addressLines = parseAddressesWithLines(screenText)
        val addresses = addressLines.map { it.second }
        val stopMarkers = lines.count { stopMarkerRegex.containsMatchIn(it) }
        val isBatch = addresses.size >= 2 || stopMarkers >= 2
        if (!isBatch) {
            val single = if (addresses.isNotEmpty()) {
                listOf(BatchStop(addresses[0], base.miles, base.pay))
            } else {
                emptyList()
            }
            return BatchResult(base.pay, base.miles, base.perMile, base.address, base.verdict, base.floor, base.note, false, single)
        }
        val milesList = parseMilesList(screenText)
        val usedMiles = mutableSetOf<Int>()
        val stops = addressLines.map { (lineIdx, address) ->
            var miles: Double? = null
            // Nearest unused miles figure within 2 lines of the address.
            val nearby = milesList
                .filter { (idx, _) -> idx in (lineIdx - 2)..(lineIdx + 2) && idx !in usedMiles }
                .minByOrNull { (idx, _) -> kotlin.math.abs(idx - lineIdx) }
            if (nearby != null) {
                miles = nearby.second
                usedMiles.add(nearby.first)
            }
            // Per-stop pay only if the same line prints its own amount.
            var pay: Double? = null
            val amounts = moneyRegex.findAll(lines[lineIdx])
                .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
                .filter { it > 0.0 && it <= 500.0 }
                .toList()
            if (amounts.size == 1) pay = amounts[0]
            BatchStop(address, miles, pay)
        }
        val totalMiles = base.miles
            ?: stops.mapNotNull { it.miles }.takeIf { it.isNotEmpty() }?.sum()
        val perMile = if (base.pay != null && totalMiles != null && totalMiles > 0) {
            base.pay / totalMiles
        } else {
            null
        }
        val verdict = when {
            perMile == null -> Verdict.UNKNOWN
            perMile >= floor -> Verdict.TAKE
            else -> Verdict.SKIP
        }
        val note = when {
            base.pay == null && totalMiles == null ->
                "I can't see a pay amount or miles on this screen. Open the offer first, then check again."
            base.pay == null -> "I can see the miles but not the pay on this screen."
            totalMiles == null -> "I can see the pay but not the miles on this screen."
            stops.any { it.miles == null } -> "Per-stop miles aren't all shown separately, so the verdict is on the batch total."
            else -> ""
        }
        return BatchResult(base.pay, totalMiles, perMile, base.address, verdict, floor, note, true, stops)
    }

    /**
     * Pay = the largest labelled amount on a line mentioning total/pay/earn/
     * payout/guaranteed/estimated, else the largest dollar amount on screen.
     * Lines like "Earn up to $100" are promos, not the offer — skipped.
     */
    fun parsePay(text: String): Double? {
        val labeled = mutableListOf<Double>()
        val all = mutableListOf<Double>()
        for (line in text.lines()) {
            if (line.contains("up to", ignoreCase = true)) continue
            if (line.contains("was", ignoreCase = true)) continue
            for (m in moneyRegex.findAll(line)) {
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
                if (v <= 0.0 || v > 500.0) continue
                all.add(v)
                if (payLabelRegex.containsMatchIn(line)) labeled.add(v)
            }
        }
        return labeled.maxOrNull() ?: all.maxOrNull()
    }

    /** Miles = the amount on a line mentioning total/distance/trip/drive, else the first miles figure. */
    fun parseMiles(text: String): Double? {
        var first: Double? = null
        for (line in text.lines()) {
            val m = milesRegex.find(line) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v <= 0.0 || v > 500.0) continue
            if (first == null) first = v
            if (milesLabelRegex.containsMatchIn(line)) return v
        }
        return first
    }

    /** First US street address found in the text, or null. */
    fun parseAddress(text: String): String? {
        val m = addressRegex.find(text) ?: return null
        return m.value.trim().trimEnd(',', ' ').replace(Regex("\\s+"), " ")
    }
}

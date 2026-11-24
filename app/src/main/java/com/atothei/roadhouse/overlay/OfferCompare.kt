package com.atothei.roadhouse.overlay

import java.util.Locale

data class OfferCandidate(
    val pay: Double,
    val miles: Double,
    val perMile: Double,
    val label: String
)

data class OfferCompareResult(
    val candidates: List<OfferCandidate>,
    val winner: OfferCandidate?,
    val runnerUp: OfferCandidate?
)

/**
 * Offer vs offer — when two offers are on screen, "which is better?"
 * compares them on $/mile (ties break on total pay) in code and names
 * the winner. It advises; the driver decides. Fail-soft: if fewer
 * than two offers with pay AND miles are visible, the text says so
 * plainly instead of guessing.
 *
 * Candidates are found line by line: a line carrying both a dollar
 * amount and a miles figure is one offer; a dollar-only line pairs
 * with a miles figure on the next line. Identical (pay, miles) pairs
 * are deduped so one offer printed twice does not count as two.
 */
object OfferCompare {

    private val moneyRegex =
        Regex("""\$(\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""")
    private val milesRegex =
        Regex("""(\d+(?:\.\d+)?)\s*(?:mi|miles)\b""", RegexOption.IGNORE_CASE)

    fun parseOffers(screenText: String): List<OfferCandidate> {
        val lines = screenText.lines()
        val found = mutableListOf<OfferCandidate>()
        val seen = mutableSetOf<Pair<Double, Double>>()
        var pendingPay: Double? = null
        var pendingLabel = ""
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                pendingPay = null
                pendingLabel = ""
                continue
            }
            if (line.contains("up to", ignoreCase = true)) continue
            val money = moneyRegex.find(line)?.groupValues?.getOrNull(1)
                ?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 500.0 }
            val miles = milesRegex.find(line)?.groupValues?.getOrNull(1)
                ?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 500.0 }
            if (money != null && miles != null) {
                val key = money to miles
                if (seen.add(key)) {
                    found.add(OfferCandidate(money, miles, money / miles, labelFor(line)))
                }
                pendingPay = null
                pendingLabel = ""
            } else if (money != null) {
                pendingPay = money
                pendingLabel = labelFor(line)
            } else if (miles != null && pendingPay != null) {
                val key = pendingPay!! to miles
                if (seen.add(key)) {
                    found.add(OfferCandidate(pendingPay!!, miles, pendingPay!! / miles, pendingLabel))
                }
                pendingPay = null
                pendingLabel = ""
            }
        }
        return found
    }

    fun compare(screenText: String): OfferCompareResult {
        val candidates = parseOffers(screenText)
            .sortedByDescending { it.perMile }
        if (candidates.size < 2) {
            return OfferCompareResult(candidates, null, null)
        }
        val winner = candidates[0]
        val runnerUp = candidates[1]
        // Exact $/mile tie breaks on total pay.
        val tied = candidates.filter { it.perMile == winner.perMile }
        val actualWinner = if (tied.size > 1) tied.maxByOrNull { it.pay }!! else winner
        val actualRunnerUp = candidates.firstOrNull { it != actualWinner }
        return OfferCompareResult(candidates, actualWinner, actualRunnerUp)
    }

    fun text(r: OfferCompareResult): String {
        if (r.candidates.size < 2 || r.winner == null || r.runnerUp == null) {
            val n = r.candidates.size
            return if (n == 0) {
                "I can't see two offers with pay and miles on this screen, so I can't compare them. Open the screen with both offers first."
            } else {
                "I can only see one offer with pay and miles on this screen — I need two to compare. Open the screen with both offers first."
            }
        }
        val w = r.winner
        val u = r.runnerUp
        return "Of the two, ${w.label} is better on dollars per mile: $%.2f for %.1f miles, $%.2f a mile, " +
            "against $%.2f for %.1f miles, $%.2f a mile for ${u.label}. " +
            "That's the math — you decide which one to take.".format(
                Locale.US, w.pay, w.miles, w.perMile, u.pay, u.miles, u.perMile
            )
    }

    private fun labelFor(line: String): String {
        val clean = line.replace(Regex("\\s+"), " ").trim().take(48)
        return if (clean.isBlank()) "the offer" else "the $clean offer"
    }
}

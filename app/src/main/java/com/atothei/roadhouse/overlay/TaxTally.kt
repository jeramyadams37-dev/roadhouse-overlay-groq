package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * Running tax tally, computed IN CODE from the local delivery log —
 * never by the model. Miles x the IRS 2026 standard mileage split rates
 * (Notice 2026-10 / Announcement 2026-11): 72.5 cents a mile Jan 1 –
 * Jun 30, 2026, and 76 cents a mile Jul 1 – Dec 31, 2026. Miles from
 * 2025 and earlier use 70 cents (the rate in force then). Miles after
 * 2026 use the latest known rate (76 cents) and the text says to confirm
 * the current IRS rate — a future year's rate is never presented as
 * settled fact. This is an estimate for his own records, not tax advice.
 */
object TaxTally {

    data class Tally(
        val miles: Double,
        val deduction: Double,
        val milesH1: Double,
        val deductionH1: Double,
        val milesH2: Double,
        val deductionH2: Double,
        val milesOtherYears: Double,
        val deductionOtherYears: Double,
        val hasPost2026Miles: Boolean,
        val perPlatform: List<PlatformSlice>
    )

    /** IRS standard mileage rate for one log date (yyyy-MM-dd). */
    fun rateForDate(date: String): Double {
        return when {
            date < "2026-01-01" -> 0.70
            date <= "2026-06-30" -> 0.725
            date <= "2026-12-31" -> 0.76
            else -> 0.76 // Latest known rate; caller flags it as unconfirmed.
        }
    }

    /** Year-to-date tally for the given year (default: current year). */
    fun tallyYear(context: Context, year: Int): Tally {
        val prefix = "$year-"
        var miles = 0.0
        var deduction = 0.0
        var milesH1 = 0.0
        var deductionH1 = 0.0
        var milesH2 = 0.0
        var deductionH2 = 0.0
        var milesOther = 0.0
        var deductionOther = 0.0
        var hasPost2026 = false
        val byPlatform = linkedMapOf<String, MutablePlatformAcc>()
        val arr: JSONArray = DeliveryLog.readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val date = obj.optString("date")
            if (!date.startsWith(prefix)) continue
            val entryMiles = if (obj.isNull("miles")) null else obj.optDouble("miles")
            if (entryMiles == null) continue
            val rate = rateForDate(date)
            val amount = entryMiles * rate
            miles += entryMiles
            deduction += amount
            when {
                date < "2026-01-01" || date > "2026-12-31" -> {
                    milesOther += entryMiles
                    deductionOther += amount
                    if (date > "2026-12-31") hasPost2026 = true
                }
                date <= "2026-06-30" -> {
                    milesH1 += entryMiles
                    deductionH1 += amount
                }
                else -> {
                    milesH2 += entryMiles
                    deductionH2 += amount
                }
            }
            val tag = Platforms.tagFromDisplay(obj.optString("platform"))
            val acc = byPlatform.getOrPut(tag) { MutablePlatformAcc() }
            acc.count++
            acc.miles += entryMiles
            acc.pay += amount // Reused as the deduction slice for this platform.
        }
        val slices = byPlatform.entries.map {
            PlatformSlice(it.key, it.value.count, it.value.pay, it.value.miles)
        }
        return Tally(
            miles = miles,
            deduction = deduction,
            milesH1 = milesH1,
            deductionH1 = deductionH1,
            milesH2 = milesH2,
            deductionH2 = deductionH2,
            milesOtherYears = milesOther,
            deductionOtherYears = deductionOther,
            hasPost2026Miles = hasPost2026,
            perPlatform = slices
        )
    }

    fun tallyCurrentYear(context: Context): Tally {
        val year = java.text.SimpleDateFormat("yyyy", Locale.US).format(java.util.Date()).toInt()
        return tallyYear(context, year)
    }

    fun text(year: Int, t: Tally): String {
        if (t.miles <= 0.0) {
            return "No miles logged in $year yet, so there is nothing to deduct. Log a delivery first."
        }
        val sb = StringBuilder()
        sb.append(
            "Tax tally for $year: %.1f miles, about $%.2f you can deduct at the IRS standard mileage rate.".format(
                Locale.US, t.miles, t.deduction
            )
        )
        if (year == 2026) {
            sb.append(
                " That's %.1f miles at 72.5 cents (Jan–Jun, $%.2f) and %.1f miles at 76 cents (Jul–Dec, $%.2f).".format(
                    Locale.US, t.milesH1, t.deductionH1, t.milesH2, t.deductionH2
                )
            )
        }
        if (t.hasPost2026Miles) {
            sb.append(" Miles after 2026 use the latest known rate, 76 cents — confirm the current IRS rate before you file.")
        }
        if (t.perPlatform.size > 1) {
            val parts = t.perPlatform.map {
                "${Platforms.displayFromTag(it.tag)}: %.1f miles, $%.2f".format(Locale.US, it.miles, it.pay)
            }
            sb.append(" By platform — ${parts.joinToString("; ")}.")
        }
        sb.append(" This is an estimate for your own records, not tax advice.")
        return sb.toString()
    }

    private class MutablePlatformAcc {
        var count = 0
        var miles = 0.0
        var pay = 0.0
    }
}

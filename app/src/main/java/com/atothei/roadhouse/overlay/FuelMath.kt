package com.atothei.roadhouse.overlay

import android.content.Context
import java.util.Locale

/**
 * Fuel math, computed IN CODE from the local delivery log — never by the
 * model. Fuel cost per mile = fuel price / MPG; per delivery = its miles
 * x price / MPG. MPG and fuel price come from Settings (defaults 25 MPG
 * and $3.50/gal — the driver changes them to match his vehicle and the
 * pump). A delivery with unknown miles has unknown fuel cost, and the
 * text says so instead of inventing a number.
 */
object FuelMath {

    data class FuelTally(
        val count: Int,
        val miles: Double,
        val fuelCost: Double,
        val totalPay: Double,
        val netAfterFuel: Double,
        val costPerMile: Double,
        val unknownMilesCount: Int
    )

    fun tallyToday(context: Context): FuelTally {
        val mpg = Prefs.getMpg(context)
        val price = Prefs.getFuelPrice(context)
        val costPerMile = if (mpg > 0) price / mpg else 0.0
        val tally = Earnings.tally(context, EarningsRange.TODAY)
        var unknownMiles = 0
        val arr = DeliveryLog.readAll(context)
        // Count today's deliveries with unknown miles directly from the log.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = fmt.format(java.util.Date())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") != todayStr) continue
            if (obj.isNull("miles")) unknownMiles++
        }
        val fuelCost = tally.totalMiles * costPerMile
        return FuelTally(
            count = tally.count,
            miles = tally.totalMiles,
            fuelCost = fuelCost,
            totalPay = tally.totalPay,
            netAfterFuel = tally.totalPay - fuelCost,
            costPerMile = costPerMile,
            unknownMilesCount = unknownMiles
        )
    }

    /** Fuel cost for one delivery's miles, or null when miles are unknown. */
    fun costForMiles(context: Context, miles: Double?): Double? {
        if (miles == null) return null
        val mpg = Prefs.getMpg(context)
        if (mpg <= 0) return null
        return miles * Prefs.getFuelPrice(context) / mpg
    }

    fun text(context: Context, t: FuelTally): String {
        if (t.count == 0) {
            return "Nothing logged today yet, so there is no fuel cost to work out. Log a delivery first."
        }
        val mpg = Prefs.getMpg(context)
        val price = Prefs.getFuelPrice(context)
        val sb = StringBuilder()
        sb.append(
            "Fuel today: %.1f miles at %.0f miles a gallon and $%.2f a gallon — fuel cost $%.2f, $%.3f a mile.".format(
                Locale.US, t.miles, mpg, price, t.fuelCost, t.costPerMile
            )
        )
        sb.append(
            " Pay today $%.2f, so about $%.2f after fuel.".format(
                Locale.US, t.totalPay, t.netAfterFuel
            )
        )
        if (t.unknownMilesCount > 0) {
            val n = if (t.unknownMilesCount == 1) "delivery" else "deliveries"
            sb.append(" $t.unknownMilesCount $n had no miles logged, so their fuel cost isn't in that total.")
        }
        return sb.toString()
    }
}

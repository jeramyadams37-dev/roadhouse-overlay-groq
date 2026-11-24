package com.atothei.roadhouse.overlay

import android.content.Context
import java.util.Locale

/**
 * Deadhead-home math: the unpaid drive from a dropoff address back to
 * home (the saved city / home base in Prefs — the same home the
 * dead-mile summary uses). Real route miles via the existing OSRM
 * lookup, fuel cost for the return leg via FuelMath. Fail-soft like the
 * rest: if the route lookup fails, the distance is unknown and the
 * text says so — a distance is never invented.
 */
object DeadheadHome {

    /**
     * Async: route miles + return-leg fuel cost for dropoff -> home.
     * Callback gets (miles, fuelCost) or (null, null) on any failure.
     * The callback runs on a background thread.
     */
    fun deadheadHome(
        context: Context,
        dropoffAddress: String,
        callback: (Double?, Double?) -> Unit
    ) {
        val home = Prefs.getWeatherCity(context)
        RouteMiles.routeMilesBetween(dropoffAddress, home) { miles ->
            if (miles == null) {
                callback(null, null)
            } else {
                callback(miles, FuelMath.costForMiles(context, miles))
            }
        }
    }

    fun text(dropoffAddress: String, home: String, miles: Double?, fuelCost: Double?): String {
        if (miles == null) {
            return "I couldn't work out the drive home from $dropoffAddress — the distance is unknown, " +
                "so I won't guess it. Check the map before you head back."
        }
        val fuelPart = if (fuelCost != null) {
            ", that's about $%.2f in gas to get back".format(Locale.US, fuelCost)
        } else {
            ""
        }
        return "This delivery leaves you about %.1f miles from home in $home$ fuelPart.".format(
            Locale.US, miles
        )
    }
}

package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dead-mile warning — the unpaid drive between one dropoff and the next
 * pickup. Computed IN CODE from the log + the offer on screen, on demand
 * or at offer-check time only. No background location polling, ever.
 *
 * HONEST LIMIT, built into the design: the overlay has no GPS trail and
 * no pickup coordinates — the delivery apps show a delivery address, not
 * a pickup address — so a dead-mile DISTANCE in miles cannot be measured
 * and is never invented. What the log does prove is a repositioning hop:
 * the last logged dropoff address is different from the next offer's
 * store/address, so the drive between them is unpaid. That is what is
 * warned about, in words, with both addresses named. The summary counts
 * those hops for today.
 */
object DeadMiles {

    data class HopSummary(
        val hops: Int,
        val deliveries: Int,
        val lastDropoff: String?
    )

    /**
     * Warning for the offer on screen, or null when there is nothing to
     * warn about (no previous dropoff today, no address on the offer, or
     * the offer is at the same address as the last dropoff).
     */
    fun warningForOffer(
        context: Context,
        offerAddress: String?,
        offerLabel: String?
    ): String? {
        if (offerAddress.isNullOrBlank() && offerLabel.isNullOrBlank()) return null
        val last = lastDropoffToday(context) ?: return null
        val lastKey = OfferParser.normalizeKey(last)
        val offerKey = OfferParser.normalizeKey(offerAddress ?: offerLabel ?: "")
        if (lastKey.isBlank() || offerKey.isBlank()) return null
        if (lastKey == offerKey) return null
        // Same street address embedded in a longer label still counts as same place.
        if (offerKey.contains(lastKey) || lastKey.contains(offerKey)) return null
        val where = offerLabel?.takeIf { it.isNotBlank() } ?: offerAddress ?: ""
        return "Heads up on dead miles: your last dropoff was at $last, and this offer is at $where. " +
            "The drive between them is unpaid — I can't measure how far that is from the log alone, " +
            "so check the map before you take it if the gap looks big."
    }

    /** Today's repositioning hops: consecutive logged deliveries with different addresses. */
    fun summaryToday(context: Context): HopSummary {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var hops = 0
        var deliveries = 0
        var prevAddress: String? = null
        var lastDropoff: String? = null
        val arr: JSONArray = DeliveryLog.readAll(context)
        // Log order is append order, which is delivery order.
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") != today) continue
            deliveries++
            val address = if (obj.isNull("address")) null else obj.optString("address")
            if (!address.isNullOrBlank()) {
                val key = OfferParser.normalizeKey(address)
                if (prevAddress != null && prevAddress != key) hops++
                prevAddress = key
                lastDropoff = address
            }
        }
        return HopSummary(hops = hops, deliveries = deliveries, lastDropoff = lastDropoff)
    }

    /** Most recent logged dropoff address today, or null. */
    fun lastDropoffToday(context: Context): String? {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var last: String? = null
        val arr: JSONArray = DeliveryLog.readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("date") != today) continue
            if (!obj.isNull("address")) {
                val a = obj.optString("address")
                if (a.isNotBlank()) last = a
            }
        }
        return last
    }

    fun text(s: HopSummary): String {
        if (s.deliveries == 0) {
            return "Nothing logged today yet, so there are no dead miles to count. Log a delivery first."
        }
        if (s.hops == 0) {
            return "No repositioning hops today — every logged delivery so far was at the same address as the one before it, " +
                "or the addresses weren't on the log. Dead miles I can't measure from the log alone: " +
                "the apps don't show pickup addresses, so I warn you when the next offer is somewhere new instead of guessing a distance."
        }
        val noun = if (s.hops == 1) "hop" else "hops"
        return "Dead miles today: $s.hops repositioning $noun across ${s.deliveries} logged deliveries — " +
            "each hop is a drive from one dropoff to a different next pickup that the offer doesn't pay for. " +
            "I can't put a mile number on those hops from the log alone, so I flag them when the address changes."
    }
}

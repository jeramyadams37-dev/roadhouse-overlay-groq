package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SeenOffer(
    val id: String,
    val date: String,
    val time: String,
    val platform: String, // stable tag: "spark" / "doordash" / "instacart" / "unknown"
    val storeKey: String,
    val storeLabel: String,
    val pay: Double?,
    val miles: Double?,
    val perMile: Double?,
    val address: String?,
    val outcome: OfferOutcome,
    val source: String, // "check" (voice/card) or "watch" (speak-new-offers)
    val needsReview: Boolean,
    val createdAtMs: Long
)

/** Result of trying to resolve a seen offer from a logged delivery. */
enum class PayMatchResult { MATCHED, AMBIGUOUS, NO_MATCH }

/**
 * Log of EVERY offer the overlay sees — seen_offers.json in the app's
 * private files dir. Every offer check (voice, card button, or the
 * opt-in speak-new-offers watch) records one, outcome UNKNOWN.
 *
 * Auto-resolve, two ways, and never by guessing:
 * (a) the accept-tap (or a "took it") marks the checked offer taken;
 * (b) pay-history matching — when a delivery is logged, a seen offer
 * with the same platform tag, a pay within $0.50, and the same store
 * key or the same delivery address is marked taken, but ONLY when
 * exactly one such offer exists. Two identical offers (same store +
 * pay) that can't be told apart are flagged needsReview on every
 * candidate and none is auto-matched. Anything unmatched stays
 * UNKNOWN until the driver marks it in the review list.
 *
 * Resolved outcomes feed offer memory (warnings), earnings, best
 * hours, and the tax tally through the delivery log they produce.
 */
object SeenOffers {
    private const val FILE_NAME = "seen_offers.json"
    private const val MAX_ENTRIES = 500
    private const val PAY_TOLERANCE = 0.50
    private const val MATCH_WINDOW_MS = 24L * 60L * 60L * 1000L

    fun record(
        context: Context,
        platformTag: String,
        storeKey: String,
        storeLabel: String,
        pay: Double?,
        miles: Double?,
        perMile: Double?,
        address: String?,
        source: String
    ): SeenOffer? {
        return try {
            val (date, time) = DeliveryLog.nowParts()
            val offer = SeenOffer(
                id = newId(),
                date = date,
                time = time,
                platform = platformTag,
                storeKey = storeKey,
                storeLabel = storeLabel,
                pay = pay,
                miles = miles,
                perMile = perMile,
                address = address,
                outcome = OfferOutcome.UNKNOWN,
                source = source,
                needsReview = false,
                createdAtMs = System.currentTimeMillis()
            )
            val arr = readAll(context)
            arr.put(toJson(offer))
            while (arr.length() > MAX_ENTRIES) arr.remove(0)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            offer
        } catch (e: Exception) {
            null
        }
    }

    /** Mark one seen offer took / skipped by id. */
    fun markOutcome(context: Context, id: String, outcome: OfferOutcome): Boolean {
        return update(context, id) { obj ->
            obj.put("outcome", outcome.name)
            obj.put("needs_review", false)
        }
    }

    /** Mark the seen offer that matches a checked offer record as taken. */
    fun markTakenForCheck(context: Context, record: OfferMemoryRecord): Boolean {
        // The checked offer and the seen offer are recorded together with
        // the same pay/miles/store; match on those, newest first.
        val candidates = offers(context).filter { it.outcome == OfferOutcome.UNKNOWN }
        val match = candidates.lastOrNull { o ->
            o.platform == Platforms.tagFromDisplay(record.platform) &&
                samePay(o.pay, record.pay) &&
                (o.storeKey.isNotBlank() && o.storeKey == record.storeKey ||
                    o.address != null && record.stops.firstOrNull() != null &&
                    OfferParser.normalizeKey(o.address) ==
                    OfferParser.normalizeKey(record.stops.first()))
        } ?: return false
        return markOutcome(context, match.id, OfferOutcome.TOOK)
    }

    /**
     * Pay-history matching (resolve path b). A logged delivery resolves
     * a seen offer only when exactly one UNKNOWN seen offer matches on
     * platform tag + pay (within $0.50) + store key or delivery address,
     * seen within the last 24 hours. Zero matches: NO_MATCH. More than
     * one: AMBIGUOUS — every candidate is flagged for manual review and
     * none is marked taken.
     */
    fun resolveByDelivery(context: Context, entry: DeliveryEntry): PayMatchResult {
        val now = System.currentTimeMillis()
        val tag = Platforms.tagFromDisplay(entry.platform)
        val candidates = offers(context).filter { o ->
            o.outcome == OfferOutcome.UNKNOWN &&
                o.platform == tag &&
                samePay(o.pay, entry.pay) &&
                now - o.createdAtMs in 0 until MATCH_WINDOW_MS &&
                (o.storeKey.isNotBlank() && entry.address != null &&
                    o.storeKey.contains(OfferParser.normalizeKey(entry.address)) ||
                    o.address != null && entry.address != null &&
                    OfferParser.normalizeKey(o.address) == OfferParser.normalizeKey(entry.address))
        }
        return when {
            candidates.isEmpty() -> PayMatchResult.NO_MATCH
            candidates.size == 1 -> {
                markOutcome(context, candidates[0].id, OfferOutcome.TOOK)
                PayMatchResult.MATCHED
            }
            else -> {
                // Identical offers that can't be told apart: flag them all
                // for manual review, mark none.
                for (c in candidates) {
                    update(context, c.id) { obj -> obj.put("needs_review", true) }
                }
                PayMatchResult.AMBIGUOUS
            }
        }
    }

    /** Unresolved offers, oldest first — the manual review list. */
    fun unresolved(context: Context): List<SeenOffer> =
        offers(context).filter { it.outcome == OfferOutcome.UNKNOWN }

    fun needsReview(context: Context): List<SeenOffer> =
        offers(context).filter { it.outcome == OfferOutcome.UNKNOWN && it.needsReview }

    fun offers(context: Context): List<SeenOffer> {
        val out = mutableListOf<SeenOffer>()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(fromJson(obj))
        }
        return out
    }

    fun readAll(context: Context): JSONArray {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun count(context: Context): Int = readAll(context).length()

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }

    private fun samePay(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return false
        return kotlin.math.abs(a - b) <= PAY_TOLERANCE
    }

    private fun update(context: Context, id: String, change: (JSONObject) -> Unit): Boolean {
        return try {
            val arr = readAll(context)
            var changed = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") == id) {
                    change(obj)
                    changed = true
                    break
                }
            }
            if (changed) File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            changed
        } catch (e: Exception) {
            false
        }
    }

    private fun newId(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date())
    }

    private fun toJson(o: SeenOffer): JSONObject {
        return JSONObject().apply {
            put("id", o.id)
            put("date", o.date)
            put("time", o.time)
            put("platform", o.platform)
            put("store_key", o.storeKey)
            put("store_label", o.storeLabel)
            if (o.pay != null) put("pay", o.pay) else put("pay", JSONObject.NULL)
            if (o.miles != null) put("miles", o.miles) else put("miles", JSONObject.NULL)
            if (o.perMile != null) put("per_mile", o.perMile) else put("per_mile", JSONObject.NULL)
            put("address", o.address ?: JSONObject.NULL)
            put("outcome", o.outcome.name)
            put("source", o.source)
            put("needs_review", o.needsReview)
            put("created_at_ms", o.createdAtMs)
        }
    }

    private fun fromJson(obj: JSONObject): SeenOffer {
        return SeenOffer(
            id = obj.optString("id"),
            date = obj.optString("date"),
            time = obj.optString("time"),
            platform = Platforms.tagFromDisplay(obj.optString("platform")),
            storeKey = obj.optString("store_key"),
            storeLabel = obj.optString("store_label"),
            pay = if (obj.isNull("pay")) null else obj.optDouble("pay"),
            miles = if (obj.isNull("miles")) null else obj.optDouble("miles"),
            perMile = if (obj.isNull("per_mile")) null else obj.optDouble("per_mile"),
            address = if (obj.isNull("address")) null else obj.optString("address"),
            outcome = try {
                OfferOutcome.valueOf(obj.optString("outcome", "UNKNOWN"))
            } catch (e: Exception) {
                OfferOutcome.UNKNOWN
            },
            source = obj.optString("source", "check"),
            needsReview = obj.optBoolean("needs_review", false),
            createdAtMs = obj.optLong("created_at_ms", 0L)
        )
    }
}

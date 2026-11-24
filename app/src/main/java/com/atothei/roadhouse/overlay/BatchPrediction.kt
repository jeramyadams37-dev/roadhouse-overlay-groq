package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

data class BatchPredictionResult(
    val totalCount: Int,
    val peakHour: Int?,
    val peakCount: Int,
    val platformTag: String?
)

/**
 * Next-batch prediction — from his own log only, code math. Histogram
 * of logged delivery times (first-offer time ≈ log time) per hour of
 * day, optionally per platform. Framed as a pattern from his log,
 * never a promise. With fewer than 5 deliveries total the sample is
 * too small and the text says so instead of guessing.
 */
object BatchPrediction {

    fun analyze(context: Context, platformTag: String? = null): BatchPredictionResult {
        val byHour = mutableMapOf<Int, Int>()
        var total = 0
        val arr: JSONArray = DeliveryLog.readAll(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (platformTag != null) {
                val tag = Platforms.tagFromDisplay(obj.optString("platform"))
                if (tag != platformTag) continue
            }
            val time = obj.optString("time")
            val hour = time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            total++
            byHour[hour] = (byHour[hour] ?: 0) + 1
        }
        if (total == 0) {
            return BatchPredictionResult(totalCount = 0, peakHour = null, peakCount = 0, platformTag = platformTag)
        }
        val peak = byHour.entries.maxByOrNull { it.value }
        return BatchPredictionResult(
            totalCount = total,
            peakHour = peak?.key,
            peakCount = peak?.value ?: 0,
            platformTag = platformTag
        )
    }

    fun text(r: BatchPredictionResult): String {
        if (r.totalCount < 5) {
            val n = r.totalCount
            val noun = if (n == 1) "delivery" else "deliveries"
            return "Only $n $noun in your log so far — too small a sample to predict when batches drop. " +
                "Log a few more days and ask again."
        }
        val peakHour = r.peakHour ?: return "Not enough of a pattern in your log yet to predict the next batch."
        val platformPart = if (r.platformTag != null) {
            "${Platforms.displayFromTag(r.platformTag)} "
        } else {
            ""
        }
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val whenPart = if (peakHour > nowHour) {
            "later today around ${BestHours.hourLabel(peakHour)}"
        } else if (peakHour == nowHour) {
            "around now — ${BestHours.hourLabel(peakHour)} is your usual peak hour"
        } else {
            "tomorrow around ${BestHours.hourLabel(peakHour)} — that hour has already passed today"
        }
        return "From your log, ${platformPart}batches usually drop $whenPart " +
            "(${r.peakCount} of your ${r.totalCount} logged deliveries landed in that hour). " +
            "That's a pattern from your own history, not a promise."
    }
}

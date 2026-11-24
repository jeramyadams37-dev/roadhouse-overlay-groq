package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Phone battery warning — one spoken warning when the phone hits 20%
 * ("phone's at 20%, plug in"), once per shift. No nagging.
 *
 * MECHANISM: no polling, no background receiver running constantly.
 * ACTION_BATTERY_CHANGED is a sticky broadcast, so the level is read
 * on demand — on the next user interaction (each voice command runs
 * through VoiceCommandHandler.handle, which calls checkAndWarn) — via
 * registerReceiver(null, filter). If the level is 20% or under and no
 * warning has been spoken this shift, warn once and record it.
 */
object BatteryCheck {

    private const val LOW_LEVEL = 20

    /** Current battery percent, or null when it cannot be read. */
    fun levelPercent(context: Context): Int? {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) null else (level * 100 / scale)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Warning to speak right now, or null. Speaks at most once per
     * shift: the warned date is recorded the first time it fires.
     */
    fun checkAndWarn(context: Context): String? {
        val level = levelPercent(context) ?: return null
        if (level > LOW_LEVEL) return null
        val today = ShiftState.todayStr()
        if (Prefs.getBatteryWarnedDate(context) == today) return null
        Prefs.setBatteryWarnedDate(context, today)
        return "Phone's at $level% — plug in when you can, before it dies on a delivery."
    }
}

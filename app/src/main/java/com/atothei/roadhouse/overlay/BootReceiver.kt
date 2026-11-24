package com.atothei.roadhouse.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the overlay after a reboot — but only if the driver already
 * finished first-run setup, so a fresh install never starts itself.
 * Also re-arms any voice reminders still in the future (alarms do not
 * survive a reboot on their own).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Reminders.rescheduleAll(context)
        FamilyDates.scheduleDailyCheck(context)
        if (!Prefs.isOnboardingDone(context)) return
        OverlayService.start(context)
    }
}

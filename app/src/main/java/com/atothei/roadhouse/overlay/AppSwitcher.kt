package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.Intent

/**
 * Voice app switching — "open Spark", "open DoorDash", "open Instacart"
 * launches the delivery app by package. ONLY the three delivery apps
 * plus his music app are launchable by voice; anything else is refused
 * with a plain message, never launched. No other app on the phone can
 * be opened this way.
 */
object AppSwitcher {

    private val ALLOWED = mapOf(
        "spark" to Platforms.SPARK,
        "doordash" to Platforms.DOORDASH,
        "dash" to Platforms.DOORDASH,
        "instacart" to Platforms.INSTACART
    )

    /** Package for a spoken app name, or null when it is not an allowed app. */
    fun packageFor(spokenName: String): String? {
        val key = spokenName.trim().lowercase()
        if (key.contains("music") || key.contains("spotify") || key.contains("youtube")) {
            return null // Music goes through MusicControl, not a bare launch.
        }
        for ((name, pkg) in ALLOWED) {
            if (key.contains(name)) return pkg
        }
        return null
    }

    fun isAllowedPackage(pkg: String?): Boolean =
        pkg == Platforms.SPARK || pkg == Platforms.DOORDASH || pkg == Platforms.INSTACART ||
            MusicControl.isMusicPackage(pkg)

    /**
     * Launch intent for the spoken app, or null when the name is not an
     * allowed app or the app is not installed. The caller speaks the
     * matching message.
     */
    fun launchIntent(context: Context, spokenName: String): Pair<Intent?, String> {
        val lower = spokenName.lowercase()
        if (lower.contains("music") || lower.contains("spotify") || lower.contains("youtube music")) {
            val app = MusicControl.resolveMusicApp(context)
            if (app == null) {
                return null to "I couldn't find a music app on this phone to open."
            }
            val intent = context.packageManager.getLaunchIntentForPackage(app.first)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return null to "I couldn't open ${app.second}."
            return intent to "Opening ${app.second}."
        }
        val pkg = packageFor(spokenName)
            ?: return null to "I only open Spark, DoorDash, Instacart, or your music app by voice — nothing else."
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null to "That app doesn't seem to be installed on this phone."
        val label = Platforms.displayFromTag(Platforms.tag(pkg))
        return intent to "Opening $label."
    }
}

package com.atothei.roadhouse.overlay

/**
 * Delivery apps RoadHouse Overlay recognises by package name.
 * Package names verified 2026-11-23:
 * - Spark: com.walmart.sparkdriver (Uptodown / APKPure listings)
 * - Instacart Shopper: com.instacart.shopper (APKMirror / APKShift)
 * - DoorDash Dasher: com.doordash.driverapp (given)
 * Anything else is still readable — the overlay works over any foreground
 * app, it just won't get a friendly platform name.
 */
object Platforms {
    const val SPARK = "com.walmart.sparkdriver"
    const val DOORDASH = "com.doordash.driverapp"
    const val INSTACART = "com.instacart.shopper"

    private val names = mapOf(
        SPARK to "Spark",
        DOORDASH to "DoorDash",
        INSTACART to "Instacart"
    )

    fun displayName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "Unknown app"
        return names[packageName] ?: "Other app"
    }

    /**
     * Stable lowercase tag for STORED data — every seen offer, log entry,
     * and earnings figure carries this, never the display name, so
     * summaries split per platform even across versions. Unknown package
     * is "unknown", never guessed.
     */
    fun tag(packageName: String?): String {
        return when (packageName) {
            SPARK -> "spark"
            DOORDASH -> "doordash"
            INSTACART -> "instacart"
            else -> "unknown"
        }
    }

    /** Normalise a stored platform value (tag or older display name) to a tag. */
    fun tagFromDisplay(display: String?): String {
        return when (display?.trim()?.lowercase()) {
            "spark" -> "spark"
            "doordash" -> "doordash"
            "instacart" -> "instacart"
            else -> "unknown"
        }
    }

    /** Display name for a stored tag, for spoken text and cards. */
    fun displayFromTag(tag: String?): String {
        return when (tagFromDisplay(tag)) {
            "spark" -> "Spark"
            "doordash" -> "DoorDash"
            "instacart" -> "Instacart"
            else -> "Unknown app"
        }
    }

    fun isDeliveryApp(packageName: String?): Boolean =
        packageName != null && names.containsKey(packageName)
}

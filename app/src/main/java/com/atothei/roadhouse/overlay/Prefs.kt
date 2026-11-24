package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings store. The Groq API key (bring-your-own-key) lives in
 * EncryptedSharedPreferences; if the encrypted store ever fails on a device,
 * it falls back to plain private preferences and flags it — the key is never
 * written to code, logs, or the APK.
 */
object Prefs {
    private const val FILE = "roadhouse_overlay_prefs"
    private const val KEY_API_KEY = "groq_api_key"
    private const val KEY_FLOOR = "per_mile_floor"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_SIZE = "overlay_size"
    private const val KEY_ONBOARDING = "onboarding_done"
    private const val KEY_WEATHER_CITY = "weather_city"
    private const val KEY_WEATHER_CITY_CACHED = "weather_city_cached"
    private const val KEY_WEATHER_LAT = "weather_lat"
    private const val KEY_WEATHER_LON = "weather_lon"
    private const val KEY_SPEAK_OFFERS = "speak_new_offers"
    private const val KEY_DAILY_GOAL = "daily_goal"
    private const val KEY_GOAL_ANNOUNCED = "goal_announced_date"
    private const val KEY_MPG = "vehicle_mpg"
    private const val KEY_FUEL_PRICE = "fuel_price"
    private const val KEY_ACCEPT_TAP = "accept_tap_logging"
    private const val KEY_SHIFT_DATE = "shift_date"
    private const val KEY_LAST_ACTIVITY_MS = "last_activity_ms"
    private const val KEY_SUMMARY_OFFERED_DATE = "summary_offered_date"
    private const val KEY_ENCOURAGED_DATE = "encouraged_date"
    private const val KEY_BATTERY_WARNED_DATE = "battery_warned_date"
    private const val KEY_BREAK_SCHEDULED_DATE = "break_scheduled_date"
    private const val KEY_HOME_NAV_ACTIVE = "home_nav_active"
    private const val KEY_ODOMETER_END_ASKED_DATE = "odometer_end_asked_date"

    const val DEFAULT_FLOOR = 1.00
    const val DEFAULT_MPG = 25.0
    const val DEFAULT_FUEL_PRICE = 3.50
    const val DEFAULT_WEATHER_CITY = "Petersburg, IL"

    @Volatile
    private var encryptedFailed = false

    private fun prefs(context: Context): SharedPreferences {
        if (encryptedFailed) {
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            encryptedFailed = true
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /** True only if the encrypted store failed and we fell back to plain prefs. */
    fun isEncryptedFallback(context: Context): Boolean {
        prefs(context)
        return encryptedFailed
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    fun getFloor(context: Context): Double =
        prefs(context).getFloat(KEY_FLOOR, DEFAULT_FLOOR.toFloat()).toDouble()

    fun setFloor(context: Context, floor: Double) {
        prefs(context).edit().putFloat(KEY_FLOOR, floor.toFloat()).apply()
    }

    fun isVoiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE, true)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    /**
     * Speak a short verdict when a fresh offer appears. OFF by default —
     * the driver turns it on in Settings. When off, the watch does nothing
     * at all (no extra event subscription, no polling).
     */
    fun isSpeakNewOffers(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEAK_OFFERS, false)

    fun setSpeakNewOffers(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK_OFFERS, enabled).apply()
    }

    /** Daily earnings goal in dollars; 0 (default) means no goal set. */
    fun getDailyGoal(context: Context): Double =
        prefs(context).getFloat(KEY_DAILY_GOAL, 0f).toDouble()

    fun setDailyGoal(context: Context, goal: Double) {
        prefs(context).edit().putFloat(KEY_DAILY_GOAL, goal.toFloat()).apply()
    }

    /** yyyy-MM-dd the goal-reached nudge was last spoken (once per day max). */
    fun getGoalAnnouncedDate(context: Context): String =
        prefs(context).getString(KEY_GOAL_ANNOUNCED, "") ?: ""

    fun setGoalAnnouncedDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_GOAL_ANNOUNCED, date).apply()
    }

    /** Vehicle miles per gallon, for fuel math. Default 25. */
    fun getMpg(context: Context): Double =
        prefs(context).getFloat(KEY_MPG, DEFAULT_MPG.toFloat()).toDouble()

    fun setMpg(context: Context, mpg: Double) {
        prefs(context).edit().putFloat(KEY_MPG, mpg.toFloat()).apply()
    }

    /** Fuel price in dollars per gallon, for fuel math. Default $3.50. */
    fun getFuelPrice(context: Context): Double =
        prefs(context).getFloat(KEY_FUEL_PRICE, DEFAULT_FUEL_PRICE.toFloat()).toDouble()

    fun setFuelPrice(context: Context, price: Double) {
        prefs(context).edit().putFloat(KEY_FUEL_PRICE, price.toFloat()).apply()
    }

    /**
     * Accept-tap auto-logging (feature 9). OFF by default — the driver
     * turns it on in Settings. When off, click events are never
     * subscribed and accept taps do nothing at all.
     */
    fun isAcceptTapLogging(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACCEPT_TAP, false)

    fun setAcceptTapLogging(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACCEPT_TAP, enabled).apply()
    }

    /** "small" | "medium" | "large" */
    fun getOverlaySize(context: Context): String =
        prefs(context).getString(KEY_SIZE, "medium") ?: "medium"

    /** yyyy-MM-dd the current shift started (first offer of the day). */
    fun getShiftDate(context: Context): String =
        prefs(context).getString(KEY_SHIFT_DATE, "") ?: ""

    fun setShiftDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_SHIFT_DATE, date).apply()
    }

    /** Epoch ms of the last voice interaction (shift auto-detect quiet timer). */
    fun getLastActivityMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ACTIVITY_MS, 0L)

    fun setLastActivityMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LAST_ACTIVITY_MS, ms).apply()
    }

    /** yyyy-MM-dd the end-of-shift summary was last offered (once per day max). */
    fun getSummaryOfferedDate(context: Context): String =
        prefs(context).getString(KEY_SUMMARY_OFFERED_DATE, "") ?: ""

    fun setSummaryOfferedDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_SUMMARY_OFFERED_DATE, date).apply()
    }

    /** yyyy-MM-dd the shift-start encouragement was spoken (once per shift). */
    fun getEncouragedDate(context: Context): String =
        prefs(context).getString(KEY_ENCOURAGED_DATE, "") ?: ""

    fun setEncouragedDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_ENCOURAGED_DATE, date).apply()
    }

    /** yyyy-MM-dd the low-battery warning was spoken (once per shift). */
    fun getBatteryWarnedDate(context: Context): String =
        prefs(context).getString(KEY_BATTERY_WARNED_DATE, "") ?: ""

    fun setBatteryWarnedDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_BATTERY_WARNED_DATE, date).apply()
    }

    /** yyyy-MM-dd the +3h break alarm was scheduled (once per shift). */
    fun getBreakScheduledDate(context: Context): String =
        prefs(context).getString(KEY_BREAK_SCHEDULED_DATE, "") ?: ""

    fun setBreakScheduledDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_BREAK_SCHEDULED_DATE, date).apply()
    }

    /** True while a "take me home" navigation is running (arrival offers the shift summary). */
    fun isHomeNavActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HOME_NAV_ACTIVE, false)

    fun setHomeNavActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_HOME_NAV_ACTIVE, active).apply()
    }

    /** yyyy-MM-dd the end-of-shift odometer prompt was asked (once per day). */
    fun getOdometerEndAskedDate(context: Context): String =
        prefs(context).getString(KEY_ODOMETER_END_ASKED_DATE, "") ?: ""

    fun setOdometerEndAskedDate(context: Context, date: String) {
        prefs(context).edit().putString(KEY_ODOMETER_END_ASKED_DATE, date).apply()
    }

    fun setOverlaySize(context: Context, size: String) {
        prefs(context).edit().putString(KEY_SIZE, size).apply()
    }

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING, false)

    fun setOnboardingDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING, true).apply()
    }

    /** City used for the pre-shift weather check. Default: home base. */
    fun getWeatherCity(context: Context): String =
        prefs(context).getString(KEY_WEATHER_CITY, DEFAULT_WEATHER_CITY)
            ?: DEFAULT_WEATHER_CITY

    fun setWeatherCity(context: Context, city: String) {
        val clean = city.trim().ifBlank { DEFAULT_WEATHER_CITY }
        prefs(context).edit().putString(KEY_WEATHER_CITY, clean).apply()
    }

    /** Cached geocode for a city, so Nominatim is asked once per city. */
    fun getCachedLatLon(context: Context, city: String): Pair<Double, Double>? {
        val p = prefs(context)
        if (p.getString(KEY_WEATHER_CITY_CACHED, "") != city) return null
        if (!p.contains(KEY_WEATHER_LAT) || !p.contains(KEY_WEATHER_LON)) return null
        val lat = p.getFloat(KEY_WEATHER_LAT, Float.NaN).toDouble()
        val lon = p.getFloat(KEY_WEATHER_LON, Float.NaN).toDouble()
        return if (lat.isNaN() || lon.isNaN()) null else lat to lon
    }

    fun setCachedLatLon(context: Context, city: String, lat: Double, lon: Double) {
        prefs(context).edit()
            .putString(KEY_WEATHER_CITY_CACHED, city)
            .putFloat(KEY_WEATHER_LAT, lat.toFloat())
            .putFloat(KEY_WEATHER_LON, lon.toFloat())
            .apply()
    }
}

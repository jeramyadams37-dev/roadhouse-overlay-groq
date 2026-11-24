package com.atothei.roadhouse.overlay

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * First-run flow + status screen. Steps, in order:
 * 1. Overlay permission (draw over other apps)
 * 2. Accessibility service (screen reading — read-only, never clicks)
 * 3. Microphone permission (voice commands)
 * 4. Groq key + $/mile floor (in Settings)
 * 5. Start overlay
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusMic: TextView
    private lateinit var statusKey: TextView
    private lateinit var statusService: TextView
    private var fallbackToastShown = false

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusOverlay = findViewById(R.id.status_overlay)
        statusAccessibility = findViewById(R.id.status_accessibility)
        statusMic = findViewById(R.id.status_mic)
        statusKey = findViewById(R.id.status_key)
        statusService = findViewById(R.id.status_service)

        findViewById<Button>(R.id.btn_overlay_permission).setOnClickListener {
            requestOverlayPermission()
        }
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Find RoadHouse Overlay Groq in the list and turn it on.",
                Toast.LENGTH_LONG
            ).show()
        }
        findViewById<Button>(R.id.btn_mic).setOnClickListener { requestMicPermission() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_start_overlay).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.btn_stop_overlay).setOnClickListener {
            OverlayService.stop(this)
            refreshStatus()
        }
        findViewById<Button>(R.id.btn_end_shift).setOnClickListener { showShiftSummary() }
        findViewById<Button>(R.id.btn_review_offers).setOnClickListener {
            startActivity(Intent(this, OfferReviewActivity::class.java))
        }
    }

    /** End-of-shift summary dialog — pure code math on today's log. */
    private fun showShiftSummary() {
        val summary = ShiftSummary.summarize(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.end_shift_title))
            .setMessage(ShiftSummary.text(summary))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        statusOverlay.text = if (overlayOk) {
            getString(R.string.status_overlay_ok)
        } else {
            getString(R.string.status_overlay_missing)
        }

        statusAccessibility.text = if (isAccessibilityEnabled()) {
            getString(R.string.status_accessibility_ok)
        } else {
            getString(R.string.status_accessibility_missing)
        }

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        statusMic.text = if (micOk) {
            getString(R.string.status_mic_ok)
        } else {
            getString(R.string.status_mic_missing)
        }

        statusKey.text = if (Prefs.hasApiKey(this)) {
            getString(R.string.status_key_ok)
        } else {
            getString(R.string.status_key_missing)
        }

        statusService.text = if (OverlayService.isRunning) {
            getString(R.string.status_service_running)
        } else {
            getString(R.string.status_service_stopped)
        }

        findViewById<Button>(R.id.btn_start_overlay).isEnabled = overlayOk
        if (!fallbackToastShown && Prefs.isEncryptedFallback(this)) {
            fallbackToastShown = true
            Toast.makeText(
                this,
                "Note: encrypted storage is unavailable on this device, so settings are stored unencrypted.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ScreenReaderService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            refreshStatus()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestMicPermission() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(perms.toTypedArray())
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow the overlay permission first.", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                "Turn on RoadHouse Overlay Groq in Accessibility settings so it can read the delivery screen.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        OverlayService.start(this)
        Prefs.setOnboardingDone(this)
        Toast.makeText(
            this,
            "RoadHouse Overlay Groq is on. Switch to your delivery app and tap the mic button.",
            Toast.LENGTH_LONG
        ).show()
        refreshStatus()
    }
}

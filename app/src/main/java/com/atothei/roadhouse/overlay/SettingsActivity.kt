package com.atothei.roadhouse.overlay

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings: Groq API key (BYOK), $/mile floor, voice on/off, overlay
 * button size, weather city, delivery-log / offer-history / store-notes
 * export/clear. The key is stored encrypted and is never shown in full
 * after saving — the field is a password field.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var floorInput: EditText
    private lateinit var weatherCityInput: EditText
    private lateinit var goalInput: EditText
    private lateinit var mpgInput: EditText
    private lateinit var fuelPriceInput: EditText
    private lateinit var voiceSwitch: Switch
    private lateinit var sizeGroup: RadioGroup
    private lateinit var logCount: TextView
    private lateinit var offerCount: TextView
    private lateinit var noteCount: TextView
    private lateinit var addressNoteCount: TextView
    private lateinit var seenCount: TextView
    private lateinit var expenseCount: TextView
    private lateinit var tipCount: TextView
    private lateinit var speakSwitch: Switch
    private lateinit var acceptTapSwitch: Switch

    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results[Manifest.permission.READ_CONTACTS] == true ||
                results[Manifest.permission.READ_SMS] == true
            Toast.makeText(
                this,
                if (granted) "Calls and messages on — say \"call Ella\" or \"read my messages\"."
                else "Not granted — calls and texts will say so plainly and do nothing.",
                Toast.LENGTH_LONG
            ).show()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            Toast.makeText(
                this,
                if (granted) "Location on — weather will use where you are."
                else "Location not granted — weather will use your saved city.",
                Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        keyInput = findViewById(R.id.input_api_key)
        floorInput = findViewById(R.id.input_floor)
        weatherCityInput = findViewById(R.id.input_weather_city)
        goalInput = findViewById(R.id.input_goal)
        mpgInput = findViewById(R.id.input_mpg)
        fuelPriceInput = findViewById(R.id.input_fuel_price)
        voiceSwitch = findViewById(R.id.switch_voice)
        sizeGroup = findViewById(R.id.group_size)
        logCount = findViewById(R.id.text_log_count)
        offerCount = findViewById(R.id.text_offer_count)
        noteCount = findViewById(R.id.text_note_count)
        addressNoteCount = findViewById(R.id.text_address_note_count)
        seenCount = findViewById(R.id.text_seen_count)
        expenseCount = findViewById(R.id.text_expense_count)
        tipCount = findViewById(R.id.text_tip_count)
        speakSwitch = findViewById(R.id.switch_speak_offers)
        speakSwitch.isChecked = Prefs.isSpeakNewOffers(this)
        acceptTapSwitch = findViewById(R.id.switch_accept_tap)
        acceptTapSwitch.isChecked = Prefs.isAcceptTapLogging(this)

        keyInput.setText(Prefs.getApiKey(this))
        floorInput.setText("%.2f".format(Prefs.getFloor(this)))
        goalInput.setText(if (Prefs.getDailyGoal(this) > 0) "%.2f".format(Prefs.getDailyGoal(this)) else "")
        mpgInput.setText("%.0f".format(Prefs.getMpg(this)))
        fuelPriceInput.setText("%.2f".format(Prefs.getFuelPrice(this)))
        weatherCityInput.setText(Prefs.getWeatherCity(this))
        voiceSwitch.isChecked = Prefs.isVoiceEnabled(this)
        when (Prefs.getOverlaySize(this)) {
            "small" -> sizeGroup.check(R.id.radio_small)
            "large" -> sizeGroup.check(R.id.radio_large)
            else -> sizeGroup.check(R.id.radio_medium)
        }
        refreshCounts()

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_test_key).setOnClickListener { testKey() }
        findViewById<Button>(R.id.btn_export_log).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            DeliveryLog.clear(this)
            refreshCounts()
            Toast.makeText(this, "Delivery log cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_export_offers).setOnClickListener { exportOffers() }
        findViewById<Button>(R.id.btn_clear_offers).setOnClickListener {
            OfferMemory.clear(this)
            refreshCounts()
            Toast.makeText(this, "Offer history cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_export_notes).setOnClickListener { exportNotes() }
        findViewById<Button>(R.id.btn_clear_notes).setOnClickListener {
            StoreNotes.clear(this)
            refreshCounts()
            Toast.makeText(this, "Store notes cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_export_address_notes).setOnClickListener { exportAddressNotes() }
        findViewById<Button>(R.id.btn_clear_address_notes).setOnClickListener {
            AddressNotes.clear(this)
            refreshCounts()
            Toast.makeText(this, "Address notes cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_export_seen).setOnClickListener { exportSeen() }
        findViewById<Button>(R.id.btn_clear_seen).setOnClickListener {
            SeenOffers.clear(this)
            refreshCounts()
            Toast.makeText(this, "Seen offers cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_location_permission).setOnClickListener {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        findViewById<Button>(R.id.btn_call_permission).setOnClickListener {
            callPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_CONTACTS
                )
            )
        }
        findViewById<Button>(R.id.btn_notification_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(
                this,
                "Find RoadHouse Overlay Groq in the list and turn it on to hear notifications read aloud.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshCounts() {
        logCount.text = getString(R.string.log_count, DeliveryLog.count(this))
        offerCount.text = getString(R.string.offer_count, OfferMemory.count(this))
        noteCount.text = getString(R.string.note_count, StoreNotes.count(this))
        addressNoteCount.text = getString(R.string.address_note_count, AddressNotes.count(this))
        seenCount.text = getString(R.string.seen_count, SeenOffers.count(this))
        expenseCount.text = getString(R.string.expense_count, ExpenseLog.count(this))
        tipCount.text = getString(R.string.tip_count, TipLog.count(this))
    }

    private fun save() {
        Prefs.setApiKey(this, keyInput.text.toString())
        val floor = floorInput.text.toString().toDoubleOrNull()
        if (floor == null || floor <= 0.0 || floor > 10.0) {
            Toast.makeText(
                this,
                "Floor must be between 0.01 and 10.00 dollars per mile.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Prefs.setFloor(this, floor)
        val goalText = goalInput.text.toString().trim()
        val goal = if (goalText.isBlank()) 0.0 else goalText.toDoubleOrNull()
        if (goal == null || goal < 0.0 || goal > 10000.0) {
            Toast.makeText(
                this,
                "Daily goal must be blank (off) or between 1 and 10,000 dollars.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Prefs.setDailyGoal(this, goal)
        val mpg = mpgInput.text.toString().toDoubleOrNull()
        if (mpg == null || mpg < 1.0 || mpg > 200.0) {
            Toast.makeText(
                this,
                "MPG must be between 1 and 200.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Prefs.setMpg(this, mpg)
        val fuelPrice = fuelPriceInput.text.toString().toDoubleOrNull()
        if (fuelPrice == null || fuelPrice <= 0.0 || fuelPrice > 20.0) {
            Toast.makeText(
                this,
                "Fuel price must be between 0.01 and 20.00 dollars per gallon.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Prefs.setFuelPrice(this, fuelPrice)
        Prefs.setWeatherCity(this, weatherCityInput.text.toString())
        Prefs.setVoiceEnabled(this, voiceSwitch.isChecked)
        Prefs.setSpeakNewOffers(this, speakSwitch.isChecked)
        Prefs.setAcceptTapLogging(this, acceptTapSwitch.isChecked)
        if (OverlayService.isRunning) {
            ScreenReaderService.setSpeakOffers(speakSwitch.isChecked)
            ScreenReaderService.setAcceptTapLogging(acceptTapSwitch.isChecked)
        }
        val size = when (sizeGroup.checkedRadioButtonId) {
            R.id.radio_small -> "small"
            R.id.radio_large -> "large"
            else -> "medium"
        }
        Prefs.setOverlaySize(this, size)
        Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testKey() {
        val key = keyInput.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Paste your Groq key first.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Testing key…", Toast.LENGTH_SHORT).show()
        GroqClient.ask(key, "Reply with exactly: OK", "", object : GroqClient.Callback {
            override fun onResult(text: String) {
                runOnUiThread {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Key works. Groq said: $text",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun exportLog() {
        val json = DeliveryLog.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "RoadHouse Overlay Groq delivery log")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share delivery log"))
    }

    private fun exportOffers() {
        val json = OfferMemory.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "RoadHouse Overlay Groq offer history")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share offer history"))
    }

    private fun exportNotes() {
        val json = StoreNotes.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} store notes")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share store notes"))
    }

    private fun exportAddressNotes() {
        val json = AddressNotes.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} address notes")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share address notes"))
    }

    private fun exportSeen() {
        val json = SeenOffers.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "RoadHouse Overlay Groq seen offers")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share seen offers"))
    }
}

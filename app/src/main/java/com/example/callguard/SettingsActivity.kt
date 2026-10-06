package com.example.callguard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * Dedicated Settings Screen:
 * Allows user to configure custom Call Duration Limit (strictly capped at 3 hours)
 * and Snooze Duration with presets and unit selectors.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var etLimitValue: EditText
    private lateinit var spnLimitUnit: Spinner
    private lateinit var tvLimitError: TextView

    private lateinit var etSnoozeValue: EditText
    private lateinit var spnSnoozeUnit: Spinner

    private lateinit var tvWaitWindowLabel: TextView
    private lateinit var btnWindow15s: Button
    private lateinit var btnWindow30s: Button
    private lateinit var btnWindow45s: Button
    private lateinit var btnWindow60s: Button

    private lateinit var swVibration: SwitchCompat
    private lateinit var btnSave: Button

    // Quick presets
    private lateinit var btnPresetLimit30s: Button
    private lateinit var btnPresetLimit1m: Button
    private lateinit var btnPresetLimit5m: Button
    private lateinit var btnPresetLimit15m: Button
    private lateinit var btnPresetLimit30m: Button
    private lateinit var btnPresetLimit1h: Button
    private lateinit var btnPresetLimit3h: Button

    private lateinit var btnPresetSnooze30s: Button
    private lateinit var btnPresetSnooze1m: Button
    private lateinit var btnPresetSnooze2m: Button
    private lateinit var btnPresetSnooze5m: Button
    private lateinit var btnPresetSnooze10m: Button

    private var selectedWindowSeconds = 30L
    private val limitUnits = arrayOf("Minutes", "Hours", "Seconds")
    private val snoozeUnits = arrayOf("Minutes", "Seconds")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallPolicyRepository.init(this)
        setContentView(R.layout.activity_settings)

        bindViews()
        setupSpinners()
        populateCurrentValues()
        setupListeners()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        etLimitValue = findViewById(R.id.etLimitValue)
        spnLimitUnit = findViewById(R.id.spnLimitUnit)
        tvLimitError = findViewById(R.id.tvLimitError)

        etSnoozeValue = findViewById(R.id.etSnoozeValue)
        spnSnoozeUnit = findViewById(R.id.spnSnoozeUnit)

        tvWaitWindowLabel = findViewById(R.id.tvWaitWindowLabel)
        btnWindow15s = findViewById(R.id.btnWindow15s)
        btnWindow30s = findViewById(R.id.btnWindow30s)
        btnWindow45s = findViewById(R.id.btnWindow45s)
        btnWindow60s = findViewById(R.id.btnWindow60s)

        swVibration = findViewById(R.id.swVibration)
        btnSave = findViewById(R.id.btnSave)

        btnPresetLimit30s = findViewById(R.id.btnPresetLimit30s)
        btnPresetLimit1m = findViewById(R.id.btnPresetLimit1m)
        btnPresetLimit5m = findViewById(R.id.btnPresetLimit5m)
        btnPresetLimit15m = findViewById(R.id.btnPresetLimit15m)
        btnPresetLimit30m = findViewById(R.id.btnPresetLimit30m)
        btnPresetLimit1h = findViewById(R.id.btnPresetLimit1h)
        btnPresetLimit3h = findViewById(R.id.btnPresetLimit3h)

        btnPresetSnooze30s = findViewById(R.id.btnPresetSnooze30s)
        btnPresetSnooze1m = findViewById(R.id.btnPresetSnooze1m)
        btnPresetSnooze2m = findViewById(R.id.btnPresetSnooze2m)
        btnPresetSnooze5m = findViewById(R.id.btnPresetSnooze5m)
        btnPresetSnooze10m = findViewById(R.id.btnPresetSnooze10m)
    }

    private fun setupSpinners() {
        val limitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, limitUnits)
        spnLimitUnit.adapter = limitAdapter

        val snoozeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, snoozeUnits)
        spnSnoozeUnit.adapter = snoozeAdapter
    }

    private fun populateCurrentValues() {
        val policy = CallPolicyRepository.getPolicy()

        // Populate limit (default in minutes if divisible by 60)
        val limitSec = policy.maxDurationSeconds
        if (limitSec % 3600L == 0L && limitSec > 0) {
            etLimitValue.setText((limitSec / 3600L).toString())
            spnLimitUnit.setSelection(1) // Hours
        } else if (limitSec % 60L == 0L) {
            etLimitValue.setText((limitSec / 60L).toString())
            spnLimitUnit.setSelection(0) // Minutes
        } else {
            etLimitValue.setText(limitSec.toString())
            spnLimitUnit.setSelection(2) // Seconds
        }

        // Populate snooze
        val snoozeSec = policy.snoozeDurationSeconds
        if (snoozeSec % 60L == 0L) {
            etSnoozeValue.setText((snoozeSec / 60L).toString())
            spnSnoozeUnit.setSelection(0) // Minutes
        } else {
            etSnoozeValue.setText(snoozeSec.toString())
            spnSnoozeUnit.setSelection(1) // Seconds
        }

        // Window & vibration
        selectedWindowSeconds = policy.confirmationWindowSeconds
        updateWindowButtons(selectedWindowSeconds)
        swVibration.isChecked = policy.vibrationEnabled
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        // Live validation for 3 hours hard cap
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateLimit()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        etLimitValue.addTextChangedListener(watcher)

        spnLimitUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                validateLimit()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Presets for limit
        btnPresetLimit30s.setOnClickListener { setLimit(30, 2) }
        btnPresetLimit1m.setOnClickListener { setLimit(1, 0) }
        btnPresetLimit5m.setOnClickListener { setLimit(5, 0) }
        btnPresetLimit15m.setOnClickListener { setLimit(15, 0) }
        btnPresetLimit30m.setOnClickListener { setLimit(30, 0) }
        btnPresetLimit1h.setOnClickListener { setLimit(1, 1) }
        btnPresetLimit3h.setOnClickListener { setLimit(3, 1) }

        // Presets for snooze
        btnPresetSnooze30s.setOnClickListener { setSnooze(30, 1) }
        btnPresetSnooze1m.setOnClickListener { setSnooze(1, 0) }
        btnPresetSnooze2m.setOnClickListener { setSnooze(2, 0) }
        btnPresetSnooze5m.setOnClickListener { setSnooze(5, 0) }
        btnPresetSnooze10m.setOnClickListener { setSnooze(10, 0) }

        // Window buttons
        btnWindow15s.setOnClickListener { updateWindowButtons(15L) }
        btnWindow30s.setOnClickListener { updateWindowButtons(30L) }
        btnWindow45s.setOnClickListener { updateWindowButtons(45L) }
        btnWindow60s.setOnClickListener { updateWindowButtons(60L) }

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun setLimit(value: Long, unitIndex: Int) {
        etLimitValue.setText(value.toString())
        spnLimitUnit.setSelection(unitIndex)
        validateLimit()
    }

    private fun setSnooze(value: Long, unitIndex: Int) {
        etSnoozeValue.setText(value.toString())
        spnSnoozeUnit.setSelection(unitIndex)
    }

    private fun validateLimit(): Boolean {
        val input = etLimitValue.text.toString().trim().toLongOrNull() ?: 0L
        val unit = spnLimitUnit.selectedItemPosition // 0 = Minutes, 1 = Hours, 2 = Seconds
        val seconds = when (unit) {
            1 -> input * 3600L // Hours
            0 -> input * 60L   // Minutes
            else -> input      // Seconds
        }

        if (seconds > CallPolicyRepository.MAX_ALLOWED_DURATION_SECONDS) {
            tvLimitError.text = "⚠️ Limit cannot exceed 3 hours (180 minutes)!"
            tvLimitError.visibility = View.VISIBLE
            btnSave.isEnabled = false
            return false
        } else if (seconds < CallPolicyRepository.MIN_ALLOWED_DURATION_SECONDS && input > 0) {
            tvLimitError.text = "⚠️ Minimum limit is 10 seconds."
            tvLimitError.visibility = View.VISIBLE
            btnSave.isEnabled = false
            return false
        } else {
            tvLimitError.visibility = View.GONE
            btnSave.isEnabled = true
            return true
        }
    }

    private fun updateWindowButtons(seconds: Long) {
        selectedWindowSeconds = seconds
        tvWaitWindowLabel.text = "Wait window before auto-disconnect: ${seconds}s"

        updateHighlight(btnWindow15s, seconds == 15L)
        updateHighlight(btnWindow30s, seconds == 30L)
        updateHighlight(btnWindow45s, seconds == 45L)
        updateHighlight(btnWindow60s, seconds == 60L)
    }

    private fun updateHighlight(btn: Button, isSelected: Boolean) {
        btn.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isSelected) android.R.color.holo_blue_dark else android.R.color.darker_gray,
        )
    }

    private fun saveSettings() {
        if (!validateLimit()) return

        val limitInput = etLimitValue.text.toString().trim().toLongOrNull() ?: 60L
        val limitUnit = spnLimitUnit.selectedItemPosition
        val limitSeconds = when (limitUnit) {
            1 -> limitInput * 3600L
            0 -> limitInput * 60L
            else -> limitInput
        }.coerceIn(CallPolicyRepository.MIN_ALLOWED_DURATION_SECONDS, CallPolicyRepository.MAX_ALLOWED_DURATION_SECONDS)

        val snoozeInput = etSnoozeValue.text.toString().trim().toLongOrNull() ?: 60L
        val snoozeUnit = spnSnoozeUnit.selectedItemPosition
        val snoozeSeconds = when (snoozeUnit) {
            0 -> snoozeInput * 60L
            else -> snoozeInput
        }.coerceIn(CallPolicyRepository.MIN_ALLOWED_SNOOZE_SECONDS, CallPolicyRepository.MAX_ALLOWED_SNOOZE_SECONDS)

        CallPolicyRepository.updateMaxDuration(limitSeconds)
        CallPolicyRepository.updateSnoozeDuration(snoozeSeconds)
        CallPolicyRepository.updateConfirmationWindow(selectedWindowSeconds)
        CallPolicyRepository.setVibrationEnabled(swVibration.isChecked)

        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        finish()
    }
}

package com.example.callguard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Main Default Dialer Screen:
 * - Handles Intent.ACTION_DIAL and provides a complete interactive dial-pad UI.
 * - Provides configurable Call Limit and Snooze durations.
 * - Directs the user to CallActivity for live incoming and ongoing calls.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dialerRole: DialerRoleManager

    // Active call banner
    private lateinit var bannerActiveCall: LinearLayout
    private lateinit var tvActiveCallBanner: TextView

    // Role
    private lateinit var tvRole: TextView
    private lateinit var btnRole: Button
    private lateinit var btnOpenAppSettings: Button

    // Policy settings
    private lateinit var tvLabelLimit: TextView
    private lateinit var btnLimit30s: Button
    private lateinit var btnLimit1m: Button
    private lateinit var btnLimit2m: Button
    private lateinit var btnLimit5m: Button
    private lateinit var btnLimit10m: Button
    private lateinit var btnLimit30m: Button

    private lateinit var tvLabelSnooze: TextView
    private lateinit var btnSnooze30s: Button
    private lateinit var btnSnooze1m: Button
    private lateinit var btnSnooze2m: Button
    private lateinit var btnSnooze5m: Button
    private lateinit var btnSnooze10m: Button

    private lateinit var btnToggleWindow: Button
    private lateinit var btnToggleVibrate: Button

    // Dialpad
    private lateinit var etDialpadNumber: EditText
    private lateinit var btnBackspace: ImageButton
    private lateinit var btnPlaceCall: ImageButton

    // Diagnostics / POC
    private lateinit var tvToggleMainPoc: TextView
    private lateinit var layoutMainPoc: LinearLayout
    private lateinit var etNotes: EditText
    private lateinit var tvDevice: TextView
    private lateinit var tvLog: TextView

    private var pocSectionExpanded = false

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            CallStateLogger.log(
                "ROLE",
                "Role request finished resultCode=${result.resultCode} held=${dialerRole.isDialerRoleHeld()}",
            )
            renderRole()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            CallStateLogger.log(
                "PERM",
                grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" },
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallPolicyRepository.init(this)
        CallVibrator.init(this)

        setContentView(R.layout.activity_main)

        dialerRole = DialerRoleManager(this)

        bindViews()
        setupClicks()
        setupDialpad()
        setupSettingsControls()
        handleDialIntent(intent)
        requestRuntimePermissions()

        tvDevice.text = deviceSummary()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ActiveCallStore.state.collect { renderCallState(it) }
                }
                launch {
                    CallPolicyRepository.policyFlow.collect { renderPolicy(it) }
                }
                launch {
                    CallStateLogger.lines.collect { tvLog.text = it.takeLast(60).joinToString("\n") }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        renderRole()
    }

    private fun bindViews() {
        bannerActiveCall = findViewById(R.id.bannerActiveCall)
        tvActiveCallBanner = findViewById(R.id.tvActiveCallBanner)

        tvRole = findViewById(R.id.tvRole)
        btnRole = findViewById(R.id.btnRole)
        btnOpenAppSettings = findViewById(R.id.btnOpenAppSettings)

        tvLabelLimit = findViewById(R.id.tvLabelLimit)
        btnLimit30s = findViewById(R.id.btnLimit30s)
        btnLimit1m = findViewById(R.id.btnLimit1m)
        btnLimit2m = findViewById(R.id.btnLimit2m)
        btnLimit5m = findViewById(R.id.btnLimit5m)
        btnLimit10m = findViewById(R.id.btnLimit10m)
        btnLimit30m = findViewById(R.id.btnLimit30m)

        tvLabelSnooze = findViewById(R.id.tvLabelSnooze)
        btnSnooze30s = findViewById(R.id.btnSnooze30s)
        btnSnooze1m = findViewById(R.id.btnSnooze1m)
        btnSnooze2m = findViewById(R.id.btnSnooze2m)
        btnSnooze5m = findViewById(R.id.btnSnooze5m)
        btnSnooze10m = findViewById(R.id.btnSnooze10m)

        btnToggleWindow = findViewById(R.id.btnToggleWindow)
        btnToggleVibrate = findViewById(R.id.btnToggleVibrate)

        etDialpadNumber = findViewById(R.id.etDialpadNumber)
        btnBackspace = findViewById(R.id.btnBackspace)
        btnPlaceCall = findViewById(R.id.btnPlaceCall)

        tvToggleMainPoc = findViewById(R.id.tvToggleMainPoc)
        layoutMainPoc = findViewById(R.id.layoutMainPoc)
        etNotes = findViewById(R.id.etNotes)
        tvDevice = findViewById(R.id.tvDevice)
        tvLog = findViewById(R.id.tvLog)
    }

    private fun setupClicks() {
        btnRole.setOnClickListener { requestDialerRole() }
        btnOpenAppSettings.setOnClickListener { openAppSettings() }

        bannerActiveCall.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }

        tvToggleMainPoc.setOnClickListener {
            pocSectionExpanded = !pocSectionExpanded
            layoutMainPoc.visibility = if (pocSectionExpanded) View.VISIBLE else View.GONE
            tvToggleMainPoc.text = if (pocSectionExpanded) "POC Verification & Diagnostics ▲" else "POC Verification & Diagnostics ▼"
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareReport() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { CallStateLogger.clear() }
    }

    // ---- Dialpad Logic --------------------------------------------------------------------

    private fun setupDialpad() {
        fun appendDigit(d: String) {
            val current = etDialpadNumber.text.toString()
            etDialpadNumber.setText(current + d)
            etDialpadNumber.setSelection(etDialpadNumber.text.length)
        }

        findViewById<Button>(R.id.btnDigit1).setOnClickListener { appendDigit("1") }
        findViewById<Button>(R.id.btnDigit2).setOnClickListener { appendDigit("2") }
        findViewById<Button>(R.id.btnDigit3).setOnClickListener { appendDigit("3") }
        findViewById<Button>(R.id.btnDigit4).setOnClickListener { appendDigit("4") }
        findViewById<Button>(R.id.btnDigit5).setOnClickListener { appendDigit("5") }
        findViewById<Button>(R.id.btnDigit6).setOnClickListener { appendDigit("6") }
        findViewById<Button>(R.id.btnDigit7).setOnClickListener { appendDigit("7") }
        findViewById<Button>(R.id.btnDigit8).setOnClickListener { appendDigit("8") }
        findViewById<Button>(R.id.btnDigit9).setOnClickListener { appendDigit("9") }
        findViewById<Button>(R.id.btnDigitStar).setOnClickListener { appendDigit("*") }
        findViewById<Button>(R.id.btnDigitHash).setOnClickListener { appendDigit("#") }

        val btn0 = findViewById<Button>(R.id.btnDigit0)
        btn0.setOnClickListener { appendDigit("0") }
        btn0.setOnLongClickListener {
            appendDigit("+")
            true
        }

        btnBackspace.setOnClickListener {
            val text = etDialpadNumber.text.toString()
            if (text.isNotEmpty()) {
                etDialpadNumber.setText(text.substring(0, text.length - 1))
                etDialpadNumber.setSelection(etDialpadNumber.text.length)
            }
        }
        btnBackspace.setOnLongClickListener {
            etDialpadNumber.setText("")
            true
        }

        btnPlaceCall.setOnClickListener { placeCall() }
    }

    private fun handleDialIntent(intent: Intent?) {
        val i = intent ?: return
        if (i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW) {
            val number = i.data?.schemeSpecificPart
            if (!number.isNullOrBlank()) {
                etDialpadNumber.setText(number)
                etDialpadNumber.setSelection(number.length)
                CallStateLogger.log("DIAL", "ACTION_DIAL received with number pre-filled")
            }
        }
    }

    private fun placeCall() {
        val number = etDialpadNumber.text.toString().trim()
        if (number.isEmpty()) {
            Toast.makeText(this, "Please dial a phone number first", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestRuntimePermissions()
            Toast.makeText(this, "Grant Phone permission to place calls", Toast.LENGTH_LONG).show()
            return
        }
        try {
            CallStateLogger.log("DIAL", "Placing call via TelecomManager...")
            val telecom = getSystemService(TelecomManager::class.java)
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())

            val callIntent = Intent(this, CallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(callIntent)
        } catch (e: SecurityException) {
            CallStateLogger.log("DIAL", "placeCall failed: ${e.message}")
            Toast.makeText(this, "Failed to place call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Settings Controls ----------------------------------------------------------------

    private fun setupSettingsControls() {
        btnLimit30s.setOnClickListener { CallPolicyRepository.updateMaxDuration(30L) }
        btnLimit1m.setOnClickListener { CallPolicyRepository.updateMaxDuration(60L) }
        btnLimit2m.setOnClickListener { CallPolicyRepository.updateMaxDuration(120L) }
        btnLimit5m.setOnClickListener { CallPolicyRepository.updateMaxDuration(300L) }
        btnLimit10m.setOnClickListener { CallPolicyRepository.updateMaxDuration(600L) }
        btnLimit30m.setOnClickListener { CallPolicyRepository.updateMaxDuration(1800L) }

        btnSnooze30s.setOnClickListener { CallPolicyRepository.updateSnoozeDuration(30L) }
        btnSnooze1m.setOnClickListener { CallPolicyRepository.updateSnoozeDuration(60L) }
        btnSnooze2m.setOnClickListener { CallPolicyRepository.updateSnoozeDuration(120L) }
        btnSnooze5m.setOnClickListener { CallPolicyRepository.updateSnoozeDuration(300L) }
        btnSnooze10m.setOnClickListener { CallPolicyRepository.updateSnoozeDuration(600L) }

        btnToggleWindow.setOnClickListener {
            val current = CallPolicyRepository.getPolicy().confirmationWindowSeconds
            val next = when (current) {
                15L -> 30L
                30L -> 45L
                45L -> 60L
                else -> 15L
            }
            CallPolicyRepository.updateConfirmationWindow(next)
        }

        btnToggleVibrate.setOnClickListener {
            val current = CallPolicyRepository.getPolicy().vibrationEnabled
            CallPolicyRepository.setVibrationEnabled(!current)
        }
    }

    private fun renderPolicy(policy: CallPolicy) {
        val limitText = formatSeconds(policy.maxDurationSeconds)
        tvLabelLimit.text = "Initial Limit (before presence check): $limitText"

        val snoozeText = formatSeconds(policy.snoozeDurationSeconds)
        tvLabelSnooze.text = "Snooze Interval (per approval): $snoozeText"

        btnToggleWindow.text = "Wait Window: ${policy.confirmationWindowSeconds}s"
        btnToggleVibrate.text = "Vibration: " + if (policy.vibrationEnabled) "ON" else "OFF"

        // Highlight selected buttons
        updateButtonHighlight(btnLimit30s, policy.maxDurationSeconds == 30L)
        updateButtonHighlight(btnLimit1m, policy.maxDurationSeconds == 60L)
        updateButtonHighlight(btnLimit2m, policy.maxDurationSeconds == 120L)
        updateButtonHighlight(btnLimit5m, policy.maxDurationSeconds == 300L)
        updateButtonHighlight(btnLimit10m, policy.maxDurationSeconds == 600L)
        updateButtonHighlight(btnLimit30m, policy.maxDurationSeconds == 1800L)

        updateButtonHighlight(btnSnooze30s, policy.snoozeDurationSeconds == 30L)
        updateButtonHighlight(btnSnooze1m, policy.snoozeDurationSeconds == 60L)
        updateButtonHighlight(btnSnooze2m, policy.snoozeDurationSeconds == 120L)
        updateButtonHighlight(btnSnooze5m, policy.snoozeDurationSeconds == 300L)
        updateButtonHighlight(btnSnooze10m, policy.snoozeDurationSeconds == 600L)
    }

    private fun updateButtonHighlight(btn: Button, isSelected: Boolean) {
        btn.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isSelected) android.R.color.holo_blue_dark else android.R.color.darker_gray,
        )
    }

    private fun formatSeconds(seconds: Long): String {
        return if (seconds >= 60) {
            if (seconds % 60 == 0L) "${seconds / 60}m" else "${seconds / 60}m ${seconds % 60}s"
        } else {
            "${seconds}s"
        }
    }

    // ---- Role & System Settings -----------------------------------------------------------

    private fun requestDialerRole() {
        val intent = dialerRole.createRequestIntent()
        if (intent == null) {
            CallStateLogger.log("ROLE", "ROLE_DIALER is not available on this device")
            Toast.makeText(this, "ROLE_DIALER not available", Toast.LENGTH_LONG).show()
            return
        }
        CallStateLogger.log("ROLE", "Launching ROLE_DIALER request")
        roleLauncher.launch(intent)
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderRole() {
        val held = dialerRole.isDialerRoleHeld()
        tvRole.text = "ROLE_DIALER: " + when {
            held -> "HELD (OK)"
            dialerRole.isRoleAvailable() -> "NOT HELD - tap button below"
            else -> "NOT AVAILABLE on this device"
        }
        btnRole.isEnabled = !held
    }

    private fun renderCallState(state: StoreState) {
        val primary = state.primary
        if (primary != null) {
            bannerActiveCall.visibility = View.VISIBLE
            val phone = primary.phoneNumber ?: "Cellular Call"
            val status = when (state.session.state) {
                SessionState.WAITING_FOR_CONFIRMATION -> "⚠️ LIMIT REACHED - Confirm Presence"
                SessionState.SNOOZED -> "Snoozed"
                else -> CallStateNames.state(primary.state)
            }
            tvActiveCallBanner.text = "🟢 $phone • $status (Tap to open)"
        } else {
            bannerActiveCall.visibility = View.GONE
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // ---- Report & Diagnostics -------------------------------------------------------------

    private fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun buildReport(): String = buildString {
        appendLine("CallGuard Phone Report")
        appendLine("Device: ${deviceSummary()}")
        appendLine("ROLE_DIALER held: ${dialerRole.isDialerRoleHeld()}")
        val policy = CallPolicyRepository.getPolicy()
        appendLine("Policy: Limit=${policy.maxDurationSeconds}s Snooze=${policy.snoozeDurationSeconds}s Window=${policy.confirmationWindowSeconds}s")
        appendLine("Notes: ${etNotes.text}")
        appendLine("--- log ---")
        append(CallStateLogger.asText())
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CallGuard report", buildReport()))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, buildReport())
        startActivity(Intent.createChooser(send, "Share CallGuard report"))
    }
}

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
import android.os.SystemClock
import android.telecom.Call
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Minimal POC screen: role status, place call, active call controls, test result, log export. */
class MainActivity : AppCompatActivity() {

    private lateinit var dialerRole: DialerRoleManager

    private lateinit var tvRole: TextView
    private lateinit var btnRole: Button
    private lateinit var etNumber: EditText
    private lateinit var btnCall: Button
    private lateinit var tvCallState: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var btnAnswer: Button
    private lateinit var btnReject: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvResults: TextView
    private lateinit var btnRemoteYes: Button
    private lateinit var btnRemoteNo: Button
    private lateinit var etNotes: EditText
    private lateinit var tvDevice: TextView
    private lateinit var tvLog: TextView

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
        // So the screen can appear over the lock screen for an incoming call.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_main)

        dialerRole = DialerRoleManager(this)
        bindViews()
        setupClicks()
        handleDialIntent(intent)
        requestRuntimePermissions()

        tvDevice.text = deviceSummary()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ActiveCallStore.state.collect { render(it) } }
                launch {
                    CallStateLogger.lines.collect { tvLog.text = it.takeLast(80).joinToString("\n") }
                }
                launch {
                    while (true) {
                        renderElapsed(ActiveCallStore.state.value)
                        delay(1000)
                    }
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
        tvRole = findViewById(R.id.tvRole)
        btnRole = findViewById(R.id.btnRole)
        etNumber = findViewById(R.id.etNumber)
        btnCall = findViewById(R.id.btnCall)
        tvCallState = findViewById(R.id.tvCallState)
        tvElapsed = findViewById(R.id.tvElapsed)
        btnAnswer = findViewById(R.id.btnAnswer)
        btnReject = findViewById(R.id.btnReject)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvResults = findViewById(R.id.tvResults)
        btnRemoteYes = findViewById(R.id.btnRemoteYes)
        btnRemoteNo = findViewById(R.id.btnRemoteNo)
        etNotes = findViewById(R.id.etNotes)
        tvDevice = findViewById(R.id.tvDevice)
        tvLog = findViewById(R.id.tvLog)
    }

    private fun setupClicks() {
        btnRole.setOnClickListener { requestDialerRole() }
        btnCall.setOnClickListener { placeCall() }
        btnAnswer.setOnClickListener { ActiveCallStore.answer() }
        btnReject.setOnClickListener { ActiveCallStore.reject() }
        btnDisconnect.setOnClickListener { ActiveCallStore.disconnectTest() }
        btnRemoteYes.setOnClickListener { ActiveCallStore.setRemoteResult(true) }
        btnRemoteNo.setOnClickListener { ActiveCallStore.setRemoteResult(false) }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareReport() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { CallStateLogger.clear() }
    }

    // ---- Role -----------------------------------------------------------------------------

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

    private fun renderRole() {
        val held = dialerRole.isDialerRoleHeld()
        tvRole.text = "ROLE_DIALER: " + when {
            held -> "HELD (OK)"
            dialerRole.isRoleAvailable() -> "NOT HELD - tap the button below"
            else -> "NOT AVAILABLE on this device"
        }
        btnRole.isEnabled = !held
    }

    // ---- Dialing --------------------------------------------------------------------------

    private fun handleDialIntent(intent: Intent?) {
        val i = intent ?: return
        if (i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW) {
            val number = i.data?.schemeSpecificPart
            if (!number.isNullOrBlank()) {
                etNumber.setText(number)
                CallStateLogger.log("DIAL", "ACTION_DIAL received (number prefilled, not logged)")
            }
        }
    }

    private fun placeCall() {
        val number = etNumber.text.toString().trim()
        if (number.isEmpty()) {
            Toast.makeText(this, "Enter a number first", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestRuntimePermissions()
            Toast.makeText(this, "Grant the phone permission, then tap again", Toast.LENGTH_LONG).show()
            return
        }
        try {
            CallStateLogger.log("DIAL", "TelecomManager.placeCall()")
            getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: SecurityException) {
            CallStateLogger.log("DIAL", "placeCall failed: ${e.message}")
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // ---- Rendering ------------------------------------------------------------------------

    private fun render(state: StoreState) {
        val primary = state.primary
        if (primary == null) {
            tvCallState.text = "No call"
        } else {
            tvCallState.text = "Call #${primary.id}  " +
                "${CallStateNames.direction(primary.direction)}  " +
                CallStateNames.state(primary.state) +
                if (state.calls.size > 1) "  (+${state.calls.size - 1} more)" else ""
        }
        val ringing = primary?.state == Call.STATE_RINGING
        btnAnswer.isEnabled = ringing
        btnReject.isEnabled = ringing
        btnDisconnect.isEnabled = primary != null && !ringing

        tvResults.text = if (state.records.isEmpty()) {
            "No disconnect tests yet."
        } else {
            state.records.mapIndexed { i, r ->
                val telecom = when (r.outcome) {
                    DisconnectOutcome.PENDING -> "waiting..."
                    DisconnectOutcome.TELECOM_CONFIRMED ->
                        "confirmed in ${(r.endedAtElapsedMs ?: r.requestedAtElapsedMs) - r.requestedAtElapsedMs} ms"
                    DisconnectOutcome.NO_CONFIRMATION -> "NOT confirmed"
                }
                val remote = when (r.remoteConfirmed) {
                    null -> "not answered"
                    true -> "yes, ended"
                    false -> "NO, still connected"
                }
                "Test ${i + 1} (${CallStateNames.direction(r.direction)}): Telecom $telecom; " +
                    "remote phone: $remote  =>  ${verdictOf(r)}"
            }.joinToString("\n")
        }
        val hasRecord = state.records.isNotEmpty()
        btnRemoteYes.isEnabled = hasRecord
        btnRemoteNo.isEnabled = hasRecord
        renderElapsed(state)
    }

    private fun renderElapsed(state: StoreState) {
        val since = state.primary?.activeSinceElapsedMs
        if (since == null) {
            tvElapsed.text = "Elapsed: --:--"
            return
        }
        val seconds = ((SystemClock.elapsedRealtime() - since) / 1000).coerceAtLeast(0)
        tvElapsed.text = "Elapsed: %02d:%02d".format(seconds / 60, seconds % 60)
    }

    // ---- Report ---------------------------------------------------------------------------

    private fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) | Build ${Build.DISPLAY}"

    private fun buildReport(): String = buildString {
        appendLine("CallGuard POC report")
        appendLine("Device: ${deviceSummary()}")
        appendLine("ROLE_DIALER held: ${dialerRole.isDialerRoleHeld()}")
        appendLine("Notes: ${etNotes.text}")
        appendLine("--- disconnect tests ---")
        appendLine(tvResults.text)
        appendLine("--- log ---")
        append(CallStateLogger.asText())
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CallGuard POC report", buildReport()))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, buildReport())
        startActivity(Intent.createChooser(send, "Share POC report"))
    }
}

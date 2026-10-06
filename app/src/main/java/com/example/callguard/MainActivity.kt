package com.example.callguard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Main Default Dialer Screen:
 * - Default launch view: Clean Keypad and dialed number display.
 * - Bottom Navigation: "Home" (Call History with timestamps & duration) and "Keypad" (Dialer).
 * - Overflow Menu (⋮): Dedicated Settings, Set as Default Phone, Diagnostics, Clear History.
 * - Handles Intent.ACTION_DIAL and public TelecomManager call placement.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dialerRole: DialerRoleManager

    // Top Bar & Active Call Banner
    private lateinit var btnMenu: ImageButton
    private lateinit var bannerActiveCall: LinearLayout
    private lateinit var tvActiveCallBanner: TextView

    // View Containers (Tabs)
    private lateinit var layoutKeypadView: ScrollView
    private lateinit var layoutHomeView: LinearLayout

    // Bottom Navigation
    private lateinit var tabHome: LinearLayout
    private lateinit var ivTabHome: ImageView
    private lateinit var tvTabHome: TextView
    private lateinit var tabKeypad: LinearLayout
    private lateinit var ivTabKeypad: ImageView
    private lateinit var tvTabKeypad: TextView

    // Dialpad Views
    private lateinit var etDialpadNumber: EditText
    private lateinit var btnBackspace: ImageButton
    private lateinit var btnPlaceCall: ImageButton

    // Call History Views
    private lateinit var layoutEmptyHistory: LinearLayout
    private lateinit var rvCallHistory: RecyclerView
    private lateinit var historyAdapter: CallHistoryAdapter

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            CallStateLogger.log(
                "ROLE",
                "Role request finished resultCode=${result.resultCode} held=${dialerRole.isDialerRoleHeld()}",
            )
            val held = dialerRole.isDialerRoleHeld()
            Toast.makeText(
                this,
                if (held) "CallGuard is now your default phone app!" else "ROLE_DIALER not granted",
                Toast.LENGTH_SHORT,
            ).show()
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
        CallHistoryRepository.init(this)
        CallVibrator.init(this)

        setContentView(R.layout.activity_main)

        dialerRole = DialerRoleManager(this)

        bindViews()
        setupBottomNav()
        setupDialpad()
        setupHistoryList()
        setupMenu()
        setupActiveCallBanner()
        handleDialIntent(intent)
        requestRuntimePermissions()

        // Default to Keypad on app launch
        showTab(NavigationTab.KEYPAD)

        // Observe reactive flows
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ActiveCallStore.state.collect { renderCallState(it) }
                }
                launch {
                    CallHistoryRepository.historyFlow.collect { records ->
                        historyAdapter.submitList(records)
                        val isEmpty = records.isEmpty()
                        layoutEmptyHistory.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        rvCallHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
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

    private fun bindViews() {
        btnMenu = findViewById(R.id.btnMenu)
        bannerActiveCall = findViewById(R.id.bannerActiveCall)
        tvActiveCallBanner = findViewById(R.id.tvActiveCallBanner)

        layoutKeypadView = findViewById(R.id.layoutKeypadView)
        layoutHomeView = findViewById(R.id.layoutHomeView)

        tabHome = findViewById(R.id.tabHome)
        ivTabHome = findViewById(R.id.ivTabHome)
        tvTabHome = findViewById(R.id.tvTabHome)

        tabKeypad = findViewById(R.id.tabKeypad)
        ivTabKeypad = findViewById(R.id.ivTabKeypad)
        tvTabKeypad = findViewById(R.id.tvTabKeypad)

        etDialpadNumber = findViewById(R.id.etDialpadNumber)
        btnBackspace = findViewById(R.id.btnBackspace)
        btnPlaceCall = findViewById(R.id.btnPlaceCall)

        layoutEmptyHistory = findViewById(R.id.layoutEmptyHistory)
        rvCallHistory = findViewById(R.id.rvCallHistory)
    }

    private enum class NavigationTab {
        HOME, KEYPAD
    }

    private fun setupBottomNav() {
        tabHome.setOnClickListener { showTab(NavigationTab.HOME) }
        tabKeypad.setOnClickListener { showTab(NavigationTab.KEYPAD) }
    }

    private fun showTab(tab: NavigationTab) {
        val activeColor = Color.parseColor("#58A6FF")
        val inactiveColor = Color.parseColor("#8B949E")

        when (tab) {
            NavigationTab.HOME -> {
                layoutHomeView.visibility = View.VISIBLE
                layoutKeypadView.visibility = View.GONE

                ivTabHome.setColorFilter(activeColor)
                tvTabHome.setTextColor(activeColor)

                ivTabKeypad.setColorFilter(inactiveColor)
                tvTabKeypad.setTextColor(inactiveColor)
            }
            NavigationTab.KEYPAD -> {
                layoutKeypadView.visibility = View.VISIBLE
                layoutHomeView.visibility = View.GONE

                ivTabKeypad.setColorFilter(activeColor)
                tvTabKeypad.setTextColor(activeColor)

                ivTabHome.setColorFilter(inactiveColor)
                tvTabHome.setTextColor(inactiveColor)
            }
        }
    }

    private fun setupHistoryList() {
        historyAdapter = CallHistoryAdapter { phoneNumber ->
            // Pre-fill dialed number and switch to Keypad
            etDialpadNumber.setText(phoneNumber)
            etDialpadNumber.setSelection(phoneNumber.length)
            showTab(NavigationTab.KEYPAD)
        }
        rvCallHistory.layoutManager = LinearLayoutManager(this)
        rvCallHistory.adapter = historyAdapter
    }

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
                showTab(NavigationTab.KEYPAD)
                CallStateLogger.log("DIAL", "ACTION_DIAL received with number pre-filled: $number")
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
            CallStateLogger.log("DIAL", "Placing call via TelecomManager to $number...")
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

    private fun setupActiveCallBanner() {
        bannerActiveCall.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
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

    // ---- Overflow Menu (⋮) ----------------------------------------------------------------

    private fun setupMenu() {
        btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_settings -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    R.id.menu_default_phone -> {
                        handleDefaultPhoneMenu()
                        true
                    }
                    R.id.menu_diagnostics -> {
                        showDiagnosticsDialog()
                        true
                    }
                    R.id.menu_clear_history -> {
                        showClearHistoryDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun handleDefaultPhoneMenu() {
        if (dialerRole.isDialerRoleHeld()) {
            Toast.makeText(this, "CallGuard is already the default phone app!", Toast.LENGTH_SHORT).show()
        } else {
            val intent = dialerRole.createRequestIntent()
            if (intent != null) {
                roleLauncher.launch(intent)
            } else {
                Toast.makeText(this, "ROLE_DIALER not available on this device", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDiagnosticsDialog() {
        val held = dialerRole.isDialerRoleHeld()
        val policy = CallPolicyRepository.getPolicy()
        val report = buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("ROLE_DIALER: ${if (held) "HELD (OK)" else "NOT HELD"}")
            appendLine("Policy: Limit=${policy.maxDurationSeconds}s (${policy.maxDurationSeconds / 60}m) | Snooze=${policy.snoozeDurationSeconds}s | Window=${policy.confirmationWindowSeconds}s | Vibrate=${policy.vibrationEnabled}")
            appendLine("--- Recent Logs ---")
            append(CallStateLogger.lines.value.takeLast(25).joinToString("\n"))
        }

        AlertDialog.Builder(this)
            .setTitle("Diagnostics & Logs")
            .setMessage(report)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CallGuard Diagnostics", report))
                Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, "Share Diagnostics"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Call History")
            .setMessage("Are you sure you want to delete all call history records?")
            .setPositiveButton("Clear") { _, _ ->
                CallHistoryRepository.clear()
                Toast.makeText(this, "Call history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
}

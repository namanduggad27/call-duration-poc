package com.example.callguard

import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Incoming and Ongoing Call Screen:
 * - Shows incoming call UI with Answer / Decline buttons over the lock screen.
 * - Shows active call UI with duration timer, live call limit status, and End Call button.
 * - Shows urgent Presence Confirmation alert when threshold is reached with Snooze & Auto-disconnect.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var tvCallStatusChip: TextView
    private lateinit var tvDirection: TextView
    private lateinit var tvCallerNumber: TextView
    private lateinit var tvCallDuration: TextView

    private lateinit var cardPresenceAlert: LinearLayout
    private lateinit var tvPresenceCountdown: TextView
    private lateinit var btnSnooze: Button
    private lateinit var btnEndFromAlert: Button

    private lateinit var cardNextCheck: LinearLayout
    private lateinit var tvNextCheckPolicy: TextView
    private lateinit var tvNextCheckCountdown: TextView

    private lateinit var layoutIncomingActions: LinearLayout
    private lateinit var btnIncomingAnswer: ImageButton
    private lateinit var btnIncomingReject: ImageButton

    private lateinit var layoutOngoingActions: LinearLayout
    private lateinit var btnOngoingEndCall: ImageButton

    private lateinit var tvTogglePocSection: TextView
    private lateinit var layoutPocSection: LinearLayout
    private lateinit var btnPocDisconnect: Button
    private lateinit var tvPocResult: TextView
    private lateinit var btnPocRemoteYes: Button
    private lateinit var btnPocRemoteNo: Button

    private var pocSectionExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_call)

        bindViews()
        setupClicks()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ActiveCallStore.state.collect { render(it) }
                }
                launch {
                    while (true) {
                        renderTimers(ActiveCallStore.state.value)
                        delay(1000L)
                    }
                }
            }
        }
    }

    private fun bindViews() {
        tvCallStatusChip = findViewById(R.id.tvCallStatusChip)
        tvDirection = findViewById(R.id.tvDirection)
        tvCallerNumber = findViewById(R.id.tvCallerNumber)
        tvCallDuration = findViewById(R.id.tvCallDuration)

        cardPresenceAlert = findViewById(R.id.cardPresenceAlert)
        tvPresenceCountdown = findViewById(R.id.tvPresenceCountdown)
        btnSnooze = findViewById(R.id.btnSnooze)
        btnEndFromAlert = findViewById(R.id.btnEndFromAlert)

        cardNextCheck = findViewById(R.id.cardNextCheck)
        tvNextCheckPolicy = findViewById(R.id.tvNextCheckPolicy)
        tvNextCheckCountdown = findViewById(R.id.tvNextCheckCountdown)

        layoutIncomingActions = findViewById(R.id.layoutIncomingActions)
        btnIncomingAnswer = findViewById(R.id.btnIncomingAnswer)
        btnIncomingReject = findViewById(R.id.btnIncomingReject)

        layoutOngoingActions = findViewById(R.id.layoutOngoingActions)
        btnOngoingEndCall = findViewById(R.id.btnOngoingEndCall)

        tvTogglePocSection = findViewById(R.id.tvTogglePocSection)
        layoutPocSection = findViewById(R.id.layoutPocSection)
        btnPocDisconnect = findViewById(R.id.btnPocDisconnect)
        tvPocResult = findViewById(R.id.tvPocResult)
        btnPocRemoteYes = findViewById(R.id.btnPocRemoteYes)
        btnPocRemoteNo = findViewById(R.id.btnPocRemoteNo)
    }

    private fun setupClicks() {
        btnIncomingAnswer.setOnClickListener {
            ActiveCallStore.answer()
        }
        btnIncomingReject.setOnClickListener {
            ActiveCallStore.reject()
        }
        btnOngoingEndCall.setOnClickListener {
            ActiveCallStore.disconnectTest()
        }
        btnSnooze.setOnClickListener {
            ActiveCallStore.snooze()
        }
        btnEndFromAlert.setOnClickListener {
            ActiveCallStore.disconnectTest()
        }

        tvTogglePocSection.setOnClickListener {
            pocSectionExpanded = !pocSectionExpanded
            layoutPocSection.visibility = if (pocSectionExpanded) View.VISIBLE else View.GONE
            tvTogglePocSection.text = if (pocSectionExpanded) "POC Verification Controls ▲" else "POC Verification Controls ▼"
        }

        btnPocDisconnect.setOnClickListener {
            ActiveCallStore.disconnectTest()
        }
        btnPocRemoteYes.setOnClickListener {
            ActiveCallStore.setRemoteResult(true)
        }
        btnPocRemoteNo.setOnClickListener {
            ActiveCallStore.setRemoteResult(false)
        }
    }

    private fun render(storeState: StoreState) {
        val primary = storeState.primary
        if (primary == null) {
            tvCallStatusChip.text = "CALL ENDED"
            tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
            tvDirection.text = "Call dropped or ended"
            layoutIncomingActions.visibility = View.GONE
            layoutOngoingActions.visibility = View.GONE
            cardPresenceAlert.visibility = View.GONE
            cardNextCheck.visibility = View.GONE
            lifecycleScope.launch {
                delay(1500L)
                if (ActiveCallStore.state.value.primary == null) {
                    finish()
                }
            }
            return
        }

        tvCallerNumber.text = primary.phoneNumber ?: "Cellular Call"
        tvDirection.text = "${CallStateNames.direction(primary.direction)} • Cellular"

        val ringing = primary.state == Call.STATE_RINGING
        if (ringing) {
            tvCallStatusChip.text = "INCOMING CALL"
            tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
            layoutIncomingActions.visibility = View.VISIBLE
            layoutOngoingActions.visibility = View.GONE
            cardPresenceAlert.visibility = View.GONE
            cardNextCheck.visibility = View.GONE
        } else {
            layoutIncomingActions.visibility = View.GONE
            layoutOngoingActions.visibility = View.VISIBLE

            val session = storeState.session
            when (session.state) {
                SessionState.WAITING_FOR_CONFIRMATION -> {
                    tvCallStatusChip.text = "⚠️ CALL LIMIT REACHED"
                    tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip_selected)
                    cardPresenceAlert.visibility = View.VISIBLE
                    cardNextCheck.visibility = View.GONE

                    val policy = CallPolicyRepository.getPolicy()
                    val snoozeText = if (policy.snoozeDurationSeconds >= 60) {
                        "${policy.snoozeDurationSeconds / 60}m"
                    } else {
                        "${policy.snoozeDurationSeconds}s"
                    }
                    btnSnooze.text = "I'm Here (Snooze +$snoozeText)"
                    tvPresenceCountdown.text = "Auto-disconnect in ${session.confirmationRemainingSeconds}s"
                }
                SessionState.SNOOZED -> {
                    tvCallStatusChip.text = "SNOOZED (CHECK #${session.snoozeCount})"
                    tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
                    cardPresenceAlert.visibility = View.GONE
                    cardNextCheck.visibility = View.VISIBLE
                }
                else -> {
                    tvCallStatusChip.text = CallStateNames.state(primary.state)
                    tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
                    cardPresenceAlert.visibility = View.GONE
                    cardNextCheck.visibility = if (primary.state == Call.STATE_ACTIVE) View.VISIBLE else View.GONE
                }
            }
        }

        renderPocResults(storeState)
    }

    private fun renderTimers(storeState: StoreState) {
        val primary = storeState.primary ?: return
        val since = primary.activeSinceElapsedMs
        if (since != null) {
            val elapsedSec = ((SystemClock.elapsedRealtime() - since) / 1000L).coerceAtLeast(0)
            tvCallDuration.text = "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60)
        } else {
            tvCallDuration.text = "00:00"
        }

        val policy = CallPolicyRepository.getPolicy()
        val limitStr = formatSeconds(policy.maxDurationSeconds)
        val snoozeStr = formatSeconds(policy.snoozeDurationSeconds)
        tvNextCheckPolicy.text = "Limit: $limitStr | Snooze: $snoozeStr"

        val remainSec = storeState.session.secondsUntilNextCheck
        tvNextCheckCountdown.text = "Next presence check in: %02d:%02d".format(remainSec / 60, remainSec % 60)
    }

    private fun renderPocResults(storeState: StoreState) {
        val records = storeState.records
        if (records.isEmpty()) {
            tvPocResult.text = "No disconnect test run yet."
            btnPocRemoteYes.isEnabled = false
            btnPocRemoteNo.isEnabled = false
        } else {
            val last = records.last()
            val telecom = when (last.outcome) {
                DisconnectOutcome.PENDING -> "waiting..."
                DisconnectOutcome.TELECOM_CONFIRMED ->
                    "confirmed in ${(last.endedAtElapsedMs ?: last.requestedAtElapsedMs) - last.requestedAtElapsedMs} ms"
                DisconnectOutcome.NO_CONFIRMATION -> "NOT confirmed"
            }
            val remote = when (last.remoteConfirmed) {
                null -> "awaiting check"
                true -> "yes, ended"
                false -> "NO, still on"
            }
            tvPocResult.text = "Latest Test: Telecom $telecom | Remote: $remote => ${verdictOf(last)}"
            btnPocRemoteYes.isEnabled = true
            btnPocRemoteNo.isEnabled = true
        }
    }

    private fun formatSeconds(seconds: Long): String {
        return if (seconds >= 60) {
            if (seconds % 60 == 0L) "${seconds / 60}m" else "${seconds / 60}m ${seconds % 60}s"
        } else {
            "${seconds}s"
        }
    }
}

package com.nextnotif.app

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class RelayCallActivity : ComponentActivity() {
    private var pendingAnswerCode by mutableStateOf<String?>(null)
    private var microphoneDenied by mutableStateOf(false)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val code = pendingAnswerCode
        pendingAnswerCode = null
        microphoneDenied = !granted
        if (granted && code != null) requestAnswer(code)
    }

    private fun requestAnswer(code: String) {
        val call = AppState.callRelay.value
        val pairing = SessionStore.load(this).pairings.firstOrNull { it.code == code } ?: return
        if (pairing.role != Role.RECEIVER || !pairing.enabled ||
            !(pairing.isWs || pairing.isFcmOnDemand) ||
            !RelayCallUiPolicy.canRequestAnswer(call.phase, call.code, code)) return
        val offeredAt = if (call.code == code) call.offeredAt
            else intent.getLongExtra(EXTRA_OFFERED_AT, 0L).takeIf { it > 0L }
        if (!CallEventFreshness.permitsInteraction(offeredAt, System.currentTimeMillis())) {
            if (call.code == code) AppState.finishCall(code, getString(R.string.call_offer_expired))
            AppState.setError(getString(R.string.call_offer_expired))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (pendingAnswerCode != null) return
            pendingAnswerCode = code
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        microphoneDenied = false
        RelayForegroundService.Controller.answerCall(this, code,
            call.number ?: intent.getStringExtra(EXTRA_NUMBER),
            call.name ?: intent.getStringExtra(EXTRA_NAME), offeredAt)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_microphone_answer", pendingAnswerCode)
        outState.putBoolean("microphone_denied", microphoneDenied)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ANSWER = "answer"
        private const val EXTRA_OFFERED_AT = "offered_at"

        fun createIntent(
            context: Context,
            code: String,
            number: String?,
            name: String?,
            answer: Boolean,
            offeredAt: Long? = null,
        ) = Intent(context, RelayCallActivity::class.java).apply {
            putExtra(EXTRA_CODE, code)
            putExtra(EXTRA_NUMBER, number)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_ANSWER, answer)
            offeredAt?.let { putExtra(EXTRA_OFFERED_AT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IncomingCallOfferStore.restore(this)
        pendingAnswerCode = savedInstanceState?.getString("pending_microphone_answer")
        microphoneDenied = savedInstanceState?.getBoolean("microphone_denied") ?: false
        val code = intent.getStringExtra(EXTRA_CODE) ?: run {
            finish()
            return
        }
        // Opening a notification is not evidence of a new incoming call. Keep the
        // current live identity; only explicit Answer may recover an idle process.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ANSWER, false)) {
            requestAnswer(code)
        }

        enableEdgeToEdge()
        setContent {
            NextNotifTheme {
                val call by AppState.callRelay.collectAsState()
                RelayCallScreen(
                    call = call,
                    canAnswer = pendingAnswerCode == null && SessionStore.load(this).pairings.firstOrNull { it.code == call.code }?.let {
                        RelayCallUiPolicy.canAnswer(call.phase, it.role, it.enabled)
                    } == true,
                    onAnswer = { call.code?.let { current ->
                        requestAnswer(current)
                    } },
                    microphoneDenied = microphoneDenied,
                    onOpenSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"))) },
                    onHangUp = { call.code?.let { RelayForegroundService.Controller.endCall(this, it) } },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) {
            intent.getStringExtra(EXTRA_CODE)?.let { code ->
                requestAnswer(code)
            }
        }
        // Compose observes current call state directly; do not recreate and lose
        // a microphone request when Android reuses this activity.
    }
}

@Composable
private fun RelayCallScreen(
    call: AppState.CallRelayState,
    canAnswer: Boolean,
    microphoneDenied: Boolean,
    onOpenSettings: () -> Unit,
    onAnswer: () -> Unit,
    onHangUp: () -> Unit,
    onClose: () -> Unit,
) {
    val ended = RelayCallUiPolicy.finished(call.phase)
    val status = when (call.phase) {
        AppState.CallPhase.RINGING -> stringResource(R.string.call_incoming)
        AppState.CallPhase.ANSWERING -> stringResource(R.string.call_answering)
        AppState.CallPhase.CONNECTING -> stringResource(R.string.call_connecting)
        AppState.CallPhase.ACTIVE -> stringResource(R.string.call_connected)
        AppState.CallPhase.RECONNECTING -> stringResource(R.string.call_reconnecting)
        AppState.CallPhase.ENDED, AppState.CallPhase.IDLE -> stringResource(R.string.call_ended)
        AppState.CallPhase.FAILED -> call.detail ?: stringResource(R.string.call_failed)
    }
    val statusColor = when (call.phase) {
        AppState.CallPhase.ACTIVE -> Color(0xFF16A34A)
        AppState.CallPhase.RECONNECTING, AppState.CallPhase.CONNECTING, AppState.CallPhase.ANSWERING -> Color(0xFFF59E0B)
        AppState.CallPhase.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase, call.connectedAt) {
        while (call.phase == AppState.CallPhase.ACTIVE || call.phase == AppState.CallPhase.RECONNECTING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = call.connectedAt?.let { ((now - it) / 1_000).coerceAtLeast(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = call.callerLabel.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = call.callerLabel,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            call.number?.takeIf { it != "unknown" && it != call.callerLabel }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                color = statusColor,
                textAlign = TextAlign.Center,
            )
            if (elapsed != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "%02d:%02d".format(elapsed / 60, elapsed % 60),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ended) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.call_keep_open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (microphoneDenied && call.phase == AppState.CallPhase.RINGING) {
                Text(stringResource(R.string.call_microphone_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.call_open_settings))
                }
            }
            if (canAnswer) Button(
                onClick = onAnswer,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.call_answer))
            }
            if (call.phase == AppState.CallPhase.RINGING) {
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(R.string.call_close))
                }
            } else Button(
                onClick = if (ended) onClose else onHangUp,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (ended) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(if (ended) stringResource(R.string.call_close) else stringResource(R.string.call_hang_up))
            }
        }
    }
}

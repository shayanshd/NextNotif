package com.nextnotif.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DiagnosticCallState { IDLE, RINGING, ACTIVE, UNKNOWN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var callState by remember { mutableStateOf(readCallState(context.getSystemService(TelephonyManager::class.java))) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var runningSource by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<AudioProbeResult>>(emptyList()) }
    var reportCallState by remember { mutableStateOf<DiagnosticCallState?>(null) }
    var reportTimestamp by remember { mutableStateOf<Long?>(null) }
    var copied by remember { mutableStateOf(false) }
    var controlledProgress by remember { mutableStateOf<ControlledProbeProgress?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    LaunchedEffect(Unit) {
        while (true) {
            callState = readCallState(context.getSystemService(TelephonyManager::class.java))
            delay(1_000)
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    val answerGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
        PackageManager.PERMISSION_GRANTED
    val busy = runningSource != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.diag_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.diag_intro_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                stringResource(R.string.diag_intro_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
            }

            item {
                DiagnosticSectionTitle(stringResource(R.string.diag_device))
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Build.MANUFACTURER} ${Build.MODEL}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · patch ${Build.VERSION.SECURITY_PATCH.ifBlank { "unknown" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                DiagnosticSectionTitle(stringResource(R.string.diag_permissions))
                Spacer(Modifier.height(8.dp))
                CapabilityRow(
                    label = stringResource(R.string.diag_call_state),
                    value = callState.label(),
                    positive = callState == DiagnosticCallState.ACTIVE,
                )
                CapabilityRow(
                    label = stringResource(R.string.diag_answer_permission),
                    value = permissionLabel(answerGranted),
                    positive = answerGranted,
                )
                CapabilityRow(
                    label = stringResource(R.string.diag_microphone_permission),
                    value = permissionLabel(micGranted),
                    positive = micGranted,
                )
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                DiagnosticSectionTitle(stringResource(R.string.diag_instructions_title))
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.diag_instructions_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (!micGranted) {
                    Button(
                        onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(stringResource(R.string.diag_request_microphone))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val stateAtStart = callState
                                scope.launch {
                                    results = emptyList()
                                    controlledProgress = null
                                    val completed = GatewayAudioProbe.run { runningSource = it }
                                    results = completed
                                    reportCallState = stateAtStart
                                    reportTimestamp = System.currentTimeMillis()
                                    runningSource = null
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Text(
                                if (busy && controlledProgress == null) {
                                    stringResource(R.string.diag_running, runningSource ?: "audio")
                                } else {
                                    stringResource(R.string.diag_run)
                                },
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                val stateAtStart = callState
                                scope.launch {
                                    results = emptyList()
                                    runningSource = "MIC CONTROLLED"
                                    val completed = GatewayAudioProbe.runControlledMic { progress ->
                                        scope.launch { controlledProgress = progress }
                                    }
                                    results = listOf(completed)
                                    reportCallState = stateAtStart
                                    reportTimestamp = System.currentTimeMillis()
                                    controlledProgress = null
                                    runningSource = null
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            val progress = controlledProgress
                            Text(
                                when {
                                    progress?.expectedSpeech == true -> stringResource(
                                        R.string.diag_speak_phase,
                                        progress.phase,
                                        progress.secondsRemaining,
                                    )
                                    progress != null -> stringResource(
                                        R.string.diag_quiet_phase,
                                        progress.phase,
                                        progress.secondsRemaining,
                                    )
                                    else -> stringResource(R.string.diag_run_controlled)
                                },
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                DiagnosticSectionTitle(stringResource(R.string.diag_results))
                if (results.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.diag_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(results, key = { it.source }) { result ->
                ProbeResultRow(result)
            }

            if (results.isNotEmpty()) {
                item {
                    val report = buildGatewayReport(
                        results = results,
                        callState = reportCallState ?: DiagnosticCallState.UNKNOWN,
                        timestamp = reportTimestamp ?: System.currentTimeMillis(),
                        micGranted = micGranted,
                        answerGranted = answerGranted,
                    )
                    FilledTonalButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(report))
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (copied) stringResource(R.string.diag_report_copied)
                            else stringResource(R.string.diag_copy_report),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.diag_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CapabilityRow(label: String, value: String, positive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        DiagnosticStatusBadge(value, positive)
    }
}

@Composable
private fun DiagnosticStatusBadge(text: String, positive: Boolean) {
    val container = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

@Composable
private fun ProbeResultRow(result: AudioProbeResult) {
    val (label, color, icon) = when (result.status) {
        ProbeStatus.SIGNAL -> Triple(stringResource(R.string.diag_status_signal), MaterialTheme.colorScheme.primary, Icons.Default.Check)
        ProbeStatus.SILENT -> Triple(stringResource(R.string.diag_status_silent), MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Info)
        ProbeStatus.BLOCKED -> Triple(stringResource(R.string.diag_status_blocked), MaterialTheme.colorScheme.tertiary, Icons.Default.Warning)
        ProbeStatus.FAILED -> Triple(stringResource(R.string.diag_status_failed), MaterialTheme.colorScheme.error, Icons.Default.Warning)
    }
    val detail = result.summary?.let {
        stringResource(R.string.diag_signal_detail, it.dbfs, it.nonZeroPercent, it.sampleCount)
    } ?: result.detail?.let { stringResource(R.string.diag_failed_detail, it) }
        ?: stringResource(R.string.diag_blocked_detail)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(result.source, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        Text(
            detail,
            modifier = Modifier.padding(start = 30.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (result.summary != null) FontFamily.Monospace else null,
        )
        if (result.timeline.isNotEmpty()) {
            TimelineChart(result.timeline)
            compareTimeline(result.timeline)?.let { comparison ->
                Text(
                    stringResource(
                        R.string.diag_timeline_comparison,
                        comparison.speechDbfs,
                        comparison.quietDbfs,
                        comparison.differenceDb,
                    ),
                    modifier = Modifier.padding(start = 30.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(modifier = Modifier.padding(start = 30.dp))
    }
}

@Composable
private fun TimelineChart(slices: List<SignalSlice>) {
    val quietColor = MaterialTheme.colorScheme.surfaceVariant
    val speechColor = MaterialTheme.colorScheme.secondaryContainer
    val barColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.padding(start = 30.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.diag_timeline_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            if (slices.isEmpty()) return@Canvas
            val widthPerSlice = size.width / slices.size
            slices.forEachIndexed { index, slice ->
                val left = index * widthPerSlice
                drawRect(
                    color = if (slice.expectedSpeech) speechColor else quietColor,
                    topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
                    size = androidx.compose.ui.geometry.Size(widthPerSlice + 1f, size.height),
                )
                val normalized = ((slice.dbfs + 80.0) / 80.0).coerceIn(0.02, 1.0).toFloat()
                val barHeight = size.height * normalized
                drawRect(
                    color = barColor,
                    topLeft = androidx.compose.ui.geometry.Offset(left + 1f, size.height - barHeight),
                    size = androidx.compose.ui.geometry.Size((widthPerSlice - 2f).coerceAtLeast(1f), barHeight),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticCallState.label(): String = stringResource(
    when (this) {
        DiagnosticCallState.IDLE -> R.string.diag_call_idle
        DiagnosticCallState.RINGING -> R.string.diag_call_ringing
        DiagnosticCallState.ACTIVE -> R.string.diag_call_active
        DiagnosticCallState.UNKNOWN -> R.string.diag_call_unknown
    },
)

@Composable
private fun permissionLabel(granted: Boolean): String =
    stringResource(if (granted) R.string.diag_granted else R.string.diag_not_granted)

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readCallState(manager: TelephonyManager?): DiagnosticCallState = try {
    when (manager?.callState) {
        TelephonyManager.CALL_STATE_IDLE -> DiagnosticCallState.IDLE
        TelephonyManager.CALL_STATE_RINGING -> DiagnosticCallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> DiagnosticCallState.ACTIVE
        else -> DiagnosticCallState.UNKNOWN
    }
} catch (_: SecurityException) {
    DiagnosticCallState.UNKNOWN
}

private fun buildGatewayReport(
    results: List<AudioProbeResult>,
    callState: DiagnosticCallState,
    timestamp: Long,
    micGranted: Boolean,
    answerGranted: Boolean,
): String = buildString {
    appendLine("NextNotif gateway diagnostics")
    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(timestamp))}")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
    appendLine("Build: Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH}")
    appendLine("Call state at start: ${callState.name}")
    appendLine("Permissions: RECORD_AUDIO=$micGranted, ANSWER_PHONE_CALLS=$answerGranted")
    results.forEach { result ->
        val measurements = result.summary?.let {
            ", %.1f dBFS, %.1f%% non-zero, %d samples".format(
                Locale.US,
                it.dbfs,
                it.nonZeroPercent,
                it.sampleCount,
            )
        } ?: result.detail?.let { ", $it" }.orEmpty()
        appendLine("${result.source}: ${result.status.name}$measurements")
        if (result.timeline.isNotEmpty()) {
            compareTimeline(result.timeline)?.let {
                appendLine(
                    "Controlled comparison: speech=%.1f dBFS, quiet=%.1f dBFS, difference=%+.1f dB".format(
                        Locale.US,
                        it.speechDbfs,
                        it.quietDbfs,
                        it.differenceDb,
                    ),
                )
            }
            appendLine(
                "Timeline (250ms dBFS): " + result.timeline.joinToString(",") { "%.1f".format(Locale.US, it.dbfs) },
            )
        }
    }
    append("Audio was measured in memory and discarded. Signal does not by itself prove caller-audio access.")
}

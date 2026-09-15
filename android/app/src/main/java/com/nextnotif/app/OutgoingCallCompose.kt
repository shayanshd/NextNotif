package com.nextnotif.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewOutgoingCallScreen(pairings: List<PairingInfo>, onBack: () -> Unit,
    initialNumber: String = "", initialOptions: JSONObject? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choices = pairings.filter { it.enabled && it.role == Role.RECEIVER && !it.isFirebase }
    var code by rememberSaveable { mutableStateOf(choices.firstOrNull()?.code) }
    var number by rememberSaveable { mutableStateOf(initialNumber) }
    var subscriptionId by rememberSaveable(code) { mutableStateOf<Int?>(null) }
    var options by remember(code) { mutableStateOf(initialOptions) }
    var loading by remember(code) { mutableStateOf(initialOptions == null) }
    var failed by remember(code) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingMenu by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.code == code }
    val selectedAvailable = remember(options, subscriptionId, loading, failed) {
        val sims = options?.takeIf { it.optString("state") == "ready" }?.optJSONArray("sims")
        if (loading || failed || sims == null || sims.length() == 0) false else {
            val wanted = subscriptionId ?: options?.takeUnless { it.isNull("default_id") }?.optInt("default_id")
            wanted != null && (0 until sims.length()).any { sims.optJSONObject(it)?.optInt("id") == wanted }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) error = context.getString(R.string.outgoing_call_microphone)
    }
    LaunchedEffect(selected?.code) {
        if (initialOptions == null) {
            loading = true
            failed = false
            val result = selected?.let { runCatching { SmsRelay.simOptions(context, it, true) } }
            options = result?.getOrNull()
            failed = selected != null && result?.isFailure == true
            loading = false
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.outgoing_call_title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.conversation_back)) }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { pairingMenu = true }, enabled = choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(selected?.displayName ?: stringResource(R.string.sms_choose_pairing))
                }
                DropdownMenu(pairingMenu, { pairingMenu = false }) {
                    choices.forEach { p -> DropdownMenuItem({ Text(p.displayName) }, { code = p.code; pairingMenu = false }) }
                }
            }
            ContactPhoneField(number, { number = it; error = null }, Modifier.fillMaxWidth(),
                isError = number.isNotEmpty() && smsDestination(number) == null)
            SenderSimSelector(options, subscriptionId, loading, failed, { subscriptionId = it }, {
                selected?.let { pairing -> scope.launch {
                    loading = true
                    failed = false
                    val result = runCatching { SmsRelay.simOptions(context, pairing, true) }
                    options = result.getOrNull()
                    failed = result.isFailure
                    loading = false
                } }
            })
            Text(stringResource(R.string.outgoing_call_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                val pairing = selected ?: return@Button
                val destination = smsDestination(number) ?: return@Button
                busy = true
                scope.launch {
                    runCatching { OutgoingCallRelay.submit(context, pairing, destination, subscriptionId) }
                        .onSuccess { context.startActivity(RelayCallActivity.createIntent(context, pairing.code, destination, null, false)) }
                        .onFailure { error = it.message ?: context.getString(R.string.outgoing_call_failed) }
                    busy = false
                }
            }, enabled = !busy && selected != null && selectedAvailable && smsDestination(number) != null && AppState.callRelay.value.phase.let(RelayCallUiPolicy::finished),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(if (busy) R.string.outgoing_call_starting else R.string.outgoing_call_action))
            }
        }
    }
}

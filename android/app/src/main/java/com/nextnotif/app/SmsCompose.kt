package com.nextnotif.app

import android.Manifest
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal fun conversationNumber(thread: TextConversation): String? = thread.entries.firstNotNullOfOrNull {
    smsDestination(it.communication?.address ?: if (it.communication == null) messagePresentation(it).identity else null)
}

@Composable
internal fun SmsPermissionCard() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SmsSender.permitted(context) && SmsSender.enabled(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SmsSender.setEnabled(context, it)
        granted = it
        denied = !it
        SmsRelay.enqueueAll(context)
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sms_permission_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sms_permission_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(if (granted) R.string.sms_replies_enabled else R.string.sms_replies_disabled))
                Switch(checked = granted, onCheckedChange = { enable ->
                    if (enable) launcher.launch(Manifest.permission.SEND_SMS)
                    else { SmsSender.setEnabled(context, false); granted = false }
                })
            }
            if (denied) TextButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.sms_open_settings)) }
        }
    }
}

@Composable
internal fun SmsComposer(pairing: PairingInfo?, number: String?, draftKey: String, onSent: (String) -> Unit = {}) {
    val context = LocalContext.current
    var body by rememberSaveable(draftKey) { mutableStateOf("") }
    var error by remember(draftKey) { mutableStateOf<String?>(null) }
    var subscriptionId by rememberSaveable(pairing?.server, pairing?.code) { mutableStateOf<Int?>(null) }
    var simOptions by remember(pairing?.server, pairing?.code) { mutableStateOf<JSONObject?>(null) }
    var simLoading by remember(pairing?.server, pairing?.code) { mutableStateOf(false) }
    var simError by remember(pairing?.server, pairing?.code) { mutableStateOf(false) }
    var refresh by remember(pairing?.server, pairing?.code) { mutableIntStateOf(0) }
    val relayEnabled = SessionStore.load(context).relayEnabled
    LaunchedEffect(pairing?.server, pairing?.code, pairing?.enabled, relayEnabled, refresh) {
        if (pairing == null || !pairing.enabled || pairing.role != Role.RECEIVER || pairing.isFirebase || !relayEnabled) return@LaunchedEffect
        simLoading = true
        simError = false
        try {
            val initial = SmsRelay.simOptions(context, pairing, true)
            simOptions = initial
            val version = initial?.optLong("updated_at")
            for (attempt in 0 until 4) {
                delay(3000)
                val latest = SmsRelay.simOptions(context, pairing, false)
                simOptions = latest
                if (latest != null && latest.optLong("updated_at") != version) break
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { simError = true }
        finally { simLoading = false }
    }
    val sims = simOptions?.optJSONArray("sims")
    val selectedAvailable = subscriptionId == null || (sims != null && (0 until sims.length()).any {
        sims.getJSONObject(it).optInt("id", -1) == subscriptionId
    })
    val unavailable = when {
        pairing == null || !pairing.enabled -> R.string.sms_pairing_unavailable
        pairing.role != Role.RECEIVER -> R.string.sms_receiver_only
        pairing.isFirebase -> R.string.sms_transport_unavailable
        number == null -> R.string.sms_address_unavailable
        !SessionStore.load(context).relayEnabled -> R.string.sms_relay_off
        else -> null
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pairing != null && pairing.enabled && pairing.role == Role.RECEIVER && !pairing.isFirebase && relayEnabled) {
                Text(stringResource(R.string.sms_send_via, pairing.displayName), style = MaterialTheme.typography.labelMedium)
                SenderSimSelector(simOptions, subscriptionId, simLoading, simError,
                    onSelect = { subscriptionId = it }, onRefresh = { refresh++ })
            }
            if (unavailable != null) {
                Text(stringResource(unavailable), style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedTextField(body, { body = it; error = null }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sms_message_label)) }, minLines = 2, maxLines = 5,
                    isError = body.length > 1600,
                    supportingText = { Text(stringResource(R.string.sms_character_count, body.length)) })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        try {
                            val id = SmsOutbox.submit(context, pairing!!, number!!, body, subscriptionId)
                            body = ""
                            onSent(id)
                        } catch (_: Exception) { error = context.getString(R.string.sms_save_failed) }
                    },
                    enabled = body.isNotBlank() && body.length <= 1600 && selectedAvailable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.sms_send_action)) }
            }
        }
    }
}

@Composable
internal fun SenderSimSelector(options: JSONObject?, selected: Int?, loading: Boolean, failed: Boolean,
    onSelect: (Int?) -> Unit, onRefresh: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val array = options?.optJSONArray("sims")
    val sims = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
    fun label(sim: JSONObject): String {
        val name = sim.optString("name").ifBlank { sim.optString("carrier") }
        val carrier = sim.optString("carrier").takeIf { it.isNotBlank() && it != name }
        return "${sim.optInt("slot") + 1} · $name" + (carrier?.let { " · $it" } ?: "")
    }
    val selection = sims.firstOrNull { it.optInt("id") == selected }
    val default = sims.firstOrNull { !options!!.isNull("default_id") && it.optInt("id") == options.optInt("default_id") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sms_sim_label), style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when {
                    selected == null -> stringResource(R.string.sms_sim_default)
                    selection != null -> stringResource(R.string.sms_sim_option, label(selection))
                    else -> stringResource(R.string.sms_sim_missing)
                })
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sms_sim_default)) },
                    onClick = { onSelect(null); menu = false })
                sims.forEach { sim ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.sms_sim_option, label(sim))) },
                        onClick = { onSelect(sim.getInt("id")); menu = false })
                }
            }
        }
        val message = when {
            selected != null && selection == null -> R.string.sms_sim_removed
            loading -> R.string.sms_sim_loading
            failed -> R.string.sms_sim_refresh_failed
            options == null -> R.string.sms_sim_no_info
            options.optString("state") == "permission_required" -> R.string.sms_sim_permission
            options.optString("state") != "ready" -> R.string.sms_sim_unavailable
            sims.isEmpty() -> R.string.sms_sim_empty
            else -> R.string.sms_sim_last_reported
        }
        Text(stringResource(message), style = MaterialTheme.typography.bodySmall,
            color = if (selected != null && selection == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected == null && default != null) Text(stringResource(R.string.sms_sim_current_default, label(default)),
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRefresh, enabled = !loading) { Text(stringResource(R.string.sms_sim_refresh)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewSmsScreen(pairings: List<PairingInfo>, onBack: () -> Unit, onSent: (String) -> Unit, initialNumber: String = "") {
    val choices = pairings.filter { it.enabled && it.role == Role.RECEIVER && !it.isFirebase }
    var code by rememberSaveable { mutableStateOf(choices.firstOrNull()?.code) }
    var number by rememberSaveable { mutableStateOf(initialNumber) }
    var menu by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.code == code }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.sms_new_message)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.conversation_back)) }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(selected?.displayName ?: stringResource(R.string.sms_choose_pairing))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        choices.forEach { p -> DropdownMenuItem(text = { Text(p.displayName) }, onClick = { code = p.code; menu = false }) }
                    }
                }
                ContactPhoneField(number, { number = it }, Modifier.fillMaxWidth(),
                    isError = number.isNotEmpty() && smsDestination(number) == null)
                Text(stringResource(R.string.sms_charges), style = MaterialTheme.typography.bodySmall)
            }
            SmsComposer(selected, smsDestination(number), "new-sms", onSent)
        }
    }
}

@Composable
internal fun smsStatusLabel(status: String): String = stringResource(when (status) {
    "pending" -> R.string.sms_status_pending
    "queued" -> R.string.sms_status_queued
    "sending" -> R.string.sms_status_sending
    "sent" -> R.string.sms_status_sent
    "failed" -> R.string.sms_status_failed
    "expired" -> R.string.sms_status_expired
    else -> R.string.sms_status_unknown
})

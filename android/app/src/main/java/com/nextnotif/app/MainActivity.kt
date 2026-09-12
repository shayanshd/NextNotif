package com.nextnotif.app

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface AppScreen {
    data object Home : AppScreen
    data object GatewayDiagnostics : AppScreen
    data class Detail(val code: String) : AppScreen
    data class AddEdit(val code: String?) : AppScreen
}

class MainActivity : ComponentActivity() {

    private lateinit var sessionState: MutableState<SessionState>
    private val nav = mutableStateOf<List<AppScreen>>(listOf(AppScreen.Home))
    private val connecting = mutableStateOf(false)
    private val uiError = mutableStateOf<String?>(null)
    private val showResetConfirm = mutableStateOf(false)
    private val removeTarget = mutableStateOf<PairingInfo?>(null)
    private val notifPermMissing = mutableStateOf(false)
    private val permGateMissing = mutableStateOf<List<MissingPerm>>(emptyList())
    private val permAskAttempted = mutableStateOf(false)
    private val liveCallPermMissing = mutableStateOf<List<String>>(emptyList())
    private val showLiveCallPermissionPrompt = mutableStateOf(false)
    private val liveCallPermAskAttempted = mutableStateOf(false)
    private var requestingLiveCallPermissions = false
    private val contactsPermissionGranted = mutableStateOf(false)
    private val contactsPermissionAskAttempted = mutableStateOf(false)
    private var requestingContactsPermission = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val wasLiveCallRequest = requestingLiveCallPermissions
        val wasContactsRequest = requestingContactsPermission
        requestingLiveCallPermissions = false
        requestingContactsPermission = false
        refreshPermissionState()
        startRelayIfPermissionsReady()
        if (wasLiveCallRequest) {
            liveCallPermAskAttempted.value = true
            showLiveCallPermissionPrompt.value = liveCallPermMissing.value.isNotEmpty()
        }
        if (wasContactsRequest) {
            contactsPermissionAskAttempted.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.initializeMessages(this)
        Notifications.ensureChannels(this)
        // Token acquisition is independent of the relay service, which lets a
        // normal app launch register FCM even before the user starts relaying.
        FcmBridge.ensureToken(this)
        sessionState = mutableStateOf(SessionStore.load(this))
        refreshPermissionState()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val stack = nav.value
                    if (stack.size > 1) {
                        nav.value = stack.dropLast(1)
                    } else {
                        finish()
                    }
                }
            },
        )

        enableEdgeToEdge()

        setContent {
            val session by sessionState
            NextNotifTheme {
                when {
                    permGateMissing.value.isNotEmpty() -> PermissionsGateScreen(
                        missing = permGateMissing.value,
                        showSettings = showPermSettingsButton(),
                        onRequest = { requestMissingPerms() },
                        onOpenSettings = { openAppSettings() },
                    )
                    else -> when (val screen = nav.value.last()) {
                        AppScreen.Home -> HomeScreen(
                            pairings = session.pairings,
                            onToggleService = {
                                val s = AppState.conn.value
                                if (
                                    s == AppState.ConnState.IDLE ||
                                    s == AppState.ConnState.DISCONNECTED
                                ) {
                                    handleAction(UiAction.StartService)
                                } else {
                                    handleAction(UiAction.StopService)
                                }
                            },
                            onBattery = { handleAction(UiAction.RequestBatteryExemption) },
                            onCallScreening = { handleAction(UiAction.OpenCallScreeningSettings) },
                            onGatewayDiagnostics = {
                                nav.value = nav.value + AppScreen.GatewayDiagnostics
                            },
                            onReset = { showResetConfirm.value = true },
                            notifPermMissing = notifPermMissing.value,
                            onRequestNotifPerm = { requestNotifPerm() },
                            liveCallPermMissing = liveCallPermMissing.value.isNotEmpty(),
                            onFinishLiveCallSetup = {
                                showLiveCallPermissionPrompt.value = true
                            },
                            onAddPairing = { nav.value = nav.value + AppScreen.AddEdit(null) },
                            onOpenPairing = { p -> nav.value = nav.value + AppScreen.Detail(p.code) },
                            onEditPairing = { p -> nav.value = nav.value + AppScreen.AddEdit(p.code) },
                            onRemovePairing = { removeTarget.value = it },
                            onTogglePairing = { p -> handleAction(UiAction.SetPairingEnabled(p.code, !p.enabled)) },
                        )
                        AppScreen.GatewayDiagnostics -> GatewayDiagnosticsScreen(
                            onBack = { nav.value = nav.value.dropLast(1) },
                        )
                        is AppScreen.Detail -> {
                            val p = session.pairings.firstOrNull { it.code == screen.code }
                            if (p != null) {
                                PairingDetailScreen(
                                    pairing = p,
                                    onBack = { nav.value = nav.value.dropLast(1) },
                                    onEdit = { nav.value = nav.value + AppScreen.AddEdit(p.code) },
                                    onRemove = { removeTarget.value = p },
                                    onToggle = { handleAction(UiAction.SetPairingEnabled(p.code, !p.enabled)) },
                                )
                            } else {
                                // Pairing was removed behind us: fall back to home.
                                nav.value = listOf(AppScreen.Home)
                            }
                        }
                        is AppScreen.AddEdit -> {
                            val p = screen.code?.let { c -> session.pairings.firstOrNull { it.code == c } }
                            AddEditPairingScreen(
                                editing = p,
                                busy = connecting.value,
                                busyLabel = if (connecting.value) stringResource(R.string.setup_connecting) else null,
                                error = uiError.value,
                                onBack = { nav.value = nav.value.dropLast(1) },
                                onGenerateCode = { server, onResult -> generateCode(server, onResult) },
                                contactsPermissionGranted = contactsPermissionGranted.value,
                                contactsPermissionDenied = contactsPermissionAskAttempted.value &&
                                    !contactsPermissionGranted.value,
                                contactsPermissionNeedsSettings = contactPermissionNeedsSettings(),
                                onRequestContactsPermission = { requestContactsPermission() },
                                onOpenAppSettings = { openAppSettings() },
                                onSubmit = { label, role, code, server, transport, fbConfig, liveCallEnabled ->
                                    uiError.value = null
                                    handleAction(
                                        UiAction.UpsertPairing(
                                            label,
                                            role,
                                            code,
                                            server,
                                            transport,
                                            fbConfig,
                                            liveCallEnabled,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                if (showResetConfirm.value) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm.value = false },
                        title = { Text(stringResource(R.string.reset_confirm_title)) },
                        text = { Text(stringResource(R.string.reset_confirm_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showResetConfirm.value = false
                                handleAction(UiAction.Reset)
                            }) {
                                Text(stringResource(R.string.ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm.value = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }

                removeTarget.value?.let { target ->
                    AlertDialog(
                        onDismissRequest = { removeTarget.value = null },
                        title = { Text(stringResource(R.string.remove_pairing_title)) },
                        text = { Text(stringResource(R.string.remove_pairing_body, target.code)) },
                        confirmButton = {
                            TextButton(onClick = {
                                removeTarget.value = null
                                handleAction(UiAction.RemovePairing(target.code))
                            }) {
                                Text(stringResource(R.string.remove_pairing_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { removeTarget.value = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }

                if (showLiveCallPermissionPrompt.value && liveCallPermMissing.value.isNotEmpty()) {
                    val useSettings = liveCallPermissionNeedsSettings()
                    AlertDialog(
                        onDismissRequest = { showLiveCallPermissionPrompt.value = false },
                        title = {
                            Text(
                                stringResource(
                                    if (liveCallPermAskAttempted.value) {
                                        R.string.live_call_perm_denied_title
                                    } else {
                                        R.string.live_call_perm_title
                                    },
                                ),
                            )
                        },
                        text = {
                            Text(
                                stringResource(
                                    if (liveCallPermAskAttempted.value) {
                                        R.string.live_call_perm_denied_body
                                    } else {
                                        R.string.live_call_perm_body
                                    },
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (useSettings) {
                                        showLiveCallPermissionPrompt.value = false
                                        openAppSettings()
                                    } else {
                                        requestLiveCallPermissions()
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (useSettings) R.string.perm_gate_settings
                                        else R.string.live_call_perm_allow
                                    ),
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLiveCallPermissionPrompt.value = false }) {
                                Text(stringResource(R.string.live_call_perm_not_now))
                            }
                        },
                    )
                }
            }
        }

        // Restore the user's last Start/Stop choice after process death or an
        // app update. Receiver-only FCM pairings perform one short sync and do
        // not leave a foreground service running.
        startRelayIfPermissionsReady()

        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra(EXTRA_RUN_GATEWAY_AUDIO_PROBE, false)
        ) {
            lifecycleScope.launch {
                Log.i(AUDIO_PROBE_TAG, "probe_start")
                val results = GatewayAudioProbe.run { source ->
                    Log.i(AUDIO_PROBE_TAG, "probe_source=$source")
                }
                results.forEach { result ->
                    val summary = result.summary
                    Log.i(
                        AUDIO_PROBE_TAG,
                        "probe_result source=${result.source} status=${result.status}" +
                            " dbfs=${summary?.dbfs} nonzero=${summary?.nonZeroPercent}" +
                            " samples=${summary?.sampleCount} detail=${result.detail}",
                    )
                }
                Log.i(AUDIO_PROBE_TAG, "probe_complete")
            }
        }

        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra(EXTRA_RUN_CONTROLLED_CALL_PROBE, false)
        ) {
            lifecycleScope.launch {
                Log.i(AUDIO_PROBE_TAG, "controlled_call_wait")
                delay(2_000)
                Log.i(AUDIO_PROBE_TAG, "controlled_call_start")
                val result = GatewayAudioProbe.runControlledCall { }
                result.timeline.forEach { slice ->
                    Log.i(
                        AUDIO_PROBE_TAG,
                        "controlled_call_slice ms=${slice.startMs} dbfs=${slice.dbfs}",
                    )
                }
                Log.i(
                    AUDIO_PROBE_TAG,
                    "controlled_call_result status=${result.status} dbfs=${result.summary?.dbfs}" +
                        " nonzero=${result.summary?.nonZeroPercent} samples=${result.summary?.sampleCount}" +
                        " detail=${result.detail}",
                )
                Log.i(AUDIO_PROBE_TAG, "controlled_call_complete")
            }
        }

        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra(EXTRA_RUN_CALL_INJECTION_PROBE, false)
        ) {
            lifecycleScope.launch {
                Log.i(AUDIO_PROBE_TAG, "injection_wait")
                delay(2_000)
                Log.i(AUDIO_PROBE_TAG, "injection_start")
                val result = GatewayCallInjectionProbe.run(manageForwarding = false)
                Log.i(
                    AUDIO_PROBE_TAG,
                    "injection_result success=${result.success} parameter=${result.parameterResult}" +
                        " frames=${result.framesWritten} detail=${result.detail}",
                )
                Log.i(AUDIO_PROBE_TAG, "injection_complete")
            }
        }
    }

    companion object {
        const val EXTRA_RUN_GATEWAY_AUDIO_PROBE = "run_gateway_audio_probe"
        const val EXTRA_RUN_CONTROLLED_CALL_PROBE = "run_controlled_call_probe"
        const val EXTRA_RUN_CALL_INJECTION_PROBE = "run_call_injection_probe"
        const val AUDIO_PROBE_TAG = "NextNotifAudioProbe"
    }

    private fun refreshSession() {
        sessionState.value = SessionStore.load(this)
    }

    private fun goHome() {
        nav.value = listOf(AppScreen.Home)
    }

    private fun handleAction(action: UiAction) {
        when (action) {
            is UiAction.UpsertPairing -> upsertPairing(action)
            is UiAction.StartService -> {
                val cur = SessionStore.load(this)
                SessionStore.save(this, cur.copy(relayEnabled = true))
                RelayForegroundService.Controller.start(this)
            }
            is UiAction.StopService -> {
                val cur = SessionStore.load(this)
                SessionStore.save(this, cur.copy(relayEnabled = false))
                RelayForegroundService.Controller.stop(this)
            }
            is UiAction.Reset -> {
                SessionStore.clear(this)
                AppState.clearMessages(this)
                RelayForegroundService.Controller.stop(this)
                AppState.clearStates()
                refreshSession()
                refreshPermissionState()
                goHome()
            }
            is UiAction.RequestBatteryExemption -> BatteryGuard.requestIfNeeded(this)
            is UiAction.OpenCallScreeningSettings -> {
                val launched = runCatching {
                    startActivity(Intent("android.settings.CALL_SCREENING_SETTINGS"))
                }.isSuccess
                if (!launched) openAppSettings()
            }
            is UiAction.RemovePairing -> {
                val cur = SessionStore.load(this)
                val remaining = cur.pairings.filterNot { it.code == action.code }
                if (remaining.isEmpty()) {
                    SessionStore.save(
                        this,
                        cur.copy(
                            pairings = emptyList(),
                            code = null,
                            server = Config.DEFAULT_SERVER,
                    deviceToken = null,
                    transport = null,
                    fbConfig = null,
                        ),
                    )
                    RelayForegroundService.Controller.stop(this)
                    AppState.clearStates()
                } else {
                    SessionStore.save(this, cur.copy(pairings = remaining))
                    if (cur.relayEnabled) restartRelay(remaining)
                }
                refreshSession()
                refreshPermissionState()
                goHome()
            }
            is UiAction.SetPairingEnabled -> {
                val cur = SessionStore.load(this)
                val updated = cur.copy(
                    pairings = cur.pairings.map {
                        if (it.code == action.code) it.copy(enabled = action.enabled) else it
                    },
                )
                SessionStore.save(this, updated)
                if (action.enabled && cur.relayEnabled) {
                    RelayForegroundService.Controller.startPairing(this, action.code)
                } else if (!action.enabled) {
                    RelayForegroundService.Controller.stopPairing(this, action.code)
                } else {
                    AppState.clearPairingState(action.code)
                }
                refreshSession()
                refreshPermissionState()
            }
        }
    }

    private fun upsertPairing(a: UiAction.UpsertPairing) {
        val isFirebase = a.transport == FirebaseRelay.TRANSPORT
        val preferencesOnly = canSavePairingPreferencesOffline(
            SessionStore.load(this).pairings.firstOrNull { it.code == a.code },
            a.code, a.role, a.server, a.transport, a.fbConfig,
        )
        connecting.value = !isFirebase && !preferencesOnly
        uiError.value = null
        lifecycleScope.launch {
            val status = if (isFirebase || preferencesOnly) {
                // Firebase transport has no server to pre-check; the relay
                // verifies the config on connect (anonymous auth — no secret needed).
                JSONObject()
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val http = a.server.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
                        val conn = URL("$http/pair/${a.code}/status").openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().readText()) else null
                    }.getOrNull()
                }
            }
            connecting.value = false
            if (!isFirebase && status == null) {
                uiError.value = getString(R.string.error_server_unreachable, a.server)
                return@launch
            }
            val cur = SessionStore.load(this@MainActivity)
            // Never forward a saved credential to another relay authority/role.
            val existing = cur.pairings.firstOrNull { it.code == a.code }
            val newPairing = if (preferencesOnly && existing != null) existing.copy(
                label = a.label,
                liveCallEnabled = liveCallEnabledFor(a.role, a.liveCallEnabled),
            ) else PairingInfo(
                code = a.code,
                role = a.role,
                server = a.server,
                transport = a.transport,
                fbConfig = a.fbConfig,
                deviceToken = retainedDeviceToken(existing, a.code, a.role, a.server, a.transport, a.fbConfig),
                label = a.label,
                enabled = existing?.enabled ?: true,
                liveCallEnabled = liveCallEnabledFor(a.role, a.liveCallEnabled),
            )
            val updated = if (existing != null) {
                cur.copy(pairings = cur.pairings.map { if (it.code == a.code) newPairing else it })
            } else {
                cur.copy(pairings = cur.pairings + newPairing)
            }
            SessionStore.save(this@MainActivity, updated)
            refreshSession()
            refreshPermissionState()
            if (updated.relayEnabled && permGateMissing.value.isEmpty()) {
                restartRelay(updated.pairings)
            }
            goHome()
            if (newPairing.liveCallEnabled && liveCallPermMissing.value.isNotEmpty()) {
                liveCallPermAskAttempted.value = false
                showLiveCallPermissionPrompt.value = true
            }
        }
    }

    private fun restartRelay(pairings: List<PairingInfo>) {
        if (pairings.isNotEmpty()) {
            // Reconfigure the current instance atomically. Stop-then-start
            // intents can be handled by the same Service object and leave a
            // freshly edited pairing stopped when the queued stop wins.
            RelayForegroundService.Controller.reload(this)
        }
    }

    private fun generateCode(
        server: String,
        onResult: (code: String?, failure: String?) -> Unit,
    ) {
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching {
                    val http = server.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
                    val conn = URL("$http/pair/create").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        JSONObject(conn.inputStream.bufferedReader().readText()).optString("code")
                    } else null
                }.getOrNull()
            }
            if (!code.isNullOrBlank()) onResult(code, null) else onResult(null, getString(R.string.error_server_unreachable, server))
        }
    }

    private fun requiredPerms(): List<MissingPerm> {
        val enabledRoles = SessionStore.load(this).pairings
            .asSequence()
            .filter { it.enabled }
            .map { it.role }
            .toSet()
        val required = PermissionPolicy.requiredFor(
            enabledRoles = enabledRoles,
            notificationsRequireRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
        return buildList {
            if (BaselinePermission.SMS in required) add(MissingPerm(
                perms = listOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                ),
                icon = Icons.Filled.Send,
                title = getString(R.string.perm_sms_title),
                reason = getString(R.string.perm_sms_reason),
            ))
            if (BaselinePermission.PHONE_STATE in required) add(MissingPerm(
                perms = listOf(Manifest.permission.READ_PHONE_STATE),
                icon = Icons.Filled.Phone,
                title = getString(R.string.perm_phone_title),
                reason = getString(R.string.perm_phone_reason),
            ))
            if (BaselinePermission.NOTIFICATIONS in required) add(MissingPerm(
                perms = listOf(Manifest.permission.POST_NOTIFICATIONS),
                icon = Icons.Filled.Notifications,
                title = getString(R.string.perm_notif_title),
                reason = getString(R.string.perm_notif_reason),
            ))
        }
    }

    private fun missingRawPerms(): List<String> =
        requiredPerms().flatMap { it.perms }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun refreshPermGate() {
        val missing = missingRawPerms().toSet()
        permGateMissing.value = requiredPerms().filter { it.perms.any { p -> p in missing } }
    }

    private fun refreshPermissionState() {
        refreshNotifPermMissing()
        refreshPermGate()
        refreshLiveCallPermMissing()
        refreshContactsPermission()
    }

    private fun refreshContactsPermission() {
        contactsPermissionGranted.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun contactPermissionNeedsSettings(): Boolean =
        contactsPermissionAskAttempted.value &&
            !contactsPermissionGranted.value &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)

    private fun requestContactsPermission() {
        if (contactsPermissionGranted.value) return
        if (contactPermissionNeedsSettings()) {
            openAppSettings()
            return
        }
        requestingContactsPermission = true
        permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
    }

    private fun refreshLiveCallPermMissing() {
        val pairings = SessionStore.load(this).pairings
        liveCallPermMissing.value = if (PermissionPolicy.needsLiveCallPermissions(pairings)) {
            listOf(
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.RECORD_AUDIO,
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        } else {
            emptyList()
        }
        if (liveCallPermMissing.value.isEmpty()) {
            showLiveCallPermissionPrompt.value = false
        }
    }

    private fun liveCallPermissionNeedsSettings(): Boolean =
        liveCallPermAskAttempted.value && liveCallPermMissing.value.any {
            !shouldShowRequestPermissionRationale(it)
        }

    private fun requestLiveCallPermissions() {
        val missing = liveCallPermMissing.value
        if (missing.isEmpty()) {
            showLiveCallPermissionPrompt.value = false
            return
        }
        if (liveCallPermissionNeedsSettings()) {
            showLiveCallPermissionPrompt.value = false
            openAppSettings()
            return
        }
        requestingLiveCallPermissions = true
        permLauncher.launch(missing.toTypedArray())
    }

    private fun startRelayIfPermissionsReady() {
        val session = SessionStore.load(this)
        if (
            session.relayEnabled &&
            session.pairings.any { it.enabled } &&
            permGateMissing.value.isEmpty()
        ) {
            restartRelay(session.pairings)
        }
    }

    private fun requestMissingPerms() {
        val missing = missingRawPerms()
        if (missing.isEmpty()) return
        val toAsk =
            if (permAskAttempted.value) {
                missing.filter { shouldShowRequestPermissionRationale(it) }
            } else {
                missing
            }
        permAskAttempted.value = true
        if (toAsk.isEmpty()) {
            openAppSettings()
        } else {
            permLauncher.launch(toAsk.toTypedArray())
        }
    }

    private fun showPermSettingsButton(): Boolean =
        permAskAttempted.value &&
            missingRawPerms().any { !shouldShowRequestPermissionRationale(it) }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName")),
            )
        }
    }

    private fun refreshNotifPermMissing() {
        val session = SessionStore.load(this)
        // Any receiver pairing needs notifications.
        val granted =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        notifPermMissing.value = session.pairings.any { it.enabled && it.role == Role.RECEIVER } &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted
    }

    private fun requestNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onResume() {
        super.onResume()
        val wasBlocked = permGateMissing.value.isNotEmpty()
        refreshPermissionState()
        if (wasBlocked && permGateMissing.value.isEmpty()) startRelayIfPermissionsReady()
    }
}

sealed interface UiAction {
    data class UpsertPairing(
        val label: String?,
        val role: Role,
        val code: String,
        val server: String,
        val transport: String?,
        val fbConfig: String?,
        val liveCallEnabled: Boolean,
    ) : UiAction
    data object StartService : UiAction
    data object StopService : UiAction
    data object Reset : UiAction
    data object RequestBatteryExemption : UiAction
    data object OpenCallScreeningSettings : UiAction
    data class RemovePairing(val code: String) : UiAction
    data class SetPairingEnabled(val code: String, val enabled: Boolean) : UiAction
}

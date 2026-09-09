package com.nextnotif.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface AppScreen {
    data object Home : AppScreen
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

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermGate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        refreshNotifPermMissing()
        refreshPermGate()
        sessionState = mutableStateOf(SessionStore.load(this))

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
                                    s == AppState.ConnState.DISCONNECTED ||
                                    s == AppState.ConnState.CONNECTING
                                ) {
                                    handleAction(UiAction.StartService)
                                } else {
                                    handleAction(UiAction.StopService)
                                }
                            },
                            onBattery = { handleAction(UiAction.RequestBatteryExemption) },
                            onCallScreening = { handleAction(UiAction.OpenCallScreeningSettings) },
                            onReset = { showResetConfirm.value = true },
                            notifPermMissing = notifPermMissing.value,
                            onRequestNotifPerm = { requestNotifPerm() },
                            onAddPairing = { nav.value = nav.value + AppScreen.AddEdit(null) },
                            onOpenPairing = { p -> nav.value = nav.value + AppScreen.Detail(p.code) },
                            onEditPairing = { p -> nav.value = nav.value + AppScreen.AddEdit(p.code) },
                            onRemovePairing = { removeTarget.value = it },
                            onTogglePairing = { p -> handleAction(UiAction.SetPairingEnabled(p.code, !p.enabled)) },
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
                                onSubmit = { label, role, code, server, transport, fbConfig ->
                                    uiError.value = null
                                    handleAction(
                                        UiAction.UpsertPairing(label, role, code, server, transport, fbConfig),
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
            }
        }
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
            is UiAction.StartService -> RelayForegroundService.Controller.start(this)
            is UiAction.StopService -> RelayForegroundService.Controller.stop(this)
            is UiAction.Reset -> {
                SessionStore.clear(this)
                RelayForegroundService.Controller.stop(this)
                AppState.clearStates()
                refreshSession()
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
                    restartRelay(remaining)
                }
                refreshSession()
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
                if (action.enabled) {
                    RelayForegroundService.Controller.startPairing(this, action.code)
                } else {
                    RelayForegroundService.Controller.stopPairing(this, action.code)
                }
                refreshSession()
            }
        }
    }

    private fun upsertPairing(a: UiAction.UpsertPairing) {
        val isFirebase = a.transport == FirebaseRelay.TRANSPORT
        connecting.value = !isFirebase
        uiError.value = null
        lifecycleScope.launch {
            val status = if (isFirebase) {
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
            // A device token belongs to the pairing it was issued for: it is
            // kept only when re-saving the exact same code, otherwise the
            // server issues a fresh one on first connect.
            val existing = cur.pairings.firstOrNull { it.code == a.code }
            val newPairing = PairingInfo(
                code = a.code,
                role = a.role,
                server = a.server,
                transport = a.transport,
                fbConfig = a.fbConfig,
                deviceToken = existing?.deviceToken,
                label = a.label,
                enabled = existing?.enabled ?: true,
            )
            val updated = if (existing != null) {
                cur.copy(pairings = cur.pairings.map { if (it.code == a.code) newPairing else it })
            } else {
                cur.copy(pairings = cur.pairings + newPairing)
            }
            SessionStore.save(this@MainActivity, updated)
            restartRelay(updated.pairings)
            refreshSession()
            goHome()
        }
    }

    private fun restartRelay(pairings: List<PairingInfo>) {
        // A stop sent while the service is not running would LAUNCH it (plain
        // startService), and its queued stopSelf() would then kill the relay
        // right after the start intent below is processed. Only stop when
        // the service is actually up.
        if (AppState.conn.value != AppState.ConnState.IDLE) {
            RelayForegroundService.Controller.stop(this)
        }
        if (pairings.isNotEmpty()) {
            RelayForegroundService.Controller.start(this)
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
        val perms = mutableListOf(
            MissingPerm(
                perms = listOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                ),
                icon = Icons.Filled.Send,
                title = getString(R.string.perm_sms_title),
                reason = getString(R.string.perm_sms_reason),
            ),
            MissingPerm(
                perms = listOf(Manifest.permission.READ_PHONE_STATE),
                icon = Icons.Filled.Phone,
                title = getString(R.string.perm_phone_title),
                reason = getString(R.string.perm_phone_reason),
            ),
            MissingPerm(
                perms = listOf(Manifest.permission.READ_CONTACTS),
                icon = Icons.Filled.Person,
                title = getString(R.string.perm_contacts_title),
                reason = getString(R.string.perm_contacts_reason),
            ),
            MissingPerm(
                perms = listOf(Manifest.permission.ANSWER_PHONE_CALLS),
                icon = Icons.Filled.Phone,
                title = getString(R.string.perm_calls_title),
                reason = getString(R.string.perm_calls_reason),
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += MissingPerm(
                perms = listOf(Manifest.permission.POST_NOTIFICATIONS),
                icon = Icons.Filled.Notifications,
                title = getString(R.string.perm_notif_title),
                reason = getString(R.string.perm_notif_reason),
            )
        }
        return perms
    }

    private fun missingRawPerms(): List<String> =
        requiredPerms().flatMap { it.perms }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun refreshPermGate() {
        val missing = missingRawPerms().toSet()
        permGateMissing.value = requiredPerms().filter { it.perms.any { p -> p in missing } }
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
        notifPermMissing.value = session.pairings.any { it.role == Role.RECEIVER } &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted
    }

    private fun requestNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotifPermMissing()
        refreshPermGate()
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
    ) : UiAction
    data object StartService : UiAction
    data object StopService : UiAction
    data object Reset : UiAction
    data object RequestBatteryExemption : UiAction
    data object OpenCallScreeningSettings : UiAction
    data class RemovePairing(val code: String) : UiAction
    data class SetPairingEnabled(val code: String, val enabled: Boolean) : UiAction
}

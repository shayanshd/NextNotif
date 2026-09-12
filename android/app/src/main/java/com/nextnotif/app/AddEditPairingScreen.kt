package com.nextnotif.app

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticRole
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditPairingScreen(
    editing: PairingInfo?,
    busy: Boolean,
    busyLabel: String?,
    error: String?,
    onBack: () -> Unit,
    onGenerateCode: (server: String, onResult: (code: String?, failure: String?) -> Unit) -> Unit,
    contactsPermissionGranted: Boolean,
    contactsPermissionDenied: Boolean,
    contactsPermissionNeedsSettings: Boolean,
    onRequestContactsPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSubmit: (
        label: String?,
        role: Role,
        code: String,
        server: String,
        transport: String?,
        fbConfig: String?,
        liveCallEnabled: Boolean,
    ) -> Unit,
) {
    val isEdit = editing != null
    var label by remember { mutableStateOf(editing?.label ?: "") }
    var role by remember { mutableStateOf<Role?>(editing?.role) }
    var code by remember { mutableStateOf(editing?.code ?: "") }
    var server by remember { mutableStateOf(editing?.server ?: Config.DEFAULT_SERVER) }
    // New pairings default to on-demand FCM; editing preserves the existing
    // choice (legacy relay pairings persist transport=null, which means WS).
    var transport by remember { mutableStateOf(initialTransportFor(editing)) }
    var fbConfig by remember { mutableStateOf(editing?.fbConfig ?: "") }
    var ownProject by remember {
        mutableStateOf(editing?.fbConfig?.isNotBlank() == true)
    }
    var generating by remember { mutableStateOf(false) }
    var liveCallEnabled by remember { mutableStateOf(editing?.liveCallEnabled ?: false) }
    var showContactsExplanation by remember { mutableStateOf(false) }

    val isFirebase = transport == FirebaseRelay.TRANSPORT
    val isFcmOnDemand = transport == FcmOnDemand.TRANSPORT
    val fbParsed = remember(fbConfig) { FirebaseConfig.parse(fbConfig) }
    val ready = when {
        role == null || code.length != 6 -> false
        isFirebase -> !ownProject || fbParsed != null
        else -> true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (isEdit) R.string.edit_title else R.string.add_title),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FieldCard(label = stringResource(R.string.add_name_label), helper = stringResource(R.string.add_name_helper)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.add_name_label)) },
                    singleLine = true,
                )
            }

            FieldCard(label = stringResource(R.string.setup_role_label)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RoleCard(
                        selected = role == Role.SENDER,
                        icon = Icons.Filled.Send,
                        title = stringResource(R.string.setup_sender),
                        description = stringResource(R.string.setup_sender_desc),
                        onClick = { role = Role.SENDER },
                        modifier = Modifier.weight(1f),
                    )
                    RoleCard(
                        selected = role == Role.RECEIVER,
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.setup_receiver),
                        description = stringResource(R.string.setup_receiver_desc),
                        onClick = {
                            role = Role.RECEIVER
                            liveCallEnabled = false
                            showContactsExplanation = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (role == Role.SENDER) {
                FieldCard(
                    label = stringResource(R.string.setup_contacts_label),
                    helper = stringResource(R.string.setup_contacts_helper),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (contactsPermissionGranted) R.string.setup_contacts_enabled
                                        else R.string.setup_contacts_title
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(
                                        if (contactsPermissionDenied) R.string.setup_contacts_denied
                                        else R.string.setup_contacts_body
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (contactsPermissionDenied) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        if (!contactsPermissionGranted) {
                            OutlinedButton(
                                onClick = {
                                    if (contactsPermissionNeedsSettings) {
                                        onOpenAppSettings()
                                    } else {
                                        showContactsExplanation = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        when {
                                            contactsPermissionNeedsSettings -> R.string.perm_gate_settings
                                            contactsPermissionDenied -> R.string.setup_contacts_retry
                                            else -> R.string.setup_contacts_allow
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }

                FieldCard(
                    label = stringResource(R.string.setup_live_call_label),
                    helper = stringResource(R.string.setup_live_call_helper),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = liveCallEnabled,
                                role = SemanticRole.Switch,
                                onValueChange = { liveCallEnabled = it },
                            )
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setup_live_call_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.setup_live_call_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = liveCallEnabled,
                            onCheckedChange = null,
                        )
                    }
                }
            }

            FieldCard(label = stringResource(R.string.setup_connection_label)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransportOption(
                        selected = isFcmOnDemand,
                        title = stringResource(R.string.setup_transport_fcm),
                        description = stringResource(R.string.setup_transport_fcm_desc),
                        onClick = { transport = FcmOnDemand.TRANSPORT },
                        modifier = Modifier.fillMaxWidth(),
                        badge = stringResource(R.string.setup_fb_recommended),
                    )
                    TransportOption(
                        selected = transport == TRANSPORT_WS,
                        title = stringResource(R.string.setup_transport_ws),
                        description = stringResource(R.string.setup_transport_ws_desc),
                        onClick = { transport = TRANSPORT_WS },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TransportOption(
                        selected = isFirebase,
                        title = stringResource(R.string.setup_transport_fb),
                        description = stringResource(R.string.setup_transport_fb_desc),
                        onClick = { transport = FirebaseRelay.TRANSPORT },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            FieldCard(
                label = stringResource(R.string.setup_pairing_label),
                helper = if (isFirebase) stringResource(R.string.setup_code_helper_fb) else stringResource(R.string.setup_code_helper),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { newValue ->
                            code = newValue.filter(Char::isDigit).take(6)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setup_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            letterSpacing = 6.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    if (!isFirebase) {
                        OutlinedButton(
                            onClick = {
                                generating = true
                                onGenerateCode(server) { generated, failure ->
                                    generating = false
                                    if (generated != null) {
                                        code = generated
                                    }
                                }
                            },
                            enabled = !busy && !generating,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.setup_generate))
                        }
                    }
                }
            }

            if (!isFirebase) {
                FieldCard(
                    label = stringResource(R.string.setup_server_label),
                    helper = stringResource(R.string.setup_server_helper),
                ) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setup_server_label)) },
                        singleLine = true,
                    )
                }
            } else {
                FieldCard(
                    label = stringResource(
                        if (ownProject) R.string.setup_fb_config_label else R.string.setup_fb_relay_label
                    ),
                    helper = stringResource(
                        if (ownProject) R.string.setup_fb_config_helper else R.string.setup_fb_builtin_note
                    ),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ownProject) {
                            OutlinedTextField(
                                value = fbConfig,
                                onValueChange = { fbConfig = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.setup_fb_config_label)) },
                                minLines = 4,
                                maxLines = 12,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                            if (fbConfig.isNotBlank() && fbParsed == null) {
                                Text(
                                    text = stringResource(R.string.setup_fb_config_invalid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        TextButton(onClick = { ownProject = !ownProject }) {
                            Text(
                                stringResource(
                                    if (ownProject) R.string.setup_fb_use_builtin else R.string.setup_fb_use_own
                                )
                            )
                        }
                    }
                }
            }

            if (error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            PrimaryCta(
                text = stringResource(if (isEdit) R.string.edit_cta else R.string.add_cta),
                enabled = ready,
                busy = busy || generating,
                busyLabel = busyLabel
                    ?: if (generating) stringResource(R.string.setup_generating)
                    else stringResource(R.string.setup_connecting),
            ) {
                val selectedRole = role ?: return@PrimaryCta
                val useOwn = isFirebase && ownProject
                onSubmit(
                    label.trim().takeIf { it.isNotEmpty() },
                    selectedRole,
                    code,
                    server,
                    when {
                        isFirebase -> FirebaseRelay.TRANSPORT
                        isFcmOnDemand -> FcmOnDemand.TRANSPORT
                        else -> null
                    },
                    if (useOwn && fbConfig.isNotBlank()) fbConfig.trim() else null,
                    liveCallEnabledFor(selectedRole, liveCallEnabled),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showContactsExplanation && PermissionPolicy.offersContactNameLookup(role)) {
        AlertDialog(
            onDismissRequest = { showContactsExplanation = false },
            title = { Text(stringResource(R.string.setup_contacts_dialog_title)) },
            text = { Text(stringResource(R.string.setup_contacts_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showContactsExplanation = false
                        onRequestContactsPermission()
                    },
                ) {
                    Text(stringResource(R.string.setup_contacts_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactsExplanation = false }) {
                    Text(stringResource(R.string.live_call_perm_not_now))
                }
            },
        )
    }
}

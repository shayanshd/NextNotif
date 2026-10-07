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
        liveCallEnabled: Boolean,
        setupMode: PairingSetupMode,
        inviteSecret: String?,
    ) -> Unit,
) {
    val isEdit = editing != null
    val secureEdit = editing?.deviceId != null
    var label by remember { mutableStateOf(editing?.label ?: "") }
    var role by remember { mutableStateOf<Role?>(editing?.role) }
    var code by remember { mutableStateOf(editing?.code ?: "") }
    var server by remember { mutableStateOf(editing?.server ?: Config.DEFAULT_SERVER) }
    var setupMode by remember { mutableStateOf(PairingSetupMode.CREATE) }
    var inviteText by remember { mutableStateOf("") }
    // New pairings default to on-demand FCM; editing preserves the existing
    // choice (legacy relay pairings persist transport=null, which means WS).
    var transport by remember { mutableStateOf(initialTransportFor(editing)) }
    var liveCallEnabled by remember { mutableStateOf(editing?.liveCallEnabled ?: false) }
    var showContactsExplanation by remember { mutableStateOf(false) }

    val isFcmOnDemand = transport == FcmOnDemand.TRANSPORT
    val parsedInvite = remember(inviteText) { SecureInviteText.parse(inviteText) }
    val ready = when {
        editing?.isFirebase == true || role == null -> false
        isEdit -> code.length == 6
        setupMode == PairingSetupMode.CREATE -> SecureInviteText.validServer(server.trim().trimEnd('/'))
        else -> parsedInvite != null && role == parsedInvite.role
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
                        onClick = { if (!secureEdit) role = Role.SENDER },
                        modifier = Modifier.weight(1f),
                    )
                    RoleCard(
                        selected = role == Role.RECEIVER,
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.setup_receiver),
                        description = stringResource(R.string.setup_receiver_desc),
                        onClick = {
                            if (!secureEdit) {
                                role = Role.RECEIVER
                                liveCallEnabled = false
                                showContactsExplanation = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (secureEdit) {
                Text(
                    stringResource(R.string.setup_secure_identity_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isEdit) {
                FieldCard(label = stringResource(R.string.setup_invite_mode_label)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportOption(
                            selected = setupMode == PairingSetupMode.CREATE,
                            title = stringResource(R.string.setup_invite_create),
                            description = stringResource(R.string.setup_invite_create_desc),
                            onClick = { setupMode = PairingSetupMode.CREATE },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TransportOption(
                            selected = setupMode == PairingSetupMode.JOIN,
                            title = stringResource(R.string.setup_invite_join),
                            description = stringResource(R.string.setup_invite_join_desc),
                            onClick = { setupMode = PairingSetupMode.JOIN },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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

                if (Config.LIVE_CALL_BETA_ENABLED) FieldCard(
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
                        badge = stringResource(R.string.setup_recommended),
                    )
                    TransportOption(
                        selected = transport == TRANSPORT_WS,
                        title = stringResource(R.string.setup_transport_ws),
                        description = stringResource(R.string.setup_transport_ws_desc),
                        onClick = { transport = TRANSPORT_WS },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (isEdit) {
                FieldCard(label = stringResource(R.string.setup_pairing_label)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setup_code_label)) },
                        readOnly = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            letterSpacing = 6.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }

            if (!isEdit && setupMode == PairingSetupMode.JOIN) {
                FieldCard(label = stringResource(R.string.setup_invite_label),
                    helper = stringResource(R.string.setup_invite_helper)) {
                    OutlinedTextField(
                        value = inviteText,
                        onValueChange = { value ->
                            inviteText = value.take(512)
                            SecureInviteText.parse(inviteText)?.let { role = it.role; server = it.server }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setup_invite_label)) },
                        minLines = 2,
                        maxLines = 4,
                    )
                    if (inviteText.isNotBlank() && parsedInvite == null) {
                        Text(stringResource(R.string.setup_invite_invalid),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (isEdit || setupMode == PairingSetupMode.CREATE) FieldCard(
                    label = stringResource(R.string.setup_server_label),
                    helper = stringResource(R.string.setup_server_helper),
                ) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        readOnly = secureEdit,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setup_server_label)) },
                        singleLine = true,
                    )
            } else if (parsedInvite != null) {
                Text(stringResource(R.string.setup_invite_server, parsedInvite.server),
                    style = MaterialTheme.typography.bodySmall)
            }

            if (editing?.isFirebase == true) {
                Text(
                    text = stringResource(R.string.setup_legacy_firebase_migration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
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
                text = stringResource(when {
                    isEdit -> R.string.edit_cta
                    setupMode == PairingSetupMode.CREATE -> R.string.setup_invite_create_action
                    else -> R.string.setup_invite_join_action
                }),
                enabled = ready,
                busy = busy,
                busyLabel = busyLabel ?: stringResource(R.string.setup_connecting),
            ) {
                val selectedRole = role ?: return@PrimaryCta
                onSubmit(
                    label.trim().takeIf { it.isNotEmpty() },
                    selectedRole,
                    if (isEdit) code else parsedInvite?.code.orEmpty(),
                    if (!isEdit && setupMode == PairingSetupMode.JOIN) parsedInvite?.server ?: server else server,
                    if (isFcmOnDemand) FcmOnDemand.TRANSPORT else TRANSPORT_WS,
                    liveCallEnabledFor(selectedRole, liveCallEnabled),
                    if (isEdit) PairingSetupMode.EDIT else setupMode,
                    if (!isEdit && setupMode == PairingSetupMode.JOIN) parsedInvite?.secret else null,
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

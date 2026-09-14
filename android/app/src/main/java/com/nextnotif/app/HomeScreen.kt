package com.nextnotif.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pairings: List<PairingInfo>,
    onToggleService: () -> Unit,
    onBattery: () -> Unit,
    onCallScreening: () -> Unit,
    onGatewayDiagnostics: () -> Unit,
    onReset: () -> Unit,
    notifPermMissing: Boolean,
    onRequestNotifPerm: () -> Unit,
    liveCallPermMissing: Boolean,
    onFinishLiveCallSetup: () -> Unit,
    onAddPairing: () -> Unit,
    onOpenPairing: (PairingInfo) -> Unit,
    onEditPairing: (PairingInfo) -> Unit,
    onRemovePairing: (PairingInfo) -> Unit,
    onTogglePairing: (PairingInfo) -> Unit,
) {
    val connState by AppState.conn.collectAsState()
    val log by AppState.log.collectAsState()
    val savedMessages by AppState.messages.collectAsState()
    val outgoingSms by SmsOutbox.records.collectAsState()
    val messages = remember(savedMessages, outgoingSms) {
        (savedMessages + outgoingSms.filterNot { it.optBoolean("hidden") }.map(SmsOutbox::asEntry)).sortedByDescending { it.ts }
    }
    var composingNew by rememberSaveable { mutableStateOf(false) }
    val connStates by AppState.connStates.collectAsState()
    val partnerStates by AppState.partnerStates.collectAsState()
    val pairingErrors by AppState.pairingErrors.collectAsState()
    val liveCallCapabilities by GatewayCapabilityFeedback.states.collectAsState()
    val running = connState != AppState.ConnState.IDLE &&
        connState != AppState.ConnState.DISCONNECTED
    var showEntry by remember { mutableStateOf<AppState.Entry?>(null) }
    var destination by rememberSaveable { mutableStateOf(HomeDestination.OVERVIEW) }
    var selectedMessagePairing by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pairingNames = remember(pairings) { pairings.associate { it.code to it.displayName } }
    val activity = (log.filterNot(MessageStore::isHumanCommunication) + messages.filter { messagePresentation(it).kind == null }).sortedByDescending { it.ts }
    var selectedConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val filteredMessages = remember(messages, selectedMessagePairing) {
        MessageStore.filterByPairing(messages, selectedMessagePairing)
    }

    val conversations = remember(filteredMessages) { textConversations(filteredMessages) }
    val conversation = conversations.firstOrNull { it.key == selectedConversation }
    val calls = filteredMessages.filter { messagePresentation(it).kind == AppState.CommunicationKind.CALL }
    BackHandler(selectedConversation != null) { selectedConversation = null }

    LaunchedEffect(pairings, selectedMessagePairing) {
        if (selectedMessagePairing != null && pairings.none { it.code == selectedMessagePairing }) {
            selectedMessagePairing = null
        }
    }

    if (composingNew) {
        NewSmsScreen(pairings, onBack = { composingNew = false }, onSent = { id ->
            composingNew = false
            selectedMessagePairing = null
            selectedConversation = textConversations(SmsOutbox.records.value.map(SmsOutbox::asEntry))
                .firstOrNull { t -> t.entries.any { it.eventId == "sms-send:$id" } }?.key
            destination = HomeDestination.MESSAGES
        })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (conversation != null && destination == HomeDestination.MESSAGES) {
                        IconButton(onClick = { selectedConversation = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.conversation_back))
                        }
                    }
                },
                title = {
                    if (conversation != null && destination == HomeDestination.MESSAGES) {
                        Column {
                            Text(conversation.identity, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(conversation.code?.let { pairingNames[it] ?: "Pairing $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(state = connState, size = 10.dp)
                        Text(
                            stringResource(
                                when (destination) {
                                    HomeDestination.OVERVIEW -> R.string.home_title
                                    HomeDestination.MESSAGES -> R.string.home_messages_title
                                    HomeDestination.CALLS -> R.string.home_calls_title
                                    HomeDestination.ACTIVITY -> R.string.home_activity_title
                                }
                            )
                        )
                    }
                },
                actions = {
                    if ((destination == HomeDestination.MESSAGES || destination == HomeDestination.CALLS) && messages.isNotEmpty()) {
                        IconButton(onClick = { showClearHistoryConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.home_messages_clear),
                            )
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (running) R.string.menu_stop else R.string.menu_start
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (running) painterResource(R.drawable.ic_stop) else painterResource(R.drawable.ic_play),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onToggleService()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_add_pairing)) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAddPairing()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_battery)) },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onBattery()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_call_screening)) },
                                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onCallScreening()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_gateway_diagnostics)) },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onGatewayDiagnostics()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.menu_reset),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onReset()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (destination == HomeDestination.MESSAGES && conversation == null && pairings.any { it.enabled && it.role == Role.RECEIVER && !it.isFirebase }) {
                ExtendedFloatingActionButton(onClick = { composingNew = true },
                    icon = { Icon(Icons.Default.Edit, null) }, text = { Text(stringResource(R.string.sms_new_message)) })
            } else if (destination == HomeDestination.OVERVIEW) {
                ExtendedFloatingActionButton(
                    onClick = onAddPairing,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.menu_add_pairing)) },
                )
            }
        },
        bottomBar = {
            if (conversation != null && destination == HomeDestination.MESSAGES) {
                Box(Modifier.navigationBarsPadding().imePadding()) {
                    SmsComposer(pairings.firstOrNull { it.code == conversation.code }, conversationNumber(conversation), conversation.key)
                }
            } else NavigationBar {
                HomeDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item; selectedConversation = null },
                        icon = {
                            Icon(
                                when (item) {
                                    HomeDestination.OVERVIEW -> Icons.Default.Home
                                    HomeDestination.MESSAGES -> Icons.Default.Notifications
                                    HomeDestination.CALLS -> Icons.Default.Phone
                                    HomeDestination.ACTIVITY -> Icons.Default.Info
                                },
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (item) {
                                        HomeDestination.OVERVIEW -> R.string.home_nav_overview
                                        HomeDestination.MESSAGES -> R.string.home_nav_messages
                                        HomeDestination.CALLS -> R.string.home_nav_calls
                                        HomeDestination.ACTIVITY -> R.string.home_nav_activity
                                    }
                                )
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        key(destination, selectedConversation) {
        val historyListState = rememberLazyListState(
            initialFirstVisibleItemIndex = if (conversation != null && destination == HomeDestination.MESSAGES)
                conversation.entries.size + conversation.entries.map { historyDay(it.ts) }.distinct().size else 0,
        )
        LaunchedEffect(conversation?.entries?.firstOrNull()?.eventId) {
            if (conversation?.entries?.firstOrNull()?.communication?.smsStatus == "pending") {
                historyListState.animateScrollToItem(conversation.entries.size + conversation.entries.map { historyDay(it.ts) }.distinct().size)
            }
        }
        LazyColumn(
            state = historyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 0.dp,
                bottom = if (destination == HomeDestination.OVERVIEW || (destination == HomeDestination.MESSAGES && conversation == null)) 96.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (destination) {
                HomeDestination.OVERVIEW -> {
                    item {
                        RelayHeroCard(
                            running = running,
                            connState = connState,
                            pairingsCount = pairings.size,
                            partnersReady = partnerStates.values.count {
                                it.state == AppState.ConnState.CONNECTED ||
                                    it.state == AppState.ConnState.LISTENING ||
                                    it.state == AppState.ConnState.ON_DEMAND
                            },
                            onToggleService = onToggleService,
                        )
                    }
                    if (pairings.any { it.enabled && it.role == Role.SENDER && !it.isFirebase }) item { SmsPermissionCard() }
                    if (notifPermMissing) item { NotifPermBanner(onEnable = onRequestNotifPerm) }
                    if (liveCallPermMissing) item {
                        LiveCallPermBanner(onFinishSetup = onFinishLiveCallSetup)
                    }
                    item {
                        SectionHeader(
                            stringResource(R.string.home_section_pairings),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (pairings.isEmpty()) {
                        item { OnboardingCard(onAddPairing = onAddPairing) }
                    } else {
                        items(pairings, key = { it.code }) { p ->
                            val partner = partnerStates[p.code]
                                ?: AppState.PartnerState(AppState.ConnState.IDLE)
                            PairingCard(
                                pairing = p,
                                ownState = connStates[p.code] ?: AppState.ConnState.IDLE,
                                partnerState = partner.state,
                                partnerName = partner.name,
                                error = pairingErrors[p.code],
                                notificationPermissionMissing = notifPermMissing,
                                liveCallCapability = liveCallCapabilities[p.code],
                                enabled = p.enabled,
                                onClick = { onOpenPairing(p) },
                                onEdit = { onEditPairing(p) },
                                onRemove = { onRemovePairing(p) },
                                onToggle = { onTogglePairing(p) },
                            )
                        }
                    }
                }
                HomeDestination.MESSAGES, HomeDestination.CALLS -> {
                    val isCalls = destination == HomeDestination.CALLS
                    if (conversation != null && !isCalls) {
                        item { LogIntro(conversationNumber(conversation) ?: stringResource(R.string.conversation_history_only)) }
                        val chronological = conversation.entries.sortedBy { it.ts }
                        chronological.forEachIndexed { index, entry ->
                            val day = historyDay(entry.ts)
                            if (index == 0 || historyDay(chronological[index - 1].ts) != day) {
                                item { Text(day, modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            item { ConversationBubble(entry) }
                        }
                    } else {
                        item { LogIntro(stringResource(if (isCalls) R.string.home_calls_body else R.string.home_messages_body)) }
                        if (pairings.size > 1) {
                            item { MessagePairingFilters(pairings, selectedMessagePairing) { selectedMessagePairing = it } }
                        }
                        if ((isCalls && calls.isEmpty()) || (!isCalls && conversations.isEmpty())) {
                            item { EmptyLogState(
                                icon = if (isCalls) Icons.Default.Phone else Icons.Default.Notifications,
                                title = stringResource(if (isCalls) R.string.home_calls_empty_title else R.string.home_messages_empty_title),
                                body = stringResource(if (isCalls) R.string.home_calls_empty_body else R.string.home_messages_empty_body),
                            ) }
                        } else if (isCalls) {
                            items(calls.sortedByDescending { it.ts }) { MessageRow(it, pairingNames) }
                        } else {
                            items(conversations, key = { it.key }) { thread ->
                                ConversationRow(thread, pairingNames) { selectedConversation = thread.key }
                            }
                        }
                    }
                }
                HomeDestination.ACTIVITY -> {
                    item {
                        LogIntro(
                            body = stringResource(R.string.home_activity_body),
                        )
                    }
                    if (activity.isEmpty()) {
                        item {
                            EmptyLogState(
                                icon = Icons.Default.Info,
                                title = stringResource(R.string.home_activity_empty_title),
                                body = stringResource(R.string.home_activity_empty_body),
                            )
                        }
                    } else {
                        items(activity, key = { "activity-${it.ts}-${it.message}" }) { entry ->
                            LogRow(entry, pairingNames) { showEntry = entry }
                        }
                    }
                }
            }
        }
        }
    }
    showEntry?.let { entry ->
        ActivityDetailDialog(
            entry = entry,
            pairingName = entry.code?.let { code ->
                pairings.firstOrNull { it.code == code }?.displayName ?: "Pairing $code"
            },
            onDismiss = { showEntry = null },
        )
    }
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(stringResource(R.string.home_messages_clear_confirm_title)) },
            text = { Text(stringResource(R.string.home_messages_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppState.clearMessages(context)
                        SmsOutbox.store(context).hideHistory()
                        SmsOutbox.refresh(context)
                        selectedMessagePairing = null
                        selectedConversation = null
                        showClearHistoryConfirm = false
                    },
                ) {
                    Text(
                        stringResource(R.string.home_messages_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private enum class HomeDestination { OVERVIEW, MESSAGES, CALLS, ACTIVITY }

@Composable
private fun MessagePairingFilters(
    pairings: List<PairingInfo>,
    selectedCode: String?,
    onSelected: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedCode == null,
                onClick = { onSelected(null) },
                label = { Text(stringResource(R.string.home_messages_filter_all)) },
            )
        }
        items(pairings, key = { it.code }) { pairing ->
            FilterChip(
                selected = selectedCode == pairing.code,
                onClick = { onSelected(pairing.code) },
                label = {
                    Text(
                        pairing.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun RelayHeroCard(
    running: Boolean,
    connState: AppState.ConnState,
    pairingsCount: Int,
    partnersReady: Int,
    onToggleService: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val fg = if (running) Color.White else cs.onSurface
    val fgSecondary = if (running) Color.White.copy(alpha = 0.85f) else cs.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (running) cs.primaryContainer else cs.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (running) Modifier.background(Brush.horizontalGradient(Brand.heroGradient)) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state = connState, size = 14.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(if (running) R.string.home_relay_on else R.string.home_relay_off),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                    )
                    Text(
                        stringResource(
                            if (running) R.string.home_relay_on_summary else R.string.home_relay_off_summary
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = fgSecondary,
                    )
                }
                FilledTonalButton(
                    onClick = onToggleService,
                    colors = if (running) {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF4F46E5),
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                ) {
                    Icon(
                        if (running) painterResource(R.drawable.ic_stop) else painterResource(R.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (running) R.string.menu_stop else R.string.menu_start))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip(
                    label = stringResource(
                        if (pairingsCount == 1) R.string.home_chip_pairings_one
                        else R.string.home_chip_pairings_many,
                        pairingsCount,
                    ),
                    running = running,
                )
                if (running && pairingsCount > 0) {
                    HeroChip(
                        label = stringResource(R.string.home_chip_partners_ready, partnersReady),
                        running = running,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroChip(label: String, running: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (running) Color.White.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingCard(
    pairing: PairingInfo,
    ownState: AppState.ConnState,
    partnerState: AppState.ConnState,
    partnerName: String?,
    error: String?,
    notificationPermissionMissing: Boolean,
    liveCallCapability: GatewayCapability?,
    enabled: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (roleIcon, roleTint, roleOnTint) = if (pairing.role == Role.SENDER) {
        Triple(Icons.Default.Send, cs.primaryContainer, cs.onPrimaryContainer)
    } else {
        Triple(Icons.Default.Notifications, cs.secondaryContainer, cs.onSecondaryContainer)
    }
    val transportLabel = when {
        pairing.isFirebase -> stringResource(
            if (pairing.fbConfig.isNullOrBlank()) R.string.home_transport_firebase
            else R.string.home_transport_firebase_own
        )
        pairing.isFcmOnDemand -> stringResource(R.string.home_transport_fcm)
        else -> serverHost(pairing.server)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val readiness = pairingReadinessKind(
        enabled = enabled,
        role = pairing.role,
        onDemand = pairing.isFcmOnDemand,
        ownState = ownState,
        partnerState = partnerState,
        notificationPermissionMissing = notificationPermissionMissing,
        error = error,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(1.dp, cs.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(roleTint),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        roleIcon,
                        contentDescription = null,
                        tint = roleOnTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pairing.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${stringResource(
                            if (pairing.role == Role.SENDER) R.string.home_role_sender_short
                            else R.string.home_role_receiver_short
                        )} · ${transportLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = cs.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(if (enabled) R.string.menu_stop else R.string.menu_start))
                            },
                            leadingIcon = {
                                Icon(
                                    if (enabled) painterResource(R.drawable.ic_stop) else painterResource(R.drawable.ic_play),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggle()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.detail_edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.detail_remove),
                                    color = cs.error,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Close, contentDescription = null, tint = cs.error)
                            },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
            PairingReadinessSummary(
                kind = readiness,
                pairingCode = pairing.code,
                partnerName = partnerName,
                error = error,
                receiverOnDemand = pairing.role == Role.RECEIVER && pairing.isFcmOnDemand,
                senderOnDemand = pairing.role == Role.SENDER && pairing.isFcmOnDemand,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            if (enabled && pairing.role == Role.SENDER && pairing.liveCallEnabled) {
                LiveCallCapabilitySummary(
                    capability = liveCallCapability,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PairingReadinessSummary(
    kind: PairingReadinessKind,
    pairingCode: String,
    partnerName: String?,
    error: String?,
    receiverOnDemand: Boolean,
    senderOnDemand: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val dotState = when (kind) {
        PairingReadinessKind.READY -> AppState.ConnState.CONNECTED
        PairingReadinessKind.CONNECTING, PairingReadinessKind.CHECKING_PARTNER ->
            AppState.ConnState.CONNECTING
        PairingReadinessKind.WAITING_FOR_PARTNER -> AppState.ConnState.ON_DEMAND
        PairingReadinessKind.NEEDS_NOTIFICATION_PERMISSION,
        PairingReadinessKind.RETRYING,
        PairingReadinessKind.OFFLINE -> AppState.ConnState.DISCONNECTED
        PairingReadinessKind.PAUSED -> AppState.ConnState.IDLE
    }
    val title = when (kind) {
        PairingReadinessKind.READY -> partnerName?.let {
            stringResource(R.string.home_status_ready_for, it)
        } ?: stringResource(R.string.home_status_ready)
        PairingReadinessKind.CHECKING_PARTNER -> stringResource(R.string.home_status_checking_partner)
        PairingReadinessKind.WAITING_FOR_PARTNER -> stringResource(R.string.home_status_waiting_partner)
        PairingReadinessKind.NEEDS_NOTIFICATION_PERMISSION ->
            stringResource(R.string.home_status_needs_permission)
        PairingReadinessKind.CONNECTING -> stringResource(R.string.home_status_connecting)
        PairingReadinessKind.RETRYING -> stringResource(R.string.home_status_reconnecting)
        PairingReadinessKind.OFFLINE -> stringResource(R.string.home_status_offline)
        PairingReadinessKind.PAUSED -> stringResource(R.string.home_status_paused)
    }
    val detail = when (kind) {
        PairingReadinessKind.READY -> stringResource(
            when {
                receiverOnDemand -> R.string.home_status_ready_detail_receiver_fcm
                senderOnDemand -> R.string.home_status_ready_detail_sender_fcm
                else -> R.string.home_status_ready_detail
            },
        )
        PairingReadinessKind.CHECKING_PARTNER ->
            stringResource(R.string.home_status_checking_partner_detail)
        PairingReadinessKind.WAITING_FOR_PARTNER ->
            stringResource(R.string.home_status_waiting_partner_detail, pairingCode)
        PairingReadinessKind.NEEDS_NOTIFICATION_PERMISSION ->
            stringResource(R.string.home_status_needs_permission_detail)
        PairingReadinessKind.CONNECTING -> stringResource(R.string.home_status_connecting_detail)
        PairingReadinessKind.RETRYING -> when (pairingIssueKind(error.orEmpty())) {
            PairingIssueKind.FIREBASE_CONFIG -> stringResource(R.string.home_issue_firebase_config)
            PairingIssueKind.ALERT_REGISTRATION -> stringResource(R.string.home_issue_alert_registration)
            PairingIssueKind.QUEUE_SYNC -> stringResource(R.string.home_issue_queue_sync)
            PairingIssueKind.ACCESS_DENIED -> stringResource(R.string.home_issue_access_denied)
            PairingIssueKind.NETWORK -> stringResource(R.string.home_issue_network)
            PairingIssueKind.UNKNOWN -> stringResource(R.string.home_issue_unknown)
        }
        PairingReadinessKind.OFFLINE -> stringResource(R.string.home_status_offline_detail)
        PairingReadinessKind.PAUSED -> stringResource(R.string.home_status_paused_detail)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(state = dotState, size = 10.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun LiveCallCapabilitySummary(
    capability: GatewayCapability?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val current = capability ?: GatewayCapability.CHECKING
    val available = current == GatewayCapability.AVAILABLE
    val title = stringResource(
        if (available) R.string.home_live_ready else R.string.home_live_unavailable,
    )
    val detail = when (current) {
        GatewayCapability.CHECKING -> stringResource(R.string.home_live_checking_detail)
        GatewayCapability.AVAILABLE -> stringResource(R.string.home_live_ready_detail)
        GatewayCapability.ROOT_UNAVAILABLE -> stringResource(R.string.home_live_root_detail)
        GatewayCapability.HELPER_UNAVAILABLE -> stringResource(R.string.home_live_helper_detail)
        GatewayCapability.PROBE_FAILED -> stringResource(R.string.home_live_probe_detail)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (available) Icons.Default.Phone else Icons.Default.Warning,
            contentDescription = null,
            tint = if (available) cs.primary else cs.error,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = if (available) cs.primary else cs.error,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnboardingCard(onAddPairing: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(Brand.heroGradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_smartphone),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                stringResource(R.string.home_onboarding_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.home_onboarding_body),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepRow(1, stringResource(R.string.setup_how_step1))
                StepRow(2, stringResource(R.string.setup_how_step2))
                StepRow(3, stringResource(R.string.setup_how_step3))
            }
            Button(
                onClick = onAddPairing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.home_onboarding_cta))
            }
        }
    }
}

@Composable
private fun NotifPermBanner(onEnable: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = cs.onErrorContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Notifications are off",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onErrorContainer,
                )
                Text(
                    "Enable them so alerts from your paired phones appear",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onErrorContainer,
                )
            }
            Button(onClick = onEnable) {
                Text("Enable")
            }
        }
    }
}

@Composable
private fun LiveCallPermBanner(onFinishSetup: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Phone,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = cs.onSecondaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.live_call_perm_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.live_call_perm_banner_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSecondaryContainer,
                )
            }
            FilledTonalButton(onClick = onFinishSetup) {
                Text(stringResource(R.string.live_call_perm_finish))
            }
        }
    }
}

@Composable
private fun LogIntro(body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLogState(icon: ImageVector, title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal data class MessagePresentation(
    val kind: AppState.CommunicationKind?,
    val direction: AppState.CommunicationDirection?,
    val identity: String,
    val address: String? = null,
    val body: String? = null,
    val callState: String? = null,
    val isRelayTest: Boolean = false,
)

/** Structured entries are exact; legacy entries retain the best detail that was persisted. */
internal fun messagePresentation(entry: AppState.Entry): MessagePresentation {
    entry.communication?.let { details ->
        val address = details.address?.takeIf { it.isNotBlank() && it != "unknown" }
        val name = details.name?.takeIf { it.isNotBlank() }
        return MessagePresentation(
            kind = details.kind,
            direction = details.direction,
            identity = name ?: address ?: "Unknown caller",
            address = address?.takeIf { name != null && it != name },
            body = details.body,
            callState = details.callState,
        )
    }
    if (entry.tag == "TEST") {
        return MessagePresentation(
            kind = null,
            direction = AppState.CommunicationDirection.INCOMING,
            identity = "Relay test",
            body = entry.message,
            isRelayTest = true,
        )
    }
    val incoming = entry.tag == "IN"
    val direction = if (incoming) {
        AppState.CommunicationDirection.INCOMING
    } else {
        AppState.CommunicationDirection.OUTGOING
    }
    if (entry.message.startsWith("SMS ")) {
        val content = entry.message
            .removePrefix(if (incoming) "SMS from " else "SMS → ")
        val separator = content.indexOf(": ")
        return MessagePresentation(
            kind = AppState.CommunicationKind.SMS,
            direction = direction,
            identity = if (separator >= 0) content.substring(0, separator) else content,
            body = if (separator >= 0) content.substring(separator + 2) else null,
        )
    }
    if (entry.message.startsWith("Call ")) {
        val separator = if (incoming) " from " else " → "
        val rest = entry.message.removePrefix("Call ")
        return MessagePresentation(
            kind = AppState.CommunicationKind.CALL,
            direction = direction,
            identity = rest.substringAfter(separator, "Unknown caller"),
            callState = rest.substringBefore(separator).ifBlank { null },
        )
    }
    return MessagePresentation(
        kind = null,
        direction = direction,
        identity = entry.message,
    )
}

@Composable
private fun MessageRow(entry: AppState.Entry, pairingNames: Map<String, String>) {
    val presentation = remember(entry) { messagePresentation(entry) }
    val incoming = presentation.direction != AppState.CommunicationDirection.OUTGOING
    val cs = MaterialTheme.colorScheme
    val directionLabel = when {
        presentation.isRelayTest -> stringResource(R.string.home_messages_direction_test)
        incoming -> stringResource(R.string.home_messages_direction_received)
        else -> stringResource(R.string.home_messages_direction_sent)
    }
    val kindLabel = when (presentation.kind) {
        AppState.CommunicationKind.SMS -> stringResource(R.string.home_messages_kind_text)
        AppState.CommunicationKind.CALL -> stringResource(R.string.home_messages_kind_call)
        null -> null
    }
    val icon = when (presentation.kind) {
        AppState.CommunicationKind.SMS -> if (incoming) Icons.Default.Notifications else Icons.Default.Send
        AppState.CommunicationKind.CALL -> Icons.Default.Phone
        null -> Icons.Default.Check
    }
    val accent = if (incoming) cs.primaryContainer else cs.secondaryContainer
    val onAccent = if (incoming) cs.onPrimaryContainer else cs.onSecondaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.55f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = onAccent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    presentation.identity,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                presentation.address?.let { address ->
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Text(
                listOfNotNull(directionLabel, kindLabel).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = onAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        presentation.body?.let { body ->
            SelectionContainer {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                )
            }
        }
        presentation.callState?.let { state ->
            Text(
                when (state) {
                    "RINGING" -> stringResource(R.string.home_messages_call_ringing)
                    "OFFHOOK", "ACTIVE" -> stringResource(R.string.home_messages_call_connected)
                    "IDLE" -> stringResource(R.string.home_messages_call_ended)
                    else -> stringResource(R.string.home_messages_call_update, state)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.code?.let { pairingNames[it] ?: "Pairing $it" }
                    ?: stringResource(R.string.home_messages_pairing_unknown),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
            Text(
                remember(entry.ts) {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(entry.ts))
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogRow(entry: AppState.Entry, pairingNames: Map<String, String>, onClick: () -> Unit) {
    val visual = entryVisual(entry)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(visual.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                visual.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = visual.onChip,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    visual.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                entry.code?.let { code ->
                    PairingChip(pairingNames[code] ?: "Pairing $code")
                }
            }
            visual.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            timeAgo(entry.ts),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingChip(label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

internal data class LogVisual(
    val icon: ImageVector,
    val chip: Color,
    val onChip: Color,
    val title: String,
    val detail: String?,
)

@Composable
internal fun entryVisual(entry: AppState.Entry): LogVisual {
    val cs = MaterialTheme.colorScheme
    val msg = entry.message
    val neutral = LogVisual(
        icon = Icons.Default.Info,
        chip = cs.onSurfaceVariant.copy(alpha = 0.12f),
        onChip = cs.onSurfaceVariant,
        title = msg,
        detail = null,
    )
    val outChip = cs.secondaryContainer
    val onOutChip = cs.onSecondaryContainer
    return when (entry.tag) {
        "WS" -> {
            val title = when {
                msg.startsWith("connected as") -> "Connected to server"
                msg.startsWith("connected via Firebase") -> "Connected via Firebase"
                msg.startsWith("firebase connected") -> "Connected via Firebase"
                msg.startsWith("reconnecting ") -> {
                    val attempt = msg.substringAfter("(attempt ", "").substringBefore(")")
                    if (attempt.all(Char::isDigit)) "Reconnecting (attempt $attempt)..." else "Reconnecting..."
                }
                msg.startsWith("closed ") -> "Connection closed"
                msg.startsWith("firebase ") && msg.contains("disconnected") -> "Connection closed"
                msg.startsWith("error ") -> "Connection error"
                msg.startsWith("flushed ") -> {
                    val count = msg.substringAfter("flushed ", "").substringBefore(" queued")
                    if (count.all(Char::isDigit)) "Sent $count queued event(s)" else "Sent queued event(s)"
                }
                else -> msg
            }
            neutral.copy(title = title)
        }
        "IN" -> when {
            msg.startsWith("SMS from ") -> {
                val rest = msg.substringAfter("SMS from ", "")
                val sep = rest.indexOf(": ")
                if (sep > 0) {
                    LogVisual(
                        icon = Icons.Default.Notifications,
                        chip = cs.primaryContainer,
                        onChip = cs.onPrimaryContainer,
                        title = "Text from ${rest.substring(0, sep)}",
                        detail = rest.substring(sep + 2).ifEmpty { null },
                    )
                } else {
                    neutral
                }
            }
            msg.startsWith("Call ") -> {
                val state = msg.substringAfter("Call ", "").substringBefore(" from ")
                val number = msg.substringAfter(" from ", "")
                val title = when (state) {
                    "RINGING" -> "Incoming call from $number"
                    "OFFHOOK" -> "Call connected from $number"
                    "IDLE" -> "Call ended from $number"
                    else -> null
                }
                if (title != null) {
                    LogVisual(
                        icon = Icons.Default.Phone,
                        chip = cs.secondaryContainer,
                        onChip = cs.onSecondaryContainer,
                        title = title,
                        detail = null,
                    )
                } else {
                    neutral
                }
            }
            else -> neutral
        }
        "OUT" -> when {
            msg.startsWith("SMS → ") -> LogVisual(
                icon = Icons.Default.Send,
                chip = outChip,
                onChip = onOutChip,
                title = "Text forwarded: ${msg.substringAfter("SMS → ", "")}",
                detail = null,
            )
            msg.startsWith("queued sms") -> LogVisual(
                icon = Icons.Default.Send,
                chip = outChip,
                onChip = onOutChip,
                title = "Text queued (offline)",
                detail = null,
            )
            msg.startsWith("queued call") -> LogVisual(
                icon = Icons.Default.Send,
                chip = outChip,
                onChip = onOutChip,
                title = "Call queued (offline)",
                detail = null,
            )
            msg.startsWith("Call ") && msg.contains(" → ") -> {
                val state = msg.substringAfter("Call ", "").substringBefore(" → ")
                val number = msg.substringAfter(" → ", "")
                LogVisual(
                    icon = Icons.Default.Send,
                    chip = outChip,
                    onChip = onOutChip,
                    title = "Call state forwarded: $state ($number)",
                    detail = null,
                )
            }
            else -> neutral
        }
        else -> neutral
    }
}

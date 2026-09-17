package com.nextnotif.app

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pairings: List<PairingInfo>,
    onToggleService: () -> Unit,
    onBattery: () -> Unit,
    onCallScreening: () -> Unit,
    onReset: () -> Unit,
    notifPermMissing: Boolean,
    onRequestNotifPerm: () -> Unit,
    onAddPairing: () -> Unit,
    onOpenPairing: (PairingInfo) -> Unit,
    onEditPairing: (PairingInfo) -> Unit,
    onRemovePairing: (PairingInfo) -> Unit,
    onTogglePairing: (PairingInfo) -> Unit,
) {
    val connState by AppState.conn.collectAsState()
    val log by AppState.log.collectAsState()
    val connStates by AppState.connStates.collectAsState()
    val partnerStates by AppState.partnerStates.collectAsState()
    val pairingErrors by AppState.pairingErrors.collectAsState()
    val connSince by AppState.connSince.collectAsState()
    val running = connState != AppState.ConnState.IDLE
    var showEntry by remember { mutableStateOf<AppState.Entry?>(null) }
    // One tick per second while the relay is up, so the hero uptime chip tracks.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(state = connState, size = 10.dp)
                        Text(stringResource(R.string.home_title))
                    }
                },
                actions = {
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
            ExtendedFloatingActionButton(
                onClick = onAddPairing,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.menu_add_pairing)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 0.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RelayHeroCard(
                    running = running,
                    connState = connState,
                    pairingsCount = pairings.size,
                    partnersOnline = partnerStates.values.count {
                        it.state == AppState.ConnState.CONNECTED || it.state == AppState.ConnState.LISTENING
                    },
                    connSince = connSince,
                    nowMs = nowTick,
                    onToggleService = onToggleService,
                )
            }
            if (notifPermMissing) {
                item { NotifPermBanner(onEnable = onRequestNotifPerm) }
            }
            item {
                SectionHeader(stringResource(R.string.home_section_pairings), modifier = Modifier.padding(top = 8.dp))
            }
            if (pairings.isEmpty()) {
                item { OnboardingCard(onAddPairing = onAddPairing) }
            } else {
                items(pairings, key = { it.code }) { p ->
                    val partner = partnerStates[p.code] ?: AppState.PartnerState(AppState.ConnState.IDLE)
                    PairingCard(
                        pairing = p,
                        ownState = connStates[p.code] ?: AppState.ConnState.IDLE,
                        partnerState = partner.state,
                        partnerName = partner.name,
                        error = pairingErrors[p.code],
                        enabled = p.enabled,
                        onClick = { onOpenPairing(p) },
                        onEdit = { onEditPairing(p) },
                        onRemove = { onRemovePairing(p) },
                        onToggle = { onTogglePairing(p) },
                    )
                }
            }
            item {
                SectionHeader(stringResource(R.string.home_activity), modifier = Modifier.padding(top = 8.dp))
            }
            if (log.isEmpty()) {
                item { EmptyActivityCard() }
            } else {
                itemsIndexed(log) { _, entry ->
                    LogRow(
                        entry,
                        pairingNames = pairings.associate { it.code to it.displayName },
                        onClick = { showEntry = entry },
                    )
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
}

@Composable
private fun RelayHeroCard(
    running: Boolean,
    connState: AppState.ConnState,
    pairingsCount: Int,
    partnersOnline: Int,
    connSince: Long,
    nowMs: Long,
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
                if (running && connSince > 0) {
                    HeroChip(
                        label = stringResource(R.string.feed_uptime, uptimeLabel(connSince, nowMs)),
                        running = running,
                    )
                }
                if (running && pairingsCount > 0) {
                    HeroChip(
                        label = stringResource(R.string.home_chip_partners_online, partnersOnline),
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
    val transportLabel = if (pairing.isFirebase) {
        stringResource(
            if (pairing.fbConfig.isNullOrBlank()) R.string.home_transport_firebase
            else R.string.home_transport_firebase_own
        )
    } else {
        serverHost(pairing.server)
    }
    var menuOpen by remember { mutableStateOf(false) }

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
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    state = ownState,
                    prefix = stringResource(R.string.home_this_phone),
                )
                StatusPill(
                    state = partnerState,
                    prefix = partnerPrefix(partnerName) ?: stringResource(R.string.home_partner),
                    partner = true,
                )
            }
            if (error != null) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = cs.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
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
private fun EmptyActivityCard() {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = cs.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    stringResource(R.string.home_activity_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
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

/** Human label for an event participant: contact name when known, else number. */
internal fun eventParticipant(name: String?, number: String): String =
    when {
        name != null && number != "unknown" -> "$name ($number)"
        name != null -> name
        else -> number
    }

@Composable
internal fun entryVisual(entry: AppState.Entry): LogVisual {
    val cs = MaterialTheme.colorScheme
    val neutral = LogVisual(
        icon = Icons.Default.Info,
        chip = cs.onSurfaceVariant.copy(alpha = 0.12f),
        onChip = cs.onSurfaceVariant,
        title = "",
        detail = null,
    )
    val outChip = cs.secondaryContainer
    val onOutChip = cs.onSecondaryContainer
    return when (val kind = entry.kind) {
        is AppState.EventKind.SmsIn -> LogVisual(
            icon = Icons.Default.Notifications,
            chip = cs.primaryContainer,
            onChip = cs.onPrimaryContainer,
            title = stringResource(R.string.feed_sms_in, eventParticipant(kind.name, kind.from)),
            detail = kind.body.ifEmpty { null },
        )
        is AppState.EventKind.CallIn -> {
            val participant = eventParticipant(kind.name, kind.number)
            val title = when (kind.state) {
                "RINGING" -> stringResource(R.string.feed_call_in_ringing, participant)
                "OFFHOOK" -> stringResource(R.string.feed_call_in_offhook, participant)
                "IDLE" -> stringResource(R.string.feed_call_in_idle, participant)
                else -> stringResource(R.string.feed_call_in_other, kind.state, participant)
            }
            LogVisual(
                icon = Icons.Default.Phone,
                chip = cs.secondaryContainer,
                onChip = cs.onSecondaryContainer,
                title = title,
                detail = null,
            )
        }
        is AppState.EventKind.SmsOut -> LogVisual(
            icon = Icons.Default.Send,
            chip = outChip,
            onChip = onOutChip,
            title = stringResource(R.string.feed_sms_out, eventParticipant(kind.name, kind.from)),
            detail = null,
        )
        is AppState.EventKind.CallOut -> LogVisual(
            icon = Icons.Default.Send,
            chip = outChip,
            onChip = onOutChip,
            title = stringResource(R.string.feed_call_out, kind.state, kind.number),
            detail = null,
        )
        is AppState.EventKind.Connected -> LogVisual(
            icon = Icons.Default.Info,
            chip = neutral.chip,
            onChip = neutral.onChip,
            title = stringResource(
                if (kind.firebase) R.string.feed_connected_fb else R.string.feed_connected_ws
            ),
            detail = null,
        )
        is AppState.EventKind.Closed -> neutral.copy(title = stringResource(R.string.feed_closed))
        is AppState.EventKind.Failed -> neutral.copy(title = stringResource(R.string.feed_failed))
        is AppState.EventKind.Reconnecting -> neutral.copy(
            title = stringResource(R.string.feed_reconnecting, kind.attempt),
        )
        is AppState.EventKind.Flushed -> neutral.copy(
            title = if (kind.count == 1) {
                stringResource(R.string.feed_flushed_one)
            } else {
                stringResource(R.string.feed_flushed, kind.count)
            },
        )
        is AppState.EventKind.Queued -> neutral.copy(
            title = when (kind.type) {
                "sms" -> stringResource(R.string.feed_queued_sms)
                "call" -> stringResource(R.string.feed_queued_call)
                else -> stringResource(R.string.feed_queued_other, kind.type)
            },
        )
        AppState.EventKind.StillWaiting -> neutral.copy(title = stringResource(R.string.feed_still_waiting))
        is AppState.EventKind.Info -> neutral.copy(title = kind.message)
    }
}

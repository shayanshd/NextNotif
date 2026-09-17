package com.nextnotif.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

const val TRANSPORT_WS = "ws"
const val MIN_SECRET_LENGTH = 6

/** Transport to preselect when opening the editor: Firebase only if the
 *  pairing is actually a Firebase relay; anything else (including relay
 *  pairings, whose transport persists as null) defaults to WS. */
fun initialTransportFor(editing: PairingInfo?): String =
    if (editing?.isFirebase == true) FirebaseRelay.TRANSPORT else TRANSPORT_WS

/** Parses a `/pair/{code}/status` body into (partnerOnline, partnerName).
 *  The partner is the slot opposite to [myRole]. A name is only returned when
 *  it is a real, non-blank string: JSON null and a literal "null" string (seen
 *  in the wild from a sinkholed/stale relay answer) both count as unknown, so
 *  the UI falls back to the "Partner" label instead of rendering "null". */
fun partnerStatusFrom(statusJson: String, myRole: Role): Pair<Boolean, String?> {
    val obj = org.json.JSONObject(statusJson)
    val senderOk = obj.optBoolean("sender_connected")
    val receiverOk = obj.optBoolean("receiver_connected")
    val partnerOnline = if (myRole == Role.SENDER) receiverOk else senderOk
    val raw = try {
        if (myRole == Role.SENDER) obj.optString("receiver_name") else obj.optString("sender_name")
    } catch (e: Exception) {
        "" // some org.json builds throw on a missing key
    }
    val name = raw.takeIf { it.isNotBlank() && it != "null" }
    return partnerOnline to name
}

/** Fixed dot colors for connection states (intentionally outside the
 *  dynamic color scheme — status colors must be consistent everywhere). */
object ConnVisuals {
    fun dot(state: AppState.ConnState): Color = when (state) {
        AppState.ConnState.CONNECTED -> Color(0xFF16A34A)
        AppState.ConnState.LISTENING -> Color(0xFF3B82F6)
        AppState.ConnState.CONNECTING -> Color(0xFFF59E0B)
        AppState.ConnState.DISCONNECTED -> Color(0xFFEF4444)
        AppState.ConnState.IDLE -> Color(0xFF9CA3AF)
    }

    fun label(state: AppState.ConnState, partner: Boolean = false): String =
        if (partner) {
            when (state) {
                AppState.ConnState.CONNECTED -> "Online"
                AppState.ConnState.LISTENING -> "Listening"
                AppState.ConnState.CONNECTING -> "Connecting"
                AppState.ConnState.DISCONNECTED -> "Offline"
                AppState.ConnState.IDLE -> "Unknown"
            }
        } else {
            when (state) {
                AppState.ConnState.CONNECTED -> "Connected"
                AppState.ConnState.LISTENING -> "Relaying"
                AppState.ConnState.CONNECTING -> "Connecting"
                AppState.ConnState.DISCONNECTED -> "Offline"
                AppState.ConnState.IDLE -> "Stopped"
            }
        }
}

@Composable
fun StatusDot(state: AppState.ConnState, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val pulsing = state == AppState.ConnState.CONNECTED || state == AppState.ConnState.LISTENING
    var alpha by remember { mutableFloatStateOf(1f) }
    if (pulsing) {
        val transition = rememberInfiniteTransition()
        alpha = transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        ).value
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ConnVisuals.dot(state).copy(alpha = alpha)),
    )
}

/** Compact pill: dot + label, e.g. "This phone: Connected" / "Partner: Online". */
@Composable
fun StatusPill(
    state: AppState.ConnState,
    prefix: String,
    partner: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ConnVisuals.dot(state).copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ConnVisuals.dot(state)),
        )
        Text(
            "$prefix: ${ConnVisuals.label(state, partner = partner)}",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun TransportOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        badge?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun StepRow(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Strips the scheme + port from a relay server URL for display. */
fun serverHost(server: String): String =
    server
        .replaceFirst("ws://", "")
        .replaceFirst("wss://", "")
        .substringBefore(":")

/** "now" / "N min ago" for the last hour, HH:mm today, else "M/d HH:mm". */
fun timeAgo(ts: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = nowMs - ts
    if (diff < 45_000L) return "now"
    if (diff < 3_600_000L) return "${diff / 60_000L} min ago"
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    val date = java.text.SimpleDateFormat("M/d", java.util.Locale.US)
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = nowMs
    val that = java.util.Calendar.getInstance()
    that.timeInMillis = ts
    return if (cal.get(java.util.Calendar.YEAR) == that.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == that.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        time.format(java.util.Date(ts))
    } else {
        "${date.format(java.util.Date(ts))} ${time.format(java.util.Date(ts))}"
    }
}

/** Relay uptime for the hero chip: "45s" under a minute, "12m" under an hour,
 *  "3h 05m" under a day, else "2d 5h". Pure for JVM tests. */
fun uptimeLabel(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (sinceMs <= 0L || nowMs <= sinceMs) return "0s"
    var seconds = (nowMs - sinceMs) / 1000L
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    if (hours < 24) {
        val rem = minutes % 60
        return "${hours}h ${rem.toString().padStart(2, '0')}m"
    }
    val days = hours / 24
    return "${days}d ${hours % 24}h"
}

/** Prefix for the partner status pill: the peer's advertised device name
 *  ("Xiaomi 23049PCD8G") when known, null to fall back to "Partner".
 *  Truncated so the pill still fits the card. */
fun partnerPrefix(name: String?): String? =
    name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length > 20) it.take(20) + "…" else it }

@Composable
fun RoleCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopEnd,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
fun PrimaryCta(
    text: String,
    enabled: Boolean,
    busy: Boolean,
    busyLabel: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = busyLabel,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
fun FieldCard(
    label: String,
    helper: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
            if (helper != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Full-entry dialog for activity feed rows: title, the complete detail
 *  (unwrapped, selectable), the pairing label when the entry is tied to one,
 *  and the timestamp. Shared by the home feed and the pairing detail screen. */
@Composable
fun ActivityDetailDialog(
    entry: AppState.Entry,
    pairingName: String?,
    onDismiss: () -> Unit,
) {
    val visual = entryVisual(entry)
    val fullTime = java.text.SimpleDateFormat("M/d/yy, HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(entry.ts))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(visual.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        visual.icon,
                        contentDescription = null,
                        tint = visual.onChip,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    visual.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visual.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (pairingName != null) {
                        Text(
                            pairingName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${timeAgo(entry.ts)} · $fullTime",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

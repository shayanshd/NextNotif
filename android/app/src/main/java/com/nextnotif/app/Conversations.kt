package com.nextnotif.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal data class TextConversation(
    val key: String,
    val code: String?,
    val identity: String,
    val entries: List<AppState.Entry>,
)

/** Normalize formatting only: guessing country codes or matching names can merge unrelated people. */
internal fun textConversations(entries: List<AppState.Entry>): List<TextConversation> = entries
    .filter { messagePresentation(it).kind == AppState.CommunicationKind.SMS }
    .groupBy { entry ->
        val raw = entry.communication?.address ?: if (entry.communication == null) messagePresentation(entry).identity else null
        val address = raw?.trim()?.takeUnless { it.isBlank() || it.equals("unknown", true) }
        val normalized = address?.let {
            if (it.all { c -> c.isDigit() || c in "+-(). " }) it.filter { c -> c.isDigit() || c == '+' }
            else it.lowercase(Locale.ROOT)
        }
        // Missing identities must never become a single fictitious contact.
        val identity = normalized ?: "unknown:${entry.eventId}:${entry.ts}:${entry.message}"
        "${entry.code.orEmpty().length}:${entry.code.orEmpty()}:$identity"
    }
    .map { (key, messages) ->
        val sorted = messages.sortedByDescending { it.ts }
        val named = sorted.firstOrNull { !it.communication?.name.isNullOrBlank() } ?: sorted.first()
        TextConversation(key, sorted.first().code, messagePresentation(named).identity, sorted)
    }
    .sortedByDescending { it.entries.first().ts }

internal fun historyDay(ts: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))

@Composable
internal fun ConversationRow(thread: TextConversation, pairingNames: Map<String, String>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val latest = thread.entries.first()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(thread.identity.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(thread.identity, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(historyDay(latest.ts), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Text(messagePresentation(latest).body.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
            Text(listOfNotNull(thread.code?.let { pairingNames[it] ?: "Pairing $it" },
                pluralStringResource(R.plurals.conversation_count, thread.entries.size, thread.entries.size)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = cs.outlineVariant)
}

@Composable
internal fun ConversationBubble(entry: AppState.Entry) {
    val presentation = messagePresentation(entry)
    val outgoing = presentation.direction == AppState.CommunicationDirection.OUTGOING
    val cs = MaterialTheme.colorScheme
    val foreground = if (outgoing) cs.onSecondaryContainer else cs.onSurface
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth(0.88f).widthIn(max = 560.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (outgoing) cs.secondaryContainer else cs.surfaceVariant)
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(presentation.body.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = foreground)
            }
            Text((entry.communication?.smsStatus?.let { smsStatusLabel(it) }
                ?: stringResource(if (outgoing) R.string.home_messages_direction_sent else R.string.home_messages_direction_received)) +
                " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.ts)),
                style = MaterialTheme.typography.labelSmall, color = foreground)
            entry.communication?.smsDetail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = foreground)
            }
        }
    }
}

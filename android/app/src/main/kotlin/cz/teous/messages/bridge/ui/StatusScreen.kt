package cz.teous.messages.bridge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.teous.liquid.LocalGlassTokens
import cz.teous.messages.bridge.R
import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.core.Hub

/**
 * The status page. No title, since it's the app's only page (teodor-taste, Phone apps): the networks' card, then
 * Messages and the notification wake, then the source. Logging in happens in Messages, so nothing here changes an
 * account.
 */
@Composable
fun StatusScreen(hub: Hub, messages: MessagesApp, wakeGranted: Boolean, onAllowWake: () -> Unit, onSource: () -> Unit) {
    val accounts by hub.accounts.collectAsState()
    val attached by hub.attached.collectAsState()
    StatusScreen(accounts, hub.networks().associate { it.id to it.name }, attached, messages, wakeGranted, onAllowWake, onSource)
}

/** [names] are the networks' names by id. */
@Composable
fun StatusScreen(
    accounts: List<AccountState>,
    names: Map<String, String>,
    attached: Boolean,
    messages: MessagesApp,
    wakeGranted: Boolean,
    onAllowWake: () -> Unit,
    onSource: () -> Unit,
) {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(insets)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            if (accounts.isEmpty()) {
                StatusRow(Icons.Message, stringResource(R.string.app_name), stringResource(R.string.no_networks))
            }
            for (a in accounts) {
                StatusRow(Icons.network(a.network), names[a.network] ?: a.network, stateText(a), problem = a.state == AccountState.BAD_CREDENTIALS)
            }
        }
        Card {
            val messagesText = when {
                messages == MessagesApp.Missing -> stringResource(R.string.messages_missing)
                messages == MessagesApp.Foreign -> stringResource(R.string.messages_foreign)
                attached -> stringResource(R.string.messages_attached)
                else -> stringResource(R.string.messages_ready)
            }
            StatusRow(Icons.Message, stringResource(R.string.messages), messagesText, problem = messages == MessagesApp.Foreign)
            StatusRow(
                Icons.Bell,
                stringResource(R.string.wake_title),
                stringResource(if (wakeGranted) R.string.wake_on else R.string.wake_off),
                action = if (wakeGranted) null else stringResource(R.string.wake_allow) to onAllowWake,
            )
        }
        Card {
            StatusRow(Icons.Code, stringResource(R.string.source), stringResource(R.string.licence), onClick = onSource)
        }
    }
}

@Composable
private fun stateText(a: AccountState): String {
    val base = stringResource(
        when (a.state) {
            AccountState.CONNECTING -> R.string.state_connecting
            AccountState.CONNECTED -> R.string.state_connected
            AccountState.SLEEPING -> R.string.state_sleeping
            AccountState.TRANSIENT_DISCONNECT -> R.string.state_transient
            AccountState.BAD_CREDENTIALS -> R.string.state_bad_credentials
            AccountState.UNKNOWN_ERROR -> R.string.state_error
            else -> R.string.state_logged_out
        },
    )
    val who = a.displayName
    return if (who != null && a.state != AccountState.LOGGED_OUT) "$base · $who" else base
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().rimCard(22.dp).padding(vertical = 6.dp)) { content() }
}

/** A row: the item's mark on a lens disc, its name and state, and a lit button or the chevron disc when it leads on. */
@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    status: String,
    problem: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalGlassTokens.current
    val shadow = t.textShadow.firstOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).rowGlass(22.dp), contentAlignment = Alignment.Center) {
            Image(rememberVectorPainter(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A problem turns only its value red, with a red dot beside it (teodor-taste, Red).
                if (problem) {
                    Box(Modifier.size(6.dp).background(t.red, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(6.dp))
                }
                BasicText(status, style = TextStyle(color = if (problem) t.red else t.text3, fontSize = 13.sp, shadow = shadow))
            }
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .rowGlass(22.dp)
                    .clickable(role = Role.Button, onClick = action.second)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(action.first, style = TextStyle(color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, shadow = shadow), modifier = Modifier.padding(vertical = 12.dp))
            }
        } else if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(32.dp).rowGlass(16.dp), contentAlignment = Alignment.Center) {
                Image(rememberVectorPainter(Icons.Chevron), contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

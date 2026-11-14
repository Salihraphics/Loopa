package com.loopa.feature.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.Remote
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.game.SocialFormat
import com.loopa.core.model.SocialOverview
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InviteFriendsViewModel(private val c: DataContainer) : ViewModel() {
    val overview: StateFlow<Remote<SocialOverview>> = c.social.overview
    private val _sent = MutableStateFlow<Set<String>>(emptySet())
    val sent: StateFlow<Set<String>> = _sent.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load() { viewModelScope.launch { c.social.refreshOverview() } }

    fun invite(userId: String, roomId: String) {
        if (_busy.value != null) return
        _busy.value = userId
        viewModelScope.launch {
            try { c.social.sendRoomInvite(userId, roomId); _sent.update { it + userId } }
            catch (e: LoopaException) { _message.value = e.error.message }
            finally { _busy.value = null }
        }
    }
    fun dismiss() { _message.value = null }
}

/** Friends of the signed-in player, each with a "Davet et" button for this room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteFriendsSheet(roomId: String, memberIds: Set<String>, onDismiss: () -> Unit) {
    val vm = containerViewModel { InviteFriendsViewModel(it) }
    val overview by vm.overview.collectAsStateWithLifecycle()
    val sent by vm.sent.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Loopa.colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).testTag("invite_sheet")) {
            Text("Arkadaşlarını davet et", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
            Text("Davet 15 dakika geçerli. Arkadaşın bildirimlerden ya da Sosyal ekranından katılır.",
                style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
            ErrorBanner(message, Modifier.padding(vertical = Spacing.xs), onDismiss = vm::dismiss)
            val friends = overview.data?.friends
            when {
                friends == null && overview.error != null -> Text(
                    if (overview.error == LoopaError.SERVER_OUTDATED) "Davetler sunucu güncellendiğinde açılacak. Şimdilik oda kodunu paylaşabilirsin."
                    else overview.error!!.message, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft,
                    modifier = Modifier.padding(vertical = Spacing.lg))
                friends == null -> CircularProgressIndicator(Modifier.padding(Spacing.lg).align(Alignment.CenterHorizontally))
                friends.isEmpty() -> Text("Henüz arkadaşın yok. Sosyal sekmesinden arkadaş ekleyebilir ya da oda kodunu paylaşabilirsin.",
                    style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, modifier = Modifier.padding(vertical = Spacing.lg))
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(friends.sortedByDescending { it.presence.online }, key = { it.userId }) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("invite_friend_${f.userId}")) {
                            AvatarView(f.avatar, size = 44.dp, showFrame = false)
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(f.username, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull("Seviye ${f.level}", SocialFormat.presence(f.presence)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                            }
                            when {
                                f.userId in memberIds -> Text("Odada", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
                                f.userId in sent -> Text("Davet edildi ✓", style = MaterialTheme.typography.labelMedium, color = LoopaPalette.MintDeep)
                                else -> LoopaButton("Davet et", { vm.invite(f.userId, roomId) }, color = LoopaPalette.Butter, compact = true,
                                    loading = busy == f.userId, modifier = Modifier.testTag("invite_send_${f.userId}"))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = Spacing.xl))
        }
    }
}

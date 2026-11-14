package com.loopa.feature.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.Remote
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaException
import com.loopa.core.game.SocialFormat
import com.loopa.core.model.Conversation
import com.loopa.core.model.InviteOutcome
import com.loopa.core.model.RoomInviteItem
import com.loopa.core.model.SocialBadges
import com.loopa.core.model.SocialOverview
import com.loopa.core.ui.components.CountBadge
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.GameArtwork
import com.loopa.core.ui.components.IconAction
import com.loopa.core.ui.components.ListRow
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaSurface
import com.loopa.core.ui.components.SectionHeader
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SocialViewModel(private val c: DataContainer) : ViewModel() {
    val overview: StateFlow<Remote<SocialOverview>> = c.social.overview
    val badges: StateFlow<SocialBadges> = c.social.badges
    val conversations: StateFlow<Remote<List<Conversation>>> = c.social.conversations
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        viewModelScope.launch { c.social.refreshOverview() }
        viewModelScope.launch { c.social.refreshConversations() }
        viewModelScope.launch { runCatching { c.social.refreshBadges() } }
    }

    private fun act(key: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        _busy.value = key
        viewModelScope.launch {
            try { block() } catch (e: LoopaException) { _message.value = e.error.message } finally { _busy.value = null }
        }
    }

    fun respondRequest(id: String, accept: Boolean) = act(id) { c.social.respondFriendRequest(id, accept) }

    fun respondInvite(invite: RoomInviteItem, accept: Boolean, onJoined: (String) -> Unit) = act(invite.inviteId) {
        when (val r = c.social.respondRoomInvite(invite.inviteId, accept)) {
            is InviteOutcome.Joined -> onJoined(r.roomId)
            InviteOutcome.Expired -> _message.value = "Bu davetin süresi dolmuş."
            InviteOutcome.RoomGone -> _message.value = "Bu oda artık yok. Davet kapatıldı."
            InviteOutcome.AlreadyAnswered -> _message.value = "Bu davet zaten cevaplanmış."
            InviteOutcome.Declined -> Unit
        }
    }

    fun dismissMessage() { _message.value = null }
}

@Composable
fun SocialScreen(
    onPublicRooms: () -> Unit,
    onOpenGames: () -> Unit,
    onFindFriends: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    onOpenConversations: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onRoomReady: (String) -> Unit = {},
) {
    val vm = containerViewModel { SocialViewModel(it) }
    val overview by vm.overview.collectAsStateWithLifecycle()
    val badges by vm.badges.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    SocialContent(
        overview, badges, conversations, busy, message,
        onPublicRooms = onPublicRooms, onOpenGames = onOpenGames, onFindFriends = onFindFriends, onOpenProfile = onOpenProfile,
        onOpenChat = onOpenChat, onOpenConversations = onOpenConversations, onOpenNotifications = onOpenNotifications,
        onRespondRequest = vm::respondRequest, onRespondInvite = { i, a -> vm.respondInvite(i, a, onRoomReady) },
        onDismissMessage = vm::dismissMessage,
    )
}

/** Stateless social hub (used by screenshot tests). */
@Composable
fun SocialContent(
    overview: Remote<SocialOverview>,
    badges: SocialBadges,
    conversations: Remote<List<Conversation>>,
    busy: String?,
    message: String?,
    onPublicRooms: () -> Unit,
    onOpenGames: () -> Unit,
    onFindFriends: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenConversations: () -> Unit,
    onOpenNotifications: () -> Unit,
    onRespondRequest: (String, Boolean) -> Unit,
    onRespondInvite: (RoomInviteItem, Boolean) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val data = overview.data
    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter).testTag("social_screen"),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text("Sosyal", style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text, modifier = Modifier.weight(1f))
                IconAction(Icons.Rounded.PersonAdd, "Arkadaş bul", onFindFriends, Modifier.testTag("social_find"))
                Box {
                    IconAction(Icons.Rounded.Notifications, "Bildirimler", onOpenNotifications, Modifier.testTag("social_notifications"))
                    CountBadge(badges.notifications, Modifier.align(Alignment.TopEnd))
                }
            }
            ErrorBanner(message, onDismiss = onDismissMessage)

            if (data == null && overview.error != null) SocialUnavailable(overview.error, Modifier.testTag("social_unavailable"))

            if (data != null) {
                // 1. Room invites: they expire, so they come first.
                AnimatedVisibility(data.invites.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SectionHeader("Oda davetleri")
                        data.invites.forEach { InviteCard(it, busy == it.inviteId, onRespondInvite) }
                    }
                }
                // 2. Friend requests waiting for an answer
                AnimatedVisibility(data.incoming.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SectionHeader("Arkadaşlık istekleri (${data.incoming.size})")
                        LoopaSurface(padding = PaddingValues(Spacing.sm), modifier = Modifier.testTag("social_requests")) {
                            data.incoming.forEach { r ->
                                PersonRow(r.user.username, r.user.avatar, subtitle = "Seviye ${r.user.level} · ${SocialFormat.ago(r.createdAt)}",
                                    onClick = { onOpenProfile(r.user.userId) }, modifier = Modifier.testTag("request_${r.requestId}")) {
                                    IconAction(Icons.Rounded.Close, "Reddet", { onRespondRequest(r.requestId, false) }, Modifier.testTag("request_decline"))
                                    IconAction(Icons.Rounded.Check, "Kabul et", { onRespondRequest(r.requestId, true) }, Modifier.testTag("request_accept"))
                                }
                            }
                        }
                    }
                }
                // 3. Who is around right now
                val online = data.onlineFriends
                if (online.isNotEmpty()) {
                    Column {
                        SectionHeader("Şu an çevrim içi")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.testTag("social_online")) {
                            items(online, key = { it.userId }) { f ->
                                Column(Modifier.width(72.dp).clip(Radii.md).clickable { onOpenChat(f.userId) }.padding(vertical = Spacing.xs),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    PresenceAvatar(f.avatar, f.presence, 56.dp)
                                    Text(f.username, style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
                // 4. Messages
                val convs = conversations.data.orEmpty()
                if (convs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        SectionHeader("Mesajlar", action = "Tümü", onAction = onOpenConversations)
                        LoopaSurface(padding = PaddingValues(Spacing.sm), modifier = Modifier.testTag("social_conversations")) {
                            convs.take(3).forEach { cv -> ConversationRow(cv) { onOpenChat(cv.userId) } }
                        }
                    }
                }
                // 5. Friends
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SectionHeader(if (data.friends.isEmpty()) "Arkadaşların" else "Arkadaşların (${data.friends.size})",
                        action = "Ekle", onAction = onFindFriends)
                    if (data.friends.isEmpty()) {
                        LoopaSurface(Modifier.testTag("social_empty")) {
                            SocialEmpty("Henüz arkadaşın yok",
                                "Kullanıcı adıyla arkadaşlarını bul, istek gönder. Kabul ettiklerinde birbirinizi odalara davet edip mesajlaşabilirsiniz.") {
                                LoopaButton("Arkadaş bul", onFindFriends, icon = Icons.Rounded.PersonAdd, color = LoopaPalette.Mint, compact = true,
                                    modifier = Modifier.testTag("social_empty_find"))
                            }
                        }
                    } else {
                        LoopaSurface(padding = PaddingValues(Spacing.sm), modifier = Modifier.testTag("social_friends")) {
                            data.friends.forEach { f ->
                                PersonRow(f.username, f.avatar, subtitle = presenceLine(f.level, f.presence), presence = f.presence,
                                    onClick = { onOpenProfile(f.userId) }, modifier = Modifier.testTag("friend_${f.userId}")) {
                                    IconAction(Icons.AutoMirrored.Rounded.Chat, "${f.username} ile mesajlaş", { onOpenChat(f.userId) })
                                }
                            }
                        }
                    }
                    if (data.outgoing.isNotEmpty()) {
                        Text("${data.outgoing.size} istek cevap bekliyor", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.accentText,
                            modifier = Modifier.clip(Radii.pill).clickable(onClick = onFindFriends).padding(Spacing.sm).testTag("social_outgoing"))
                    }
                }
            }

            // Always available: playing with others without an account relation.
            SectionHeader("Birlikte oyna")
            LoopaSurface(padding = PaddingValues(Spacing.sm)) {
                ListRow("Açık odalar", subtitle = "Herkese açık bir odaya katıl, yeni insanlarla oyna.", icon = Icons.Rounded.Public,
                    iconTint = LoopaPalette.Sky, onClick = onPublicRooms, modifier = Modifier.testTag("social_public_rooms"))
                ListRow("Oda kodunu paylaş", subtitle = "Bir oda kur; lobide arkadaşlarını davet et ya da kodu paylaş.", icon = Icons.Rounded.Share,
                    iconTint = LoopaPalette.Mint, onClick = onOpenGames, modifier = Modifier.testTag("social_invite"))
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun InviteCard(invite: RoomInviteItem, busy: Boolean, onRespond: (RoomInviteItem, Boolean) -> Unit) {
    LoopaSurface(color = LoopaPalette.Butter.copy(alpha = 0.45f), modifier = Modifier.testTag("invite_${invite.inviteId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameArtwork(invite.gameMode, Modifier.size(52.dp).clip(Radii.sm), label = GameModes.name(invite.gameMode))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text("${invite.from.username} seni davet etti", style = MaterialTheme.typography.titleSmall, color = LoopaPalette.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(GameModes.name(invite.gameMode), "Oda ${invite.roomCode}", SocialFormat.remaining(invite.expiresAt))
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.7f))
                if (invite.roomStatus == "in_game") Text("Oyun başladı: izleyici olarak katılırsın.", style = MaterialTheme.typography.labelSmall,
                    color = LoopaPalette.Ink.copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            LoopaButton("Reddet", { onRespond(invite, false) }, color = androidx.compose.ui.graphics.Color.White, compact = true,
                enabled = !busy, modifier = Modifier.weight(1f).testTag("invite_decline"))
            LoopaButton("Katıl", { onRespond(invite, true) }, color = LoopaPalette.Mint, compact = true, loading = busy,
                modifier = Modifier.weight(1f).testTag("invite_accept"))
        }
    }
}

@Composable
fun ConversationRow(cv: Conversation, onClick: () -> Unit) {
    val last = cv.lastMessage
    val mine = last != null && last.senderId != cv.userId
    PersonRow(cv.username, cv.avatar, presence = cv.presence, onClick = onClick, modifier = Modifier.testTag("conversation_${cv.userId}"),
        subtitle = last?.let { (if (mine) "Sen: " else "") + it.body } ?: "") {
        Column(horizontalAlignment = Alignment.End) {
            Text(SocialFormat.ago(last?.createdAt), style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
            if (cv.unread > 0) CountBadge(cv.unread, Modifier.padding(top = 4.dp))
        }
    }
}

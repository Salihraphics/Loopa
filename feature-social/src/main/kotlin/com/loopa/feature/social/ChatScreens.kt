package com.loopa.feature.social

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.Remote
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.game.SocialFormat
import com.loopa.core.model.AppNotification
import com.loopa.core.model.Conversation
import com.loopa.core.model.DirectMessage
import com.loopa.core.model.InviteOutcome
import com.loopa.core.model.NotificationKind
import com.loopa.core.model.Relation
import com.loopa.core.model.UserProfileView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.IconAction
import com.loopa.core.ui.components.LoopaSurface
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// ===================================================================== chat
enum class SendStatus { SENDING, SENT, READ, FAILED }

/** A row of the chat: a stored message, or one of mine still on its way (keyed by client id). */
data class ChatItem(val key: String, val mine: Boolean, val body: String, val createdAt: String?, val status: SendStatus?, val clientId: String?)

data class ChatState(
    val peer: UserProfileView? = null,
    val messages: List<DirectMessage> = emptyList(),
    val pending: List<ChatItem> = emptyList(),
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val reachedStart: Boolean = false,
    val error: LoopaError? = null,
    val message: String? = null,
) {
    /** Newest first (the list is drawn bottom-up). */
    fun items(me: String?): List<ChatItem> {
        val stored = messages.map { m ->
            val mine = m.senderId == me
            ChatItem("m${m.id}", mine, m.body, m.createdAt, if (!mine) null else if (m.readAt != null) SendStatus.READ else SendStatus.SENT, m.clientId)
        }
        val delivered = messages.mapNotNull { it.clientId }.toSet()
        return pending.filter { it.clientId !in delivered } + stored
    }
    val canMessage: Boolean get() = peer?.relation == Relation.FRIEND
}

class ChatViewModel(private val c: DataContainer, private val userId: String) : ViewModel() {
    private val _s = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _s.asStateFlow()
    val me: String? get() = c.backend.currentUserId
    private var visible = false

    init {
        viewModelScope.launch { c.social.changes.collect { refreshNewest() } }
    }

    fun onVisible(v: Boolean) { visible = v; if (v) { load(); } }

    private fun load() = viewModelScope.launch {
        try { _s.update { it.copy(peer = c.social.profile(userId)) } } catch (e: LoopaException) { _s.update { it.copy(error = e.error) } }
        refreshNewest()
    }

    private suspend fun refreshNewest() {
        try {
            val page = c.social.messages(userId)
            _s.update { st ->
                val merged = (page + st.messages).distinctBy { it.id }.sortedByDescending { it.id }
                st.copy(messages = merged, loading = false, error = null, reachedStart = st.reachedStart || (st.messages.isEmpty() && page.size < 50))
            }
            if (visible && page.any { it.senderId == userId && it.readAt == null }) runCatching { c.social.markConversationRead(userId) }
        } catch (e: LoopaException) {
            _s.update { it.copy(loading = false, error = if (it.messages.isEmpty()) e.error else it.error) }
        }
    }

    fun loadOlder() {
        val st = _s.value
        if (st.loadingOlder || st.reachedStart || st.messages.isEmpty()) return
        _s.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val older = c.social.messages(userId, before = st.messages.minOf { it.id })
                _s.update { s -> s.copy(messages = (s.messages + older).distinctBy { it.id }.sortedByDescending { it.id },
                    loadingOlder = false, reachedStart = older.size < 50) }
            } catch (e: LoopaException) { _s.update { it.copy(loadingOlder = false, message = e.error.message) } }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty() || body.length > SocialFormat.MESSAGE_MAX) return
        val cid = UUID.randomUUID().toString()
        _s.update { it.copy(pending = listOf(ChatItem("p$cid", true, body, null, SendStatus.SENDING, cid)) + it.pending) }
        deliver(cid, body)
    }

    fun retry(item: ChatItem) {
        val cid = item.clientId ?: return
        _s.update { s -> s.copy(pending = s.pending.map { if (it.clientId == cid) it.copy(status = SendStatus.SENDING) else it }) }
        deliver(cid, item.body)   // same client id: the server never stores it twice
    }

    private fun deliver(cid: String, body: String) = viewModelScope.launch {
        try {
            val m = c.social.sendMessage(userId, body, cid)
            _s.update { s -> s.copy(messages = (listOf(m) + s.messages).distinctBy { it.id }.sortedByDescending { it.id },
                pending = s.pending.filterNot { it.clientId == cid }) }
        } catch (e: LoopaException) {
            _s.update { s -> s.copy(pending = s.pending.map { if (it.clientId == cid) it.copy(status = SendStatus.FAILED) else it },
                message = e.error.message) }
        }
    }

    fun dismiss() = _s.update { it.copy(message = null) }
}

@Composable
fun ChatScreen(userId: String, onBack: () -> Unit, onOpenProfile: (String) -> Unit) {
    val vm = containerViewModel(key = "chat-$userId") { ChatViewModel(it, userId) }
    val st by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onVisible(false) }
    ChatContent(st, vm.me, onBack, { onOpenProfile(userId) }, vm::send, vm::retry, vm::loadOlder, vm::dismiss)
}

@Composable
fun ChatContent(
    st: ChatState,
    me: String?,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onSend: (String) -> Unit,
    onRetry: (ChatItem) -> Unit,
    onLoadOlder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val items = st.items(me)
    val atTop by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 } }
    LaunchedEffect(atTop, items.size) { if (atTop && items.isNotEmpty()) onLoadOlder() }
    // Jump to the newest message when one arrives or is sent.
    LaunchedEffect(items.firstOrNull()?.key) { if (items.isNotEmpty()) list.animateScrollToItem(0) }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LoopaTopBar(st.peer?.username ?: "Sohbet", onBack = onBack)
                }
                Box(Modifier.padding(end = Spacing.sm).clip(Radii.pill).clickable(onClick = onOpenProfile).padding(Spacing.xs).testTag("chat_peer")) {
                    PresenceAvatar(st.peer?.avatar, st.peer?.presence, 40.dp)
                }
            }
            st.peer?.presence?.let { p -> SocialFormat.presence(p)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft, modifier = Modifier.padding(start = Spacing.gutter))
            } }
            ErrorBanner(st.message, Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs), onDismiss = onDismiss)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    st.loading && items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Loopa.colors.textSoft)
                    st.error != null && items.isEmpty() -> Box(Modifier.align(Alignment.Center).padding(Spacing.gutter)) { SocialUnavailable(st.error) }
                    items.isEmpty() -> SocialEmpty("Henüz mesaj yok", "İlk mesajı sen gönder! Mesajlar yalnızca ikinizin arasında kalır.",
                        Modifier.align(Alignment.Center).padding(Spacing.gutter).testTag("chat_empty"), accent = LoopaPalette.Sky)
                    else -> LazyColumn(Modifier.fillMaxSize().testTag("chat_list"), state = list, reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(items, key = { it.key }) { m -> Bubble(m, onRetry) }
                        if (st.loadingOlder) item { CircularProgressIndicator(Modifier.padding(Spacing.md).size(20.dp), strokeWidth = 2.dp) }
                    }
                }
            }
            if (st.peer != null && !st.canMessage) {
                Text(if (st.peer.relation == Relation.BLOCKED) "Bu kişiyi engelledin; mesaj gönderemezsin."
                     else "Yalnızca arkadaşlarınla mesajlaşabilirsin.",
                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                    modifier = Modifier.fillMaxWidth().background(Loopa.colors.card).padding(Spacing.lg).testTag("chat_locked"))
            } else {
                Row(Modifier.fillMaxWidth().background(Loopa.colors.card).padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.Bottom) {
                    LoopaTextField(text, { text = it.take(SocialFormat.MESSAGE_MAX) }, Modifier.weight(1f).testTag("chat_input"),
                        placeholder = "Mesaj yaz…", singleLine = false, accent = LoopaPalette.Sky,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        trailing = if (text.length > SocialFormat.MESSAGE_MAX - 100) {
                            { Text("${text.length}/${SocialFormat.MESSAGE_MAX}", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft) }
                        } else null)
                    Spacer(Modifier.width(Spacing.sm))
                    IconAction(Icons.AutoMirrored.Rounded.Send, "Gönder", {
                        if (text.isNotBlank() && st.peer != null) { onSend(text); text = "" }
                    }, Modifier.testTag("chat_send"))
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatItem, onRetry: (ChatItem) -> Unit) {
    val shape = RoundedCornerShape(18.dp, 18.dp, if (m.mine) 4.dp else 18.dp, if (m.mine) 18.dp else 4.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (m.mine) LoopaPalette.Lavender else Loopa.colors.card)
                .then(if (m.status == SendStatus.FAILED) Modifier.clickable { onRetry(m) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 9.dp).animateContentSize().testTag(if (m.mine) "bubble_mine" else "bubble_theirs"),
            horizontalAlignment = Alignment.End,
        ) {
            Text(m.body, style = MaterialTheme.typography.bodyMedium, color = if (m.mine) LoopaPalette.Ink else Loopa.colors.text,
                modifier = Modifier.align(Alignment.Start))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (m.status == SendStatus.FAILED) "Gönderilemedi · tekrar dene" else SocialFormat.clock(m.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.mine) LoopaPalette.Ink.copy(alpha = 0.65f) else Loopa.colors.textSoft)
                if (m.mine && m.status != null) {
                    Spacer(Modifier.width(4.dp))
                    val (icon, label) = when (m.status) {
                        SendStatus.SENDING -> Icons.Rounded.Schedule to "Gönderiliyor"
                        SendStatus.SENT -> Icons.Rounded.Done to "Gönderildi"
                        SendStatus.READ -> Icons.Rounded.DoneAll to "Görüldü"
                        SendStatus.FAILED -> Icons.Rounded.ErrorOutline to "Gönderilemedi"
                    }
                    Icon(icon, label, tint = if (m.status == SendStatus.READ) LoopaPalette.MintDeep else if (m.status == SendStatus.FAILED) LoopaPalette.Error else LoopaPalette.Ink.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp).semantics { contentDescription = label }.testTag("status_${m.status.name}"))
                }
            }
        }
    }
}

// ===================================================================== conversations
class ConversationsViewModel(private val c: DataContainer) : ViewModel() {
    val conversations: StateFlow<Remote<List<Conversation>>> = c.social.conversations
    fun refresh() { viewModelScope.launch { c.social.refreshConversations() } }
}

@Composable
fun ConversationsScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit, onFindFriends: () -> Unit) {
    val vm = containerViewModel { ConversationsViewModel(it) }
    val convs by vm.conversations.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            LoopaTopBar("Mesajlar", onBack = onBack)
            val list = convs.data
            LazyColumn(Modifier.fillMaxSize().testTag("conversations"), contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                when {
                    list == null && convs.error != null -> item { SocialUnavailable(convs.error) }
                    list == null -> item { CircularProgressIndicator(Modifier.padding(Spacing.xl), color = Loopa.colors.textSoft) }
                    list.isEmpty() -> item {
                        SocialEmpty("Henüz sohbet yok", "Bir arkadaşının profilinden “Mesaj gönder”e dokun.", accent = LoopaPalette.Sky) {
                            com.loopa.core.ui.components.LoopaButton("Arkadaş bul", onFindFriends, color = LoopaPalette.Mint, compact = true)
                        }
                    }
                    else -> items(list, key = { it.userId }) { cv -> ConversationRow(cv) { onOpenChat(cv.userId) } }
                }
            }
        }
    }
}

// ===================================================================== notifications
class NotificationsViewModel(private val c: DataContainer) : ViewModel() {
    val notifications: StateFlow<Remote<List<AppNotification>>> = c.social.notifications
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow<Long?>(null)
    val busy: StateFlow<Long?> = _busy.asStateFlow()

    fun refresh() { viewModelScope.launch { c.social.refreshNotifications() } }
    fun markAll() { viewModelScope.launch { runCatching { c.social.markNotificationsRead(null) } } }
    fun markOne(id: Long) { viewModelScope.launch { runCatching { c.social.markNotificationsRead(listOf(id)) } } }

    fun joinInvite(n: AppNotification, onJoined: (String) -> Unit) {
        val id = n.refId ?: return
        _busy.value = n.id
        viewModelScope.launch {
            try {
                when (val r = c.social.respondRoomInvite(id, true)) {
                    is InviteOutcome.Joined -> onJoined(r.roomId)
                    InviteOutcome.Expired -> _message.value = "Bu davetin süresi dolmuş."
                    InviteOutcome.RoomGone -> _message.value = "Bu oda artık yok."
                    else -> _message.value = "Bu davet artık geçerli değil."
                }
            } catch (e: LoopaException) { _message.value = e.error.message }
            finally { _busy.value = null; c.social.refreshNotifications() }
        }
    }
    fun dismiss() { _message.value = null }
}

/** Where a notification leads. */
sealed interface NotificationTarget {
    data class Chat(val userId: String) : NotificationTarget
    data class Profile(val userId: String) : NotificationTarget
    data object Requests : NotificationTarget
}

fun NotificationKind.text(actor: String, n: AppNotification): Pair<String, String?> = when (this) {
    NotificationKind.FRIEND_REQUEST -> "$actor sana arkadaşlık isteği gönderdi" to null
    NotificationKind.FRIEND_ACCEPTED -> "$actor arkadaşlık isteğini kabul etti" to "Artık birbirinizi odalara davet edebilirsiniz."
    NotificationKind.ROOM_INVITE -> "$actor seni odaya davet etti" to listOfNotNull(
        n.payload["game_mode"]?.let { com.loopa.core.game.GameModes.name((it as kotlinx.serialization.json.JsonPrimitive).content) },
        n.payload["room_code"]?.let { "Oda " + (it as kotlinx.serialization.json.JsonPrimitive).content },
        when (n.inviteStatus) { "accepted" -> "katıldın"; "declined" -> "reddettin"; "expired" -> "süresi doldu"; "cancelled" -> "iptal edildi"; else -> null },
    ).joinToString(" · ")
    NotificationKind.MESSAGE -> {
        val count = (n.payload["count"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 1
        (if (count > 1) "$actor sana $count mesaj gönderdi" else "$actor sana mesaj gönderdi") to
            (n.payload["preview"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
    NotificationKind.OTHER -> "Yeni bildirim" to null
}

@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpen: (NotificationTarget) -> Unit, onRoomReady: (String) -> Unit) {
    val vm = containerViewModel { NotificationsViewModel(it) }
    val list by vm.notifications.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            LoopaTopBar("Bildirimler", onBack = onBack) {
                if (list.data.orEmpty().any { it.unread }) {
                    Text("Tümünü okundu say", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.accentText,
                        modifier = Modifier.clip(Radii.pill).clickable(onClick = vm::markAll).padding(Spacing.sm).testTag("notifications_mark_all"))
                }
            }
            ErrorBanner(message, Modifier.padding(horizontal = Spacing.gutter), onDismiss = vm::dismiss)
            val data = list.data
            LazyColumn(Modifier.fillMaxSize().testTag("notifications_list"), contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when {
                    data == null && list.error != null -> item { SocialUnavailable(list.error) }
                    data == null -> item { CircularProgressIndicator(Modifier.padding(Spacing.xl), color = Loopa.colors.textSoft) }
                    data.isEmpty() -> item {
                        SocialEmpty("Bildirim yok", "Arkadaşlık istekleri, oda davetleri ve yeni mesajlar burada görünür.",
                            Modifier.testTag("notifications_empty"), accent = LoopaPalette.Lavender)
                    }
                    else -> items(data, key = { it.id }) { n ->
                        val actor = n.actor
                        val (title, body) = n.kind.text(actor?.username ?: "Bir oyuncu", n)
                        val invitePending = n.kind == NotificationKind.ROOM_INVITE && n.inviteStatus == "pending"
                        LoopaSurface(
                            color = if (n.unread) LoopaPalette.Lavender.copy(alpha = 0.22f) else Loopa.colors.card,
                            padding = PaddingValues(Spacing.sm),
                            modifier = Modifier.testTag("notification_${n.id}"),
                            onClick = {
                                if (n.unread) vm.markOne(n.id)
                                when (n.kind) {
                                    NotificationKind.MESSAGE -> actor?.let { onOpen(NotificationTarget.Chat(it.userId)) }
                                    NotificationKind.FRIEND_REQUEST -> onOpen(NotificationTarget.Requests)
                                    NotificationKind.FRIEND_ACCEPTED -> actor?.let { onOpen(NotificationTarget.Profile(it.userId)) }
                                    NotificationKind.ROOM_INVITE -> if (!invitePending) actor?.let { onOpen(NotificationTarget.Profile(it.userId)) }
                                    NotificationKind.OTHER -> Unit
                                }
                            },
                        ) {
                            PersonRow(title, actor?.avatar, subtitle = listOfNotNull(body, SocialFormat.ago(n.createdAt)).joinToString(" · ")) {
                                if (n.unread) Box(Modifier.size(10.dp).clip(Radii.pill).background(LoopaPalette.PeachDeep)
                                    .semantics { contentDescription = "Okunmadı" })
                            }
                            if (invitePending) {
                                com.loopa.core.ui.components.LoopaButton("Odaya katıl", { vm.joinInvite(n, onRoomReady) }, color = LoopaPalette.Mint,
                                    compact = true, loading = busy == n.id, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs).testTag("notification_join"))
                            }
                        }
                    }
                }
            }
        }
    }
}

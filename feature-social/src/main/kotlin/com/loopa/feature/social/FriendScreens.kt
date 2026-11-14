package com.loopa.feature.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import com.loopa.core.model.Relation
import com.loopa.core.model.RoomRef
import com.loopa.core.model.SocialOverview
import com.loopa.core.model.UserProfileView
import com.loopa.core.model.UserSearchResult
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ConfirmDialog
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.IconAction
import com.loopa.core.ui.components.LevelBadge
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaSurface
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.components.SectionHeader
import com.loopa.core.ui.components.StatTile
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ===================================================================== find friends
data class FindState(
    val query: String = "",
    val results: List<UserSearchResult> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
)

class FindFriendsViewModel(private val c: DataContainer) : ViewModel() {
    val overview: StateFlow<Remote<SocialOverview>> = c.social.overview
    private val _s = MutableStateFlow(FindState())
    val state: StateFlow<FindState> = _s.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null

    fun refresh() { viewModelScope.launch { c.social.refreshOverview() } }

    fun setQuery(q: String) {
        _s.update { it.copy(query = q.take(16), message = null) }
        job?.cancel()
        if (q.trim().length < 2) { _s.update { it.copy(results = emptyList(), searching = false, searched = false) }; return }
        job = viewModelScope.launch {
            delay(350)   // wait until typing pauses
            search()
        }
    }

    private suspend fun search() {
        _s.update { it.copy(searching = true) }
        try {
            val r = c.social.search(_s.value.query)
            _s.update { it.copy(results = r, searching = false, searched = true) }
        } catch (e: LoopaException) {
            _s.update { it.copy(searching = false, message = e.error.message) }
        }
    }

    private fun act(key: String, block: suspend () -> Unit) {
        if (_s.value.busy != null) return
        _s.update { it.copy(busy = key) }
        viewModelScope.launch {
            try { block(); if (_s.value.query.trim().length >= 2) search() }
            catch (e: LoopaException) { _s.update { it.copy(message = e.error.message) } }
            finally { _s.update { it.copy(busy = null) } }
        }
    }

    fun add(userId: String) = act(userId) {
        val friends = c.social.sendFriendRequest(userId)
        _s.update { it.copy(message = if (friends) "Artık arkadaşsınız!" else null) }
    }
    fun accept(requestId: String, userId: String) = act(userId) { c.social.respondFriendRequest(requestId, true) }
    fun cancel(requestId: String) = act(requestId) { c.social.cancelFriendRequest(requestId) }
    fun dismiss() = _s.update { it.copy(message = null) }
}

@Composable
fun FindFriendsScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit) {
    val vm = containerViewModel { FindFriendsViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val incomingByUser = overview.data?.incoming.orEmpty().associateBy { it.user.userId }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().imePadding()) {
            LoopaTopBar("Arkadaş bul", onBack = onBack)
            LoopaTextField(
                st.query, vm::setQuery, Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag("friend_search"),
                placeholder = "Kullanıcı adı", accent = LoopaPalette.Mint,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                leading = { Icon(Icons.Rounded.Search, null, tint = Loopa.colors.textSoft) },
                trailing = { if (st.searching) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp, color = Loopa.colors.textSoft) },
            )
            ErrorBanner(st.message, Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs), onDismiss = vm::dismiss)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("friend_results"),
                contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (st.query.trim().length < 2) {
                    val outgoing = overview.data?.outgoing.orEmpty()
                    item { Text("Arkadaşının LOOPA kullanıcı adını yaz. Kullanıcı adını Profil ekranında görebilir.",
                        style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, modifier = Modifier.padding(vertical = Spacing.sm)) }
                    if (outgoing.isNotEmpty()) {
                        item { SectionHeader("Gönderilen istekler") }
                        items(outgoing, key = { it.requestId }) { r ->
                            PersonRow(r.user.username, r.user.avatar, subtitle = "Cevap bekleniyor · ${SocialFormat.ago(r.createdAt)}",
                                onClick = { onOpenProfile(r.user.userId) }, modifier = Modifier.testTag("outgoing_${r.requestId}")) {
                                LoopaButton("Geri çek", { vm.cancel(r.requestId) }, color = Color.White, compact = true,
                                    loading = st.busy == r.requestId, modifier = Modifier.testTag("outgoing_cancel"))
                            }
                        }
                    }
                } else if (st.searched && st.results.isEmpty()) {
                    item { SocialEmpty("Kimse bulunamadı", "“${st.query.trim()}” ile eşleşen bir oyuncu yok. Yazımı kontrol et.", accent = LoopaPalette.Sky) }
                } else {
                    items(st.results, key = { it.userId }) { u ->
                        PersonRow(u.username, u.avatar, subtitle = "Seviye ${u.level}", onClick = { onOpenProfile(u.userId) },
                            modifier = Modifier.testTag("result_${u.userId}")) {
                            when (u.relation) {
                                Relation.FRIEND -> StatePill("Arkadaş", LoopaPalette.Mint)
                                Relation.OUTGOING -> StatePill("İstek gönderildi")
                                Relation.BLOCKED -> StatePill("Engelli")
                                Relation.INCOMING -> LoopaButton("Kabul et", {
                                    incomingByUser[u.userId]?.let { vm.accept(it.requestId, u.userId) } ?: vm.add(u.userId)
                                }, icon = Icons.Rounded.Check, color = LoopaPalette.Mint, compact = true, loading = st.busy == u.userId)
                                else -> LoopaButton("Ekle", { vm.add(u.userId) }, icon = Icons.Rounded.PersonAdd, color = LoopaPalette.Lavender,
                                    compact = true, loading = st.busy == u.userId, modifier = Modifier.testTag("add_${u.userId}"))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===================================================================== user profile
data class ProfileViewState(
    val profile: UserProfileView? = null,
    val error: LoopaError? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val activeRoom: RoomRef? = null,
    val invited: Boolean = false,
)

class UserProfileViewModel(private val c: DataContainer, private val userId: String) : ViewModel() {
    private val _s = MutableStateFlow(ProfileViewState())
    val state: StateFlow<ProfileViewState> = _s.asStateFlow()

    fun load() = viewModelScope.launch {
        try { _s.update { it.copy(profile = c.social.profile(userId), error = null) } }
        catch (e: LoopaException) { _s.update { it.copy(error = e.error) } }
        val room = runCatching { c.rooms.activeRoom() }.getOrNull()
        _s.update { it.copy(activeRoom = room) }
    }

    private fun act(block: suspend () -> Unit) {
        if (_s.value.busy) return
        _s.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { block() } catch (e: LoopaException) { _s.update { it.copy(message = e.error.message) } }
            finally { _s.update { it.copy(busy = false) }; load() }
        }
    }

    fun add() = act { c.social.sendFriendRequest(userId) }
    fun acceptIncoming() = act {
        val req = c.social.refreshOverview().data?.incoming?.firstOrNull { it.user.userId == userId }
        if (req != null) c.social.respondFriendRequest(req.requestId, true) else c.social.sendFriendRequest(userId)
    }
    fun cancelOutgoing() = act {
        c.social.refreshOverview().data?.outgoing?.firstOrNull { it.user.userId == userId }?.let { c.social.cancelFriendRequest(it.requestId) }
    }
    fun remove() = act { c.social.removeFriend(userId) }
    fun block() = act { c.social.block(userId) }
    fun unblock() = act { c.social.unblock(userId) }
    fun invite() = act {
        val room = _s.value.activeRoom ?: return@act
        c.social.sendRoomInvite(userId, room.id)
        _s.update { it.copy(invited = true, message = null) }
    }
    fun dismiss() = _s.update { it.copy(message = null) }
}

@Composable
fun UserProfileScreen(userId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val vm = containerViewModel(key = "profile-$userId") { UserProfileViewModel(it, userId) }
    val st by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    var confirm by remember { mutableStateOf<String?>(null) }
    val p = st.profile

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            LoopaTopBar(p?.username ?: "Profil", onBack = onBack)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).testTag("user_profile"),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                if (p == null && st.error != null) {
                    if (st.error == LoopaError.SERVER_OUTDATED) SocialUnavailable(st.error)
                    else SocialEmpty("Kullanıcı bulunamadı", st.error?.message.orEmpty(), accent = LoopaPalette.Peach)
                }
                if (p != null) {
                    LoopaSurface(color = LoopaPalette.Sky.copy(alpha = 0.4f)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                AvatarView(p.avatar, size = 112.dp)
                                LevelBadge(p.level, Modifier.align(Alignment.BottomEnd), size = 36.dp)
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            Text(p.username, style = MaterialTheme.typography.headlineMedium, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
                            val presence = SocialFormat.presence(p.presence)
                            Text(listOfNotNull("Seviye ${p.level}", presence, "${p.friendCount} arkadaş").joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                        }
                    }
                    ErrorBanner(st.message, onDismiss = vm::dismiss)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Maç", "${p.matchesPlayed}", LoopaPalette.Lavender, Modifier.weight(1f))
                        StatTile("Galibiyet", "${p.matchWins}", LoopaPalette.Mint, Modifier.weight(1f))
                    }
                    when (p.relation) {
                        Relation.FRIEND -> {
                            LoopaButton("Mesaj gönder", { onOpenChat(p.userId) }, icon = Icons.AutoMirrored.Rounded.Chat, color = LoopaPalette.Mint,
                                modifier = Modifier.fillMaxWidth().testTag("profile_message"))
                            val room = st.activeRoom
                            if (room != null) {
                                LoopaButton(if (st.invited) "Davet gönderildi ✓" else "Odama davet et (${room.code})", vm::invite,
                                    icon = Icons.Rounded.SportsEsports, color = LoopaPalette.Butter, enabled = !st.invited, loading = st.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("profile_invite"))
                            }
                            LoopaButton("Arkadaşlıktan çıkar", { confirm = "remove" }, icon = Icons.Rounded.PersonRemove, color = Color.White,
                                compact = true, modifier = Modifier.fillMaxWidth().testTag("profile_remove"))
                        }
                        Relation.OUTGOING -> LoopaButton("İsteği geri çek", vm::cancelOutgoing, icon = Icons.Rounded.Close, color = Color.White,
                            loading = st.busy, modifier = Modifier.fillMaxWidth())
                        Relation.INCOMING -> LoopaButton("İsteği kabul et", vm::acceptIncoming, icon = Icons.Rounded.Check, color = LoopaPalette.Mint,
                            loading = st.busy, modifier = Modifier.fillMaxWidth())
                        Relation.BLOCKED -> LoopaButton("Engeli kaldır", vm::unblock, color = LoopaPalette.Butter, loading = st.busy,
                            modifier = Modifier.fillMaxWidth().testTag("profile_unblock"))
                        Relation.NONE -> LoopaButton("Arkadaş ekle", vm::add, icon = Icons.Rounded.PersonAdd, color = LoopaPalette.Lavender,
                            loading = st.busy, modifier = Modifier.fillMaxWidth().testTag("profile_add"))
                        Relation.SELF -> Text("Bu senin profilin.", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
                    }
                    if (p.relation != Relation.BLOCKED && p.relation != Relation.SELF) {
                        LoopaButton("Engelle", { confirm = "block" }, icon = Icons.Rounded.Block, color = LoopaPalette.Peach, compact = true,
                            modifier = Modifier.fillMaxWidth().testTag("profile_block"))
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
    when (confirm) {
        "remove" -> ConfirmDialog("Arkadaşlıktan çıkarılsın mı?", "${p?.username} arkadaş listenden çıkar. Mesaj geçmişin silinmez ama yeni mesaj gönderemezsiniz.",
            "Çıkar", onConfirm = { confirm = null; vm.remove() }, onDismiss = { confirm = null })
        "block" -> ConfirmDialog("${p?.username} engellensin mi?", "Arkadaşlığınız ve bekleyen istekler/davetler kaldırılır. Seni aramada göremez, sana istek ya da mesaj gönderemez. İstediğin zaman engeli kaldırabilirsin.",
            "Engelle", onConfirm = { confirm = null; vm.block() }, onDismiss = { confirm = null })
    }
}

// ===================================================================== blocked users
class BlockedViewModel(private val c: DataContainer) : ViewModel() {
    val overview: StateFlow<Remote<SocialOverview>> = c.social.overview
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    fun refresh() { viewModelScope.launch { c.social.refreshOverview() } }
    fun unblock(id: String) {
        _busy.value = id
        viewModelScope.launch { runCatching { c.social.unblock(id) }; _busy.value = null }
    }
}

@Composable
fun BlockedUsersScreen(onBack: () -> Unit) {
    val vm = containerViewModel { BlockedViewModel(it) }
    val overview by vm.overview.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val blocked = overview.data?.blocked.orEmpty()
    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            LoopaTopBar("Engellenenler", onBack = onBack)
            LazyColumn(Modifier.fillMaxSize().testTag("blocked_list"), contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (overview.data == null && overview.error != null) item { SocialUnavailable(overview.error) }
                else if (blocked.isEmpty()) item { SocialEmpty("Engellediğin kimse yok", "Bir oyuncunun profilinden onu engelleyebilirsin.", accent = LoopaPalette.Sky) }
                items(blocked, key = { it.userId }) { u ->
                    PersonRow(u.username, u.avatar, subtitle = "Seviye ${u.level}") {
                        LoopaButton("Engeli kaldır", { vm.unblock(u.userId) }, color = LoopaPalette.Butter, compact = true, loading = busy == u.userId)
                    }
                }
            }
        }
    }
}

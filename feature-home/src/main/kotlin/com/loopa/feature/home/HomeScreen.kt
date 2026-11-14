package com.loopa.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import com.loopa.core.game.GameCards
import com.loopa.core.game.GameModeInfo
import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaException
import com.loopa.core.game.RoomCodes
import com.loopa.core.model.GameCatalog
import com.loopa.core.model.MatchHistoryEntry
import com.loopa.core.model.Membership
import com.loopa.core.model.ModeStat
import com.loopa.core.model.ProfileOverview
import com.loopa.core.model.RoomRef
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.FeaturedGame
import com.loopa.core.ui.components.GameArtwork
import com.loopa.core.ui.components.IconAction
import com.loopa.core.ui.components.LevelBadge
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaLogo
import com.loopa.core.ui.components.LoopaSurface
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.PlanBadge
import com.loopa.core.ui.components.SectionHeader
import com.loopa.core.ui.components.StatusBadge
import com.loopa.core.ui.components.XpBar
import com.loopa.core.ui.components.featuredGame
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val activeRoom: RoomRef? = null,
    val creating: String? = null,
    val joining: String? = null,       // "join" | "spectate"
    val code: String = "",
    val isPublic: Boolean = false,
    val error: String? = null,
    val modeStats: List<ModeStat> = emptyList(),
)

class HomeViewModel(private val c: DataContainer) : ViewModel() {
    val profile: StateFlow<ProfileOverview?> = c.profiles.profile
    val catalog: StateFlow<Remote<GameCatalog>> = c.catalog.state
    val membership: StateFlow<Remote<Membership>> = c.membership.state
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            runCatching { c.profiles.refresh() }
            val active = runCatching { c.rooms.activeRoom() }.getOrNull()
            _ui.update { it.copy(activeRoom = active) }
        }
        viewModelScope.launch { _ui.update { it.copy(modeStats = c.profiles.modeStats()) } }
    }

    fun setCode(v: String) = _ui.update { it.copy(code = RoomCodes.normalize(v), error = null) }
    fun setPublic(v: Boolean) = _ui.update { it.copy(isPublic = v) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Creates a room for [mode]; the server checks that this host may host it. */
    fun createRoom(mode: String, onReady: (String) -> Unit) {
        if (_ui.value.creating != null) return
        _ui.update { it.copy(creating = mode, error = null) }
        viewModelScope.launch {
            try {
                val ref = c.rooms.createRoom(_ui.value.isPublic, mode)
                _ui.update { it.copy(creating = null) }
                onReady(ref.id)
            } catch (e: LoopaException) {
                _ui.update { it.copy(creating = null, error = e.error.message) }
            }
        }
    }

    fun join(onReady: (String) -> Unit, spectator: Boolean = false) {
        val code = _ui.value.code
        if (code.length != 6) { _ui.update { it.copy(error = "Oda kodu 6 karakter olmalı.") }; return }
        if (_ui.value.joining != null) return
        _ui.update { it.copy(joining = if (spectator) "spectate" else "join", error = null) }
        viewModelScope.launch {
            try {
                val ref = c.rooms.joinRoom(code, spectator)
                _ui.update { it.copy(joining = null) }
                onReady(ref.id)
            } catch (e: LoopaException) {
                _ui.update { it.copy(joining = null, error = e.error.message) }
            }
        }
    }
}

/** Recently played games, newest first (from the server's per-game statistics). */
fun recentGames(stats: List<ModeStat>): List<GameModeInfo> = stats.filter { it.matchesPlayed > 0 }
    .sortedByDescending { it.lastPlayedAt.orEmpty() }
    .mapNotNull { s -> GameModes.all.firstOrNull { it.id == s.gameMode } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRoomReady: (String) -> Unit,
    onOpenGames: () -> Unit,
    onPublicRooms: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenPlus: () -> Unit = {},
) {
    val vm = containerViewModel { HomeViewModel(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val membership by vm.membership.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    var picker by rememberSaveable { mutableStateOf(false) }
    var notifications by rememberSaveable { mutableStateOf(false) }

    HomeContent(
        profile = profile, ui = ui, catalog = catalog.data, membership = membership.data,
        onRoomReady = onRoomReady, onOpenGames = onOpenGames, onPublicRooms = onPublicRooms, onOpenProfile = onOpenProfile,
        onOpenPlus = onOpenPlus, onCreate = { picker = true }, onCreateMode = { vm.createRoom(it, onRoomReady) },
        onCode = vm::setCode, onJoin = { vm.join(onRoomReady, it) }, onDismissError = vm::dismissError,
        onNotifications = { notifications = true },
    )

    if (picker) {
        ModalBottomSheet(onDismissRequest = { picker = false }, containerColor = Loopa.colors.background) {
            GamePicker(catalog.data, ui.isPublic, vm::setPublic, ui.creating) { id -> vm.createRoom(id) { picker = false; onRoomReady(it) } }
        }
    }
    if (notifications) {
        ModalBottomSheet(onDismissRequest = { notifications = false }, containerColor = Loopa.colors.background) {
            Column(Modifier.fillMaxWidth().padding(Spacing.xl).navigationBarsPadding().testTag("notifications_sheet"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Rounded.Notifications, null, tint = Loopa.colors.textSoft, modifier = Modifier.size(40.dp))
                Text("Bildirim yok", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text)
                Text("Oda davetleri ve arkadaş bildirimleri eklendiğinde burada görünecek.",
                    style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

/** Stateless home dashboard; used directly by screenshot tests. */
@Composable
fun HomeContent(
    profile: ProfileOverview?,
    ui: HomeUiState,
    catalog: GameCatalog?,
    membership: Membership?,
    onRoomReady: (String) -> Unit,
    onOpenGames: () -> Unit,
    onPublicRooms: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenPlus: () -> Unit,
    onCreate: () -> Unit,
    onCreateMode: (String) -> Unit,
    onCode: (String) -> Unit,
    onJoin: (spectator: Boolean) -> Unit,
    onDismissError: () -> Unit,
    onNotifications: () -> Unit,
) {
    val joinCard = remember { BringIntoViewRequester() }
    var codeFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(LocalDensity.current)
    // Keep the whole join card (field + buttons) visible while the keyboard opens or resizes.
    LaunchedEffect(codeFocused, imeBottom) { if (codeFocused) joinCard.bringIntoView() }
    // A complete code needs no more typing: close the keyboard so the Join button is reachable.
    LaunchedEffect(ui.code) { if (ui.code.length == 6 && codeFocused) { keyboard?.hide(); focusManager.clearFocus() } }
    val recent = recentGames(ui.modeStats)

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter).testTag("home_scroll"),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // A. Header: brand, notifications, avatar
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                LoopaLogo(size = 34.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text("LOOPA", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text, modifier = Modifier.weight(1f))
                IconAction(Icons.Rounded.Notifications, "Bildirimler", onNotifications, Modifier.testTag("open_notifications"))
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onOpenProfile).testTag("home_avatar"), contentAlignment = Alignment.Center) {
                    AvatarView(profile?.avatar, size = 40.dp, showFrame = false)
                }
            }
            // B. Player overview
            LoopaSurface(onClick = onOpenProfile, modifier = Modifier.testTag("player_overview")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Merhaba, ${profile?.username ?: "…"}", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Seviye ${profile?.level ?: 1} · ${profile?.xp ?: 0} XP", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
                    }
                    PlanBadge(membership?.tier)
                    Spacer(Modifier.width(Spacing.sm))
                    LevelBadge(profile?.level ?: 1, size = 40.dp)
                }
                Spacer(Modifier.height(Spacing.md))
                XpBar(profile?.xp ?: 0, height = 10.dp)
            }
            ErrorBanner(ui.error, onDismiss = onDismissError)
            // Return to an open room
            AnimatedVisibility(ui.activeRoom != null) {
                val room = ui.activeRoom
                if (room != null) {
                    LoopaSurface(color = LoopaPalette.Butter.copy(alpha = 0.6f), onClick = { onRoomReady(room.id) }, modifier = Modifier.testTag("active_room")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Replay, null, tint = Loopa.colors.text)
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text("Odana geri dön", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                                Text("Oda ${room.code} · ${if (room.status == "in_game") "oyun devam ediyor" else "lobide"}",
                                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                            }
                        }
                    }
                }
            }
            // C. Quick actions: create and join, always visible near the top
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                LoopaButton("Oda kur", onCreate, icon = Icons.Rounded.AddCircle, color = LoopaPalette.Mint,
                    loading = ui.creating != null, modifier = Modifier.weight(1f).testTag("create_room"), sfx = Sfx.JOIN)
                LoopaButton("Açık odalar", onPublicRooms, icon = Icons.Rounded.Public, color = LoopaPalette.Sky,
                    modifier = Modifier.weight(1f).testTag("public_rooms"))
            }
            LoopaSurface(Modifier.bringIntoViewRequester(joinCard).testTag("join_card")) {
                Text("Odaya katıl", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                Spacer(Modifier.height(Spacing.sm))
                LoopaTextField(
                    value = ui.code, onValueChange = onCode, placeholder = "ÖRN. KPX7A2",
                    modifier = Modifier.fillMaxWidth().onFocusChanged { codeFocused = it.isFocused }.testTag("room_code_field"),
                    accent = LoopaPalette.ButterDeep,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); focusManager.clearFocus(); onJoin(false) }),
                    trailing = { Text("${ui.code.length}/6", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft) },
                )
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    LoopaButton("Katıl", { onJoin(false) }, icon = Icons.AutoMirrored.Rounded.Login, color = LoopaPalette.Butter,
                        loading = ui.joining == "join", enabled = ui.code.length == 6, compact = true, modifier = Modifier.weight(1f).testTag("join_button"), sfx = Sfx.JOIN)
                    LoopaButton("İzle", { onJoin(true) }, icon = Icons.Rounded.Visibility, color = Loopa.colors.cardHigh,
                        loading = ui.joining == "spectate", enabled = ui.code.length == 6, compact = true, modifier = Modifier.weight(0.7f))
                }
            }
            // D. Continue playing
            if (recent.isNotEmpty()) {
                Column {
                    SectionHeader("Son oynananlar", action = "Tümü", onAction = onOpenGames)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.testTag("home_recent")) {
                        items(recent, key = { it.id }) { g ->
                            val st = GameCards.status(g, catalog)
                            Column(Modifier.width(104.dp).clip(Radii.md).clickable { if (st.canStart) onCreateMode(g.id) else onOpenGames() }) {
                                Box {
                                    GameArtwork(g.id, Modifier.fillMaxWidth().aspectRatio(1f).clip(Radii.md), label = g.name)
                                    if (!st.canStart) Icon(Icons.Rounded.Lock, "Kilitli", tint = LoopaPalette.Ink,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                                }
                                Text(g.name, style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            // E. Featured (this week's free game when the rotation is on)
            Column {
                SectionHeader(if (catalog?.rotation?.active == true) "Bu haftanın oyunu" else "Öne çıkan", action = "Tüm oyunlar", onAction = onOpenGames)
                val f = featuredGame(catalog)
                FeaturedGame(f, GameCards.status(f, catalog), ui.creating == f.id, { onCreateMode(f.id) }, onOpenPlus,
                    showPlusAction = membership?.hasPlusAccess != true, weekly = catalog?.rotation?.active == true && f.id in catalog.rotation.freeModes)
            }
            // G. Recent results (real match history only)
            val history = profile?.history.orEmpty()
            if (history.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeader("Son maçlar", action = "Profil", onAction = onOpenProfile)
                    history.take(3).forEach { HistoryRow(it) }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

/** Game choice before creating a room: shows what this host may start right now. */
@Composable
fun GamePicker(catalog: GameCatalog?, isPublic: Boolean, onPublic: (Boolean) -> Unit, creating: String?, onPick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().testTag("game_picker"), contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.section),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            Text("Hangi oyun?", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isPublic) "Açık oda" else "Özel oda", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                    Text(if (isPublic) "Oda tarayıcısında herkes görebilir." else "Yalnızca kodu bilenler katılabilir.",
                        style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                }
                Switch(isPublic, onPublic, colors = SwitchDefaults.colors(checkedTrackColor = LoopaPalette.MintDeep), modifier = Modifier.testTag("picker_public"))
            }
        }
        items(GameModes.all, key = { it.id }) { g ->
            val st = GameCards.status(g, catalog)
            Row(
                Modifier.fillMaxWidth().clip(Radii.md).clickable(enabled = st.canStart && creating == null) { onPick(g.id) }
                    .background(Loopa.colors.card).padding(Spacing.sm).testTag("picker_${g.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameArtwork(g.id, Modifier.size(52.dp).clip(Radii.sm), label = g.name)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, color = if (st.canStart) Loopa.colors.text else Loopa.colors.textSoft)
                    Text(if (st.canStart) g.players else st.explanation, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (creating == g.id) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Loopa.colors.text)
                else StatusBadge(st)
            }
        }
    }
}

@Composable
fun HistoryRow(entry: MatchHistoryEntry, modifier: Modifier = Modifier) {
    val medal = when (entry.finalRank) { 1 -> LoopaPalette.Butter; 2 -> LoopaPalette.Sky; 3 -> LoopaPalette.Peach; else -> Loopa.colors.cardHigh }
    LoopaSurface(modifier.fillMaxWidth(), padding = PaddingValues(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(Radii.sm).background(medal), contentAlignment = Alignment.Center) {
                Text("${entry.finalRank}.", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text("${entry.finalRank}. / ${entry.playerCount} oyuncu", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                Text("Oda ${entry.roomCode} · ${entry.finishedAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${entry.totalPoints} p", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                Text("+${entry.xpEarned} XP", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.MintDeep)
            }
        }
    }
}

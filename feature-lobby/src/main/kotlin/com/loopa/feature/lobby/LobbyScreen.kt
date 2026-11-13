package com.loopa.feature.lobby

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.ConnectionState
import com.loopa.core.data.DataContainer
import com.loopa.core.data.RoomSession
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaException
import com.loopa.core.model.RoomPlayer
import com.loopa.core.model.RoomSettings
import com.loopa.core.model.RoomState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ConfirmDialog
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LevelBadge
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.Haptic
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LobbyViewModel(private val c: DataContainer, roomId: String) : ViewModel() {
    val session: RoomSession = c.sessions.open(roomId)
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() { _error.value = null }

    private fun run(tag: String, block: suspend RoomSession.() -> Unit) {
        if (_busy.value != null) return
        _busy.value = tag
        viewModelScope.launch {
            try { session.block() } catch (e: LoopaException) { _error.value = e.error.message }
            _busy.value = null
        }
    }

    fun toggleReady(current: Boolean) = run("ready") { setReady(!current) }
    fun start() = run("start") { startMatch() }
    fun kick(userId: String) = run("kick") { kick(userId) }
    fun makeHost(userId: String) = run("host") { transferHost(userId) }
    fun setSpectator(v: Boolean) = run("role") { setSpectator(v) }
    fun saveSettings(settings: RoomSettings, isPublic: Boolean, done: () -> Unit) = run("settings") {
        updateSettings(settings, isPublic)
        done()
    }
    fun closeRoom(onClosed: () -> Unit) = run("close") {
        closeRoom()
        c.sessions.close()
        onClosed()
    }
    /** Host only: can this room's game be started by the current host? Asked from the server catalogue. */
    private val _modeStatus = MutableStateFlow<com.loopa.core.game.GameCardStatus?>(null)
    val modeStatus: StateFlow<com.loopa.core.game.GameCardStatus?> = _modeStatus.asStateFlow()
    fun checkMode(modeId: String) = viewModelScope.launch {
        if (modeId == com.loopa.core.game.GameModes.ISIM_SEHIR) { _modeStatus.value = null; return@launch }
        val catalog = c.catalog.refresh().data
        _modeStatus.value = com.loopa.core.game.GameCards.status(com.loopa.core.game.GameModes.info(modeId), catalog)
    }

    fun leave(onLeft: () -> Unit) {
        viewModelScope.launch {
            session.leave()
            c.sessions.close()
            onLeft()
        }
    }
}

@Composable
fun LobbyScreen(roomId: String, state: RoomState, onLeft: () -> Unit) {
    val vm = containerViewModel(key = "lobby-$roomId") { LobbyViewModel(it, roomId) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val connection by vm.session.connection.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val me = state.player(state.me.userId)
    val players = state.activePlayers
    val notReady = players.count { !it.isReady && !it.isHost }
    val modeId = state.room.settings.gameMode
    val modeStatus by vm.modeStatus.collectAsStateWithLifecycle()
    // Re-checked whenever this client becomes host (e.g. after the previous host left) or the mode changes.
    LaunchedEffect(state.isHost, modeId, state.room.hostId) { if (state.isHost) vm.checkMode(modeId) }
    val modeBlocked = state.isHost && modeId != com.loopa.core.game.GameModes.ISIM_SEHIR && modeStatus?.canStart != true
    val canStart = state.isHost && players.size >= 2 && notReady == 0 && !modeBlocked

    // joining / readiness sounds
    var lastCount by remember { mutableStateOf(players.size) }
    LaunchedEffect(players.size) {
        if (players.size > lastCount) { feedback.play(Sfx.JOIN); feedback.haptic(Haptic.LIGHT) }
        lastCount = players.size
    }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            // ---------------- header
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { confirmLeave = true }) { Icon(Icons.AutoMirrored.Rounded.Logout, "Odadan çık", tint = Loopa.colors.text) }
                Text("Lobi", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text, modifier = Modifier.weight(1f))
                ConnectionPill(connection)
                if (state.isHost) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menü", tint = Loopa.colors.text) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Oda ayarları") }, onClick = { menu = false; showSettings = true }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                            DropdownMenuItem(text = { Text("Odayı kapat") }, onClick = { menu = false; confirmClose = true }, leadingIcon = { Icon(Icons.Rounded.Lock, null) })
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { RoomCodeCard(state.room.code, state.room.isPublic, context) }
                item { ErrorBanner(error, onDismiss = vm::dismissError) }
                item { SettingsSummary(state.room.settings, state.isHost) { showSettings = true } }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Oyuncular", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, modifier = Modifier.weight(1f))
                        Pill("${players.size}/${state.room.settings.maxPlayers}", LoopaPalette.Lavender)
                    }
                }
                items(players, key = { it.userId }) { p ->
                    PlayerRow(
                        p, isMe = p.userId == state.me.userId, hostView = state.isHost,
                        onKick = { vm.kick(p.userId) }, onMakeHost = { vm.makeHost(p.userId) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (players.size < 2) {
                    item {
                        Text(
                            "Başlamak için en az 2 oyuncu gerekli. Oda kodunu paylaş!",
                            style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft,
                        )
                    }
                }
                if (state.spectators.isNotEmpty()) {
                    item { Text("İzleyiciler", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text) }
                    items(state.spectators, key = { "s" + it.userId }) { p ->
                        PlayerRow(p, isMe = p.userId == state.me.userId, hostView = state.isHost, onKick = { vm.kick(p.userId) }, onMakeHost = null, modifier = Modifier.animateItem())
                    }
                }
            }
            // ---------------- bottom actions
            Column(
                Modifier.fillMaxWidth().background(Loopa.colors.card).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    state.isHost -> {
                        if (modeBlocked) Text(
                            modeStatus?.let { "${com.loopa.core.game.GameModes.name(modeId)} bu oda için başlatılamıyor. ${it.explanation} Oda ayarlarından İsim Şehir'e geçebilirsin." }
                                ?: "Oyunun erişim durumu kontrol ediliyor…",
                            style = MaterialTheme.typography.bodySmall, color = LoopaPalette.PeachDeep,
                            modifier = Modifier.testTag("lobby_mode_blocked"),
                        )
                        LoopaButton(
                            text = when {
                                players.size < 2 -> "Oyuncu bekleniyor…"
                                notReady > 0 -> "$notReady oyuncu hazır değil"
                                else -> "Oyunu Başlat"
                            },
                            onClick = vm::start, enabled = canStart, loading = busy == "start",
                            icon = Icons.Rounded.PlayArrow, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth(), sfx = Sfx.LETTER,
                        )
                    }
                    state.isSpectator -> {
                        Text("İzleyici modundasın: oyunu izleyebilir ama cevap veremezsin.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                        LoopaButton("Oyuncu olarak katıl", { vm.setSpectator(false) }, loading = busy == "role", color = LoopaPalette.Lavender, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        val ready = me?.isReady == true
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LoopaButton(
                                if (ready) "Hazırım ✓" else "Hazır ol", { vm.toggleReady(ready); feedback.haptic(Haptic.MEDIUM) },
                                loading = busy == "ready", color = if (ready) LoopaPalette.Mint else LoopaPalette.Butter,
                                icon = Icons.Rounded.CheckCircle, modifier = Modifier.weight(1f), sfx = Sfx.READY,
                            )
                            if (state.room.settings.allowSpectators) {
                                LoopaButton("İzle", { vm.setSpectator(true) }, loading = busy == "role", color = LoopaPalette.Sky, icon = Icons.Rounded.Visibility)
                            }
                        }
                        Text("Oda sahibi oyunu başlattığında otomatik olarak oyuna geçeceksin.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                    }
                }
            }
        }
    }

    if (showSettings && state.isHost) {
        RoomSettingsSheet(
            initial = state.room.settings,
            initialPublic = state.room.isPublic,
            currentPlayers = players.size,
            saving = busy == "settings",
            onDismiss = { showSettings = false },
            onSave = { s, pub -> vm.saveSettings(s, pub) { showSettings = false } },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            "Odadan çık?",
            if (state.isHost) "Oda sahipliği başka bir oyuncuya devredilecek." else "İstediğin zaman kodla tekrar katılabilirsin.",
            "Çık", onConfirm = { confirmLeave = false; vm.leave(onLeft) }, onDismiss = { confirmLeave = false },
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            "Odayı kapat?", "Tüm oyuncular odadan çıkarılacak.", "Kapat",
            onConfirm = { confirmClose = false; vm.closeRoom(onLeft) }, onDismiss = { confirmClose = false },
        )
    }
}

@Composable
fun ConnectionPill(state: ConnectionState) {
    val (text, color) = when (state) {
        ConnectionState.LIVE -> "Canlı" to LoopaPalette.Success
        ConnectionState.CONNECTING -> "Bağlanıyor" to LoopaPalette.ButterDeep
        ConnectionState.OFFLINE -> "Bağlantı yok" to LoopaPalette.Error
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text)
    }
}

private fun shareCode(context: Context, code: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "LOOPA'da benimle İsim Şehir oyna! Oda kodu: $code\nPlay together. Think faster.")
    }
    context.startActivity(Intent.createChooser(intent, "Oda kodunu paylaş"))
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("LOOPA oda kodu", code))
    Toast.makeText(context, "Oda kodu kopyalandı", Toast.LENGTH_SHORT).show()
}

@Composable
private fun RoomCodeCard(code: String, isPublic: Boolean, context: Context) {
    val feedback = LocalFeedback.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(LoopaPalette.Lavender).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isPublic) Icons.Rounded.Public else Icons.Rounded.Lock, null, tint = LoopaPalette.Ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isPublic) "AÇIK ODA KODU" else "ÖZEL ODA KODU", style = MaterialTheme.typography.labelMedium, color = LoopaPalette.Ink.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            code.forEachIndexed { i, ch ->
                Box(
                    Modifier.size(width = 42.dp, height = 54.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ch.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp), color = LoopaPalette.accent(i).let { darken(it, 0.55f) })
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LoopaButton("Kopyala", { copyCode(context, code); feedback.haptic(Haptic.LIGHT) }, icon = Icons.Rounded.ContentCopy, color = Color.White, compact = true)
            LoopaButton("Paylaş", { shareCode(context, code) }, icon = Icons.Rounded.Share, color = LoopaPalette.Mint, compact = true)
        }
    }
}

@Composable
private fun SettingsSummary(s: RoomSettings, isHost: Boolean, onEdit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Loopa.colors.card)
            .border(2.dp, Loopa.colors.outline, RoundedCornerShape(24.dp)).padding(16.dp).animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Oyun ayarları", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text, modifier = Modifier.weight(1f))
            if (isHost) LoopaButton("Düzenle", onEdit, icon = Icons.Rounded.Settings, color = LoopaPalette.Butter, compact = true)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("🎮 ${com.loopa.core.game.GameModes.name(s.gameMode)}", com.loopa.core.ui.components.gameVisual(s.gameMode).color, Modifier.testTag("lobby_game_mode"))
            Pill("⏱ ${s.roundSeconds} sn", LoopaPalette.Butter)
            Pill("🔁 ${s.rounds} tur", LoopaPalette.Peach)
            Pill("👥 en fazla ${s.maxPlayers}", LoopaPalette.Sky)
            Pill("🗳 oylama ${s.voteSeconds} sn", LoopaPalette.Lavender)
            if (s.excludedLetters.isNotEmpty()) Pill("Hariç: ${s.excludedLetters.joinToString(" ")}", Loopa.colors.cardHigh, textColor = Loopa.colors.text)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.categories.forEachIndexed { i, cat ->
                Pill(cat.name + if (cat.custom) " ★" else "", LoopaPalette.accent(i).copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun PlayerRow(
    p: RoomPlayer,
    isMe: Boolean,
    hostView: Boolean,
    onKick: () -> Unit,
    onMakeHost: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val readyScale by animateFloatAsState(if (p.isReady || p.isHost) 1f else 0.85f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "ready")
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(if (isMe) LoopaPalette.Lavender.copy(alpha = 0.28f) else Loopa.colors.card)
            .border(2.dp, if (p.isReady || p.isHost) LoopaPalette.Mint else Loopa.colors.outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AvatarView(p.avatar, size = 48.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(Loopa.colors.card).padding(2.dp)
                    .clip(CircleShape).background(if (p.online) LoopaPalette.Success else LoopaPalette.InkSoft),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.username + if (isMe) " (sen)" else "", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (p.isHost) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Star, "Oda sahibi", tint = LoopaPalette.ButterDeep, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                when {
                    !p.online -> "Bağlantı koptu…"
                    p.role == "spectator" -> "İzleyici"
                    p.isHost -> "Oda sahibi"
                    p.isReady -> "Hazır"
                    else -> "Hazırlanıyor"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (p.isReady || p.isHost) darken(LoopaPalette.Success, 0.85f) else Loopa.colors.textSoft,
                fontWeight = FontWeight(800),
            )
        }
        LevelBadge(p.level, size = 28.dp)
        Spacer(Modifier.width(8.dp))
        AnimatedVisibility(p.role == "player", enter = scaleIn() + fadeIn()) {
            Icon(
                if (p.isReady || p.isHost) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty, null,
                tint = if (p.isReady || p.isHost) LoopaPalette.Success else LoopaPalette.InkSoft,
                modifier = Modifier.size(26.dp).scale(readyScale),
            )
        }
        if (hostView && !p.isHost) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Oyuncu menüsü", tint = Loopa.colors.textSoft) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (onMakeHost != null) DropdownMenuItem(text = { Text("Oda sahibi yap") }, onClick = { menu = false; onMakeHost() }, leadingIcon = { Icon(Icons.Rounded.Star, null) })
                    DropdownMenuItem(text = { Text("Odadan çıkar") }, onClick = { menu = false; onKick() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) })
                }
            }
        }
    }
}

package com.loopa.feature.lobby

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaException
import com.loopa.core.model.PublicRoom
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.EmptyState
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayUiState(
    val code: String = "",
    val isPublic: Boolean = false,
    val busy: String? = null,          // "create" | "join" | "spectate" | "refresh"
    val error: String? = null,
    val publicRooms: List<PublicRoom> = emptyList(),
    val loadedPublic: Boolean = false,
)

class PlayViewModel(private val c: DataContainer) : ViewModel() {
    private val _ui = MutableStateFlow(PlayUiState())
    val ui: StateFlow<PlayUiState> = _ui.asStateFlow()

    private val allowed = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    fun setCode(v: String) = _ui.update { s ->
        s.copy(code = v.uppercase().filter { it in allowed }.take(6), error = null)
    }
    fun setPublic(v: Boolean) = _ui.update { it.copy(isPublic = v) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    private fun run(tag: String, block: suspend () -> Unit) {
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = tag, error = null) }
        viewModelScope.launch {
            try { block() } catch (e: LoopaException) { _ui.update { it.copy(error = e.error.message) } }
            _ui.update { it.copy(busy = null) }
        }
    }

    fun create(onReady: (String) -> Unit) = run("create") { onReady(c.rooms.createRoom(_ui.value.isPublic).id) }

    fun join(onReady: (String) -> Unit, spectator: Boolean = false, code: String = _ui.value.code) {
        if (code.length != 6) { _ui.update { it.copy(error = "Oda kodu 6 karakter olmalı.") }; return }
        run(if (spectator) "spectate" else "join") { onReady(c.rooms.joinRoom(code, spectator).id) }
    }

    fun refreshPublic() = run("refresh") {
        val rooms = c.rooms.publicRooms()
        _ui.update { it.copy(publicRooms = rooms, loadedPublic = true) }
    }
}

@Composable
fun PlayScreen(onRoomReady: (String) -> Unit, onPublicRooms: () -> Unit, focusJoin: Boolean = false) {
    val vm = containerViewModel { PlayViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusJoin) { if (focusJoin) runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Oyna", style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text)
            Text("İsim Şehir · Klasik mod", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            ErrorBanner(ui.error, onDismiss = vm::dismissError)

            LoopaCard(color = LoopaPalette.Mint.copy(alpha = 0.7f)) {
                Text("Oda Kur", style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink)
                Spacer(Modifier.height(6.dp))
                Text("Sen oda sahibi olursun: süreyi, tur sayısını ve kategorileri sen belirlersin.", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink.copy(alpha = 0.75f))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ui.isPublic) Icons.Rounded.Public else Icons.Rounded.Lock, null, tint = LoopaPalette.Ink)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (ui.isPublic) "Açık oda" else "Özel oda", style = MaterialTheme.typography.titleSmall, color = LoopaPalette.Ink)
                        Text(
                            if (ui.isPublic) "Oda tarayıcısında herkes görebilir." else "Yalnızca kodu bilenler katılabilir.",
                            style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.7f),
                        )
                    }
                    Switch(ui.isPublic, vm::setPublic, colors = SwitchDefaults.colors(checkedTrackColor = LoopaPalette.MintDeep))
                }
                Spacer(Modifier.height(12.dp))
                LoopaButton("Odayı Oluştur", { vm.create(onRoomReady) }, icon = Icons.Rounded.AddCircle, color = androidx.compose.ui.graphics.Color.White,
                    loading = ui.busy == "create", modifier = Modifier.fillMaxWidth(), sfx = Sfx.JOIN)
            }

            LoopaCard(color = LoopaPalette.Butter.copy(alpha = 0.8f)) {
                Text("Odaya Katıl", style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink)
                Spacer(Modifier.height(10.dp))
                LoopaTextField(
                    value = ui.code,
                    onValueChange = vm::setCode,
                    placeholder = "ÖRN. LOP7X2",
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    accent = LoopaPalette.ButterDeep,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { vm.join(onRoomReady) }),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoopaButton("Katıl", { vm.join(onRoomReady) }, icon = Icons.AutoMirrored.Rounded.Login, color = androidx.compose.ui.graphics.Color.White,
                        loading = ui.busy == "join", enabled = ui.code.length == 6, modifier = Modifier.weight(1f), sfx = Sfx.JOIN)
                    LoopaButton("İzle", { vm.join(onRoomReady, spectator = true) }, icon = Icons.Rounded.Visibility, color = LoopaPalette.Sky,
                        loading = ui.busy == "spectate", enabled = ui.code.length == 6, modifier = Modifier.weight(0.8f))
                }
            }

            LoopaCard(color = LoopaPalette.Sky.copy(alpha = 0.75f), onClick = onPublicRooms) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Public, null, tint = LoopaPalette.Ink, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Açık Odalar", style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink)
                        Text("Herkese açık bir odaya katıl ve yeni insanlarla oyna.", style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }
}

@Composable
fun PublicRoomsScreen(onBack: () -> Unit, onRoomReady: (String) -> Unit) {
    val vm = containerViewModel { PlayViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshPublic() }
    Column(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        LoopaTopBar("Açık Odalar", onBack = onBack) {
            IconButton(onClick = vm::refreshPublic) { Icon(Icons.Rounded.Refresh, "Yenile", tint = Loopa.colors.text) }
        }
        ErrorBanner(ui.error, Modifier.padding(horizontal = 20.dp), onDismiss = vm::dismissError)
        PullToRefreshBox(isRefreshing = ui.busy == "refresh", onRefresh = vm::refreshPublic, modifier = Modifier.fillMaxSize()) {
            if (ui.loadedPublic && ui.publicRooms.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            "Şu an açık oda yok",
                            "Kendi açık odanı kur, başkaları da katılsın!",
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ui.publicRooms, key = { it.id }) { room ->
                        LoopaCard(onClick = { vm.join(onRoomReady, code = room.code) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AvatarView(room.hostAvatar, size = 52.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${room.hostName} odası", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Pill("${room.roundSeconds} sn", LoopaPalette.Butter)
                                        Pill("${room.rounds} tur", LoopaPalette.Peach)
                                        Pill("${room.categories} kategori", LoopaPalette.Mint)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${room.players}/${room.maxPlayers}", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = Loopa.colors.text)
                                    Text("oyuncu", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

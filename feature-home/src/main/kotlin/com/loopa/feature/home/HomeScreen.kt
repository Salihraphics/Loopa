package com.loopa.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaException
import com.loopa.core.model.MatchHistoryEntry
import com.loopa.core.model.ProfileOverview
import com.loopa.core.model.RoomRef
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LevelBadge
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.LoopaLogo
import com.loopa.core.ui.components.SectionTitle
import com.loopa.core.ui.components.StatTile
import com.loopa.core.ui.components.XpBar
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val activeRoom: RoomRef? = null,
    val creating: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(private val c: DataContainer) : ViewModel() {
    val profile: StateFlow<ProfileOverview?> = c.profiles.profile
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            runCatching { c.profiles.refresh() }
            val active = runCatching { c.rooms.activeRoom() }.getOrNull()
            _ui.update { it.copy(activeRoom = active) }
        }
    }

    fun createRoom(onReady: (String) -> Unit) {
        if (_ui.value.creating) return
        _ui.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val ref = c.rooms.createRoom(isPublic = false)
                _ui.update { it.copy(creating = false) }
                onReady(ref.id)
            } catch (e: LoopaException) {
                _ui.update { it.copy(creating = false, error = e.error.message) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }
}

@Composable
fun HomeScreen(
    onRoomReady: (String) -> Unit,
    onJoinRoom: () -> Unit,
    onPublicRooms: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAvatar: () -> Unit,
    publicRoomsEnabled: Boolean = true,
) {
    val vm = containerViewModel { HomeViewModel(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(profile, onOpenProfile, onOpenAvatar)
            ErrorBanner(ui.error, onDismiss = vm::dismissError)
            AnimatedVisibility(ui.activeRoom != null) {
                val room = ui.activeRoom
                if (room != null) {
                    LoopaCard(color = LoopaPalette.Butter, onClick = { onRoomReady(room.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Replay, null, tint = LoopaPalette.Ink)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Odana geri dön", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
                                Text(
                                    "Oda ${room.code} · ${if (room.status == "in_game") "oyun devam ediyor" else "lobide"}",
                                    style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                }
            }
            BigAction("Oda Kur", "Yeni bir oda aç, kodu arkadaşlarınla paylaş.", Icons.Rounded.AddCircle, LoopaPalette.Mint, loading = ui.creating) {
                vm.createRoom(onRoomReady)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SmallAction("Odaya Katıl", Icons.AutoMirrored.Rounded.Login, LoopaPalette.Butter, Modifier.weight(1f), onJoinRoom)
                if (publicRoomsEnabled) SmallAction("Açık Odalar", Icons.Rounded.Public, LoopaPalette.Sky, Modifier.weight(1f), onPublicRooms)
            }
            val stats = profile?.stats
            SectionTitle("İstatistiklerin")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Maç", "${stats?.matchesPlayed ?: 0}", LoopaPalette.Lavender, Modifier.weight(1f))
                StatTile("Galibiyet", "${stats?.matchWins ?: 0}", LoopaPalette.Mint, Modifier.weight(1f))
                StatTile("Tur", "${stats?.roundWins ?: 0}", LoopaPalette.Peach, Modifier.weight(1f))
                StatTile("Puan", "${stats?.totalPoints ?: 0}", LoopaPalette.Butter, Modifier.weight(1f))
            }
            val history = profile?.history.orEmpty()
            SectionTitle("Son maçlar")
            if (history.isEmpty()) {
                LoopaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoopaLogo(size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Henüz maç oynamadın. Bir oda kur ve ilk zaferini kazan!",
                            style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft,
                        )
                    }
                }
            } else {
                history.take(3).forEach { HistoryRow(it) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Header(profile: ProfileOverview?, onProfile: () -> Unit, onAvatar: () -> Unit) {
    LoopaCard(color = LoopaPalette.Lavender.copy(alpha = 0.6f), onClick = onProfile) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AvatarView(profile?.avatar, size = 68.dp, modifier = Modifier.clip(CircleShape))
                LevelBadge(profile?.level ?: 1, Modifier.align(Alignment.BottomEnd), size = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Merhaba,", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink.copy(alpha = 0.7f))
                Text(profile?.username ?: "…", style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink)
                Spacer(Modifier.height(6.dp))
                XpBar(profile?.xp ?: 0, height = 10.dp, showLabel = false)
                Text("Seviye ${profile?.level ?: 1} · ${profile?.xp ?: 0} XP", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Ink.copy(alpha = 0.7f))
            }
            LoopaButton("Avatar", onAvatar, color = Color.White, compact = true)
        }
    }
}

@Composable
private fun BigAction(title: String, body: String, icon: ImageVector, color: Color, loading: Boolean, onClick: () -> Unit) {
    LoopaCard(color = color, onClick = if (loading) null else onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = LoopaPalette.Ink)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink.copy(alpha = 0.75f))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = LoopaPalette.Ink)
                else Icon(icon, null, tint = LoopaPalette.Ink, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun SmallAction(title: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    LoopaCard(color = color, onClick = onClick, modifier = modifier) {
        Icon(icon, null, tint = LoopaPalette.Ink, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
    }
}

@Composable
fun HistoryRow(entry: MatchHistoryEntry, modifier: Modifier = Modifier) {
    val medal = when (entry.finalRank) { 1 -> LoopaPalette.Butter; 2 -> LoopaPalette.Sky; 3 -> LoopaPalette.Peach; else -> Loopa.colors.cardHigh }
    LoopaCard(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(medal), contentAlignment = Alignment.Center) {
                if (entry.finalRank == 1) Icon(Icons.Rounded.EmojiEvents, null, tint = LoopaPalette.Ink)
                else Text("${entry.finalRank}.", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${entry.finalRank}. / ${entry.playerCount} oyuncu", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                Text("Oda ${entry.roomCode} · ${entry.roundWins} tur galibiyeti", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${entry.totalPoints} p", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                Text("+${entry.xpEarned} XP", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.MintDeep)
            }
        }
    }
}

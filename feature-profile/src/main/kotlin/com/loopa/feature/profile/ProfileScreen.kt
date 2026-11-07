package com.loopa.feature.profile

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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.Achievements
import com.loopa.core.game.LoopaException
import com.loopa.core.model.ProfileOverview
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LevelBadge
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.SectionTitle
import com.loopa.core.ui.components.StatTile
import com.loopa.core.ui.components.XpBar
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(private val c: DataContainer) : ViewModel() {
    val profile: StateFlow<ProfileOverview?> = c.profiles.profile
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _catalogSize = MutableStateFlow(0)
    val catalogSize: StateFlow<Int> = _catalogSize.asStateFlow()

    fun refresh() = viewModelScope.launch {
        runCatching { c.profiles.refresh() }
        runCatching { _catalogSize.value = c.profiles.catalog().size }
    }

    fun rename(name: String, done: () -> Unit) = viewModelScope.launch {
        try { c.profiles.updateUsername(name); _error.value = null; done() } catch (e: LoopaException) { _error.value = e.error.message }
    }

    fun dismissError() { _error.value = null }
}

private val reasonText = mapOf(
    "match_complete" to "Maç tamamlandı", "round_win" to "Tur galibiyeti",
    "match_winner" to "Maç birinciliği", "match_second" to "Maç ikinciliği", "match_third" to "Maç üçüncülüğü",
)

@Composable
fun ProfileScreen(onEditAvatar: () -> Unit, onOpenShop: () -> Unit) {
    val vm = containerViewModel { ProfileViewModel(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val catalogSize by vm.catalogSize.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val p = profile
    val stats = p?.stats

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LoopaCard(color = LoopaPalette.Lavender.copy(alpha = 0.55f)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        AvatarView(p?.avatar, size = 128.dp)
                        LevelBadge(p?.level ?: 1, Modifier.align(Alignment.BottomEnd), size = 40.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p?.username ?: "…", style = MaterialTheme.typography.headlineMedium, color = LoopaPalette.Ink)
                        IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Adı düzenle", tint = LoopaPalette.Ink) }
                    }
                    Text("Seviye ${p?.level ?: 1} · ${p?.xp ?: 0} toplam XP", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink.copy(alpha = 0.75f))
                    Spacer(Modifier.height(10.dp))
                    XpBar(p?.xp ?: 0)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LoopaButton("Avatar", onEditAvatar, icon = Icons.Rounded.Palette, color = androidx.compose.ui.graphics.Color.White, compact = true)
                        LoopaButton("Mağaza", onOpenShop, icon = Icons.Rounded.Storefront, color = LoopaPalette.Butter, compact = true)
                    }
                }
            }
            ErrorBanner(error, onDismiss = vm::dismissError)

            SectionTitle("İstatistikler")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Toplam maç", "${stats?.matchesPlayed ?: 0}", LoopaPalette.Lavender, Modifier.weight(1f))
                StatTile("Maç galibiyeti", "${stats?.matchWins ?: 0}", LoopaPalette.Mint, Modifier.weight(1f))
                StatTile("Kazanma oranı", "%${stats?.winRate ?: 0}", LoopaPalette.Sky, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Tur galibiyeti", "${stats?.roundWins ?: 0}", LoopaPalette.Peach, Modifier.weight(1f))
                StatTile("Toplam puan", "${stats?.totalPoints ?: 0}", LoopaPalette.Butter, Modifier.weight(1f))
                StatTile("Erken bitirme", "${stats?.earlyBonuses ?: 0}", LoopaPalette.Mint, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Kürsü (2./3.)", "${(stats?.secondPlaces ?: 0) + (stats?.thirdPlaces ?: 0)}", LoopaPalette.Sky, Modifier.weight(1f))
                StatTile("Kullanılan joker", "${stats?.jokersUsed ?: 0}", LoopaPalette.Lavender, Modifier.weight(1f))
                StatTile("Kozmetik", "${p?.ownedCosmetics?.size ?: 0}/${catalogSize.takeIf { it > 0 } ?: "?"}", LoopaPalette.Peach, Modifier.weight(1f))
            }

            SectionTitle("Başarımlar")
            Achievements.evaluate(stats, p?.level ?: 1).forEach { a ->
                LoopaCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(CircleShape).background(if (a.unlocked) LoopaPalette.Butter else Loopa.colors.cardHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (a.unlocked) Icons.Rounded.Verified else Icons.Rounded.Lock, null, tint = if (a.unlocked) LoopaPalette.Ink else Loopa.colors.textSoft)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                            Text(a.description, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (a.progress.toFloat() / a.goal).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                                color = LoopaPalette.MintDeep, trackColor = Loopa.colors.cardHigh,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${a.progress.coerceAtMost(a.goal)}/${a.goal}", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                    }
                }
            }

            SectionTitle("Son XP kazanımları")
            if (p?.recentXp.isNullOrEmpty()) {
                Text("Henüz XP kazanmadın.", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            } else {
                LoopaCard {
                    p!!.recentXp.forEach { x ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(reasonText[x.reason] ?: x.reason, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text, modifier = Modifier.weight(1f))
                            Pill("+${x.amount} XP", LoopaPalette.Mint)
                        }
                    }
                }
            }

            SectionTitle("Maç geçmişi")
            if (p?.history.isNullOrEmpty()) {
                Text("Henüz tamamlanmış maç yok.", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            } else {
                p!!.history.forEach { h ->
                    LoopaCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill("${h.finalRank}.", if (h.finalRank == 1) LoopaPalette.Butter else LoopaPalette.Lavender)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${h.playerCount} oyuncu · Oda ${h.roomCode}", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                                Text(h.finishedAt.take(10), style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${h.totalPoints} puan", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                                Text("+${h.xpEarned} XP", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.MintDeep)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (editing) {
        var name by remember { mutableStateOf(p?.username.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = Loopa.colors.card,
            title = { Text("Kullanıcı adı", style = MaterialTheme.typography.headlineSmall) },
            text = { LoopaTextField(name, { name = it.take(16) }, Modifier.fillMaxWidth(), placeholder = "3-16 karakter") },
            confirmButton = { TextButton(onClick = { vm.rename(name.trim()) { editing = false } }, enabled = name.trim().length >= 3) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Vazgeç") } },
        )
    }
}

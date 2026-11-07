package com.loopa.feature.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LevelCurve
import com.loopa.core.game.LoopaException
import com.loopa.core.model.CosmeticItem
import com.loopa.core.model.ProfileOverview
import com.loopa.core.ui.avatar.AvatarConfig
import com.loopa.core.ui.avatar.AvatarSlot
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LevelBadge
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaChip
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.XpBar
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShopViewModel(private val c: DataContainer) : ViewModel() {
    val profile: StateFlow<ProfileOverview?> = c.profiles.profile
    private val _catalog = MutableStateFlow<List<CosmeticItem>>(emptyList())
    val catalog: StateFlow<List<CosmeticItem>> = _catalog.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { if (c.profiles.profile.value == null) c.profiles.refresh() }
            try { _catalog.value = c.profiles.catalog() } catch (e: LoopaException) { _error.value = e.error.message }
        }
    }

    fun unlock(item: CosmeticItem) = viewModelScope.launch {
        _busy.value = item.id
        try { c.profiles.unlockCosmetic(item.id); _error.value = null } catch (e: LoopaException) { _error.value = e.error.message }
        _busy.value = null
    }

    fun unlockAll(items: List<CosmeticItem>) = viewModelScope.launch {
        _busy.value = "all"
        for (i in items) runCatching { c.profiles.unlockCosmetic(i.id) }
        _busy.value = null
    }

    fun dismissError() { _error.value = null }
}

private val shopSlots = listOf(
    AvatarSlot.HAIR, AvatarSlot.HAT, AvatarSlot.GLASSES, AvatarSlot.OUTFIT, AvatarSlot.BACKGROUND,
    AvatarSlot.FRAME, AvatarSlot.DECORATION, AvatarSlot.ACCESSORY, AvatarSlot.EYES, AvatarSlot.MOUTH,
    AvatarSlot.HAIR_COLOR, AvatarSlot.SKIN, AvatarSlot.BASE,
)

/**
 * Cosmetic shop. Items are unlocked purely by account level (XP progression):
 * there is no currency and nothing here affects gameplay.
 */
@Composable
fun ShopScreen(onBack: () -> Unit) {
    val vm = containerViewModel { ShopViewModel(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var slot by remember { mutableStateOf(AvatarSlot.HAIR) }
    val feedback = LocalFeedback.current
    val level = profile?.level ?: 1
    val owned = profile?.ownedCosmetics.orEmpty().toSet()
    val avatar = AvatarConfig.fromJson(profile?.avatar)
    val claimable = catalog.filter { it.id !in owned && it.requiredLevel <= level }
    val nextUnlock = catalog.filter { it.requiredLevel > level }.minByOrNull { it.requiredLevel }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            LoopaTopBar("Kozmetik Mağazası", onBack = onBack)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(24.dp)).background(LoopaPalette.Butter.copy(alpha = 0.6f)).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelBadge(level, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Seviyenle açılır", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
                        Text(
                            nextUnlock?.let { "Sıradaki: ${it.name} · Seviye ${it.requiredLevel}" } ?: "Tüm kozmetikleri açtın!",
                            style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.75f),
                        )
                    }
                    Text("${owned.size}/${catalog.size}", style = MaterialTheme.typography.titleLarge, color = LoopaPalette.Ink)
                }
                Spacer(Modifier.height(10.dp))
                XpBar(profile?.xp ?: 0, showLabel = false)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bir sonraki seviye için ${LevelCurve.progress(profile?.xp ?: 0).remaining} XP. Mağaza yalnızca görünüm satar — oyun avantajı yoktur.",
                    style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Ink.copy(alpha = 0.7f),
                )
                if (claimable.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LoopaButton("${claimable.size} öğeyi aç", { vm.unlockAll(claimable); feedback.play(Sfx.LEVEL_UP) },
                        icon = Icons.Rounded.LockOpen, color = LoopaPalette.Mint, compact = true, loading = busy == "all")
                }
            }
            ErrorBanner(error, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), onDismiss = vm::dismissError)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shopSlots.forEachIndexed { i, s -> LoopaChip(s.title, slot == s, { slot = s }, color = LoopaPalette.accent(i)) }
            }
            val items = catalog.filter { it.slot == slot.key }.sortedBy { it.requiredLevel }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Yükleniyor…", color = Loopa.colors.textSoft, modifier = Modifier.padding(16.dp))
                }
                items(items, key = { it.id }) { item ->
                    val isOwned = item.id in owned
                    val canUnlock = !isOwned && item.requiredLevel <= level
                    val shape = RoundedCornerShape(24.dp)
                    Column(
                        Modifier.clip(shape).background(Loopa.colors.card).border(2.dp, if (isOwned) LoopaPalette.Mint else Loopa.colors.outline, shape).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AvatarView(avatar.with(slot, item.id), size = 96.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(item.name, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        when {
                            isOwned -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = LoopaPalette.Success, modifier = Modifier.padding(end = 4.dp))
                                Text("Sende", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
                            }
                            canUnlock -> LoopaButton("Aç", { vm.unlock(item) }, color = LoopaPalette.Mint, compact = true, loading = busy == item.id, sfx = Sfx.LEVEL_UP)
                            else -> Pill("🔒 Seviye ${item.requiredLevel}", Loopa.colors.cardHigh, textColor = Loopa.colors.text)
                        }
                    }
                }
            }
        }
    }
}

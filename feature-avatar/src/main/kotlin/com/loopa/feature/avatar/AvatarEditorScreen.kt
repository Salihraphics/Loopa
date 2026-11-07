package com.loopa.feature.avatar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaException
import com.loopa.core.model.CosmeticItem
import com.loopa.core.ui.avatar.AvatarConfig
import com.loopa.core.ui.avatar.AvatarSlot
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaChip
import com.loopa.core.ui.components.LoopaTopBar
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

data class AvatarEditorState(
    val catalog: List<CosmeticItem> = emptyList(),
    val owned: Set<String> = emptySet(),
    val level: Int = 1,
    val draft: AvatarConfig = AvatarConfig.DEFAULT,
    val original: AvatarConfig = AvatarConfig.DEFAULT,
    val saving: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
    val saved: Boolean = false,
)

class AvatarEditorViewModel(private val c: DataContainer) : ViewModel() {
    private val _s = MutableStateFlow(AvatarEditorState())
    val state: StateFlow<AvatarEditorState> = _s.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            val profile = c.profiles.profile.value ?: c.profiles.refresh()
            val catalog = c.profiles.catalog()
            val avatar = AvatarConfig.fromJson(profile?.avatar)
            _s.update {
                it.copy(catalog = catalog, owned = profile?.ownedCosmetics.orEmpty().toSet(), level = profile?.level ?: 1,
                    draft = avatar, original = avatar, loaded = true, error = null)
            }
        } catch (e: LoopaException) {
            _s.update { it.copy(error = e.error.message, loaded = true) }
        }
    }

    fun select(slot: AvatarSlot, item: CosmeticItem?) {
        val st = _s.value
        if (item != null && item.id !in st.owned) {
            if (item.requiredLevel > st.level) { _s.update { it.copy(error = "${item.name} için seviye ${item.requiredLevel} gerekli.") }; return }
            // eligible but not claimed yet: claim it (server validates the level)
            viewModelScope.launch {
                try {
                    c.profiles.unlockCosmetic(item.id)
                    _s.update { it.copy(owned = it.owned + item.id, draft = it.draft.with(slot, item.id), error = null, saved = false) }
                } catch (e: LoopaException) { _s.update { it.copy(error = e.error.message) } }
            }
            return
        }
        _s.update { it.copy(draft = it.draft.with(slot, item?.id), error = null, saved = false) }
    }

    fun randomize() = _s.update { st ->
        var cfg = st.draft
        AvatarSlot.entries.forEach { slot ->
            val options = st.catalog.filter { it.slot == slot.key && it.id in st.owned }
            if (options.isEmpty()) return@forEach
            cfg = if (slot.optional && slot != AvatarSlot.HAIR && Math.random() < 0.55) cfg.with(slot, null) else cfg.with(slot, options.random().id)
        }
        st.copy(draft = cfg, saved = false)
    }

    fun save() {
        val st = _s.value
        _s.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                c.profiles.updateAvatar(st.draft.toJson())
                _s.update { it.copy(saving = false, original = it.draft, saved = true) }
            } catch (e: LoopaException) {
                _s.update { it.copy(saving = false, error = e.error.message) }
            }
        }
    }

    fun dismissError() = _s.update { it.copy(error = null) }
}

@Composable
fun AvatarEditorScreen(onOpenShop: () -> Unit, onBack: (() -> Unit)? = null) {
    val vm = containerViewModel { AvatarEditorViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    var slot by remember { mutableStateOf(AvatarSlot.HAIR) }
    var bounceKey by remember { mutableStateOf(0) }
    val bounce by animateFloatAsState(if (bounceKey % 2 == 0) 1f else 1.06f, spring(dampingRatio = Spring.DampingRatioHighBouncy), label = "bounce")
    LaunchedEffect(st.saved) { if (st.saved) { feedback.play(Sfx.READY); feedback.haptic(Haptic.SUCCESS) } }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            LoopaTopBar("Avatar Oluşturucu", onBack = onBack) {
                LoopaButton("Mağaza", onOpenShop, icon = Icons.Rounded.Storefront, color = LoopaPalette.Butter, compact = true)
                Spacer(Modifier.width(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarView(st.draft, size = 150.dp, modifier = Modifier.scale(bounce))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    LoopaButton("Rastgele", { vm.randomize(); bounceKey++ }, icon = Icons.Rounded.Casino, color = LoopaPalette.Sky, compact = true, sfx = Sfx.JOKER, modifier = Modifier.fillMaxWidth())
                    LoopaButton(
                        if (st.saved && st.draft == st.original) "Kaydedildi ✓" else "Kaydet", vm::save, icon = Icons.Rounded.Save,
                        color = LoopaPalette.Mint, loading = st.saving, enabled = st.draft != st.original, compact = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ErrorBanner(st.error, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), onDismiss = vm::dismissError)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AvatarSlot.entries.forEachIndexed { i, s ->
                    LoopaChip(s.title, slot == s, { slot = s }, color = LoopaPalette.accent(i))
                }
            }
            val items = st.catalog.filter { it.slot == slot.key }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (slot.optional) {
                    item(key = "none") {
                        ItemTile(
                            name = "Yok", preview = st.draft.with(slot, null), selected = st.draft.parts[slot] == null,
                            locked = false, level = null, claimable = false, noneIcon = true,
                        ) { vm.select(slot, null); bounceKey++ }
                    }
                }
                items(items, key = { it.id }) { item ->
                    val owned = item.id in st.owned
                    ItemTile(
                        name = item.name, preview = st.draft.with(slot, item.id), selected = st.draft[slot] == item.id,
                        locked = !owned && item.requiredLevel > st.level, level = item.requiredLevel, claimable = !owned && item.requiredLevel <= st.level,
                    ) { vm.select(slot, item); bounceKey++ }
                }
            }
            if (!st.loaded) Text("Kozmetikler yükleniyor…", Modifier.padding(20.dp), color = Loopa.colors.textSoft)
        }
    }
}

@Composable
fun ItemTile(
    name: String,
    preview: AvatarConfig,
    selected: Boolean,
    locked: Boolean,
    level: Int?,
    claimable: Boolean,
    noneIcon: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.clip(shape).background(if (selected) LoopaPalette.Lavender.copy(alpha = 0.45f) else Loopa.colors.card)
            .border(3.dp, if (selected) LoopaPalette.LavenderDeep else Loopa.colors.outline, shape)
            .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (noneIcon) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Loopa.colors.cardHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Block, null, tint = Loopa.colors.textSoft, modifier = Modifier.size(34.dp))
                }
            } else {
                AvatarView(preview, size = 72.dp, modifier = Modifier.then(if (locked) Modifier.scale(0.95f) else Modifier))
            }
            if (locked) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Loopa.colors.background.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Lock, "Kilitli", tint = Loopa.colors.text)
                        Text("Sv. $level", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (claimable) Text("Aç!", style = MaterialTheme.typography.labelSmall, color = darken(LoopaPalette.Success, 0.8f))
    }
}

package com.loopa.feature.lobby

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.Remote
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.GameCardKind
import com.loopa.core.game.GameCardStatus
import com.loopa.core.game.GameCards
import com.loopa.core.game.GameCategory
import com.loopa.core.game.GameModeInfo
import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaException
import com.loopa.core.game.PlusFormat
import com.loopa.core.model.GameCatalog
import com.loopa.core.model.Membership
import com.loopa.core.model.ModeStat
import com.loopa.core.ui.components.EmptyState
import com.loopa.core.ui.components.FeaturedGame
import com.loopa.core.ui.components.GameCard
import com.loopa.core.ui.components.featuredGame
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.GameArtwork
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaChip
import com.loopa.core.ui.components.LoopaSurface
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.PlanBadge
import com.loopa.core.ui.components.PlusGradient
import com.loopa.core.ui.components.SectionHeader
import com.loopa.core.ui.components.gameVisual
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GamesViewModel(private val c: DataContainer) : ViewModel() {
    val catalog: StateFlow<Remote<GameCatalog>> = c.catalog.state
    val membership: StateFlow<Remote<Membership>> = c.membership.state
    private val _creating = MutableStateFlow<String?>(null)
    val creating: StateFlow<String?> = _creating.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _modeStats = MutableStateFlow<List<ModeStat>>(emptyList())
    /** Per-game statistics of the player: drives "Son oynananlar". */
    val modeStats: StateFlow<List<ModeStat>> = _modeStats.asStateFlow()

    fun refresh() = viewModelScope.launch {
        launch { c.catalog.refresh() }
        launch { c.membership.refresh() }
        launch { _modeStats.value = c.profiles.modeStats() }
    }

    fun dismissError() { _error.value = null }

    /** Creates a private room for [info]. Only for games this client can play and the server allows. */
    fun create(info: GameModeInfo, onReady: (String) -> Unit) {
        val status = GameCards.status(info, catalog.value.data)
        if (!status.canStart || _creating.value != null) return
        _creating.value = info.id
        viewModelScope.launch {
            // The server checks again that this host may host the game.
            try { onReady(c.rooms.createRoom(false, info.id).id) } catch (e: LoopaException) { _error.value = e.error.message }
            _creating.value = null
        }
    }
}

/** Oyunlar: the catalogue, as a tab (no back button) or as a pushed screen ([onBack] set). */
@Composable
fun GamesScreen(onBack: (() -> Unit)?, onRoomReady: (String) -> Unit, onOpenPlus: () -> Unit) {
    val vm = containerViewModel { GamesViewModel(it) }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val membership by vm.membership.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val stats by vm.modeStats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    GamesContent(catalog, membership, creating, error, onBack ?: {}, vm::refresh, { vm.create(it, onRoomReady) }, onOpenPlus, vm::dismissError,
        modeStats = stats, showBack = onBack != null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GamesContent(
    catalog: Remote<GameCatalog>,
    membership: Remote<Membership>,
    creating: String?,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: (GameModeInfo) -> Unit,
    onOpenPlus: () -> Unit,
    onDismissError: () -> Unit,
    modeStats: List<ModeStat> = emptyList(),
    showBack: Boolean = true,
) {
    val data = catalog.data
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<GameCategory?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val filtering = query.isNotBlank() || category != null
    val games = GameModes.search(query, category)
    val showPlusAction = membership.data?.hasPlusAccess != true
    val recent = modeStats.filter { it.matchesPlayed > 0 }.sortedByDescending { it.lastPlayedAt.orEmpty() }
        .mapNotNull { s -> GameModes.all.firstOrNull { it.id == s.gameMode } }.take(6)

    Column(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(start = if (showBack) Spacing.xs else Spacing.gutter, end = Spacing.sm, top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Geri", tint = Loopa.colors.text) }
            Text("Oyunlar", style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text, modifier = Modifier.weight(1f))
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.testTag("games_search_toggle")) {
                Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Aramayı kapat" else "Ara", tint = Loopa.colors.text)
            }
            if (catalog.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Loopa.colors.text)
            else IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Yenile", tint = Loopa.colors.text) }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = if (maxWidth >= 720.dp) 2 else 1
            LazyColumn(
                Modifier.align(Alignment.TopCenter).fillMaxSize().testTag("games_list"),
                contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.section),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (searching || query.isNotBlank()) item(key = "search") {
                    LoopaTextField(query, { query = it.take(30) }, Modifier.fillMaxWidth().testTag("games_search"), placeholder = "Oyun ara",
                        leading = { Icon(Icons.Rounded.Search, null, tint = Loopa.colors.textSoft) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
                }
                item(key = "categories") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("games_categories"), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        LoopaChip("Tümü", category == null, { category = null }, modifier = Modifier.testTag("category_all"))
                        GameModes.categories.forEach { c ->
                            LoopaChip(c.label, category == c, { category = if (category == c) null else c },
                                color = LoopaPalette.accent(c.ordinal + 1), modifier = Modifier.testTag("category_${c.name}"))
                        }
                    }
                }
                item(key = "error") { ErrorBanner(error, onDismiss = onDismissError) }
                if (!filtering) {
                    item(key = "plan") { PlanStrip(membership, onOpenPlus) }
                    item(key = "featured") {
                        val f = featuredGame(data)
                        FeaturedGame(f, GameCards.status(f, data), creating == f.id, { onCreate(f) }, onOpenPlus, showPlusAction,
                            weekly = data?.rotation?.active == true && f.id in data.rotation.freeModes)
                    }
                    if (recent.isNotEmpty()) item(key = "recent") {
                        Column {
                            SectionHeader("Son oynananlar")
                            LazyRow(Modifier.testTag("recent_games"), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                items(recent, key = { it.id }) { g ->
                                    MiniGameTile(g, GameCards.status(g, data)) { if (GameCards.status(g, data).canStart) onCreate(g) else onOpenPlus() }
                                }
                            }
                        }
                    }
                    item(key = "rotation") { RotationCard(catalog) }
                }
                item(key = "all_header") {
                    SectionHeader(if (filtering) "${games.size} oyun bulundu" else "Tüm oyunlar")
                }
                if (games.isEmpty()) item(key = "empty") {
                    EmptyState("Sonuç yok", "Başka bir ad dene ya da filtreyi kaldır.")
                }
                games.chunked(columns).forEach { row ->
                    item(key = "row_" + row.joinToString("_") { it.id }) {
                        Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            row.forEach { info ->
                                GameCard(info, GameCards.status(info, data), creating == info.id, { onCreate(info) }, onOpenPlus,
                                    showPlusAction = showPlusAction, modifier = Modifier.weight(1f))
                            }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanStrip(membership: Remote<Membership>, onOpenPlus: () -> Unit) {
    val m = membership.data
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("games_plan")) {
        PlanBadge(m?.tier)
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                m == null && membership.unsupported -> "İsim Şehir herkese ücretsiz."
                m == null && membership.error != null -> "Üyelik bilgisi alınamadı."
                m == null -> "Üyelik bilgisi yükleniyor…"
                m.hasPlusAccess -> "Tüm oyunlara erişimin var."
                else -> "İsim Şehir her zaman ücretsiz."
            },
            style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, modifier = Modifier.weight(1f),
        )
        if (m != null && !m.hasPlusAccess) {
            Text("Plus nedir?", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.accentText,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenPlus).padding(10.dp).testTag("games_plus_link"))
        }
    }
}

@Composable
private fun MiniGameTile(info: GameModeInfo, status: GameCardStatus, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clip(Radii.md).clickable(onClick = onClick).testTag("recent_${info.id}")) {
        Box {
            GameArtwork(info.id, Modifier.fillMaxWidth().aspectRatio(1f).clip(Radii.md), label = info.name)
            if (!status.canStart) Icon(Icons.Rounded.Lock, "Kilitli", tint = LoopaPalette.Ink,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
        }
        Text(info.name, style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** This week's and next week's free games. Says nothing about weeks while the rotation is not active. */
@Composable
private fun RotationCard(catalog: Remote<GameCatalog>) {
    val data = catalog.data
    LoopaSurface(Modifier.testTag("rotation_card").animateContentSize(), color = LoopaPalette.Butter.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = Loopa.colors.text)
            Spacer(Modifier.width(10.dp))
            Text("Haftalık ücretsiz oyunlar", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        }
        Spacer(Modifier.height(8.dp))
        val ink = Loopa.colors.textSoft
        when {
            data == null && catalog.unsupported -> Text(
                "Haftalık rotasyon bu sunucuda henüz etkin değil. Şimdilik İsim Şehir oynanabilir.",
                style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.testTag("rotation_unsupported"))
            data == null && catalog.error != null -> Text(
                "Oyun kataloğu alınamadı (${catalog.error?.message}). Erişim bilgisi olmadan hiçbir oyun açık gösterilmez; İsim Şehir her zaman ücretsiz.",
                style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.testTag("rotation_error"))
            data == null -> Text("Yükleniyor…", style = MaterialTheme.typography.bodyMedium, color = ink)
            !data.rotation.active -> Text(
                "Haftalık rotasyon, yeni oyunlar yayınlandığı hafta başlayacak. Her hafta 1-2 oyun herkese ücretsiz olacak; İsim Şehir her zaman ücretsiz.",
                style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.testTag("rotation_inactive"))
            else -> Column(Modifier.testTag("rotation_active")) {
                val until = PlusFormat.date(data.rotation.weekEndsAt)
                WeekRow("Bu hafta", data.rotation.freeModes, until?.let { "Yeni liste $it tarihinde" })
                Spacer(Modifier.height(8.dp))
                WeekRow("Gelecek hafta", data.rotation.nextFreeModes, null)
                Text("Hafta, pazartesi 00:00'da (Türkiye saati) yenilenir.", style = MaterialTheme.typography.bodySmall,
                    color = ink, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekRow(title: String, modes: List<String>, note: String?) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Loopa.colors.text)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("İsim Şehir", LoopaPalette.Mint)
        modes.forEach { Pill(GameModes.name(it), gameVisual(it).color) }
    }
    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, modifier = Modifier.padding(top = 4.dp))
}


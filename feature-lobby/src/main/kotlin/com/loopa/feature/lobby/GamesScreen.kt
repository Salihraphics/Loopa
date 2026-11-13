package com.loopa.feature.lobby

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import com.loopa.core.game.GameModeInfo
import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaException
import com.loopa.core.game.PlusFormat
import com.loopa.core.model.GameCatalog
import com.loopa.core.model.Membership
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.GameIcon
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.PlanBadge
import com.loopa.core.ui.components.PlusGradient
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.components.gameVisual
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
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

    fun refresh() = viewModelScope.launch {
        launch { c.catalog.refresh() }
        launch { c.membership.refresh() }
    }

    fun dismissError() { _error.value = null }

    /** Creates a private room for [info]. Only for games this client can play and the server allows. */
    fun create(info: GameModeInfo, onReady: (String) -> Unit) {
        val status = GameCards.status(info, catalog.value.data)
        if (!status.canStart || _creating.value != null) return
        // This app version can only play İsim Şehir, which every new room already is.
        if (info.id != GameModes.ISIM_SEHIR) { _error.value = com.loopa.core.game.LoopaError.MODE_NOT_IMPLEMENTED.message; return }
        _creating.value = info.id
        viewModelScope.launch {
            try { onReady(c.rooms.createRoom(false).id) } catch (e: LoopaException) { _error.value = e.error.message }
            _creating.value = null
        }
    }
}

@Composable
fun GamesScreen(onBack: () -> Unit, onRoomReady: (String) -> Unit, onOpenPlus: () -> Unit) {
    val vm = containerViewModel { GamesViewModel(it) }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val membership by vm.membership.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    GamesContent(catalog, membership, creating, error, onBack, vm::refresh, { vm.create(it, onRoomReady) }, onOpenPlus, vm::dismissError)
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
) {
    val data = catalog.data
    Column(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        LoopaTopBar("Oyunlar", onBack = onBack) {
            if (catalog.loading) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.5.dp, color = Loopa.colors.text)
            else IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Yenile", tint = Loopa.colors.text) }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().testTag("games_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ErrorBanner(error, onDismiss = onDismissError) }
                item { PlanStrip(membership, onOpenPlus) }
                item { RotationCard(catalog) }
                items(GameModes.all, key = { it.id }) { info ->
                    GameCard(info, GameCards.status(info, data), creating == info.id, { onCreate(info) }, onOpenPlus,
                        showPlusAction = membership.data?.hasPlusAccess != true)
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
            Text("Plus nedir?", style = MaterialTheme.typography.labelMedium, color = LoopaPalette.LavenderDeep,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenPlus).padding(6.dp).testTag("games_plus_link"))
        }
    }
}

/** This week's and next week's free games. Says nothing about weeks while the rotation is not active. */
@Composable
private fun RotationCard(catalog: Remote<GameCatalog>) {
    val data = catalog.data
    LoopaCard(color = LoopaPalette.Butter.copy(alpha = 0.55f), modifier = Modifier.testTag("rotation_card").animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = LoopaPalette.Ink)
            Spacer(Modifier.width(10.dp))
            Text("Haftalık ücretsiz oyunlar", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
        }
        Spacer(Modifier.height(8.dp))
        val ink = LoopaPalette.Ink.copy(alpha = 0.78f)
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
    Text(title, style = MaterialTheme.typography.labelLarge, color = LoopaPalette.Ink)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("İsim Şehir", LoopaPalette.Mint)
        modes.forEach { Pill(GameModes.name(it), gameVisual(it).color) }
    }
    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun GameCard(
    info: GameModeInfo,
    status: GameCardStatus,
    creating: Boolean,
    onCreate: () -> Unit,
    onOpenPlus: () -> Unit,
    showPlusAction: Boolean,
) {
    val visual = gameVisual(info.id)
    val dim = status.kind != GameCardKind.PLAYABLE
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (dim) Loopa.colors.card else visual.color.copy(alpha = 0.35f))
            .border(2.dp, if (dim) Loopa.colors.outline else darken(visual.color).copy(alpha = 0.45f), shape)
            .padding(16.dp).testTag("game_card_${info.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameIcon(info.id, dimmed = dim)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                Text(info.players, style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
            }
            StatusBadge(status)
        }
        Spacer(Modifier.height(8.dp))
        Text(info.tagline, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text)
        Spacer(Modifier.height(4.dp))
        Text(status.explanation, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
            modifier = Modifier.testTag("game_status_${info.id}"))
        if (status.freeThisWeek || status.freeNextWeek) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status.freeThisWeek) Pill("Bu hafta ücretsiz", LoopaPalette.Butter)
                if (status.freeNextWeek) Pill("Gelecek hafta ücretsiz", LoopaPalette.Sky)
            }
        }
        when {
            status.canStart -> {
                Spacer(Modifier.height(12.dp))
                LoopaButton("Oda kur", onCreate, icon = Icons.Rounded.AddCircle, color = Color.White, loading = creating,
                    modifier = Modifier.fillMaxWidth().testTag("game_create_${info.id}"), sfx = Sfx.JOIN)
            }
            status.kind == GameCardKind.LOCKED_PLUS && showPlusAction -> {
                Spacer(Modifier.height(12.dp))
                LoopaButton("LOOPA Plus'ı incele", onOpenPlus, icon = Icons.Rounded.AutoAwesome, color = LoopaPalette.Lavender, compact = true,
                    modifier = Modifier.testTag("game_plus_${info.id}"))
            }
        }
    }
}

@Composable
private fun StatusBadge(status: GameCardStatus) {
    val shape = RoundedCornerShape(50)
    val (bg, icon) = when (status.kind) {
        GameCardKind.PLAYABLE -> Modifier.background(LoopaPalette.Mint) to null
        GameCardKind.LOCKED_PLUS -> Modifier.background(PlusGradient) to Icons.Rounded.Lock
        GameCardKind.COMING_SOON -> Modifier.background(Loopa.colors.cardHigh) to Icons.Rounded.Schedule
        GameCardKind.UNAVAILABLE -> Modifier.background(Loopa.colors.cardHigh) to null
    }
    Row(Modifier.clip(shape).then(bg).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = LoopaPalette.Ink, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(status.badge, style = MaterialTheme.typography.labelSmall,
            color = if (status.kind == GameCardKind.PLAYABLE || status.kind == GameCardKind.LOCKED_PLUS) LoopaPalette.Ink else Loopa.colors.text)
    }
}

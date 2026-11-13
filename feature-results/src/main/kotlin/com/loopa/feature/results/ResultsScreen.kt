package com.loopa.feature.results

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LevelCurve
import com.loopa.core.game.LoopaException
import com.loopa.core.model.MatchResultPlayer
import com.loopa.core.model.MatchResults
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.FullScreenLoading
import com.loopa.core.ui.components.LevelBadge
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.XpBar
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
import kotlin.math.sin
import kotlin.random.Random

data class ResultsUiState(
    val results: MatchResults? = null,
    val error: String? = null,
    val levelBefore: Int? = null,
    val levelAfter: Int? = null,
    val myXp: Int = 0,
)

class ResultsViewModel(private val c: DataContainer, private val matchId: String) : ViewModel() {
    private val _ui = MutableStateFlow(ResultsUiState(levelBefore = c.profiles.profile.value?.level))
    val ui: StateFlow<ResultsUiState> = _ui.asStateFlow()
    val myId: String? get() = c.auth.userId

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                val r = c.rooms.matchResults(matchId)
                val profile = runCatching { c.profiles.refresh() }.getOrNull()
                _ui.update { it.copy(results = r, error = null, levelAfter = profile?.level, myXp = profile?.xp ?: 0) }
            } catch (e: LoopaException) {
                _ui.update { it.copy(error = e.error.message) }
            }
        }
    }
}

@Composable
fun ResultsScreen(
    matchId: String,
    onPlayAgain: () -> Unit,
    onReturnToLobby: () -> Unit,
    onLeaveRoom: () -> Unit,
) {
    val vm = containerViewModel(key = "results-$matchId") { ResultsViewModel(it, matchId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    val results = ui.results
    if (results == null) {
        if (ui.error != null) {
            Column(Modifier.fillMaxSize().background(Loopa.colors.background).padding(24.dp), verticalArrangement = Arrangement.Center) {
                ErrorBanner(ui.error)
                Spacer(Modifier.height(12.dp))
                LoopaButton("Tekrar dene", vm::load, color = LoopaPalette.Sky, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LoopaButton("Lobiye dön", onReturnToLobby, color = LoopaPalette.Lavender, modifier = Modifier.fillMaxWidth())
            }
        } else {
            FullScreenLoading("Sonuçlar hesaplanıyor…")
        }
        return
    }
    val me = results.players.firstOrNull { it.userId == vm.myId }
    val iWon = me?.finalRank == 1
    val leveledUp = ui.levelBefore != null && ui.levelAfter != null && ui.levelAfter!! > ui.levelBefore!!
    LaunchedEffect(results.id) {
        if (iWon) { feedback.play(Sfx.WIN); feedback.haptic(Haptic.SUCCESS) } else feedback.play(Sfx.SCORE)
        if (leveledUp) { kotlinx.coroutines.delay(1_200); feedback.play(Sfx.LEVEL_UP) }
    }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Confetti(Modifier.fillMaxSize(), intense = iWon)
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Trophy()
                        Text(if (iWon) "Kazandın!" else "Maç bitti!", style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text)
                        val winner = results.players.firstOrNull { it.finalRank == 1 }
                        Text(
                            if (iWon) "Muhteşem bir performans." else "Kazanan: ${winner?.username ?: "-"}",
                            style = MaterialTheme.typography.bodyLarge, color = Loopa.colors.textSoft,
                        )
                        if (results.status == "abandoned") Pill("Maç yarıda kaldı", LoopaPalette.Peach)
                        Spacer(Modifier.height(6.dp))
                        Pill(com.loopa.core.game.GameModes.name(results.gameMode), com.loopa.core.ui.components.gameVisual(results.gameMode).color)
                        TeamSummary(results)
                    }
                }
                item { Podium(results.players) }
                if (me != null) {
                    item { MyRewards(me, ui.myXp, leveledUp, ui.levelAfter, results.gameMode) }
                }
                item { Text("Sıralama", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text) }
                items(results.players, key = { it.userId }) { p -> RankRow(p, isMe = p.userId == vm.myId, gameMode = results.gameMode) }
                if (results.roundWinners.isNotEmpty()) {
                    item {
                        LoopaCard {
                            Text("Tur galipleri", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                            Spacer(Modifier.height(8.dp))
                            results.roundWinners.forEach { rw ->
                                val names = rw.winners.orEmpty().mapNotNull { id -> results.players.firstOrNull { it.userId == id }?.username }
                                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Pill("Tur ${rw.round}", LoopaPalette.accent(rw.round))
                                    Spacer(Modifier.width(6.dp))
                                    Pill(rw.letter, LoopaPalette.Butter)
                                    Spacer(Modifier.width(10.dp))
                                    Text(names.ifEmpty { listOf("—") }.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text)
                                }
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(Loopa.colors.card).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LoopaButton("Tekrar Oyna", onPlayAgain, icon = Icons.Rounded.Replay, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth(), sfx = Sfx.READY)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoopaButton("Lobiye Dön", onReturnToLobby, icon = Icons.Rounded.MeetingRoom, color = LoopaPalette.Lavender, modifier = Modifier.weight(1f), compact = true)
                    LoopaButton("Odadan Çık", onLeaveRoom, icon = Icons.AutoMirrored.Rounded.Logout, color = LoopaPalette.Peach, modifier = Modifier.weight(1f), compact = true)
                }
            }
        }
    }
}

@Composable
private fun Trophy() {
    val t = rememberInfiniteTransition(label = "trophy")
    val bob by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "bob")
    val appear = remember { Animatable(0.2f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
    Box(
        Modifier.padding(bottom = 8.dp).size(110.dp).graphicsLayer { translationY = bob; scaleX = appear.value; scaleY = appear.value }
            .clip(CircleShape).background(LoopaPalette.Butter).border(4.dp, LoopaPalette.ButterDeep, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.EmojiEvents, "Kupa", tint = LoopaPalette.Ink, modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun Podium(players: List<MatchResultPlayer>) {
    val first = players.firstOrNull { it.finalRank == 1 }
    val second = players.firstOrNull { it.finalRank == 2 && it != first } ?: players.getOrNull(1)
    val third = players.firstOrNull { it.finalRank == 3 } ?: players.getOrNull(2)
    Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        PodiumColumn(second, 2, 120.dp, LoopaPalette.Sky, Modifier.weight(1f), delayMs = 300)
        PodiumColumn(first, 1, 165.dp, LoopaPalette.Butter, Modifier.weight(1f), delayMs = 700)
        PodiumColumn(third, 3, 90.dp, LoopaPalette.Peach, Modifier.weight(1f), delayMs = 0)
    }
}

@Composable
private fun PodiumColumn(p: MatchResultPlayer?, place: Int, height: Dp, color: Color, modifier: Modifier, delayMs: Int) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(delayMs.toLong()); grow.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (p != null) {
            AvatarView(p.avatar, size = if (place == 1) 74.dp else 58.dp, modifier = Modifier.scale(grow.value.coerceIn(0f, 1f)))
            Text(p.username, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.totalPoints} p", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(height * grow.value.coerceIn(0.05f, 1.1f)).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(color),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text("$place", style = MaterialTheme.typography.displaySmall, color = LoopaPalette.Ink, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun MyRewards(me: MatchResultPlayer, xp: Int, leveledUp: Boolean, level: Int?, gameMode: String = com.loopa.core.game.GameModes.ISIM_SEHIR) {
    val earned by animateIntAsState(me.xpEarned, tween(1400), label = "xp")
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(leveledUp) { if (leveledUp) { kotlinx.coroutines.delay(1_200); pulse.animateTo(1.15f, tween(200)); pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy)) } }
    LoopaCard(color = LoopaPalette.Lavender.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kazandığın XP", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
                Text("+$earned XP", style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text)
            }
            LevelBadge(level ?: LevelCurve.levelForXp(xp), size = 54.dp, modifier = Modifier.scale(pulse.value))
        }
        if (leveledUp) {
            Spacer(Modifier.height(6.dp))
            Pill("🎉 Seviye atladın! Seviye $level — yeni kozmetikler açılmış olabilir.", LoopaPalette.Butter)
        }
        Spacer(Modifier.height(10.dp))
        XpBar(xp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("${me.roundWins} tur galibiyeti", LoopaPalette.Butter)
            if (gameMode == com.loopa.core.game.GameModes.ISIM_SEHIR) {
                Pill("${me.validAnswers} geçerli", LoopaPalette.Mint)
                Pill("${me.uniqueAnswers} benzersiz", LoopaPalette.Sky)
                if (me.earlyBonuses > 0) Pill("⚡${me.earlyBonuses}", LoopaPalette.Peach)
            } else {
                Pill("${me.correctActions} isabet", LoopaPalette.Mint)
            }
        }
        Text(
            "XP: maçı tamamlama +20, tur galibiyeti +30, 1./2./3. +100/+50/+30. Seviyeler yalnızca kozmetik açar.",
            style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun RankRow(p: MatchResultPlayer, isMe: Boolean, gameMode: String = com.loopa.core.game.GameModes.ISIM_SEHIR) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (isMe) LoopaPalette.Lavender.copy(alpha = 0.3f) else Loopa.colors.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${p.finalRank ?: "-"}.", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
        AvatarView(p.avatar, size = 42.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.username + if (isMe) " (sen)" else "", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
            Text(if (gameMode == com.loopa.core.game.GameModes.ISIM_SEHIR) "${p.roundWins} tur · ${p.validAnswers} geçerli cevap" else "${p.roundWins} tur · ${p.correctActions} isabet",
                style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${p.totalPoints}", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text)
            Text("+${p.xpEarned} XP", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.MintDeep)
        }
    }
}

/** Lightweight confetti (pure Canvas, no allocations per frame beyond the particle list). */
@Composable
fun Confetti(modifier: Modifier = Modifier, intense: Boolean = true) {
    val particles = remember {
        val rnd = Random(42)
        List(if (intense) 70 else 28) {
            floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.35f + rnd.nextFloat(), rnd.nextFloat() * 360f, rnd.nextInt(5).toFloat(), 6f + rnd.nextFloat() * 8f)
        }
    }
    val t by rememberInfiniteTransition(label = "confetti").animateFloat(0f, 1f, infiniteRepeatable(tween(5_000, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        particles.forEach { p ->
            val y = ((p[1] + t * p[2]) % 1.2f - 0.1f) * size.height
            val x = (p[0] + 0.03f * sin((t * 6.28f * 2f) + p[3])) * size.width
            val color = LoopaPalette.accent(p[4].toInt())
            rotate(p[3] + t * 720f * p[2], Offset(x, y)) {
                drawRect(color, Offset(x - p[5] / 2, y - p[5] / 4), Size(p[5], p[5] / 2))
            }
        }
    }
}

/** Anlat Bakalım: the two team totals. */
@Composable
private fun TeamSummary(results: com.loopa.core.model.MatchResults) {
    if (results.gameMode != "anlat_bakalim") return
    val pub = results.modePublic.let { com.loopa.core.model.ModeJson.decodeFromJsonElement(com.loopa.core.game.modes.AnlatPublic.serializer(),
        it ?: kotlinx.serialization.json.JsonObject(emptyMap())) }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pub.teamScores.forEachIndexed { t, sc -> Pill("${com.loopa.core.game.modes.AnlatBakalimLogic.teamName(t)}: $sc", if (t == 0) LoopaPalette.Sky else LoopaPalette.Peach) }
    }
}

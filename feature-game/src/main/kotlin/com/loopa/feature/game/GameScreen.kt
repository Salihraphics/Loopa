package com.loopa.feature.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopa.core.data.ConnectionState
import com.loopa.core.data.SessionEvent
import com.loopa.core.data.containerViewModel
import com.loopa.core.data.rememberDataContainer
import com.loopa.core.game.Timestamps
import com.loopa.core.model.JokerType
import com.loopa.core.model.RoomState
import com.loopa.core.model.RoundPhase
import com.loopa.core.ui.components.ConfirmDialog
import com.loopa.core.ui.components.FullScreenLoading
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.Haptic
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Banner(val title: String, val body: String, val color: Color, val icon: JokerType?)

@Composable
fun GameScreen(roomId: String, state: RoomState, onLeft: () -> Unit) {
    val vm = containerViewModel(key = "game-$roomId") { GameViewModel(it, roomId) }
    val container = rememberDataContainer()
    val feedback = LocalFeedback.current
    val now by rememberServerNow(vm.clock)
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val busyJoker by vm.busyJoker.collectAsStateWithLifecycle()
    val votingIds by vm.votingIds.collectAsStateWithLifecycle()
    val connection by vm.session.connection.collectAsStateWithLifecycle()
    var banner by remember { mutableStateOf<Banner?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(state.round?.id, state.round?.phase) { vm.syncFromServer(state) }
    LaunchedEffect(vm) {
        vm.toasts.collect { t ->
            banner = when (t) {
                is GameToast.JokerUsed -> Banner(t.type.displayName, when (t.type) {
                    JokerType.EXTRA_TIME -> "+10 saniye eklendi!"
                    JokerType.TIME_THIEF -> "Rakipten ${t.seconds} saniye çaldın!"
                    JokerType.SLOWDOWN -> "Rakibin 10 saniye kaybetti!"
                    JokerType.DOUBLE_POINTS -> "Seçtiğin kategori bu tur 2X puan!"
                }, jokerColor(t.type), t.type)
                is GameToast.EarlyBonus -> { feedback.play(Sfx.WIN); Banner("+${t.points} Bonus!", "Tüm kategorileri ilk bitiren sensin.", LoopaPalette.Butter, null) }
                is GameToast.Error -> { feedback.play(Sfx.ERROR); Banner("Olmadı", t.message, LoopaPalette.Error.copy(alpha = 0.6f), null) }
            }
        }
    }
    LaunchedEffect(vm) {
        vm.session.events.collect { e ->
            if (e is SessionEvent.JokerHit) {
                feedback.play(Sfx.JOKER); feedback.haptic(Haptic.WARNING)
                val by = state.player(e.event.userId)?.username ?: "Bir rakip"
                banner = Banner(
                    e.event.type.displayName,
                    when (e.event.type) {
                        JokerType.TIME_THIEF -> "$by senden ${e.event.seconds ?: 5} saniye çaldı!"
                        JokerType.SLOWDOWN -> "$by seni yavaşlattı: -${e.event.seconds ?: 10} saniye!"
                        else -> "$by sana joker kullandı!"
                    },
                    jokerColor(e.event.type), e.event.type,
                )
            }
        }
    }
    LaunchedEffect(banner) { if (banner != null) { delay(2_600); banner = null } }

    val round = state.round
    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize()) {
            // header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { confirmLeave = true }) { Icon(Icons.AutoMirrored.Rounded.Logout, "Oyundan çık", tint = Loopa.colors.text) }
                Text(
                    "Tur ${state.match?.currentRound ?: 0}/${state.match?.totalRounds ?: 0}",
                    style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, modifier = Modifier.weight(1f),
                )
                if (state.isSpectator) { Pill("İzleyici", LoopaPalette.Sky); Spacer(Modifier.width(6.dp)) }
                val myScore = state.match?.scoreboard?.firstOrNull { it.userId == state.me.userId }?.totalPoints
                if (myScore != null) { Pill("$myScore puan", LoopaPalette.Lavender); Spacer(Modifier.width(6.dp)) }
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        when (connection) { ConnectionState.LIVE -> LoopaPalette.Success; ConnectionState.CONNECTING -> LoopaPalette.ButterDeep; ConnectionState.OFFLINE -> LoopaPalette.Error },
                    ),
                )
                Spacer(Modifier.width(12.dp))
            }
            if (connection == ConnectionState.OFFLINE) {
                Text(
                    "Bağlantı koptu — yeniden bağlanılıyor. Süren sunucuda işlemeye devam ediyor.",
                    style = MaterialTheme.typography.labelSmall, color = darken(LoopaPalette.Error, 0.8f),
                    modifier = Modifier.fillMaxWidth().background(LoopaPalette.Error.copy(alpha = 0.15f)).padding(8.dp), textAlign = TextAlign.Center,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (round == null) {
                    FullScreenLoading("Tur hazırlanıyor…")
                } else {
                    val startsIn = ((Timestamps.parse(round.startedAt) ?: now) - now).coerceAtLeast(0)
                    val stage = when {
                        round.phase == RoundPhase.ANSWERING && startsIn > 0 -> "letter"
                        round.phase == RoundPhase.ANSWERING -> "answer"
                        else -> round.phase.name
                    }
                    AnimatedContent(stage to round.id, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() }, label = "phase") { (st, _) ->
                        when (st) {
                            "letter" -> LetterReveal(round, startsIn)
                            "answer" -> {
                                val me = round.player(state.me.userId)
                                val remaining = vm.remainingMs(state, now)
                                when {
                                    state.isSpectator || me == null -> SpectatorAnsweringView(state, round, now)
                                    me.submitted || remaining <= 0 -> WaitingView(state, round, now, timeUp = !me.submitted, bonus = round.firstSubmitterId == state.me.userId)
                                    else -> AnsweringView(
                                        state, round, now, remaining, drafts[round.id].orEmpty(), submitting, busyJoker,
                                        onAnswer = { k, v -> vm.setAnswer(round.id, k, v) },
                                        onSubmit = { vm.submit(state) },
                                        onFinalSave = { vm.finalSave(round.id) },
                                        onUseJoker = { j, t, c -> vm.useJoker(state, j, t, c) },
                                    )
                                }
                            }
                            "VOTING" -> VotingView(state, round, now, votingIds, vm::vote)
                            "REVEAL" -> RevealView(state, round, now, vm::skip)
                            else -> RoundResultsView(state, round, now, vm::skip)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            banner != null, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp),
            enter = slideInVertically { -it } + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)), exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val b = banner
            if (b != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(24.dp)).background(b.color).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (b.icon != null) {
                        Icon(jokerIcon(b.icon), null, tint = LoopaPalette.Ink, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(b.title, style = MaterialTheme.typography.titleLarge, color = LoopaPalette.Ink)
                        Text(b.body, style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            "Oyundan çık?",
            "Maç devam ederken çıkarsan bu maçtan XP kazanamazsın. Aynı kodla geri dönebilirsin.",
            "Çık",
            onConfirm = {
                confirmLeave = false
                scope.launch {
                    vm.session.leave()
                    container.sessions.close()
                    onLeft()
                }
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

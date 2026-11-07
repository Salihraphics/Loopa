package com.loopa.feature.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopa.core.game.AnswerGrouping
import com.loopa.core.game.RoundTiming
import com.loopa.core.game.Timestamps
import com.loopa.core.model.Answer
import com.loopa.core.model.RoomState
import com.loopa.core.model.Round
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.feedback.Haptic
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

// ----------------------------------------------------------------------------- Voting
@Composable
fun VotingView(state: RoomState, round: Round, nowMs: Long, votingIds: Set<String>, onVote: (String, Boolean) -> Unit) {
    val pending = round.answers.filter { it.isPendingVote }
    val deadline = Timestamps.parse(round.votingDeadline) ?: nowMs
    val remaining = (deadline - nowMs).coerceAtLeast(0)
    val total = (state.match?.settings?.voteSeconds ?: 20) * 1000L
    val iParticipate = round.player(state.me.userId) != null && !state.isSpectator
    val myOpen = pending.count { it.userId != state.me.userId && it.myVote == null }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(LoopaPalette.Lavender), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.HowToVote, null, tint = LoopaPalette.Ink, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Oylama zamanı!", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
                Text(
                    if (!iParticipate) "Oyuncular şüpheli cevapları oyluyor."
                    else if (myOpen > 0) "$myOpen cevap senin oyunu bekliyor. Kabul mü, red mi?"
                    else "Oyunu verdin! Diğerleri bekleniyor.",
                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                )
            }
            TimerRing(remaining, total, size = 60.dp)
        }
        Text(
            "Sözlükte bulunamayan cevaplar oylanır. Çoğunluk kabul ederse geçerli sayılır; eşitlikte reddedilir.",
            style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pending, key = { it.id }) { a ->
                VoteCard(state, round, a, canVote = iParticipate && a.userId != state.me.userId, busy = a.id in votingIds, onVote = onVote, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun VoteCard(state: RoomState, round: Round, a: Answer, canVote: Boolean, busy: Boolean, onVote: (String, Boolean) -> Unit, modifier: Modifier) {
    val feedback = LocalFeedback.current
    val cat = round.categories.firstOrNull { it.key == a.categoryKey }
    val author = state.player(a.userId)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Loopa.colors.card)
            .border(2.dp, Loopa.colors.outline, RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(cat?.name ?: com.loopa.core.model.BuiltInCategories.all.firstOrNull { it.key == a.categoryKey }?.name ?: a.categoryKey, LoopaPalette.accentFor(a.categoryKey))
            Spacer(Modifier.width(6.dp))
            Pill("Harf: ${round.letter}", LoopaPalette.Butter)
            Spacer(Modifier.weight(1f))
            Text("${a.votesCast}/${a.votesNeeded ?: "?"} oy", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
        }
        Spacer(Modifier.height(12.dp))
        Text("“${a.text}”", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarView(author?.avatar, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(author?.username ?: "?", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
        }
        Spacer(Modifier.height(12.dp))
        when {
            a.userId == state.me.userId -> Pill("Senin cevabın — diğerleri oyluyor", Loopa.colors.cardHigh, textColor = Loopa.colors.textSoft)
            !canVote -> Pill("Oy veremezsin", Loopa.colors.cardHigh, textColor = Loopa.colors.textSoft)
            a.myVote != null -> Pill(if (a.myVote == true) "✓ Kabul ettin" else "✗ Reddettin", if (a.myVote == true) LoopaPalette.Mint else LoopaPalette.Peach)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoopaButton("Kabul", { onVote(a.id, true); feedback.haptic(Haptic.LIGHT) }, icon = Icons.Rounded.Check, color = LoopaPalette.Mint,
                    loading = busy, modifier = Modifier.weight(1f), sfx = Sfx.VOTE)
                LoopaButton("Red", { onVote(a.id, false); feedback.haptic(Haptic.LIGHT) }, icon = Icons.Rounded.Close, color = LoopaPalette.Peach,
                    enabled = !busy, modifier = Modifier.weight(1f), sfx = Sfx.VOTE)
            }
        }
    }
}

// ----------------------------------------------------------------------------- Reveal
/** Category-by-category reveal, synchronized across devices via server timestamps. */
@Composable
fun RevealView(state: RoomState, round: Round, nowMs: Long, onSkip: () -> Unit) {
    val feedback = LocalFeedback.current
    val index = RoundTiming.revealIndex(round, nowMs).coerceAtMost(round.categories.lastIndex)
    val cat = round.categories.getOrNull(index) ?: return
    val stepMs = RoundTiming.revealStepMs(round)
    val answers = round.answersFor(cat.key)
    val groups = AnswerGrouping.group(answers)
    LaunchedEffect(index) { feedback.play(Sfx.REVEAL) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cevaplar açıklanıyor", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
                Text(cat.name, style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LetterBadge(round.letter, size = 60.dp)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            round.categories.forEachIndexed { i, _ ->
                Box(
                    Modifier.height(7.dp).weight(1f).clip(RoundedCornerShape(50))
                        .background(if (i <= index) LoopaPalette.accent(i) else Loopa.colors.cardHigh),
                )
            }
        }
        key(cat.key) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                var order = 0
                groups.forEach { group ->
                    val delayStep = (stepMs * 0.6f / answers.size.coerceAtLeast(1)).toLong().coerceIn(120, 600)
                    val groupDelay = order * delayStep
                    order += group.answers.size
                    item(key = group.normalized) { RevealGroupCard(state, round, group, groupDelay) }
                }
            }
        }
        if (state.isHost) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                LoopaButton("Atla", onSkip, icon = Icons.Rounded.SkipNext, color = LoopaPalette.Sky, compact = true)
            }
        }
    }
}

@Composable
private fun RevealGroupCard(state: RoomState, round: Round, group: AnswerGrouping.Group, delayMs: Long) {
    val feedback = LocalFeedback.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs)
        appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        if (group.answers.any { it.points > 0 }) feedback.play(Sfx.SCORE)
    }
    val first = group.answers.first()
    val status = statusOf(first)
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f); rotationX = (1f - appear.value) * 70f; scaleX = 0.9f + 0.1f * appear.value; scaleY = scaleX }
            .clip(RoundedCornerShape(22.dp)).background(status.bg).border(2.dp, status.border, RoundedCornerShape(22.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (first.text.isBlank()) "— boş —" else first.text,
                style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Pill(status.label, Color.White.copy(alpha = 0.75f))
        }
        if (group.answers.size > 1 && first.text.isNotBlank()) {
            Text("${group.answers.size} kişi aynı cevabı yazdı", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Ink.copy(alpha = 0.7f))
        }
        if (first.validation == "uncertain" && first.votesAccept != null) {
            Text("Oylama: ${first.votesAccept} kabul · ${first.votesReject ?: 0} red", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Ink.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            group.answers.forEach { a ->
                val p = state.player(a.userId)
                val pts by animateIntAsState(if (appear.value > 0.5f) a.points else 0, tween(600), label = "pts")
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.7f)).padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarView(p?.avatar, size = 30.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(p?.username ?: "?", style = MaterialTheme.typography.labelMedium, color = LoopaPalette.Ink, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text("+$pts", style = MaterialTheme.typography.labelLarge, color = if (a.points > 0) darken(LoopaPalette.Success, 0.7f) else LoopaPalette.InkSoft)
                    if (a.doubled) { Spacer(Modifier.width(4.dp)); Pill("2X", LoopaPalette.Butter) }
                }
            }
        }
    }
}

private data class Status(val label: String, val bg: Color, val border: Color)

private fun statusOf(a: Answer): Status = when {
    a.finalStatus == "empty" || a.text.isBlank() -> Status("Boş", Color(0xFFEDE9F2), Color(0xFFD9D3E3))
    a.accepted && a.isDuplicate -> Status("Aynı · 10", LoopaPalette.Sky.copy(alpha = 0.8f), LoopaPalette.SkyDeep)
    a.accepted && a.validation == "uncertain" -> Status("Oyla kabul", LoopaPalette.Mint.copy(alpha = 0.8f), LoopaPalette.MintDeep)
    a.accepted -> Status("Benzersiz · 20", LoopaPalette.Mint.copy(alpha = 0.9f), LoopaPalette.MintDeep)
    a.validation == "uncertain" -> Status("Oyla red", LoopaPalette.Peach.copy(alpha = 0.8f), LoopaPalette.PeachDeep)
    else -> Status("Geçersiz", LoopaPalette.Error.copy(alpha = 0.45f), LoopaPalette.Error)
}

// ----------------------------------------------------------------------------- Round results
@Composable
fun RoundResultsView(state: RoomState, round: Round, nowMs: Long, onSkip: () -> Unit) {
    val feedback = LocalFeedback.current
    val ranked = round.players.sortedByDescending { it.points ?: 0 }
    val winners = round.players.filter { it.isRoundWinner }.map { it.userId }.toSet()
    val iWon = state.me.userId in winners
    val newJoker = state.match?.myJokers?.firstOrNull { it.sourceRoundId == round.id }
    val isLast = (state.match?.currentRound ?: 0) >= (state.match?.totalRounds ?: 0)
    val next = ((Timestamps.parse(round.resultsDeadline) ?: nowMs) - nowMs).coerceAtLeast(0)
    LaunchedEffect(round.id) {
        if (iWon) { feedback.play(Sfx.WIN); feedback.haptic(Haptic.SUCCESS) } else feedback.play(Sfx.SCORE)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tur ${round.number} sonuçları", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)
                Spacer(Modifier.height(6.dp))
                val names = winners.mapNotNull { state.player(it)?.username }
                AnimatedVisibility(true, enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn()) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(LoopaPalette.Butter).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.EmojiEvents, null, tint = LoopaPalette.Ink)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (names.isEmpty()) "Bu turda puan alan olmadı" else "Tur galibi: ${names.joinToString(", ")}",
                            style = MaterialTheme.typography.titleSmall, color = LoopaPalette.Ink,
                        )
                    }
                }
                if (iWon) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            newJoker != null -> "+30 XP ve yeni joker: ${newJoker.type.displayName}!"
                            else -> "+30 XP! (Joker envanterin dolu olduğu için yeni joker verilmedi.)"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = darken(LoopaPalette.Success, 0.8f), textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(ranked, key = { it.userId }) { rp ->
            val p = state.player(rp.userId)
            val total = state.match?.scoreboard?.firstOrNull { it.userId == rp.userId }?.totalPoints ?: 0
            val pts by animateIntAsState(rp.points ?: 0, tween(900), label = "round-pts")
            val isWinner = rp.userId in winners
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(if (isWinner) LoopaPalette.Butter.copy(alpha = 0.6f) else Loopa.colors.card)
                    .border(2.dp, if (isWinner) LoopaPalette.ButterDeep else Loopa.colors.outline, RoundedCornerShape(22.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(p?.avatar, size = 46.dp, modifier = Modifier.scale(if (isWinner) 1.08f else 1f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.username ?: "?", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (rp.earlyBonus > 0) Pill("⚡ +${rp.earlyBonus}", LoopaPalette.Butter)
                        rp.doubleCategory?.let { k -> Pill("2X ${round.categories.firstOrNull { it.key == k }?.name ?: ""}", LoopaPalette.Lavender) }
                        Text("Toplam $total", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                    }
                }
                Text("+$pts", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = Loopa.colors.textSoft, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isLast) "Maç sonuçları ${(next + 999) / 1000} sn içinde…" else "Sonraki tur ${(next + 999) / 1000} sn içinde…",
                        style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft,
                    )
                }
                if (state.isHost) {
                    Spacer(Modifier.height(10.dp))
                    LoopaButton(if (isLast) "Sonuçlara geç" else "Sonraki tura geç", onSkip, icon = Icons.Rounded.SkipNext, color = LoopaPalette.Sky, compact = true)
                }
            }
        }
    }
}

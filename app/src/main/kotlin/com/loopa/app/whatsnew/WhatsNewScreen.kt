package com.loopa.app.whatsnew

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.release.Accent
import com.loopa.core.release.FeatureArt
import com.loopa.core.release.ReleaseNotes
import com.loopa.core.release.WhatsNewFeature
import com.loopa.core.release.WhatsNewHero
import com.loopa.core.ui.components.IconAction
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaMotion
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Motion
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

fun Accent.color(): Color = when (this) {
    Accent.LAVENDER -> LoopaPalette.Lavender
    Accent.MINT -> LoopaPalette.Mint
    Accent.PEACH -> LoopaPalette.Peach
    Accent.BUTTER -> LoopaPalette.Butter
    Accent.SKY -> LoopaPalette.Sky
}

private fun FeatureArt.icon(): ImageVector = when (this) {
    FeatureArt.FRIENDS -> Icons.Rounded.People
    FeatureArt.SEARCH -> Icons.Rounded.PersonSearch
    FeatureArt.INVITE -> Icons.Rounded.Mail
    FeatureArt.CHAT -> Icons.AutoMirrored.Rounded.Chat
    FeatureArt.BELL -> Icons.Rounded.Notifications
    FeatureArt.PRIVACY -> Icons.Rounded.Shield
    FeatureArt.GAMES -> Icons.Rounded.SportsEsports
    FeatureArt.SPARK -> Icons.Rounded.AutoAwesome
}

/**
 * Release notes as a short, paged story: an intro page with the version and a hero illustration,
 * then one card per feature. Works for any [ReleaseNotes]; nothing here is version specific.
 */
@Composable
fun WhatsNewScreen(notes: ReleaseNotes, onClose: () -> Unit) {
    val pages = 1 + notes.features.size
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    val reduced = LoopaMotion.reduced
    val last = pager.currentPage == pages - 1

    Box(Modifier.fillMaxSize().background(Loopa.colors.background).testTag("whats_new")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight && maxHeight < 520.dp     // phone landscape
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 620.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("YENİLİKLER", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
                    IconAction(Icons.Rounded.Close, "Kapat", onClose, Modifier.testTag("whats_new_close"))
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("whats_new_pager"), beyondViewportPageCount = 1) { page ->
                    val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            if (!reduced) {
                                val s = 1f - 0.08f * offset
                                scaleX = s; scaleY = s
                                alpha = 1f - 0.5f * offset
                            }
                        },
                    ) {
                        if (page == 0) IntroPage(notes, wide, pager.currentPage == 0)
                        else FeaturePage(notes.features[page - 1], page, notes.features.size, wide, pager.currentPage == page)
                    }
                }
                PageDots(pager, pages, Modifier.align(Alignment.CenterHorizontally).padding(vertical = if (wide) Spacing.xs else Spacing.md))
                CtaButton(
                    text = if (last) notes.ctaText else "İleri",
                    highlight = last,
                    onClick = { if (last) onClose() else scope.launch { if (reduced) pager.scrollToPage(pager.currentPage + 1) else pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(bottom = if (wide) Spacing.sm else Spacing.lg).testTag("whats_new_cta"),
                )
            }
        }
    }
}

/** A gentle back-and-forth value; a constant [rest] when the system asks for reduced motion. */
@Composable
private fun loop(ms: Int, from: Float, to: Float, label: String, rest: Float = 0f): Float {
    if (LoopaMotion.reduced) return rest
    val t = rememberInfiniteTransition(label = label)
    val v by t.animateFloat(from, to, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = label)
    return v
}

/** Fades and lifts children in one after another; instant with reduced motion. */
@Composable
private fun rememberEntrance(active: Boolean, index: Int): Pair<Float, Float> {
    val reduced = LoopaMotion.reduced
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (reduced) { a.snapTo(1f); return@LaunchedEffect }
        if (a.value < 1f) { delay(90L * index); a.animateTo(1f, tween(Motion.SLOW, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    }
    return a.value to (1f - a.value) * 24f
}

private fun Modifier.entrance(v: Pair<Float, Float>) = graphicsLayer { alpha = v.first; translationY = v.second * density }

@Composable
private fun IntroPage(notes: ReleaseNotes, wide: Boolean, active: Boolean) {
    val e0 = rememberEntrance(active, 0); val e1 = rememberEntrance(active, 1)
    val e2 = rememberEntrance(active, 2); val e3 = rememberEntrance(active, 3)
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m, horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
            Text("SÜRÜM", style = MaterialTheme.typography.labelLarge, color = Loopa.colors.textSoft, modifier = Modifier.entrance(e1))
            Text(notes.versionName, modifier = Modifier.entrance(e1).semantics { contentDescription = "Sürüm ${notes.versionName}" },
                style = MaterialTheme.typography.displayLarge.copy(fontSize = if (wide) 44.sp else 64.sp, fontWeight = FontWeight.Bold), color = Loopa.colors.accentText)
            Spacer(Modifier.height(Spacing.sm))
            Text(notes.title, style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, color = Loopa.colors.text,
                textAlign = if (wide) TextAlign.Start else TextAlign.Center, modifier = Modifier.entrance(e2).semantics { heading() }.testTag("whats_new_title"))
            Spacer(Modifier.height(Spacing.sm))
            Text(notes.subtitle, style = MaterialTheme.typography.bodyLarge, color = Loopa.colors.textSoft,
                textAlign = if (wide) TextAlign.Start else TextAlign.Center, modifier = Modifier.entrance(e3))
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = Spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
            HeroArt(notes.hero, Modifier.weight(1f).entrance(e0))
            Spacer(Modifier.width(Spacing.xl))
            text(Modifier.weight(1f).verticalScroll(rememberScrollState()))
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            HeroArt(notes.hero, Modifier.fillMaxWidth(0.85f).widthIn(max = 360.dp).entrance(e0))
            Spacer(Modifier.height(Spacing.xl))
            text(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FeaturePage(f: WhatsNewFeature, index: Int, count: Int, wide: Boolean, active: Boolean) {
    val e0 = rememberEntrance(active, 0); val e1 = rememberEntrance(active, 1); val e2 = rememberEntrance(active, 2)
    val accent = f.accent.color()
    val card: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().clip(Radii.xl).background(accent.copy(alpha = 0.32f)).padding(Spacing.xl).testTag("whats_new_feature_$index"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FeatureArtwork(f.art, accent, Modifier.size(if (wide) 140.dp else 180.dp).entrance(e0))
            Spacer(Modifier.height(Spacing.lg))
            Text("$index / $count", style = MaterialTheme.typography.labelMedium, color = LoopaPalette.Ink.copy(alpha = 0.6f), modifier = Modifier.entrance(e1))
            Text(f.title, style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink, textAlign = TextAlign.Center,
                modifier = Modifier.entrance(e1).semantics { heading() })
            Spacer(Modifier.height(Spacing.sm))
            Text(f.description, style = MaterialTheme.typography.bodyLarge, color = LoopaPalette.Ink.copy(alpha = 0.8f), textAlign = TextAlign.Center,
                modifier = Modifier.entrance(e2))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
        verticalArrangement = Arrangement.Center) { card() }
}

/** Big pastel blob with the feature's icon and a few geometric accents. */
@Composable
private fun FeatureArtwork(art: FeatureArt, accent: Color, modifier: Modifier) {
    val bob = loop(2400, 0f, 1f, "bob")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Color.White.copy(alpha = 0.85f), radius = r * 0.86f)
            drawCircle(accent, radius = r * 0.7f)
            drawCircle(LoopaPalette.Ink, radius = r * 0.7f, style = Stroke(3.dp.toPx()))
            drawCircle(LoopaPalette.Butter, radius = r * 0.12f, center = Offset(size.width * 0.84f, size.height * (0.2f + 0.04f * bob)))
            drawRoundRect(LoopaPalette.Peach, topLeft = Offset(size.width * 0.06f, size.height * (0.72f - 0.04f * bob)),
                size = Size(r * 0.3f, r * 0.3f), cornerRadius = CornerRadius(r * 0.08f))
            drawCircle(LoopaPalette.Sky, radius = r * 0.07f, center = Offset(size.width * 0.12f, size.height * 0.2f))
        }
        Icon(art.icon(), null, tint = LoopaPalette.Ink, modifier = Modifier.fillMaxSize(0.38f))
    }
}

/** Hero illustration of the intro page, drawn from the palette (no bitmaps). */
@Composable
fun HeroArt(hero: WhatsNewHero, modifier: Modifier = Modifier) {
    val wobble = loop(3000, -1f, 1f, "wobble")
    Canvas(modifier.aspectRatio(1.25f).semantics { contentDescription = "Yenilikler illüstrasyonu" }) {
        when (hero) {
            WhatsNewHero.SOCIAL -> drawSocialHero(wobble)
            WhatsNewHero.GAMES, WhatsNewHero.GENERIC -> drawGenericHero(wobble)
        }
    }
}

private fun DrawScope.outline() = Stroke(3.dp.toPx())

private fun DrawScope.drawSocialHero(w: Float) {
    val W = size.width; val H = size.height; val ink = LoopaPalette.Ink
    drawCircle(LoopaPalette.Mint.copy(alpha = 0.55f), radius = H * 0.46f, center = Offset(W * 0.5f, H * 0.54f))
    // three friends
    listOf(Triple(0.28f, LoopaPalette.Lavender, 0.0f), Triple(0.5f, LoopaPalette.Peach, -0.05f), Triple(0.72f, LoopaPalette.Sky, 0.0f)).forEach { (x, c, dy) ->
        val top = H * (0.6f + dy)
        drawRoundRect(c, Offset(W * x - W * 0.09f, top), Size(W * 0.18f, H * 0.32f), CornerRadius(W * 0.07f))
        drawRoundRect(ink, Offset(W * x - W * 0.09f, top), Size(W * 0.18f, H * 0.32f), CornerRadius(W * 0.07f), style = outline())
        drawCircle(Color.White, H * 0.09f, Offset(W * x, top - H * 0.07f))
        drawCircle(ink, H * 0.09f, Offset(W * x, top - H * 0.07f), style = outline())
    }
    // speech bubbles
    fun bubble(x: Float, y: Float, bw: Float, c: Color) {
        drawRoundRect(c, Offset(W * x, H * y), Size(W * bw, H * 0.15f), CornerRadius(H * 0.07f))
        drawRoundRect(ink, Offset(W * x, H * y), Size(W * bw, H * 0.15f), CornerRadius(H * 0.07f), style = outline())
    }
    bubble(0.06f, 0.12f + 0.012f * w, 0.26f, LoopaPalette.Butter)
    bubble(0.64f, 0.06f - 0.012f * w, 0.3f, Color.White)
    for (i in 0..2) drawCircle(ink, 3.5.dp.toPx(), Offset(W * (0.13f + i * 0.06f), H * (0.195f + 0.012f * w)))
    drawLine(ink, Offset(W * 0.69f, H * (0.13f - 0.012f * w)), Offset(W * 0.88f, H * (0.13f - 0.012f * w)), 3.dp.toPx())
    // heart and sparkles
    drawCircle(LoopaPalette.PeachDeep, H * 0.035f, Offset(W * 0.47f, H * 0.2f))
    drawCircle(LoopaPalette.PeachDeep, H * 0.035f, Offset(W * 0.53f, H * 0.2f))
    drawLine(LoopaPalette.ButterDeep, Offset(W * 0.9f, H * 0.42f), Offset(W * 0.96f, H * 0.42f), 4.dp.toPx())
    drawLine(LoopaPalette.ButterDeep, Offset(W * 0.93f, H * 0.39f), Offset(W * 0.93f, H * 0.45f), 4.dp.toPx())
}

private fun DrawScope.drawGenericHero(w: Float) {
    val W = size.width; val H = size.height
    drawCircle(LoopaPalette.Lavender.copy(alpha = 0.55f), H * 0.46f, Offset(W * 0.5f, H * 0.52f))
    drawRoundRect(LoopaPalette.Butter, Offset(W * 0.3f, H * (0.3f + 0.02f * w)), Size(W * 0.4f, H * 0.4f), CornerRadius(H * 0.08f))
    drawRoundRect(LoopaPalette.Ink, Offset(W * 0.3f, H * (0.3f + 0.02f * w)), Size(W * 0.4f, H * 0.4f), CornerRadius(H * 0.08f), style = outline())
    drawCircle(LoopaPalette.Mint, H * 0.08f, Offset(W * 0.2f, H * 0.25f))
    drawCircle(LoopaPalette.Peach, H * 0.06f, Offset(W * 0.82f, H * 0.75f))
}

@Composable
private fun PageDots(pager: PagerState, count: Int, modifier: Modifier) {
    Row(modifier.semantics { contentDescription = "Sayfa ${pager.currentPage + 1} / $count" }.testTag("whats_new_dots"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val sel = i == pager.currentPage
            val w by animateDpAsState(if (sel) 24.dp else 8.dp, Motion.standard(LoopaMotion.reduced), label = "dot")
            Box(Modifier.height(8.dp).width(w).clip(Radii.pill).background(if (sel) LoopaPalette.LavenderDeep else Loopa.colors.outline))
        }
    }
}

/** The main button; on the last page it breathes gently to invite the tap (not with reduced motion). */
@Composable
private fun CtaButton(text: String, highlight: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scale = if (highlight) loop(900, 1f, 1.03f, "pulse", rest = 1f) else 1f
    LoopaButton(text, onClick, color = if (highlight) LoopaPalette.Mint else LoopaPalette.Lavender, modifier = modifier.scale(scale))
}

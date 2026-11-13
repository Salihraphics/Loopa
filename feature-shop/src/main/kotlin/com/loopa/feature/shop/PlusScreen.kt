package com.loopa.feature.shop

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.Remote
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.PlusFormat
import com.loopa.core.model.Membership
import com.loopa.core.model.PlanTier
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.PlanBadge
import com.loopa.core.ui.components.PlusGradient
import com.loopa.core.ui.components.SectionTitle
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlusViewModel(private val c: DataContainer) : ViewModel() {
    val membership: StateFlow<Remote<Membership>> = c.membership.state
    fun refresh() = viewModelScope.launch { c.membership.refresh() }
}

/** A Plus benefit. None of them is live yet, so every one carries "Yakında". */
data class PlusBenefit(val icon: ImageVector, val title: String, val body: String, val color: Color)

/** Tournaments are deliberately not listed: they are not built yet. */
val plusBenefits = listOf(
    PlusBenefit(Icons.Rounded.SportsEsports, "10 oyunun tamamı", "Haftalık rotasyonu beklemeden her oyun için oda kur. Oyunlar yayınlandıkça açılır.", LoopaPalette.Mint),
    PlusBenefit(Icons.Rounded.LibraryBooks, "Özel içerik paketleri", "Kendi kelime, soru ve kategori paketlerini oluştur ve kaydet.", LoopaPalette.Butter),
    PlusBenefit(Icons.Rounded.Insights, "Gelişmiş istatistikler", "Oyun bazında performansın, en iyi kategorilerin ve gelişimin.", LoopaPalette.Sky),
    PlusBenefit(Icons.Rounded.Palette, "Özel oda temaları", "Odana renk ve tema seç; arkadaşların da görsün.", LoopaPalette.Peach),
    PlusBenefit(Icons.Rounded.Checkroom, "Ek avatar kozmetikleri", "Yalnızca Plus üyelerine özel avatar parçaları.", LoopaPalette.Lavender),
    PlusBenefit(Icons.Rounded.RocketLaunch, "Yeni oyunlara erken erişim", "Yeni oyunları herkesten önce dene.", LoopaPalette.Mint),
)

@Composable
fun PlusScreen(onBack: () -> Unit, onOpenPromo: () -> Unit) {
    val vm = containerViewModel { PlusViewModel(it) }
    val state by vm.membership.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    PlusContent(state, onBack, onOpenPromo)
}

@Composable
fun PlusContent(state: Remote<Membership>, onBack: () -> Unit, onOpenPromo: () -> Unit) {
    val m = state.data
    Column(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        LoopaTopBar("LOOPA Plus", onBack = onBack)
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Hero
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(PlusGradient).padding(22.dp).testTag("plus_hero"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = LoopaPalette.Ink, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("LOOPA Plus", style = MaterialTheme.typography.displaySmall, color = LoopaPalette.Ink)
                    Text("Daha çok oyun, daha çok kahkaha.", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink.copy(alpha = 0.8f))
                    Spacer(Modifier.height(12.dp))
                    PlanBadge(m?.tier)
                    Spacer(Modifier.height(6.dp))
                    Text(statusLine(state), style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center, modifier = Modifier.testTag("plus_status"))
                }

                SectionTitle("Plus avantajları")
                Text("Avantajlar, ilgili oyun ve özellikler yayınlandıkça açılır.",
                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                plusBenefits.forEach { BenefitRow(it) }

                LoopaCard(color = LoopaPalette.Sky.copy(alpha = 0.45f)) {
                    InfoLine(Icons.Rounded.Groups, "Arkadaşların da oynar",
                        "Plus üyesi bir oda sahibi kilitli bir oyunu açtığında, ücretsiz hesaplar da katılıp oynayabilir.")
                    Spacer(Modifier.height(10.dp))
                    InfoLine(Icons.Rounded.VerifiedUser, "Adil oyun",
                        "Plus oyunda avantaj sağlamaz: puanlama, süreler ve jokerler herkes için aynıdır.")
                }

                // No payment exists yet: the button is disabled and says so.
                LoopaButton("Satın alma yakında", {}, enabled = false, color = LoopaPalette.Lavender,
                    modifier = Modifier.fillMaxWidth().testTag("plus_buy"))
                Text("Uygulama içi satın alma henüz açılmadı; şu an hiçbir ödeme alınmaz.",
                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                LoopaButton("Promosyon kodum var", onOpenPromo, icon = Icons.Rounded.CardGiftcard, color = LoopaPalette.Mint,
                    modifier = Modifier.fillMaxWidth().testTag("plus_promo"))
            }
        }
    }
}

private fun statusLine(state: Remote<Membership>): String {
    val m = state.data ?: return when {
        state.unsupported -> "LOOPA Plus bu sunucuda henüz etkin değil."
        state.error != null -> "Üyelik bilgisi alınamadı. Bağlantını kontrol et."
        else -> "Üyelik bilgisi yükleniyor…"
    }
    return when (m.tier) {
        PlanTier.ADMIN -> "Admin hesabısın: bütün oyunlara erişimin var."
        PlanTier.PLUS -> if (m.plusUnlimited) "Plus üyeliğin aktif (süresiz)."
            else "Plus üyeliğin aktif." + (PlusFormat.dateTime(m.plusUntil)?.let { " Bitiş: $it" } ?: "")
        PlanTier.FREE -> "Şu an ücretsiz plandasın. İsim Şehir her zaman ücretsiz."
    }
}

@Composable
private fun BenefitRow(b: PlusBenefit) {
    LoopaCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(b.color), contentAlignment = Alignment.Center) {
                Icon(b.icon, null, tint = LoopaPalette.Ink)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.title, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Pill("Yakında", Loopa.colors.cardHigh, textColor = Loopa.colors.textSoft)
                }
                Text(b.body, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = LoopaPalette.Ink)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = LoopaPalette.Ink)
            Text(body, style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.75f))
        }
    }
}

package com.loopa.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.Remote
import com.loopa.core.game.LoopaException
import com.loopa.core.game.PlusFormat
import com.loopa.core.model.Membership
import com.loopa.core.model.PlanTier
import com.loopa.core.model.PromoRedemption
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.PlanBadge
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PromoUiState(
    val code: String = "",
    val busy: Boolean = false,
    val success: String? = null,
    val error: String? = null,
)

/** Builds the confirmation text from the server's answer only (no client-side date maths). */
fun promoSuccessText(r: PromoRedemption): String {
    val until = PlusFormat.dateTime(r.plusUntil)
    return if (r.isNew) {
        val days = r.days?.let { "$it günlük" } ?: "Süreli"
        "${r.code} kodu kullanıldı: $days LOOPA Plus hesabına tanımlandı." + (until?.let { " Plus bitiş: $it." } ?: "")
    } else {
        "${r.code} kodunu bu hesapta daha önce kullandın; ikinci kez tanımlanmaz." + (until?.let { " Plus bitiş: $it." } ?: "")
    }
}

class MembershipViewModel(private val c: DataContainer) : ViewModel() {
    val membership: StateFlow<Remote<Membership>> = c.membership.state
    private val _promo = MutableStateFlow(PromoUiState())
    val promo: StateFlow<PromoUiState> = _promo.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch { c.membership.refresh() }

    fun setCode(v: String) = _promo.update { it.copy(code = v.take(24), error = null, success = null) }
    fun dismiss() = _promo.update { it.copy(error = null, success = null) }

    fun redeem() {
        val code = _promo.value.code
        if (_promo.value.busy) return
        if (code.isBlank()) { _promo.update { it.copy(error = "Bir promosyon kodu yaz.") }; return }
        _promo.update { it.copy(busy = true, error = null, success = null) }
        viewModelScope.launch {
            try {
                val r = c.membership.redeem(code)
                runCatching { c.catalog.refresh() }   // a Plus host may now open more games
                _promo.update { it.copy(busy = false, code = "", success = promoSuccessText(r)) }
            } catch (e: LoopaException) {
                _promo.update { it.copy(busy = false, error = e.error.message) }
            }
        }
    }
}

/** Plan summary. Never shows "Ücretsiz" unless the server said so. */
@Composable
fun MembershipCard(state: Remote<Membership>, onRetry: () -> Unit, onOpenPlus: () -> Unit) {
    val m = state.data
    LoopaCard(modifier = Modifier.testTag("membership_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Üyelik", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text, modifier = Modifier.weight(1f))
            PlanBadge(m?.tier)
        }
        Spacer(Modifier.height(8.dp))
        when {
            m != null -> {
                Text(membershipText(m), style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
                if (state.error != null) {
                    Text("Son bilinen durum gösteriliyor; güncellenemedi (${state.error?.message})",
                        style = MaterialTheme.typography.bodySmall, color = LoopaPalette.PeachDeep, modifier = Modifier.padding(top = 6.dp))
                }
            }
            state.loading || state.error == null ->
                Text("Üyelik bilgisi yükleniyor…", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            state.unsupported ->
                Text("LOOPA Plus bu sunucuda henüz etkin değil. İsim Şehir her zamanki gibi ücretsiz oynanır.",
                    style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            else ->
                Text("Üyelik bilgisi alınamadı: ${state.error?.message}", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.PeachDeep)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoopaButton(if (m?.hasPlusAccess == true) "Plus avantajları" else "LOOPA Plus'ı keşfet", onOpenPlus,
                icon = Icons.Rounded.AutoAwesome, color = LoopaPalette.Lavender, compact = true, modifier = Modifier.testTag("open_plus"))
            if (state.error != null) LoopaButton("Yenile", onRetry, icon = Icons.Rounded.Refresh, color = androidx.compose.ui.graphics.Color.White,
                compact = true, loading = state.loading)
        }
    }
}

fun membershipText(m: Membership): String = when (m.tier) {
    PlanTier.ADMIN -> "Admin hesabı: bütün oyunlara erişim."
    PlanTier.PLUS -> if (m.plusUnlimited) "LOOPA Plus aktif (süresiz)."
        else "LOOPA Plus aktif." + (PlusFormat.dateTime(m.plusUntil)?.let { " Bitiş: $it." } ?: "")
    PlanTier.FREE -> "Ücretsiz plandasın. İsim Şehir her zaman ücretsiz; diğer oyunlar yayınlandıkça haftalık rotasyonla açılır."
}

@Composable
fun PromoCodeCard(membership: Remote<Membership>, ui: PromoUiState, onCode: (String) -> Unit, onRedeem: () -> Unit, onDismiss: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val m = membership.data
    val unsupported = m == null && membership.unsupported
    val needsGoogle = m != null && !m.googleLinked
    LoopaCard(modifier = Modifier.testTag("promo_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CardGiftcard, null, tint = Loopa.colors.text)
            Spacer(Modifier.width(10.dp))
            Text("Promosyon kodu", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "LOPPAWEEK kodu 7 günlük LOOPA Plus verir. Yalnızca Google hesabına bağlı hesaplarda ve hesap başına bir kez kullanılabilir; " +
                "kod sunucuda doğrulanır. Mevcut Plus süren varsa yeni süre onun sonuna eklenir.",
            style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
        )
        if (needsGoogle) {
            Text("Önce yukarıdaki Hesap bölümünden Google hesabını bağlamalısın.",
                style = MaterialTheme.typography.bodySmall, color = LoopaPalette.PeachDeep, modifier = Modifier.padding(top = 6.dp).testTag("promo_needs_google"))
        }
        if (unsupported) {
            Text("Promosyon kodları bu sunucuda henüz etkin değil.", style = MaterialTheme.typography.bodySmall,
                color = LoopaPalette.PeachDeep, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        val enabled = !unsupported && !needsGoogle && !ui.busy
        LoopaTextField(
            value = ui.code, onValueChange = onCode, placeholder = "KODU YAZ",
            modifier = Modifier.fillMaxWidth().testTag("promo_field"), enabled = !unsupported && !needsGoogle,
            accent = LoopaPalette.Lavender, isError = ui.error != null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus(); if (enabled) onRedeem() }),
        )
        Spacer(Modifier.height(10.dp))
        LoopaButton("Kodu kullan", { keyboard?.hide(); focus.clearFocus(); onRedeem() }, enabled = enabled && ui.code.isNotBlank(),
            loading = ui.busy, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("promo_redeem"), sfx = Sfx.TAP)
        ErrorBanner(ui.error, Modifier.padding(top = 10.dp).testTag("promo_error"), onDismiss = onDismiss)
        if (ui.success != null) {
            Row(Modifier.padding(top = 10.dp).testTag("promo_success"), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.CheckCircle, null, tint = LoopaPalette.MintDeep)
                Spacer(Modifier.width(8.dp))
                Text(ui.success, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text)
            }
        }
    }
}

/** Membership + promo, used in Settings. */
@Composable
fun MembershipSettings(onOpenPlus: () -> Unit, vm: MembershipViewModel) {
    val membership by vm.membership.collectAsStateWithLifecycle()
    val promo by vm.promo.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MembershipCard(membership, vm::refresh, onOpenPlus)
        PromoCodeCard(membership, promo, vm::setCode, vm::redeem, vm::dismiss)
    }
}

package com.loopa.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.loopa.core.data.Remote
import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaError
import com.loopa.core.model.CatalogMode
import com.loopa.core.model.GameCatalog
import com.loopa.core.model.Membership
import com.loopa.core.model.ModeAccess
import com.loopa.core.model.PromoRedemption
import com.loopa.core.model.Rotation
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.lobby.GamesContent
import com.loopa.feature.settings.MembershipCard
import com.loopa.feature.settings.PromoCodeCard
import com.loopa.feature.settings.PromoUiState
import com.loopa.feature.settings.promoSuccessText
import com.loopa.feature.shop.PlusContent
import com.loopa.feature.shop.plusBenefits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Free / Plus / Admin / older-server states of the game list, the Plus screen and the
 * membership + promo cards. Inputs are server answers (as the repositories hold them);
 * each test checks what is shown and what is allowed, and writes a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class PlusUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { LoopaTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { content() } } }
        compose.mainClock.advanceTimeBy(1_000)
        // Scrolling a lazy list needs frames; none of these screens has an endless animation.
        compose.mainClock.autoAdvance = true
    }

    private fun snap(name: String) {
        compose.mainClock.advanceTimeBy(600)
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- server answers
    private val free = Membership(plan = "free", googleLinked = false)
    private val freeLinked = Membership(plan = "free", googleLinked = true)
    private val plus = Membership(plan = "plus", googleLinked = true, plusUntil = "2026-11-20T10:00:00+00:00")
    private val admin = Membership(plan = "admin", isAdmin = true, googleLinked = true)

    /** What get_game_catalog returns today: only İsim Şehir is available, the rest is coming soon. */
    private fun catalog(plan: String, rotation: Rotation = Rotation()) = GameCatalog(
        plan = plan, rotation = rotation,
        modes = GameModes.all.map { info ->
            val isim = info.id == GameModes.ISIM_SEHIR
            CatalogMode(
                id = info.id, name = info.name, status = if (isim) "available" else "coming_soon", alwaysFree = isim,
                access = when {
                    isim -> ModeAccess(allowed = true, unlockedBy = "always_free", plan = plan)
                    plan == "admin" -> ModeAccess(allowed = true, unlockedBy = "admin", plan = plan)
                    else -> ModeAccess(allowed = false, reason = "MODE_NOT_AVAILABLE", plan = plan)
                },
            )
        },
    )
    private val activeRotation = Rotation(active = true, weekSlot = 1, weekStartsAt = "2026-11-08T21:00:00+00:00",
        weekEndsAt = "2026-11-15T21:00:00+00:00", freeModes = listOf("ciz_tahmin"), nextWeekSlot = 2,
        nextFreeModes = listOf("en_muhtemel_kim", "gizli_kelime"))

    @Composable
    private fun games(catalog: Remote<GameCatalog>, membership: Remote<Membership>, onPlus: () -> Unit = {}) =
        GamesContent(catalog, membership, null, null, {}, {}, {}, onPlus, {})

    private fun scrollTo(tag: String) = compose.onNodeWithTag("games_list").performScrollToNode(hasTestTag(tag))

    /** [badge]: "Yakında" when the server says coming soon, "Bilgi yok" when there is no catalogue. */
    private fun assertOnlyIsimSehirStartable(badge: String = "Yakında") {
        scrollTo("game_card_isim_sehir"); compose.onNodeWithTag("game_create_isim_sehir").assertIsDisplayed()
        GameModes.all.drop(1).forEach { info ->
            scrollTo("game_card_${info.id}")
            assertEquals("no create button for ${info.id}", 0, compose.onAllNodesWithTag("game_create_${info.id}").fetchSemanticsNodes().size)
            compose.onNodeWithTag("game_status_${info.id}").assertIsDisplayed()
            compose.onNodeWithTag("game_card_${info.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText(badge)))
        }
    }

    // ---------------------------------------------------------------- game list
    @Test fun freeUserRotationNotStarted() {
        render { games(Remote(catalog("free")), Remote(free)) }
        snap("20_games_free")
        compose.onNodeWithTag("rotation_inactive").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("rotation_active").fetchSemanticsNodes().size)
        assertOnlyIsimSehirStartable()
        // Not started => no weekly claims anywhere.
        assertEquals(0, compose.onAllNodesWithText("Bu hafta ücretsiz").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Gelecek hafta ücretsiz").fetchSemanticsNodes().size)
    }

    @Test fun allTenGamesAreListed() {
        render { games(Remote(catalog("free")), Remote(free)) }
        GameModes.all.forEach { info ->
            scrollTo("game_card_${info.id}")
            assertTrue(info.name, compose.onAllNodesWithText(info.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun activeRotationPreview() {
        render { games(Remote(catalog("free", activeRotation)), Remote(free)) }
        snap("21_games_rotation")
        compose.onNodeWithTag("rotation_active").assertIsDisplayed()
        compose.onNodeWithText("Bu hafta").assertIsDisplayed()
        compose.onNodeWithText("Gelecek hafta").assertIsDisplayed()
        scrollTo("game_card_ciz_tahmin")
        compose.onNodeWithText("Bu hafta ücretsiz").assertIsDisplayed()
        // Still coming soon: free this week does not make an unfinished game startable.
        assertEquals(0, compose.onAllNodesWithTag("game_create_ciz_tahmin").fetchSemanticsNodes().size)
        assertOnlyIsimSehirStartable()
    }

    @Test fun plusUserSeesAllAccessButUnfinishedGamesStayComingSoon() {
        render { games(Remote(catalog("plus")), Remote(plus)) }
        snap("22_games_plus")
        compose.onNodeWithText("Tüm oyunlara erişimin var.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("games_plus_link").fetchSemanticsNodes().size)
        assertOnlyIsimSehirStartable()
    }

    @Test fun adminSeesAdminBadgeAndNothingUnfinishedStarts() {
        render { games(Remote(catalog("admin")), Remote(admin)) }
        compose.onNodeWithText("Admin").assertIsDisplayed()
        assertOnlyIsimSehirStartable()
    }

    @Test fun olderServerWithoutPlus() {
        // Hosted project before the stage-2 migrations: both RPCs answer PGRST202.
        render { games(Remote(error = LoopaError.SERVER_OUTDATED), Remote(error = LoopaError.SERVER_OUTDATED)) }
        snap("23_games_older_server")
        compose.onNodeWithTag("rotation_unsupported").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Ücretsiz").fetchSemanticsNodes().size)   // plan unknown: no "Ücretsiz" badge
        assertOnlyIsimSehirStartable("Bilgi yok")
    }

    @Test fun offlineCatalogue() {
        render { games(Remote(error = LoopaError.NETWORK), Remote(error = LoopaError.NETWORK)) }
        compose.onNodeWithTag("rotation_error").assertIsDisplayed()
        compose.onNodeWithText("Üyelik bilgisi alınamadı.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Üyelik bilgisi yükleniyor…").fetchSemanticsNodes().size)
        assertOnlyIsimSehirStartable("Bilgi yok")
    }

    @Test fun lockedCardOffersPlusOnlyToFreeUsers() {
        // gizli_kelime is available on the server but this Free host may not start it this week.
        val c = catalog("free").let { cat -> cat.copy(modes = cat.modes.map {
            if (it.id == "gizli_kelime") it.copy(status = "available", access = ModeAccess(allowed = false, reason = "PLUS_REQUIRED")) else it }) }
        render { games(Remote(c), Remote(free)) }
        scrollTo("game_card_gizli_kelime")
        compose.onNodeWithTag("game_plus_gizli_kelime").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("game_create_gizli_kelime").fetchSemanticsNodes().size)
        compose.onNodeWithText("Bir Plus üyesinin odasına katılarak da oynayabilirsin.", substring = true).assertIsDisplayed()
    }

    /** What the 0.5.0 server returns: every game available, access by plan and rotation. */
    private fun liveCatalog(plan: String, rotation: Rotation = Rotation()) = GameCatalog(plan = plan, rotation = rotation,
        modes = GameModes.all.map { info ->
            val isim = info.id == GameModes.ISIM_SEHIR
            val free = rotation.active && info.id in rotation.freeModes
            CatalogMode(id = info.id, name = info.name, status = "available", alwaysFree = isim, access = when {
                isim -> ModeAccess(allowed = true, unlockedBy = "always_free")
                plan != "free" -> ModeAccess(allowed = true, unlockedBy = plan)
                free -> ModeAccess(allowed = true, unlockedBy = "rotation")
                else -> ModeAccess(allowed = false, reason = "PLUS_REQUIRED")
            })
        })

    @Test fun plusHostCanStartEveryGame() {
        render { games(Remote(liveCatalog("plus")), Remote(plus)) }
        snap("29_games_plus_all")
        GameModes.all.forEach { info ->
            scrollTo("game_card_${info.id}")
            compose.onNodeWithTag("game_create_${info.id}").assertIsDisplayed()
        }
    }

    @Test fun freeHostGetsTheWeeksGamesAndLocksTheRest() {
        render { games(Remote(liveCatalog("free", activeRotation)), Remote(free)) }
        scrollTo("game_card_isim_sehir"); compose.onNodeWithTag("game_create_isim_sehir").assertIsDisplayed()
        scrollTo("game_card_ciz_tahmin"); compose.onNodeWithTag("game_create_ciz_tahmin").assertIsDisplayed()
        scrollTo("game_card_gizli_kelime")
        assertEquals(0, compose.onAllNodesWithTag("game_create_gizli_kelime").fetchSemanticsNodes().size)
        compose.onNodeWithTag("game_plus_gizli_kelime").assertIsDisplayed()
        compose.onNodeWithText("Gelecek hafta herkese ücretsiz!", substring = true).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- Plus screen
    @Test fun plusScreenFree() {
        var promo = 0
        render { PlusContent(Remote(free), onBack = {}, onOpenPromo = { promo++ }) }
        snap("24_plus_free")
        compose.onNodeWithTag("plus_status").assertIsDisplayed()
        compose.onNode(hasTestTag("plus_buy")).assertIsNotEnabled()
        assertEquals(6, plusBenefits.size)
        assertFalse(plusBenefits.any { it.title.contains("Turnuva", ignoreCase = true) || it.body.contains("turnuva", ignoreCase = true) })
        assertEquals(0, compose.onAllNodesWithText("Turnuva", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test fun plusScreenPlusDark() {
        render(dark = true) { PlusContent(Remote(plus), onBack = {}, onOpenPromo = {}) }
        snap("25_plus_active_dark")
        compose.onNodeWithTag("plan_badge", useUnmergedTree = true).assert(hasAnyDescendant(hasText("LOOPA Plus")))
        compose.onNode(hasText("Plus üyeliğin aktif", substring = true)).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- membership + promo
    @Composable
    private fun promoHarness(m: Remote<Membership>, initial: PromoUiState = PromoUiState(), onRedeem: (String) -> PromoUiState = { initial }) {
        var ui by remember { mutableStateOf(initial) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MembershipCard(m, {}, {})
            PromoCodeCard(m, ui, { ui = ui.copy(code = it, error = null) }, { ui = onRedeem(ui.code) }, { ui = ui.copy(error = null) })
        }
    }

    @Test fun guestCannotEnterPromo() {
        render { promoHarness(Remote(free)) }
        snap("26_promo_guest")
        compose.onNodeWithTag("promo_needs_google").assertIsDisplayed()
        compose.onNodeWithTag("promo_field").assertIsNotEnabled()
        compose.onNodeWithTag("promo_redeem").assertIsNotEnabled()
        compose.onNode(hasText("7 günlük LOOPA Plus", substring = true)).assertIsDisplayed()
    }

    @Test fun linkedUserWrongCodeShowsServerReason() {
        render { promoHarness(Remote(freeLinked), onRedeem = { PromoUiState(code = it, error = LoopaError.PROMO_INVALID.message) }) }
        compose.onNodeWithTag("promo_redeem").assertIsNotEnabled()       // empty
        compose.onNodeWithTag("promo_field").performTextInput("LOPAWEEK")
        compose.onNodeWithTag("promo_redeem").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(LoopaError.PROMO_INVALID.message).assertIsDisplayed()
        snap("27_promo_error")
    }

    @Test fun successUsesTheServersDates() {
        val r = PromoRedemption(status = "redeemed", code = "LOPPAWEEK", days = 7,
            startsAt = "2026-11-13T10:00:00+00:00", endsAt = "2026-11-20T10:00:00+00:00", plusUntil = "2026-11-20T10:00:00+00:00")
        val text = promoSuccessText(r)
        assertTrue(text, text.contains("7 günlük LOOPA Plus"))
        assertTrue(text, text.contains("20 Kasım 2026"))
        val again = promoSuccessText(r.copy(status = "already_redeemed", days = null))
        assertTrue(again, again.contains("daha önce kullandın"))
        render { promoHarness(Remote(plus), PromoUiState(success = text)) }
        snap("28_promo_success")
        compose.onNodeWithTag("promo_success").assertIsDisplayed()
        compose.onNodeWithTag("plan_badge").assertIsDisplayed()
    }

    @Test fun unknownPlanIsNeverShownAsFree() {
        render { promoHarness(Remote(loading = true)) }
        compose.onNodeWithText("Üyelik bilgisi yükleniyor…").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Ücretsiz").fetchSemanticsNodes().size)
    }

    @Test fun staleMembershipKeepsLastKnownPlan() {
        render { promoHarness(Remote(data = plus, error = LoopaError.NETWORK)) }
        compose.onNode(hasText("Son bilinen durum", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("LOOPA Plus").assertIsDisplayed()
    }

}

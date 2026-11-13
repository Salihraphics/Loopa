package com.loopa.core.game

import com.loopa.core.model.CatalogMode
import com.loopa.core.model.GameCatalog
import com.loopa.core.model.Membership
import com.loopa.core.model.ModeAccess
import com.loopa.core.model.PlanTier
import com.loopa.core.model.PromoRedemption
import com.loopa.core.model.RoomSettings
import com.loopa.core.model.Rotation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCardsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun mode(id: String, status: String = "available", allowed: Boolean, reason: String? = null, by: String? = null) =
        CatalogMode(id = id, status = status, alwaysFree = id == GameModes.ISIM_SEHIR,
            access = ModeAccess(allowed = allowed, reason = reason, unlockedBy = by))

    private fun catalog(vararg modes: CatalogMode, rotation: Rotation = Rotation()) = GameCatalog(rotation = rotation, modes = modes.toList())
    private fun info(id: String) = GameModes.info(id)

    @Test fun tenGamesInTheAgreedOrder() {
        assertEquals(10, GameModes.all.size)
        assertEquals(
            listOf("İsim Şehir", "Gizli Kelime", "Kelime Zinciri", "Bilgi Arenası", "İki Doğru Bir Yalan", "Çiz ve Tahmin Et",
                "En Muhtemel Kim?", "Anlat Bakalım", "Çizim Telefonu", "Sessiz Sinema"),
            GameModes.all.map { it.name },
        )
        assertEquals(setOf("isim_sehir"), GameModes.implemented)
    }

    @Test fun isimSehirIsAlwaysPlayableEvenWithoutCatalogue() {
        val s = GameCards.status(info("isim_sehir"), null)
        assertEquals(GameCardKind.PLAYABLE, s.kind)
        assertTrue(s.canStart)
        assertEquals("Her zaman ücretsiz", s.badge)
    }

    @Test fun withoutCatalogueOtherGamesAreComingSoonAndClaimNothing() {
        GameModes.all.drop(1).forEach {
            val s = GameCards.status(it, null)
            assertEquals(it.id, GameCardKind.COMING_SOON, s.kind)
            assertFalse(s.canStart)
            assertFalse(s.freeThisWeek); assertFalse(s.freeNextWeek)
        }
    }

    @Test fun comingSoonNeverStartsEvenForPlusOrAdmin() {
        val c = catalog(mode("gizli_kelime", status = "coming_soon", allowed = true, by = "admin"))
        val s = GameCards.status(info("gizli_kelime"), c)
        assertEquals(GameCardKind.COMING_SOON, s.kind)
        assertFalse(s.canStart)
    }

    @Test fun serverAvailableButNotImplementedInThisAppIsComingSoon() {
        // An admin may switch a mode to "available" before this app version can play it.
        val c = catalog(mode("gizli_kelime", allowed = true, by = "plus"))
        val s = GameCards.status(info("gizli_kelime"), c)
        assertEquals(GameCardKind.COMING_SOON, s.kind)
        assertFalse(s.canStart)
    }

    @Test fun inactiveRotationShowsNoWeeklyClaims() {
        val c = catalog(mode("ciz_tahmin", status = "coming_soon", allowed = false, reason = "MODE_NOT_AVAILABLE"),
            rotation = Rotation(active = false, freeModes = listOf("ciz_tahmin"), nextFreeModes = listOf("ciz_tahmin")))
        val s = GameCards.status(info("ciz_tahmin"), c)
        assertFalse(s.freeThisWeek); assertFalse(s.freeNextWeek)
        assertFalse(s.explanation.contains("hafta"))
    }

    @Test fun activeRotationIsPreviewedOnComingSoonCards() {
        val r = Rotation(active = true, weekSlot = 1, freeModes = listOf("ciz_tahmin"), nextWeekSlot = 2,
            nextFreeModes = listOf("gizli_kelime", "en_muhtemel_kim"))
        val c = catalog(mode("ciz_tahmin", status = "coming_soon", allowed = false, reason = "MODE_NOT_AVAILABLE"),
            mode("gizli_kelime", status = "coming_soon", allowed = false, reason = "MODE_NOT_AVAILABLE"), rotation = r)
        assertTrue(GameCards.status(info("ciz_tahmin"), c).freeThisWeek)
        assertTrue(GameCards.status(info("gizli_kelime"), c).freeNextWeek)
        assertFalse(GameCards.status(info("gizli_kelime"), c).freeThisWeek)
    }

    // A future app version that can play gizli_kelime:
    private val future = setOf("isim_sehir", "gizli_kelime")

    @Test fun lockedCardExplainsWhyAndNeverAsksJoinersToBuy() {
        val r = Rotation(active = true, weekSlot = 1, freeModes = listOf("ciz_tahmin"), nextWeekSlot = 2, nextFreeModes = listOf("gizli_kelime"))
        val c = catalog(mode("gizli_kelime", allowed = false, reason = "PLUS_REQUIRED"), rotation = r)
        val s = GameCards.status(info("gizli_kelime"), c, future)
        assertEquals(GameCardKind.LOCKED_PLUS, s.kind)
        assertFalse(s.canStart)
        assertTrue(s.explanation.contains("Bu hafta ücretsiz oyunlar arasında değil"))
        assertTrue(s.explanation.contains("Gelecek hafta herkese ücretsiz"))
        assertTrue(s.explanation.contains("Plus üyesinin odasına katılarak"))
        assertTrue(s.freeNextWeek)
    }

    @Test fun unlockedByRotationPlusOrAdmin() {
        val r = Rotation(active = true, weekSlot = 2, freeModes = listOf("gizli_kelime"))
        assertEquals("Bu hafta ücretsiz", GameCards.status(info("gizli_kelime"),
            catalog(mode("gizli_kelime", allowed = true, by = "rotation"), rotation = r), future).badge)
        val plus = GameCards.status(info("gizli_kelime"), catalog(mode("gizli_kelime", allowed = true, by = "plus")), future)
        assertEquals(GameCardKind.PLAYABLE, plus.kind); assertTrue(plus.canStart); assertEquals("Plus", plus.badge)
        assertEquals("Admin", GameCards.status(info("gizli_kelime"), catalog(mode("gizli_kelime", allowed = true, by = "admin")), future).badge)
        assertEquals("Erken erişim", GameCards.status(info("gizli_kelime"),
            catalog(mode("gizli_kelime", status = "early_access", allowed = true, by = "plus")), future).badge)
    }

    @Test fun implementedButCatalogueMissingIsUnavailableNotFree() {
        val s = GameCards.status(info("gizli_kelime"), catalog(), future)
        assertEquals(GameCardKind.UNAVAILABLE, GameCards.status(info("gizli_kelime"), null, future).kind)
        assertEquals(GameCardKind.UNAVAILABLE, s.kind)
        assertFalse(s.canStart)
    }

    @Test fun disabledModeIsUnavailable() {
        val s = GameCards.status(info("gizli_kelime"), catalog(mode("gizli_kelime", allowed = false, reason = "MODE_NOT_AVAILABLE")), future)
        assertEquals(GameCardKind.UNAVAILABLE, s.kind); assertFalse(s.canStart)
    }

    @Test fun membershipParsesServerAnswer() {
        val free = json.decodeFromString(Membership.serializer(),
            """{"plan":"free","is_admin":false,"plus_until":null,"plus_unlimited":false,"google_linked":false,"server_time":"2026-11-13T10:00:00+00:00","entitlements":[]}""")
        assertEquals(PlanTier.FREE, free.tier); assertFalse(free.hasPlusAccess)
        val plus = json.decodeFromString(Membership.serializer(),
            """{"plan":"plus","is_admin":false,"plus_until":"2026-11-20T10:00:00+00:00","plus_unlimited":false,"google_linked":true,
               "entitlements":[{"id":"e1","source":"promo","starts_at":"2026-11-13T10:00:00+00:00","ends_at":"2026-11-20T10:00:00+00:00","revoked":false}]}""")
        assertEquals(PlanTier.PLUS, plus.tier); assertTrue(plus.hasPlusAccess)
        assertEquals("2026-11-20T10:00:00+00:00", plus.plusUntil)
        assertEquals(PlanTier.ADMIN, PlanTier.fromServer("admin"))
        assertEquals(PlanTier.FREE, PlanTier.fromServer("platinum"))
    }

    @Test fun promoAnswerParses() {
        val r = json.decodeFromString(PromoRedemption.serializer(),
            """{"status":"redeemed","code":"LOPPAWEEK","days":7,"starts_at":"2026-11-13T10:00:00+00:00","ends_at":"2026-11-20T10:00:00+00:00","plus_until":"2026-11-20T10:00:00+00:00"}""")
        assertTrue(r.isNew); assertEquals(7, r.days)
        val again = json.decodeFromString(PromoRedemption.serializer(),
            """{"status":"already_redeemed","code":"LOPPAWEEK","redeemed_at":"2026-11-13T10:00:00+00:00","plus_until":"2026-11-20T10:00:00+00:00"}""")
        assertFalse(again.isNew); assertNull(again.days)
    }

    @Test fun oldRoomSettingsWithoutModeAreIsimSehir() {
        val s = json.decodeFromString(RoomSettings.serializer(), """{"round_seconds":60,"rounds":5,"max_players":10,"categories":[]}""")
        assertEquals("isim_sehir", s.gameMode)
    }

    @Test fun displayDates() {
        assertEquals("20 Kasım 2026", PlusFormat.date("2026-11-20T10:00:00+00:00", java.time.ZoneId.of("Europe/Istanbul")))
        assertEquals("20 Kasım 2026, 13:00", PlusFormat.dateTime("2026-11-20T10:00:00+00:00", java.time.ZoneId.of("Europe/Istanbul")))
        assertNull(PlusFormat.date(null))
        assertNull(PlusFormat.date("not a date"))
    }
}

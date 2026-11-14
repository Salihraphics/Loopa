package com.loopa.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLogicTest {
    @Test fun everyGameHasACategoryAndCategoriesComeFromData() {
        val cats = GameModes.categories
        assertTrue(cats.isNotEmpty())
        assertEquals(GameModes.all.map { it.category }.toSet(), cats.toSet())
    }

    @Test fun searchIsTurkishAwareAndFiltersByCategory() {
        assertEquals(listOf("isim_sehir"), GameModes.search("İSİM", null).map { it.id })
        assertEquals(listOf("isim_sehir"), GameModes.search("isim şehir", null).map { it.id })
        val drawing = GameModes.search("", GameCategory.CIZIM)
        assertTrue(drawing.isNotEmpty() && drawing.all { it.category == GameCategory.CIZIM })
        assertEquals(GameModes.all.size, GameModes.search("  ", null).size)
        assertTrue(GameModes.search("zzzz", null).isEmpty())
    }

    @Test fun roomCodesNormalize() {
        assertEquals("KPX7A2", RoomCodes.normalize(" kpx-7a 2 "))
        assertEquals(6, RoomCodes.normalize("abcdefghij").length)
        assertTrue(RoomCodes.normalize("IO01").none { it in "IO01" })
    }

    @Test fun orientationDefaultsToFlexible() {
        // Every game, Buckshot included, is designed for portrait and adapts to landscape.
        assertTrue(GameModes.all.all { it.orientation == GameOrientation.FLEXIBLE })
    }

    @Test fun roadmapIsPlanningOnly() {
        assertEquals(24, GameRoadmap.targetCount)
        assertEquals((21..24).toList(), GameRoadmap.planned.map { it.number })
        val implemented = GameModes.all.map { it.id }.toSet()
        assertTrue(GameRoadmap.planned.none { it.id in implemented })
        assertEquals(GameRoadmap.planned.size, GameRoadmap.planned.map { it.id }.toSet().size)
    }

    @Test fun everyGameDeclaresHowItSyncs() {
        // Krallıklar stores hidden orders and resolves them at the deadline; the rest re-fetch on a signal.
        assertTrue(GameModes.all.all { it.kind.sync == SyncModel.SNAPSHOT_ON_SIGNAL || it.kind == GameKind.STRATEGY })
        assertEquals(20, GameModes.all.size)
        assertEquals(4, GameModes.info("okey").minPlayers)
        assertEquals(6, GameModes.info("kurt_adam").minPlayers)
    }
}

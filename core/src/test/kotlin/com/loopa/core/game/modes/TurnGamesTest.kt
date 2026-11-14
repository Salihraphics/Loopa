package com.loopa.core.game.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnGamesTest {
    private val start = listOf(-2, 0, 0, 0, 0, 5, 0, 3, 0, 0, 0, -5, 5, 0, 0, 0, -3, 0, -5, 0, 0, 0, 0, 2)

    @Test fun tavlaRelativeBoardMirrorsForSideTwo() {
        val rel = TavlaLogic.relative(start, 2)
        // the second player's own checkers are positive and the opening position is symmetric
        assertEquals(start, rel)
        assertEquals(15, rel.filter { it > 0 }.sum())
    }

    @Test fun tavlaStepsLikeTheServer() {
        val p = TavlaLogic.Pos(start, 0, 0, 0)
        // 24 -> 18 is blocked (5 opponents on 19 is irrelevant; 18 is empty): legal
        assertNotNull(TavlaLogic.step(p, 24, 6))
        // 24 - 5 = 19 holds five opponents: illegal
        assertNull(TavlaLogic.step(p, 24, 5))
        // from the bar first
        val onBar = p.copy(bar = 1)
        assertNull(TavlaLogic.step(onBar, 13, 1))
        assertNotNull(TavlaLogic.step(onBar, 25, 3))
        // hitting a single opponent sends it to the bar
        val blot = TavlaLogic.Pos(List(24) { if (it == 9) 1 else if (it == 6) -1 else 0 }, 0, 0, 0)
        val hit = TavlaLogic.step(blot, 10, 3)!!
        assertEquals(1, hit.obar); assertEquals(1, hit.b[6])
    }

    @Test fun tavlaBearingOff() {
        val home = TavlaLogic.Pos(List(24) { if (it == 1) 2 else if (it == 4) 1 else 0 }, 0, 0, 13)
        assertNotNull(TavlaLogic.step(home, 5, 5))            // exact
        assertNull(TavlaLogic.step(home, 2, 6))               // a higher checker exists on 5
        assertNotNull(TavlaLogic.step(home, 5, 6))            // highest checker with a larger die
        assertEquals(listOf(5), TavlaLogic.sources(home, listOf(6)))
    }

    @Test fun cardFaces() {
        assertEquals("A", CardFace.rank(1)); assertEquals("K", CardFace.rank(13))
        assertEquals(21, CardFace.yirmiBir(listOf(1, 13)))
        assertEquals(12, CardFace.yirmiBir(listOf(1, 1)))
        assertEquals(22, CardFace.yirmiBir(listOf(10, 10, 2)))
        assertEquals(CardFace.Tile("r12a", "r", 12), CardFace.okeyTile("r12a"))
        assertNull(CardFace.okeyTile("fj1").color)
    }

    @Test fun hexRoundTrip() {
        for (q in -5..5) for (r in -5..5) {
            val (x, y) = Hex.center(q, r, 20f)
            assertEquals(Pair(q, r), Hex.at(x, y, 20f))
        }
        assertEquals(3, Hex.distance(0, 0, 3, -1))
        assertEquals(1, Hex.turnsToArrive(2, 2)); assertEquals(3, Hex.turnsToArrive(5, 2))
        assertTrue(Hex.turnsToArrive(0, 2) >= 1)
    }
}

package com.loopa.feature.lobby

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomCodeTest {
    @Test fun normalizesKeyboardAndClipboardInput() {
        assertEquals("KPX7A2", normalizeRoomCode("kpx7a2"))
        assertEquals("KPX7A2", normalizeRoomCode(" kpx-7a 2 "))
        assertEquals("KPX7A2", normalizeRoomCode("KPX 7A2\n"))
        assertEquals("ABCDEF", normalizeRoomCode("abcdefgh"))          // max 6
        assertEquals("", normalizeRoomCode("01OI "))                   // ambiguous characters are never in codes
        assertEquals("KLM", normalizeRoomCode("klmıi"))                // Turkish ı/i are not code characters
    }
}

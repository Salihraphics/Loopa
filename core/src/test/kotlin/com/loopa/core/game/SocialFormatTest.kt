package com.loopa.core.game

import com.loopa.core.model.Presence
import com.loopa.core.model.SocialBadges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SocialFormatTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()
    private fun iso(secondsAgo: Long) = Instant.ofEpochMilli(now - secondsAgo * 1000).toString()

    @Test fun presenceRespectsPrivacy() {
        assertNull(SocialFormat.presence(Presence(visible = false, online = true, lastSeenAt = iso(10)), now))
        assertEquals("Çevrim içi", SocialFormat.presence(Presence(true, true, iso(10)), now))
        assertEquals("5 dk önce görüldü", SocialFormat.presence(Presence(true, false, iso(300)), now))
        assertNull(SocialFormat.presence(Presence(true, false, null), now))
    }

    @Test fun relativeTimes() {
        assertEquals("az önce", SocialFormat.ago(iso(20), now))
        assertEquals("3 sa önce", SocialFormat.ago(iso(3 * 3600 + 5), now))
        assertEquals("dün", SocialFormat.ago(iso(30 * 3600), now))
        assertEquals("4 gün önce", SocialFormat.ago(iso(4 * 86_400), now))
        assertEquals("", SocialFormat.ago(null as String?, now))
    }

    @Test fun inviteRemaining() {
        assertEquals("12 dk kaldı", SocialFormat.remaining(Instant.ofEpochMilli(now + 11 * 60_000 + 30_000).toString(), now))
        assertNull(SocialFormat.remaining(iso(1), now))
    }

    @Test fun socialTabCountsWhatWaitsForAnAnswer() {
        assertEquals(6, SocialBadges(notifications = 9, messages = 3, requests = 2, invites = 1).socialTab)
    }
}

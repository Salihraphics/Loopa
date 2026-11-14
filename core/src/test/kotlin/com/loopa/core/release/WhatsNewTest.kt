package com.loopa.core.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    private fun notes(v: String) = ReleaseNotes(v, "t$v", "s", WhatsNewHero.GENERIC,
        listOf(WhatsNewFeature("f", "d", FeatureArt.SPARK, Accent.MINT), WhatsNewFeature("s", "d", FeatureArt.CHAT, Accent.SKY, ServerFeature.SOCIAL)))
    private val list = listOf(notes("0.7.0"), notes("0.8.0"), notes("0.9.0"))

    @Test fun versionsCompareNumerically() {
        assertTrue(WhatsNew.compare("0.10.0", "0.9.9") > 0)
        assertEquals(0, WhatsNew.compare("0.7.0-debug", "0.7.0"))
        assertEquals(0, WhatsNew.compare("0.7", "0.7.0"))
        assertTrue(WhatsNew.compare("0.6.0", "0.7.0") < 0)
    }

    @Test fun updateFromBeforeTheSystemShowsCurrentNotes() {
        assertEquals(WhatsNewDecision.Show(notes("0.7.0")), WhatsNew.decide("0.7.0", lastSeen = null, onboarded = true, list = list))
    }

    @Test fun freshInstallShowsNothingButRemembers() {
        assertEquals(WhatsNewDecision.MarkSeen, WhatsNew.decide("0.7.0", lastSeen = null, onboarded = false, list = list))
    }

    @Test fun sameVersionIsNotShownTwice() {
        assertEquals(WhatsNewDecision.None, WhatsNew.decide("0.7.0", lastSeen = "0.7.0", onboarded = true, list = list))
        assertEquals(WhatsNewDecision.None, WhatsNew.decide("0.7.0-debug", lastSeen = "0.7.0", onboarded = true, list = list))
        // a downgrade never shows older notes again
        assertEquals(WhatsNewDecision.None, WhatsNew.decide("0.7.0", lastSeen = "0.8.0", onboarded = true, list = list))
    }

    @Test fun skippingVersionsShowsOnlyTheNewest() {
        assertEquals(WhatsNewDecision.Show(notes("0.9.0")), WhatsNew.decide("0.9.0", lastSeen = "0.6.0", onboarded = true, list = list))
        assertEquals(WhatsNewDecision.Show(notes("0.8.0")), WhatsNew.decide("0.8.3", lastSeen = "0.7.0", onboarded = true, list = list))
    }

    @Test fun patchWithoutNotesIsJustRemembered() {
        assertEquals(WhatsNewDecision.MarkSeen, WhatsNew.decide("0.9.1", lastSeen = "0.9.0", onboarded = true, list = list))
    }

    @Test fun featuresWithoutBackendAreNotAnnounced() {
        assertEquals(1, notes("0.7.0").availableOn(emptySet()).features.size)
        assertEquals(2, notes("0.7.0").availableOn(setOf(ServerFeature.SOCIAL)).features.size)
    }

    @Test fun lookupsForSettings() {
        assertEquals("0.8.0", WhatsNew.latestUpTo("0.8.5", list)?.versionName)
        assertEquals(null, WhatsNew.notesFor("0.8.5", list))
        assertTrue(WhatsNew.releases.any { it.versionName == "0.7.0" })
    }
}

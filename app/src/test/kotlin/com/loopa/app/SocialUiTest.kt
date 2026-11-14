package com.loopa.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.loopa.app.whatsnew.WhatsNewScreen
import com.loopa.core.data.Remote
import com.loopa.core.model.Conversation
import com.loopa.core.model.DirectMessage
import com.loopa.core.model.Friend
import com.loopa.core.model.FriendRequestItem
import com.loopa.core.model.Presence
import com.loopa.core.model.Relation
import com.loopa.core.model.RoomInviteItem
import com.loopa.core.model.SocialBadges
import com.loopa.core.model.SocialOverview
import com.loopa.core.model.UserCard
import com.loopa.core.model.UserProfileView
import com.loopa.core.release.WhatsNew
import com.loopa.core.ui.avatar.AvatarCatalog
import com.loopa.core.ui.theme.LocalReducedMotion
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.social.ChatContent
import com.loopa.feature.social.ChatItem
import com.loopa.feature.social.ChatState
import com.loopa.feature.social.SendStatus
import com.loopa.feature.social.SocialContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * Social hub, chat and What's New with fixed data: behaviour (paging, close, CTA, statuses) and
 * screenshots in light / dark, phone / tablet / landscape, normal and reduced motion.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class SocialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun iso(offsetMs: Long) = Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs).toString()
    private fun avatar(i: Int) = AvatarCatalog.random(Random(i * 11 + 5)).toJson()
    private fun card(i: Int, name: String) = UserCard("u$i", name, avatar(i), level = 2 + i * 3)

    private val overview = SocialOverview(
        friends = listOf(
            Friend("u1", "Ayşe", avatar(1), 7, Presence(true, true, iso(-5_000))),
            Friend("u2", "Barış", avatar(2), 12, Presence(true, false, iso(-25 * 60_000))),
            Friend("u3", "Cem", avatar(3), 4, Presence(false)),
        ),
        incoming = listOf(FriendRequestItem("r1", iso(-3_600_000), card(4, "Deniz"))),
        invites = listOf(RoomInviteItem("i1", "room1", "KPX7A2", "ciz_tahmin", "lobby", iso(-60_000), iso(12 * 60_000), card(1, "Ayşe"))),
    )
    private val conversations = listOf(
        Conversation("u1", "Ayşe", avatar(1), 7, DirectMessage(9, "u1", "me", "Bu akşam Çiz ve Tahmin?", null, iso(-120_000)), 2, true, Presence(true, true)),
        Conversation("u2", "Barış", avatar(2), 12, DirectMessage(8, "me", "u2", "Tamamdır!", null, iso(-86_400_000), iso(-80_000_000)), 0, true),
    )

    private fun render(dark: Boolean = false, reduced: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            LoopaTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { content() }
                }
            }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun hub(dark: Boolean = false, data: SocialOverview = overview, convs: List<Conversation> = conversations) = render(dark, reduced = true) {
        SocialContent(Remote(data), SocialBadges(notifications = 3, messages = 2, requests = 1, invites = 1), Remote(convs), null, null,
            {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
    }

    // ------------------------------------------------------------------ social hub
    @Test fun socialHub() {
        hub()
        compose.onNodeWithTag("invite_i1").assertIsDisplayed()
        compose.onNodeWithTag("social_requests").assertExists()
        compose.onNodeWithTag("social_online").assertExists()          // only Ayşe is online
        compose.onNodeWithTag("social_friends").performScrollTo()
        save("50_social_hub")
    }

    @Test fun socialHubDark() { hub(dark = true); save("51_social_hub_dark") }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun socialHubTablet() { hub(); save("52_social_hub_tablet") }

    @Test fun socialEmpty() {
        hub(data = SocialOverview(), convs = emptyList())
        compose.onNodeWithTag("social_empty").assertIsDisplayed()
        compose.onNodeWithText("Henüz arkadaşın yok").assertExists()
        save("53_social_empty")
    }

    @Test fun socialUnavailableIsHonest() {
        render { SocialContent(Remote(error = com.loopa.core.game.LoopaError.SERVER_OUTDATED), SocialBadges(), Remote(), null, null,
            {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}) }
        compose.onNodeWithTag("social_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("social_public_rooms").assertExists()     // playing together still works
    }

    // ------------------------------------------------------------------ chat
    private val chat = ChatState(
        peer = UserProfileView("u1", "Ayşe", avatar(1), 7, relationRaw = "friend", presence = Presence(true, true)),
        messages = listOf(
            DirectMessage(4, "me", "u1", "Olur, kodu atıyorum.", "c4", iso(-30_000), null),
            DirectMessage(3, "u1", "me", "Bu akşam Çiz ve Tahmin oynayalım mı? 🎨", null, iso(-90_000)),
            DirectMessage(2, "me", "u1", "Selam! Nasılsın?", "c2", iso(-300_000), iso(-200_000)),
        ),
        pending = listOf(
            ChatItem("pfail", true, "Bu gönderilemedi", null, SendStatus.FAILED, "cf"),
            ChatItem("psend", true, "Yolda…", null, SendStatus.SENDING, "cs"),
        ),
        loading = false,
    )

    @Test fun chatShowsDeliveryStates() {
        render(reduced = true) { ChatContent(chat, "me", {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("status_READ", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("status_SENT", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("status_FAILED", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("chat_input").assertExists()
        save("54_chat")
    }

    @Test fun chatDark() { render(dark = true, reduced = true) { ChatContent(chat, "me", {}, {}, {}, {}, {}, {}) }; save("55_chat_dark") }

    @Test fun chatLockedForNonFriends() {
        render(reduced = true) { ChatContent(chat.copy(peer = chat.peer!!.copy(relationRaw = "none")), "me", {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("chat_locked").assertIsDisplayed()
        assertEquals(Relation.NONE, chat.peer!!.copy(relationRaw = "none").relation)
    }

    @Test fun chatEmpty() {
        render(reduced = true) { ChatContent(ChatState(peer = chat.peer, loading = false), "me", {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("chat_empty").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ What's New
    private val notes = WhatsNew.notesFor("0.7.0")!!

    private fun whatsNewWalkthrough(reduced: Boolean, name: String) {
        var closed = 0
        render(reduced = reduced) { WhatsNewScreen(notes) { closed++ } }
        if (!reduced) { compose.mainClock.advanceTimeBy(1_500) }
        compose.onNodeWithTag("whats_new_title").assertTextContains(notes.title)
        save("${name}_intro")
        repeat(notes.features.size) { i ->
            compose.onNodeWithTag("whats_new_cta").assertTextContains("İleri")
            compose.onNodeWithTag("whats_new_cta").performClick()
            if (!reduced) compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
            compose.onNodeWithTag("whats_new_feature_${i + 1}").assertExists()
        }
        compose.onNodeWithTag("whats_new_cta").assertTextContains(notes.ctaText)
        save("${name}_last")
        compose.onNodeWithTag("whats_new_cta").performClick()
        assertEquals("CTA on the last page closes", 1, closed)
        compose.onNodeWithTag("whats_new_close").performClick()
        assertEquals("X closes", 2, closed)
    }

    @Test fun whatsNewPagesAndCloses() = whatsNewWalkthrough(reduced = false, name = "60_whats_new")

    @Test fun whatsNewReducedMotion() = whatsNewWalkthrough(reduced = true, name = "61_whats_new_reduced")

    @Test fun whatsNewDark() { render(dark = true, reduced = true) { WhatsNewScreen(notes) {} }; save("62_whats_new_dark") }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun whatsNewTablet() { render(reduced = true) { WhatsNewScreen(notes) {} }; save("63_whats_new_tablet") }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    fun whatsNewLandscape() {
        render(reduced = true) { WhatsNewScreen(notes) {} }
        compose.onNodeWithTag("whats_new_cta").assertIsDisplayed()
        save("64_whats_new_landscape")
    }

    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun whatsNewSmallPhone() {
        render(reduced = true) { WhatsNewScreen(notes) {} }
        compose.onNodeWithTag("whats_new_cta").assertIsDisplayed()
        save("65_whats_new_small")
    }
}

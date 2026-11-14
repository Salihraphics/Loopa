package com.loopa.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.loopa.app.navigation.AppNavHost
import com.loopa.core.ui.theme.LocalReducedMotion
import com.loopa.core.ui.theme.LoopaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Release audit: the real AppNavHost, driven only through the UI. Every tab and every pushed
 * destination reachable from them opens, and Back returns to where the user came from.
 * (No backend: screens render their offline / unavailable states.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class NavigationAuditTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }          // only nodes inside a scrolling container need it
        node.performClick(); compose.waitForIdle()
    }
    private fun tab(name: String) { compose.onNodeWithTag("nav_$name").performClick(); compose.waitForIdle(); compose.onNodeWithTag("nav_$name").assertIsSelected() }
    private fun seesTag(tag: String) = compose.onNode(hasTestTag(tag), useUnmergedTree = true).assertExists()
    private fun seesText(text: String) = compose.onNode(hasText(text), useUnmergedTree = true).assertExists()

    @Test fun everyDestinationOpensAndReturns() {
        compose.setContent {
            LoopaTheme(darkTheme = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) { AppNavHost(versionName = "0.7.0", resumeRoomId = null, onLoggedOut = {}) }
            }
        }
        // ---- Ana Sayfa
        seesTag("bottom_nav"); seesTag("join_card")
        click("public_rooms"); seesText("Açık Odalar"); back(); seesTag("join_card")
        click("open_notifications"); seesTag("notifications_list"); back(); seesTag("join_card")
        // ---- Oyunlar
        tab("GAMES"); seesTag("games_list")
        // ---- Sosyal
        tab("SOCIAL"); seesTag("social_screen")
        click("social_find"); seesTag("friend_search"); back(); seesTag("social_screen")
        click("social_notifications"); seesTag("notifications_list"); back(); seesTag("social_screen")
        click("social_public_rooms"); seesText("Açık Odalar"); back(); seesTag("social_screen")
        // ---- Profil and everything behind it
        tab("PROFILE"); seesTag("profile_scroll")
        click("profile_avatar"); seesText("Avatar Oluşturucu"); seesTag("avatar_tabs"); back(); seesTag("profile_scroll")
        click("profile_shop"); seesText("Kozmetik Mağazası"); back(); seesTag("profile_scroll")
        click("profile_plus"); seesTag("plus_hero"); back(); seesTag("profile_scroll")
        click("open_settings"); seesText("Ayarlar")
        click("settings_blocked"); seesTag("blocked_list"); back(); seesText("Ayarlar")
        click("settings_whats_new"); seesTag("whats_new"); click("whats_new_close"); seesText("Ayarlar")
        back(); seesTag("profile_scroll")
        // tab state survives the round trips; back to Home
        tab("HOME"); seesTag("join_card")
    }
}

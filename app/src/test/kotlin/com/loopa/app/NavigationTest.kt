package com.loopa.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.loopa.app.navigation.MainTabs
import com.loopa.app.navigation.Tab
import com.loopa.core.ui.theme.LoopaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The four primary destinations, the tablet rail and the Profile → Settings entry point. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fourTabsAndSettingsFromProfile() {
        var settingsOpened = 0
        compose.setContent { LoopaTheme(darkTheme = false) { MainTabs(onRoomReady = {}, onPublicRooms = {}, onShop = {}, onSettings = { settingsOpened++ }) } }
        assertEquals(4, Tab.entries.size)
        compose.onNodeWithTag("bottom_nav").assertExists()
        Tab.entries.forEach { compose.onNodeWithTag("nav_${it.name}").assertExists() }
        compose.onNodeWithTag("nav_HOME").assertIsSelected()
        compose.onNodeWithTag("join_card").assertExists()

        compose.onNodeWithTag("nav_GAMES").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("nav_GAMES").assertIsSelected()
        compose.onNodeWithTag("games_list").assertExists()

        compose.onNodeWithTag("nav_SOCIAL").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("social_screen").assertExists()
        compose.onNodeWithTag("social_public_rooms").assertExists()

        compose.onNodeWithTag("nav_PROFILE").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("open_settings").performClick()
        assertTrue("Settings is reachable from Profile", settingsOpened == 1)
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun tabletUsesRail() {
        compose.setContent { LoopaTheme(darkTheme = false) { MainTabs(onRoomReady = {}, onPublicRooms = {}, onShop = {}) } }
        compose.onNodeWithTag("nav_rail").assertExists()
        compose.onAllNodesWithTag("bottom_nav").assertCountEquals(0)
    }
}

package com.loopa.app.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.loopa.core.ui.components.LoopaBottomNav
import com.loopa.core.ui.components.LoopaNavRail
import com.loopa.core.ui.components.NavItem
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaMotion
import com.loopa.core.ui.theme.Motion
import com.loopa.feature.avatar.AvatarEditorScreen
import com.loopa.feature.home.HomeScreen
import com.loopa.feature.social.SocialScreen
import com.loopa.feature.lobby.GamesScreen
import com.loopa.feature.lobby.PublicRoomsScreen
import com.loopa.feature.profile.ProfileScreen
import com.loopa.feature.settings.SettingsScreen
import com.loopa.feature.shop.PlusScreen
import com.loopa.feature.shop.ShopScreen
import kotlinx.serialization.Serializable

@Serializable data object MainRoute
@Serializable data class RoomRoute(val roomId: String)
@Serializable data object PublicRoomsRoute
@Serializable data object ShopRoute
@Serializable data object PlusRoute
@Serializable data object SettingsRoute
@Serializable data object AvatarRoute

/**
 * The four primary destinations. Adding one is a new entry here plus a branch in [MainTabs];
 * the navigation bar (or rail on tablets) is built from this list.
 */
enum class Tab(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Ana Sayfa", Icons.Outlined.Home, Icons.Rounded.Home),
    GAMES("Oyunlar", Icons.Outlined.SportsEsports, Icons.Rounded.SportsEsports),
    SOCIAL("Sosyal", Icons.Outlined.Groups, Icons.Rounded.Groups),
    PROFILE("Profil", Icons.Outlined.Person, Icons.Rounded.Person),
}

@Composable
fun AppNavHost(versionName: String, resumeRoomId: String?, onLoggedOut: () -> Unit, onAccountSwitched: () -> Unit = onLoggedOut) {
    val nav = rememberNavController()
    val feedback = LocalFeedback.current
    LaunchedEffect(resumeRoomId) {
        if (resumeRoomId != null) nav.navigate(RoomRoute(resumeRoomId)) { launchSingleTop = true }
    }
    val container = com.loopa.core.data.rememberDataContainer()
    val reduced = LoopaMotion.reduced
    fun openRoom(id: String) = nav.navigate(RoomRoute(id)) {
        launchSingleTop = true
        popUpTo(MainRoute)
    }

    NavHost(
        navController = nav,
        startDestination = MainRoute,
        modifier = Modifier.fillMaxSize().background(Loopa.colors.background),
        enterTransition = { if (reduced) fadeIn(tween(0)) else slideInHorizontally(tween(Motion.MEDIUM)) { it / 4 } + fadeIn(tween(Motion.MEDIUM)) },
        exitTransition = { fadeOut(tween(if (reduced) 0 else Motion.FAST)) },
        popEnterTransition = { fadeIn(tween(if (reduced) 0 else Motion.MEDIUM)) },
        popExitTransition = { if (reduced) fadeOut(tween(0)) else slideOutHorizontally(tween(Motion.MEDIUM)) { it / 4 } + fadeOut(tween(Motion.MEDIUM)) },
    ) {
        composable<MainRoute> {
            LaunchedEffect(Unit) { feedback.setMusicPlaying(true) }
            // Plan and catalogue come from the server; failures are kept in the repositories' state.
            LaunchedEffect(Unit) { container.membership.refresh(); container.catalog.refresh() }
            MainTabs(
                onRoomReady = ::openRoom,
                onPublicRooms = { nav.navigate(PublicRoomsRoute) { launchSingleTop = true } },
                onShop = { nav.navigate(ShopRoute) { launchSingleTop = true } },
                onPlus = { nav.navigate(PlusRoute) { launchSingleTop = true } },
                onSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
                onAvatar = { nav.navigate(AvatarRoute) { launchSingleTop = true } },
            )
        }
        composable<RoomRoute> { entry ->
            val route = entry.toRoute<RoomRoute>()
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).safeDrawingPadding()) {
                RoomHost(route.roomId, onExit = {
                    if (!nav.popBackStack(MainRoute, inclusive = false)) nav.navigate(MainRoute)
                })
            }
        }
        composable<PublicRoomsRoute> {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                PublicRoomsScreen(onBack = { nav.popBackStack() }, onRoomReady = ::openRoom)
            }
        }
        composable<PlusRoute> {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                // The promo code field lives in Settings → Üyelik.
                PlusScreen(onBack = { nav.popBackStack() }, onOpenPromo = { nav.navigate(SettingsRoute) { launchSingleTop = true } })
            }
        }
        composable<ShopRoute> {
            Box(Modifier.fillMaxSize().systemBarsPadding()) { ShopScreen(onBack = { nav.popBackStack() }) }
        }
        composable<SettingsRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding().imePadding()) {
                SettingsScreen(versionName = versionName, onLoggedOut = onLoggedOut, onAccountSwitched = onAccountSwitched,
                    onOpenPlus = { nav.navigate(PlusRoute) { launchSingleTop = true } }, onBack = { nav.popBackStack() })
            }
        }
        composable<AvatarRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                AvatarEditorScreen(onOpenShop = { nav.navigate(ShopRoute) { launchSingleTop = true } }, onBack = { nav.popBackStack() })
            }
        }
    }
}

@Composable
internal fun MainTabs(
    onRoomReady: (String) -> Unit,
    onPublicRooms: () -> Unit,
    onShop: () -> Unit,
    initialTab: Tab = Tab.HOME,
    onPlus: () -> Unit = {},
    onSettings: () -> Unit = {},
    onAvatar: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Edge-to-edge: the window is not resized for the keyboard, so the content handles IME insets.
    val imeVisible = WindowInsets.isImeVisible
    val holder = rememberSaveableStateHolder()
    val feedback = LocalFeedback.current
    val reduced = LoopaMotion.reduced
    val items = Tab.entries.map { NavItem(it.name, it.title, it.icon, it.selectedIcon) }
    fun select(key: String) {
        val t = Tab.valueOf(key)
        if (t != tab) feedback.play(Sfx.TAP)
        tab = t
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedContent(
            tab, modifier,
            transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(Motion.MEDIUM)) { dir * it / 12 } + fadeIn(tween(Motion.MEDIUM))) togetherWith fadeOut(tween(Motion.FAST))
                }
            },
            label = "tabs",
        ) { t ->
            holder.SaveableStateProvider(t.name) {
                when (t) {
                    Tab.HOME -> HomeScreen(
                        onRoomReady = onRoomReady,
                        onOpenGames = { tab = Tab.GAMES },
                        onPublicRooms = onPublicRooms,
                        onOpenProfile = { tab = Tab.PROFILE },
                        onOpenPlus = onPlus,
                    )
                    Tab.GAMES -> GamesScreen(onBack = null, onRoomReady = onRoomReady, onOpenPlus = onPlus)
                    Tab.SOCIAL -> SocialScreen(onPublicRooms = onPublicRooms, onOpenGames = { tab = Tab.GAMES })
                    Tab.PROFILE -> ProfileScreen(onEditAvatar = onAvatar, onOpenShop = onShop, onOpenPlus = onPlus, onOpenSettings = onSettings)
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        if (maxWidth >= 720.dp) {
            // Tablets and landscape: a side rail keeps the content area wide and tall.
            Row(Modifier.fillMaxSize()) {
                LoopaNavRail(items, tab.name, ::select, Modifier.fillMaxHeight().systemBarsPadding())
                content(Modifier.weight(1f).fillMaxHeight().systemBarsPadding().imePadding())
            }
        } else {
            Scaffold(
                containerColor = Loopa.colors.background,
                // Hidden while typing: more room above the keyboard on small phones.
                bottomBar = { if (!imeVisible) LoopaBottomNav(items, tab.name, ::select) },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding)
                        .consumeWindowInsets(padding)   // bottom bar / navigation bar already accounted for
                        .statusBarsPadding()
                        .imePadding(),                  // content (and its scroll area) ends above the keyboard
                ) { content(Modifier.fillMaxSize()) }
            }
        }
    }
}

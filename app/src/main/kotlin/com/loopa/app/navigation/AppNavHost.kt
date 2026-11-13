package com.loopa.app.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.feature.avatar.AvatarEditorScreen
import com.loopa.feature.home.HomeScreen
import com.loopa.feature.lobby.GamesScreen
import com.loopa.feature.lobby.PlayScreen
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
@Serializable data object GamesRoute
@Serializable data object PlusRoute

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Ana Sayfa", Icons.Rounded.Home),
    PLAY("Oyna", Icons.Rounded.PlayCircle),
    PROFILE("Profil", Icons.Rounded.Person),
    AVATAR("Avatar", Icons.Rounded.Face),
    SETTINGS("Ayarlar", Icons.Rounded.Settings),
}

@Composable
fun AppNavHost(versionName: String, resumeRoomId: String?, onLoggedOut: () -> Unit, onAccountSwitched: () -> Unit = onLoggedOut) {
    val nav = rememberNavController()
    val feedback = LocalFeedback.current
    LaunchedEffect(resumeRoomId) {
        if (resumeRoomId != null) nav.navigate(RoomRoute(resumeRoomId)) { launchSingleTop = true }
    }
    val container = com.loopa.core.data.rememberDataContainer()
    /** Tab requested from a screen outside the tabs (e.g. Plus → promo code in Settings). */
    var requestedTab by rememberSaveable { mutableStateOf<Tab?>(null) }
    fun openRoom(id: String) = nav.navigate(RoomRoute(id)) {
        launchSingleTop = true
        popUpTo(MainRoute)
    }

    NavHost(
        navController = nav,
        startDestination = MainRoute,
        modifier = Modifier.fillMaxSize().background(Loopa.colors.background),
        enterTransition = { slideInHorizontally { it / 3 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 3 } + fadeOut() },
    ) {
        composable<MainRoute> {
            LaunchedEffect(Unit) { feedback.setMusicPlaying(true) }
            // Plan and catalogue come from the server; failures are kept in the repositories' state.
            LaunchedEffect(Unit) { container.membership.refresh(); container.catalog.refresh() }
            MainTabs(
                versionName = versionName,
                onRoomReady = ::openRoom,
                onPublicRooms = { nav.navigate(PublicRoomsRoute) },
                onShop = { nav.navigate(ShopRoute) },
                onLoggedOut = onLoggedOut,
                onAccountSwitched = onAccountSwitched,
                onGames = { nav.navigate(GamesRoute) { launchSingleTop = true } },
                onPlus = { nav.navigate(PlusRoute) { launchSingleTop = true } },
                requestedTab = requestedTab,
                onTabRequestHandled = { requestedTab = null },
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
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                PublicRoomsScreen(onBack = { nav.popBackStack() }, onRoomReady = ::openRoom)
            }
        }
        composable<GamesRoute> {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                GamesScreen(onBack = { nav.popBackStack() }, onRoomReady = ::openRoom, onOpenPlus = { nav.navigate(PlusRoute) { launchSingleTop = true } })
            }
        }
        composable<PlusRoute> {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                PlusScreen(onBack = { nav.popBackStack() }, onOpenPromo = {
                    requestedTab = Tab.SETTINGS
                    if (!nav.popBackStack(MainRoute, inclusive = false)) nav.navigate(MainRoute)
                })
            }
        }
        composable<ShopRoute> {
            Box(Modifier.fillMaxSize().statusBarsPadding()) { ShopScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

@Composable
internal fun MainTabs(
    versionName: String,
    onRoomReady: (String) -> Unit,
    onPublicRooms: () -> Unit,
    onShop: () -> Unit,
    onLoggedOut: () -> Unit,
    onAccountSwitched: () -> Unit,
    initialTab: Tab = Tab.HOME,
    onGames: () -> Unit = {},
    onPlus: () -> Unit = {},
    requestedTab: Tab? = null,
    onTabRequestHandled: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(requestedTab) { if (requestedTab != null) { tab = requestedTab; onTabRequestHandled() } }
    // Edge-to-edge: the window is not resized for the keyboard, so the content handles IME insets.
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    var focusJoin by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val feedback = LocalFeedback.current
    Scaffold(
        containerColor = Loopa.colors.background,
        bottomBar = {
            // Hidden while typing: more room above the keyboard on small phones.
            if (!imeVisible) NavigationBar(containerColor = Loopa.colors.card, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { if (tab != t) feedback.play(Sfx.TAP); tab = t; if (t != Tab.PLAY) focusJoin = false },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = LoopaPalette.Lavender,
                            selectedIconColor = LoopaPalette.Ink,
                            selectedTextColor = Loopa.colors.text,
                            unselectedIconColor = Loopa.colors.textSoft,
                            unselectedTextColor = Loopa.colors.textSoft,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .consumeWindowInsets(padding)   // bottom bar / navigation bar already accounted for
                .statusBarsPadding()
                .imePadding(),                  // content (and its scroll area) ends above the keyboard
        ) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tabs") { t ->
                holder.SaveableStateProvider(t.name) {
                    when (t) {
                        Tab.HOME -> HomeScreen(
                            onRoomReady = onRoomReady,
                            onJoinRoom = { focusJoin = true; tab = Tab.PLAY },
                            onPublicRooms = onPublicRooms,
                            onOpenProfile = { tab = Tab.PROFILE },
                            onOpenAvatar = { tab = Tab.AVATAR },
                        )
                        Tab.PLAY -> PlayScreen(onRoomReady = onRoomReady, onPublicRooms = onPublicRooms, focusJoin = focusJoin, onOpenGames = onGames)
                        Tab.PROFILE -> ProfileScreen(onEditAvatar = { tab = Tab.AVATAR }, onOpenShop = onShop, onOpenPlus = onPlus)
                        Tab.AVATAR -> AvatarEditorScreen(onOpenShop = onShop)
                        Tab.SETTINGS -> SettingsScreen(versionName = versionName, onLoggedOut = onLoggedOut, onAccountSwitched = onAccountSwitched, onOpenPlus = onPlus)
                    }
                }
            }
        }
    }
}

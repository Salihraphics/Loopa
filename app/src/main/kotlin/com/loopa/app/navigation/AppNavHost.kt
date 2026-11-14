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
import com.loopa.feature.social.FindFriendsScreen
import com.loopa.feature.social.UserProfileScreen
import com.loopa.feature.social.ConversationsScreen
import com.loopa.feature.social.ChatScreen
import com.loopa.feature.social.NotificationsScreen
import com.loopa.feature.social.NotificationTarget
import com.loopa.feature.social.BlockedUsersScreen
import com.loopa.app.whatsnew.WhatsNewScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.loopa.feature.lobby.GamesScreen
import com.loopa.feature.lobby.PublicRoomsScreen
import com.loopa.feature.profile.ProfileScreen
import com.loopa.feature.settings.SettingsScreen
import com.loopa.feature.shop.PlusScreen
import com.loopa.feature.shop.ShopScreen
import kotlinx.serialization.Serializable

@Serializable data class MainRoute(val tab: String? = null)
@Serializable data class RoomRoute(val roomId: String)
@Serializable data object PublicRoomsRoute
@Serializable data object ShopRoute
@Serializable data object PlusRoute
@Serializable data object SettingsRoute
@Serializable data object AvatarRoute
@Serializable data object FindFriendsRoute
@Serializable data class UserProfileRoute(val userId: String)
@Serializable data object ConversationsRoute
@Serializable data class ChatRoute(val userId: String)
@Serializable data object NotificationsRoute
@Serializable data object BlockedRoute
@Serializable data class WhatsNewRoute(val version: String)
@Serializable data class ProgressRoute(val tab: Int = 0)
@Serializable data object TournamentsRoute

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
    val signedOut: () -> Unit = { container.social.clear(); onLoggedOut() }
    val switched: () -> Unit = { container.social.clear(); onAccountSwitched() }

    // Social: live signal for this account, presence while the app is in the foreground.
    val userId = container.backend.currentUserId
    LaunchedEffect(userId) { if (userId != null) container.social.startLive(userId) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(userId, lifecycle) {
        if (userId == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            container.social.poke()           // catch up on anything missed in the background
            container.social.runPresence()
        }
    }
    // What's New: once per new version, after the server says which features it really has.
    val whatsNew by container.whatsNew.collectAsStateWithLifecycle()
    LaunchedEffect(whatsNew) {
        val d = whatsNew as? com.loopa.core.release.WhatsNewDecision.Show ?: return@LaunchedEffect
        val server = container.social.serverFeatures() ?: return@LaunchedEffect      // offline: try next start
        val notes = d.notes.availableOn(server)
        container.whatsNew.value = null
        if (notes.features.isEmpty()) return@LaunchedEffect                           // announce when the backend is there
        container.settings.setWhatsNewSeen(versionName.substringBefore('-'))
        nav.navigate(WhatsNewRoute(d.notes.versionName)) { launchSingleTop = true }
    }
    fun openRoom(id: String) = nav.navigate(RoomRoute(id)) {
        launchSingleTop = true
        popUpTo<MainRoute>()
    }

    NavHost(
        navController = nav,
        startDestination = MainRoute(),
        modifier = Modifier.fillMaxSize().background(Loopa.colors.background),
        enterTransition = { if (reduced) fadeIn(tween(0)) else slideInHorizontally(tween(Motion.MEDIUM)) { it / 4 } + fadeIn(tween(Motion.MEDIUM)) },
        exitTransition = { fadeOut(tween(if (reduced) 0 else Motion.FAST)) },
        popEnterTransition = { fadeIn(tween(if (reduced) 0 else Motion.MEDIUM)) },
        popExitTransition = { if (reduced) fadeOut(tween(0)) else slideOutHorizontally(tween(Motion.MEDIUM)) { it / 4 } + fadeOut(tween(Motion.MEDIUM)) },
    ) {
        composable<MainRoute> { entry ->
            val start = entry.toRoute<MainRoute>().tab?.let { t -> Tab.entries.firstOrNull { it.name == t } } ?: Tab.HOME
            LaunchedEffect(Unit) { feedback.setMusicPlaying(true) }
            // Plan and catalogue come from the server; failures are kept in the repositories' state.
            LaunchedEffect(Unit) { container.membership.refresh(); container.catalog.refresh() }
            MainTabs(
                initialTab = start,
                onRoomReady = ::openRoom,
                onPublicRooms = { nav.navigate(PublicRoomsRoute) { launchSingleTop = true } },
                onShop = { nav.navigate(ShopRoute) { launchSingleTop = true } },
                onPlus = { nav.navigate(PlusRoute) { launchSingleTop = true } },
                onSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
                onAvatar = { nav.navigate(AvatarRoute) { launchSingleTop = true } },
                onProgress = { nav.navigate(ProgressRoute()) { launchSingleTop = true } },
                onTournaments = { nav.navigate(TournamentsRoute) { launchSingleTop = true } },
                social = SocialNav(
                    findFriends = { nav.navigate(FindFriendsRoute) { launchSingleTop = true } },
                    profile = { nav.navigate(UserProfileRoute(it)) { launchSingleTop = true } },
                    chat = { nav.navigate(ChatRoute(it)) { launchSingleTop = true } },
                    conversations = { nav.navigate(ConversationsRoute) { launchSingleTop = true } },
                    notifications = { nav.navigate(NotificationsRoute) { launchSingleTop = true } },
                ),
            )
        }
        composable<RoomRoute> { entry ->
            val route = entry.toRoute<RoomRoute>()
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).safeDrawingPadding()) {
                RoomHost(route.roomId, onExit = {
                    if (!nav.popBackStack<MainRoute>(inclusive = false)) nav.navigate(MainRoute())
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
        composable<ProgressRoute> { entry ->
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding().imePadding()) {
                com.loopa.feature.profile.ProgressHubScreen(onBack = { nav.popBackStack() }, initialTab = entry.toRoute<ProgressRoute>().tab)
            }
        }
        composable<TournamentsRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding().imePadding()) {
                com.loopa.feature.lobby.TournamentsScreen(onBack = { nav.popBackStack() }, onRoomReady = ::openRoom)
            }
        }
        composable<ShopRoute> {
            Box(Modifier.fillMaxSize().systemBarsPadding()) { ShopScreen(onBack = { nav.popBackStack() }) }
        }
        composable<SettingsRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding().imePadding()) {
                SettingsScreen(versionName = versionName, onLoggedOut = signedOut, onAccountSwitched = switched,
                    onOpenPlus = { nav.navigate(PlusRoute) { launchSingleTop = true } }, onBack = { nav.popBackStack() },
                    onOpenWhatsNew = { nav.navigate(WhatsNewRoute(versionName.substringBefore('-'))) { launchSingleTop = true } },
                    onOpenBlocked = { nav.navigate(BlockedRoute) { launchSingleTop = true } })
            }
        }
        composable<FindFriendsRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                FindFriendsScreen(onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate(UserProfileRoute(it)) })
            }
        }
        composable<UserProfileRoute> { entry ->
            val r = entry.toRoute<UserProfileRoute>()
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                UserProfileScreen(r.userId, onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(ChatRoute(it)) { launchSingleTop = true } })
            }
        }
        composable<ConversationsRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                ConversationsScreen(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(ChatRoute(it)) },
                    onFindFriends = { nav.navigate(FindFriendsRoute) })
            }
        }
        composable<ChatRoute> { entry ->
            val r = entry.toRoute<ChatRoute>()
            // Edge-to-edge: the chat input follows the keyboard (imePadding inside the screen).
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                ChatScreen(r.userId, onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate(UserProfileRoute(it)) { launchSingleTop = true } })
            }
        }
        composable<NotificationsRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                NotificationsScreen(onBack = { nav.popBackStack() }, onRoomReady = ::openRoom, onOpen = { t ->
                    when (t) {
                        is NotificationTarget.Chat -> nav.navigate(ChatRoute(t.userId))
                        is NotificationTarget.Profile -> nav.navigate(UserProfileRoute(t.userId))
                        NotificationTarget.Requests -> nav.navigate(MainRoute(tab = Tab.SOCIAL.name)) { popUpTo<MainRoute> { inclusive = true } }
                    }
                })
            }
        }
        composable<BlockedRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) { BlockedUsersScreen(onBack = { nav.popBackStack() }) }
        }
        composable<WhatsNewRoute>(
            enterTransition = { if (reduced) fadeIn(tween(0)) else fadeIn(tween(Motion.SLOW)) + androidx.compose.animation.scaleIn(tween(Motion.SLOW), initialScale = 0.96f) },
            popExitTransition = { fadeOut(tween(if (reduced) 0 else Motion.MEDIUM)) },
        ) { entry ->
            val r = entry.toRoute<WhatsNewRoute>()
            val notes = com.loopa.core.release.WhatsNew.notesFor(r.version) ?: com.loopa.core.release.WhatsNew.latestUpTo(r.version)
            // Only announce what this server really offers; offline, show everything that ships.
            val server by androidx.compose.runtime.produceState<Set<com.loopa.core.release.ServerFeature>?>(null) {
                value = container.social.serverFeatures() ?: com.loopa.core.release.ServerFeature.entries.toSet()
            }
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                val s = server
                if (notes == null) { LaunchedEffect(Unit) { nav.popBackStack() } }
                else if (s != null) WhatsNewScreen(notes.availableOn(s), onClose = { nav.popBackStack() })
            }
        }
        composable<AvatarRoute> {
            Box(Modifier.fillMaxSize().background(Loopa.colors.background).systemBarsPadding()) {
                AvatarEditorScreen(onOpenShop = { nav.navigate(ShopRoute) { launchSingleTop = true } }, onBack = { nav.popBackStack() })
            }
        }
    }
}

/** Social destinations opened from the tabs (pushed routes). */
data class SocialNav(
    val findFriends: () -> Unit = {},
    val profile: (String) -> Unit = {},
    val chat: (String) -> Unit = {},
    val conversations: () -> Unit = {},
    val notifications: () -> Unit = {},
)

@Composable
internal fun MainTabs(
    onRoomReady: (String) -> Unit,
    onPublicRooms: () -> Unit,
    onShop: () -> Unit,
    initialTab: Tab = Tab.HOME,
    onPlus: () -> Unit = {},
    onSettings: () -> Unit = {},
    onAvatar: () -> Unit = {},
    onProgress: () -> Unit = {},
    onTournaments: () -> Unit = {},
    social: SocialNav = SocialNav(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val container = com.loopa.core.data.rememberDataContainer()
    val badges by container.social.badges.collectAsStateWithLifecycle()
    // Edge-to-edge: the window is not resized for the keyboard, so the content handles IME insets.
    val imeVisible = WindowInsets.isImeVisible
    val holder = rememberSaveableStateHolder()
    val feedback = LocalFeedback.current
    val reduced = LoopaMotion.reduced
    val items = Tab.entries.map { NavItem(it.name, it.title, it.icon, it.selectedIcon, badge = if (it == Tab.SOCIAL) badges.socialTab else 0) }
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
                        onOpenNotifications = social.notifications,
                        notificationCount = badges.notifications,
                    )
                    Tab.GAMES -> GamesScreen(onBack = null, onRoomReady = onRoomReady, onOpenPlus = onPlus)
                    Tab.SOCIAL -> SocialScreen(
                        onPublicRooms = onPublicRooms, onOpenGames = { tab = Tab.GAMES }, onFindFriends = social.findFriends,
                        onOpenProfile = social.profile, onOpenChat = social.chat, onOpenConversations = social.conversations,
                        onOpenNotifications = social.notifications, onRoomReady = onRoomReady,
                    )
                    Tab.PROFILE -> ProfileScreen(onEditAvatar = onAvatar, onOpenShop = onShop, onOpenPlus = onPlus, onOpenSettings = onSettings,
                        onOpenProgress = onProgress, onOpenTournaments = onTournaments)
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

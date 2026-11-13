package com.loopa.core.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loopa.core.game.ServerClock
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.SupabaseConfig

/** Manual dependency container (kept deliberately small; no DI framework needed). */
class DataContainer(context: Context, val config: SupabaseConfig = SupabaseConfig.fromBuildConfig()) {
    val backend = LoopaBackend(config)
    val clock = ServerClock()
    val settings = SettingsRepository(context.applicationContext)
    val auth = AuthRepository(backend)
    val profiles = ProfileRepository(backend)
    val rooms = RoomRepository(backend)
    val membership = MembershipRepository(backend)
    val catalog = GameCatalogRepository(backend)
    val sessions = RoomSessionManager(backend, clock)
}

interface DataContainerProvider {
    val container: DataContainer
}

@Composable
fun rememberDataContainer(): DataContainer =
    (LocalContext.current.applicationContext as DataContainerProvider).container

/** Creates a ViewModel with access to the [DataContainer]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (DataContainer) -> VM,
): VM {
    val container = rememberDataContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

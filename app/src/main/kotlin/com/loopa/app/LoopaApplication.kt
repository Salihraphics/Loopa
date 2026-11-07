package com.loopa.app

import android.app.Application
import com.loopa.core.data.DataContainer
import com.loopa.core.data.DataContainerProvider
import com.loopa.core.ui.feedback.AndroidFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LoopaApplication : Application(), DataContainerProvider {
    override lateinit var container: DataContainer
        private set
    lateinit var feedback: AndroidFeedback
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = DataContainer(this)
        feedback = AndroidFeedback(this)
        appScope.launch {
            container.settings.settings.collect { s ->
                feedback.soundEnabled = s.soundEnabled
                feedback.vibrationEnabled = s.vibrationEnabled
                feedback.musicEnabled = s.musicEnabled
            }
        }
    }
}

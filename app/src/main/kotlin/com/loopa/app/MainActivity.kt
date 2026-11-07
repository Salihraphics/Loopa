package com.loopa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.loopa.app.navigation.LoopaRoot
import com.loopa.core.data.ThemeMode
import com.loopa.core.data.UserSettings
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.theme.LoopaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as LoopaApplication
        setContent {
            val settings by app.container.settings.settings.collectAsStateWithLifecycle(UserSettings(onboardingDone = true))
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            CompositionLocalProvider(LocalFeedback provides app.feedback) {
                LoopaTheme(darkTheme = dark) {
                    LoopaRoot(versionName = BuildConfig.VERSION_NAME)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as LoopaApplication).feedback.setMusicPlaying(false)
    }
}

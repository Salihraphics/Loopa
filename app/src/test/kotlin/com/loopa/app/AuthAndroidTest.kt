package com.loopa.app

import android.content.Intent
import android.net.Uri
import androidx.startup.AppInitializer
import androidx.test.core.app.ApplicationProvider
import com.loopa.core.data.AuthCallbackResult
import com.loopa.core.data.AuthRepository
import com.loopa.core.data.ProfileRepository
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.SupabaseConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Keycloak
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android-runtime checks for Google sign-in plumbing (Robolectric, real merged manifest,
 * real supabase-kt Android session storage on SharedPreferences). These do not replace a
 * test on a physical device: no real browser and no real Google consent are involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class)
class AuthAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun oauthRedirectOpensMainActivity() {
        val pm = context.packageManager
        fun resolve(uri: String) = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName), 0,
        ).map { it.activityInfo.name }
        assertEquals(listOf(MainActivity::class.java.name), resolve("loopa://auth-callback?code=abc"))
        assertEquals(listOf(MainActivity::class.java.name), resolve("loopa://auth-callback?error=access_denied&error_code=identity_already_exists"))
        assertTrue("other hosts are not claimed", resolve("loopa://something-else").isEmpty())
        assertTrue("https is not claimed", resolve("https://zxtrxbgphutuiizznakd.supabase.co/auth/v1/callback").isEmpty())
        // launchMode singleTask: the browser's return reaches the running activity via onNewIntent
        val info = pm.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java), 0)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals(LoopaBackend.OAUTH_REDIRECT, "loopa://auth-callback")
        // Session storage depends on these androidx.startup initializers being in the merged manifest.
        val provider = pm.getProviderInfo(android.content.ComponentName(context, "androidx.startup.InitializationProvider"), android.content.pm.PackageManager.GET_META_DATA)
        val meta = provider.metaData.keySet()
        assertTrue("settings initializer registered: $meta", "com.russhwolf.settings.SettingsInitializer" in meta)
        assertTrue("supabase initializer registered: $meta", "io.github.jan.supabase.auth.SupabaseInitializer" in meta)
    }

    private fun initSdkStorage() {
        // The SDK's androidx.startup initializer (internal class) captures the Context for its storage.
        // On a device androidx.startup runs these from the merged manifest (checked in
        // oauthRedirectOpensMainActivity); Robolectric does not start content providers, so run them here.
        for (name in listOf("com.russhwolf.settings.SettingsInitializer", "io.github.jan.supabase.auth.SupabaseInitializer")) {
            @Suppress("UNCHECKED_CAST")
            val initializer = Class.forName(name) as Class<out androidx.startup.Initializer<Any>>
            AppInitializer.getInstance(context).initializeComponent<Any>(initializer)
        }
    }

    @Test
    fun guestSessionSurvivesAppRestart() = runBlocking {
        val url = System.getProperty("loopa.e2e.url").orEmpty(); val key = System.getProperty("loopa.e2e.key").orEmpty()
        assumeTrue("LOOPA_E2E_URL / LOOPA_E2E_ANON_KEY not set", url.isNotBlank() && key.isNotBlank())
        initSdkStorage()
        val cfg = SupabaseConfig(url, key)
        val first = AuthRepository(LoopaBackend(cfg))           // default persistent Android storage
        first.signInAnonymously()
        val id = first.userId!!
        println("[ANDROID] signed in as $id; simulating app restart")
        // "restart": a brand-new SDK instance that only has what was written to device storage
        val restarted = LoopaBackend(cfg)
        restarted.client!!.auth.awaitInitialization()
        assertEquals("session restored from storage", id, restarted.currentUserId)
        val status = AuthRepository(restarted).accountStatus()
        assertNotNull("restored token is accepted by the server", status)
        assertEquals(id, status!!.userId)
        println("[ANDROID] restored session $id accepted by server (anonymous=${status.isAnonymous})")
        AuthRepository(restarted).signOut()
        val afterSignOut = LoopaBackend(cfg); afterSignOut.client!!.auth.awaitInitialization()
        assertEquals("sign-out clears storage", null, afterSignOut.currentUserId)
        println("TEMP_USER_ID=$id")
    }

    @Test
    fun linkCompletesAfterProcessDeathAndSurvivesRestart() = runBlocking {
        val url = System.getProperty("loopa.e2e.local.url").orEmpty(); val key = System.getProperty("loopa.e2e.local.key").orEmpty()
        assumeTrue("local stack with OIDC test provider not configured", url.isNotBlank() && key.isNotBlank())
        initSdkStorage()
        val cfg = SupabaseConfig(url, key)
        var opened: String? = null
        val before = LoopaBackend(cfg, oauthProvider = Keycloak, urlOpener = { opened = it })
        val auth1 = AuthRepository(before)
        auth1.signInAnonymously()
        val guestId = auth1.userId!!
        ProfileRepository(before).ensureProfile("Robo${guestId.take(4)}", null)
        auth1.linkGoogle()
        assertNotNull(opened)

        // The OS kills LOOPA while the browser is open: a new process, new SDK, nothing in memory.
        val reborn = LoopaBackend(cfg, oauthProvider = Keycloak)
        val callback = completeConsent(opened!!, "robo${System.currentTimeMillis() % 100000}")
        val result = AuthRepository(reborn).handleCallback(callback)
        assertEquals("link finished in the new process, same user", AuthCallbackResult.Linked(guestId), result)

        // Next app start: still the same, now non-anonymous, account.
        val nextStart = LoopaBackend(cfg, oauthProvider = Keycloak)
        nextStart.client!!.auth.awaitInitialization()
        assertEquals(guestId, nextStart.currentUserId)
        val status = AuthRepository(nextStart).accountStatus()!!
        assertFalse(status.isAnonymous)
        assertTrue("keycloak" in status.providers)
        assertNotNull("profile still there", ProfileRepository(nextStart).refresh())
        println("[ANDROID] link survived process death and restart: $guestId providers=${status.providers}")
    }

    /** Plays the browser through Supabase and the test identity provider until loopa://auth-callback. */
    private fun completeConsent(start: String, user: String): String {
        var next = start
        repeat(10) {
            var target = next.replace("host.docker.internal", "127.0.0.1")
            if (target.contains("/protocol/openid-connect/auth")) target += "&fake_user=$user"
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            val location = conn.getHeaderField("Location") ?: error("no redirect at $target (${conn.responseCode})")
            if (location.startsWith(LoopaBackend.OAUTH_REDIRECT)) return location
            next = if (location.startsWith("http")) location else URL(URL(target), location).toString()
        }
        error("too many redirects")
    }
}

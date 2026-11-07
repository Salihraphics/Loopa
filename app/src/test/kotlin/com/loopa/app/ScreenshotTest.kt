package com.loopa.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.loopa.core.data.DataContainer
import com.loopa.core.data.DataContainerProvider
import com.loopa.core.model.Answer
import com.loopa.core.model.BuiltInCategories
import com.loopa.core.model.JokerEvent
import com.loopa.core.model.JokerType
import com.loopa.core.model.Match
import com.loopa.core.model.Me
import com.loopa.core.model.OwnedJoker
import com.loopa.core.model.Room
import com.loopa.core.model.RoomPlayer
import com.loopa.core.model.RoomSettings
import com.loopa.core.model.RoomState
import com.loopa.core.model.Round
import com.loopa.core.model.RoundPhase
import com.loopa.core.model.RoundPlayer
import com.loopa.core.model.ScoreEntry
import com.loopa.core.ui.avatar.AvatarCatalog
import com.loopa.core.ui.avatar.AvatarConfig
import com.loopa.core.ui.avatar.AvatarSlot
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.LoopaLogo
import com.loopa.core.ui.components.LoopaWordmark
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.auth.OnboardingScreen
import com.loopa.feature.game.AnsweringView
import com.loopa.feature.game.LetterReveal
import com.loopa.feature.game.RevealView
import com.loopa.feature.game.RoundResultsView
import com.loopa.feature.game.VotingView
import com.loopa.feature.home.HomeScreen
import com.loopa.feature.lobby.LobbyScreen
import com.loopa.feature.lobby.PlayScreen
import com.loopa.feature.settings.SettingsScreen
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import kotlin.random.Random

/** Test application: real DataContainer, but no backend credentials (offline rendering). */
class ScreenshotApp : Application(), DataContainerProvider {
    override val container: DataContainer by lazy { DataContainer(this) }
}

/**
 * Renders real screens headlessly (Robolectric native graphics) to PNG files in
 * app/build/screenshots so the UI can be reviewed without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String, dark: Boolean = false, advanceMs: Long = 1_500, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { LoopaTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { content() } } }
        compose.mainClock.advanceTimeBy(advanceMs)
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ fixtures
    private fun iso(offsetMs: Long) = Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs).toString()

    private val names = listOf("Ayşe", "Barış", "Cem", "Deniz", "Elif")
    private val ids = names.indices.map { "u$it" }
    private fun avatar(i: Int): JsonObject = AvatarCatalog.random(Random(i * 7 + 3)).toJson()

    private val players = names.mapIndexed { i, n ->
        RoomPlayer(ids[i], n, avatar(i), level = 1 + i * 3, isReady = i % 3 != 2, isHost = i == 0, online = i != 4,
            role = if (i == 4) "spectator" else "player")
    }
    private val settings = RoomSettings(categories = BuiltInCategories.defaults, excludedLetters = listOf("J"))
    private val cats = BuiltInCategories.defaults.take(6)

    private fun round(phase: RoundPhase, startedIn: Long = -10_000) = Round(
        id = "r1", number = 2, letter = "K", categories = cats, phase = phase,
        startedAt = iso(startedIn), baseDeadline = iso(startedIn + 60_000),
        votingDeadline = iso(12_000), revealStartedAt = iso(-4_000), revealDeadline = iso(40_000), resultsDeadline = iso(6_000),
        firstSubmitterId = if (phase == RoundPhase.ANSWERING) null else ids[1],
        players = ids.take(4).mapIndexed { i, id ->
            RoundPlayer(id, deadlineAt = iso(listOf(42_000L, 27_000, 8_000, 50_000)[i]), submitted = i == 3,
                points = listOf(85, 110, 40, 60)[i], earlyBonus = if (i == 1) 5 else 0, isRoundWinner = i == 1,
                doubleCategory = if (i == 0) "isim" else null, jokerUsed = i == 2)
        },
        jokerEvents = listOf(JokerEvent("j1", ids[2], JokerType.SLOWDOWN, ids[0], 10, iso(-3_000))),
        answers = buildList {
            val words = mapOf(
                "isim" to listOf("Kerem", "Kerem", "Kaan", ""), "sehir" to listOf("Kayseri", "Konya", "Kars", "Kütahya"),
                "hayvan" to listOf("Kedi", "Kartal", "Kedi", "Koala"), "bitki" to listOf("Karanfil", "Kaktüs", "", "Kekik"),
                "esya" to listOf("Kalem", "Kaşık", "Kitap", "Kalem"), "ulke" to listOf("Kanada", "Katar", "Kenya", "Kanada"),
            )
            words.forEach { (k, list) ->
                list.forEachIndexed { i, w ->
                    val dup = w.isNotBlank() && list.count { it == w } > 1
                    add(Answer("$k$i", ids[i], k, w, w.lowercase(), validation = if (w.isBlank()) "empty" else if (k == "esya" && i == 2) "uncertain" else "valid",
                        finalStatus = when { phase == RoundPhase.VOTING && k == "esya" && i == 2 -> null; w.isBlank() -> "empty"; else -> "accepted" },
                        isDuplicate = dup, points = if (w.isBlank()) 0 else if (dup) 10 else 20, doubled = k == "isim" && i == 0,
                        votesCast = 1, votesNeeded = 3, votesAccept = 2, votesReject = 1))
                }
            }
            if (phase == RoundPhase.VOTING) add(Answer("v2", ids[1], "unlu", "Kenan Yıldız", "kenan yıldız", validation = "uncertain", votesNeeded = 3, votesCast = 2))
        },
    )

    private fun state(phase: RoundPhase?, me: Int = 0) = RoomState(
        serverTime = iso(0), me = Me(ids[me], if (me == 4) "spectator" else "player", "active"),
        room = Room("room1", "LOP7X2", ids[0], if (phase == null) "lobby" else "in_game", isPublic = false, settings = settings),
        players = players,
        match = if (phase == null) null else Match("m1", "in_progress", 5, 2, settings,
            scoreboard = ids.take(4).mapIndexed { i, id -> ScoreEntry(id, names[i], avatar(i), listOf(150, 205, 90, 120)[i], i % 2) },
            myJokers = listOf(OwnedJoker("j-a", JokerType.TIME_THIEF), OwnedJoker("j-b", JokerType.DOUBLE_POINTS))),
        round = phase?.let { round(it) },
    )

    // ------------------------------------------------------------------ shots
    @Test fun brand() = shot("01_brand") {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LoopaLogo(size = 200.dp)
            LoopaWordmark()
        }
    }

    @Test fun onboarding() = shot("02_onboarding", advanceMs = 2_000) { OnboardingScreen(onDone = {}) }

    @Test fun home() = shot("03_home") { HomeScreen({}, {}, {}, {}, {}) }

    @Test fun play() = shot("04_play") { PlayScreen(onRoomReady = {}, onPublicRooms = {}) }

    @Test fun lobby() = shot("05_lobby") { LobbyScreen("room1", state(null), onLeft = {}) }

    @Test fun letterReveal() = shot("06_letter_reveal") { LetterReveal(round(RoundPhase.ANSWERING, startedIn = 900), 900) }

    @Test fun answering() = shot("07_answering") {
        val s = state(RoundPhase.ANSWERING)
        AnsweringView(s, s.round!!, System.currentTimeMillis(), 42_000,
            mapOf("isim" to "Kerem", "sehir" to "Kayseri", "hayvan" to "Kedi", "bitki" to "Lale"), false, false, { _, _ -> }, {}, {}, { _, _, _ -> })
    }

    @Test fun answeringDark() = shot("08_answering_dark", dark = true) {
        val s = state(RoundPhase.ANSWERING)
        AnsweringView(s, s.round!!, System.currentTimeMillis(), 7_000, mapOf("isim" to "Kerem"), false, false, { _, _ -> }, {}, {}, { _, _, _ -> })
    }

    @Test fun voting() = shot("09_voting") {
        val s = state(RoundPhase.VOTING, me = 1)
        VotingView(s, s.round!!, System.currentTimeMillis(), emptySet()) { _, _ -> }
    }

    @Test fun reveal() = shot("10_reveal", advanceMs = 3_000) {
        val s = state(RoundPhase.REVEAL)
        RevealView(s, s.round!!, System.currentTimeMillis()) {}
    }

    @Test fun roundResults() = shot("11_round_results", advanceMs = 2_500) {
        val s = state(RoundPhase.RESULTS, me = 1)
        RoundResultsView(s, s.round!!, System.currentTimeMillis()) {}
    }

    @Test fun avatars() = shot("12_avatars") {
        Column(Modifier.padding(16.dp)) {
            Text("Avatar katmanları", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hats = listOf(null, "hat_cap", "hat_beanie", "hat_party", "hat_bucket", "hat_headphones", "hat_flower", "hat_wizard", "hat_crown")
                val hairs = listOf("hair_short", "hair_curly", "hair_long", "hair_bob", "hair_bun", "hair_spiky", "hair_ponytail", "hair_afro", "hair_mohawk")
                val outfits = listOf("outfit_tshirt", "outfit_hoodie", "outfit_stripes", "outfit_sweater", "outfit_overall", "outfit_jersey", "outfit_suit", "outfit_cape")
                val eyes = listOf("eyes_dot", "eyes_round", "eyes_happy", "eyes_wink", "eyes_sleepy", "eyes_star", "eyes_heart")
                val mouths = listOf("mouth_smile", "mouth_grin", "mouth_open", "mouth_flat", "mouth_tongue", "mouth_cat")
                val glasses = listOf(null, "glasses_round", "glasses_square", "glasses_sun", "glasses_star", "glasses_monocle")
                val bgs = listOf("bg_lavender", "bg_mint", "bg_peach", "bg_butter", "bg_sky", "bg_dots", "bg_stripes", "bg_stars", "bg_sunburst")
                val skins = AvatarCatalog.skinColors.keys.toList()
                val hcs = AvatarCatalog.hairColors.keys.toList()
                val bases = listOf("base_round", "base_oval", "base_square", "base_bean")
                val acc = listOf(null, "acc_blush", "acc_freckles", "acc_earrings", "acc_bowtie", "acc_scarf", "acc_bandaid", "acc_necklace")
                val frames = listOf(null, "frame_mint", "frame_loopa", "frame_gold", "frame_rainbow")
                val decos = listOf(null, "deco_sparkles", "deco_hearts", "deco_lightning", "deco_halo")
                repeat(30) { i ->
                    var c = AvatarConfig.DEFAULT
                    c = c.with(AvatarSlot.HAIR, hairs[i % hairs.size]).with(AvatarSlot.HAT, hats[i % hats.size])
                        .with(AvatarSlot.OUTFIT, outfits[i % outfits.size]).with(AvatarSlot.EYES, eyes[i % eyes.size])
                        .with(AvatarSlot.MOUTH, mouths[i % mouths.size]).with(AvatarSlot.GLASSES, glasses[(i / 2) % glasses.size])
                        .with(AvatarSlot.BACKGROUND, bgs[i % bgs.size]).with(AvatarSlot.SKIN, skins[i % skins.size])
                        .with(AvatarSlot.HAIR_COLOR, hcs[(i * 3) % hcs.size]).with(AvatarSlot.BASE, bases[i % bases.size])
                        .with(AvatarSlot.ACCESSORY, acc[(i / 3) % acc.size]).with(AvatarSlot.FRAME, frames[i % frames.size])
                        .with(AvatarSlot.DECORATION, decos[(i / 4) % decos.size])
                    AvatarView(c, size = 68.dp)
                }
            }
        }
    }

    @Test fun settings() = shot("13_settings") { SettingsScreen(versionName = "0.1.0", onLoggedOut = {}) }
}

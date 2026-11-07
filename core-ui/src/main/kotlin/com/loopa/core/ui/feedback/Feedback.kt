package com.loopa.core.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

enum class Sfx { TAP, JOIN, READY, LETTER, TICK, WARNING, SUBMIT, JOKER, REVEAL, VOTE, SCORE, WIN, LEVEL_UP, ERROR }
enum class Haptic { LIGHT, MEDIUM, SUCCESS, WARNING }

/** Sound + vibration facade. Every call is a no-op when the player disabled it in settings. */
interface LoopaFeedback {
    fun play(sfx: Sfx) {}
    fun haptic(kind: Haptic) {}
    fun setMusicPlaying(playing: Boolean) {}
}

object SilentFeedback : LoopaFeedback

val LocalFeedback = staticCompositionLocalOf<LoopaFeedback> { SilentFeedback }

/**
 * All sounds are synthesized at first launch into small WAV files (no
 * bundled audio assets, no licensing issues) and played via SoundPool.
 */
class AndroidFeedback(private val context: Context) : LoopaFeedback {
    @Volatile var soundEnabled = true
    @Volatile var vibrationEnabled = true
    @Volatile var musicEnabled = false
        set(value) { field = value; applyMusic() }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = java.util.concurrent.ConcurrentHashMap<Sfx, Int>()
    private val io = Executors.newSingleThreadExecutor()
    private var music: MediaPlayer? = null
    @Volatile private var musicWanted = false

    init {
        io.execute {
            val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
            Sfx.entries.forEach { sfx ->
                runCatching {
                    val f = File(dir, "${sfx.name.lowercase()}_v1.wav")
                    if (!f.exists()) writeWav(f, Synth.render(sfx))
                    ids[sfx] = pool.load(f.absolutePath, 1)
                }
            }
        }
    }

    override fun play(sfx: Sfx) {
        if (!soundEnabled) return
        val id = ids[sfx] ?: return
        val volume = if (sfx == Sfx.TAP || sfx == Sfx.TICK) 0.45f else 0.8f
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    override fun haptic(kind: Haptic) {
        if (!vibrationEnabled) return
        val vibrator = vibrator() ?: return
        val effect = when (kind) {
            Haptic.LIGHT -> VibrationEffect.createOneShot(12, 80)
            Haptic.MEDIUM -> VibrationEffect.createOneShot(28, 160)
            Haptic.SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 40), intArrayOf(0, 180, 0, 255), -1)
            Haptic.WARNING -> VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), intArrayOf(0, 200, 0, 200), -1)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    override fun setMusicPlaying(playing: Boolean) {
        musicWanted = playing
        applyMusic()
    }

    private fun applyMusic() {
        io.execute {
            val shouldPlay = musicEnabled && musicWanted
            if (shouldPlay) {
                val mp = music ?: runCatching {
                    val f = File(context.cacheDir, "sfx/music_loop_v1.wav")
                    if (!f.exists()) writeWav(f, Synth.musicLoop())
                    MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        setDataSource(f.absolutePath)
                        isLooping = true
                        setVolume(0.25f, 0.25f)
                        prepare()
                    }
                }.getOrNull().also { music = it }
                if (mp != null && !mp.isPlaying) runCatching { mp.start() }
            } else {
                music?.let { runCatching { if (it.isPlaying) it.pause() } }
            }
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int = Synth.RATE) {
        val dataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }
        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { body.putShort(it) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(header.array()); it.write(body.array()) }
        tmp.renameTo(file)
    }
}

/** Tiny additive synthesizer for friendly, rounded game sounds. */
internal object Synth {
    const val RATE = 22_050

    private data class Note(val freq: Double, val start: Double, val dur: Double, val gain: Double = 0.6, val wave: Int = 0)

    private fun notes(sfx: Sfx): List<Note> = when (sfx) {
        Sfx.TAP -> listOf(Note(880.0, 0.0, 0.05, 0.35))
        Sfx.JOIN -> listOf(Note(523.3, 0.0, 0.09), Note(784.0, 0.08, 0.14))
        Sfx.READY -> listOf(Note(659.3, 0.0, 0.08), Note(987.8, 0.07, 0.16))
        Sfx.LETTER -> listOf(Note(392.0, 0.0, 0.1), Note(523.3, 0.1, 0.1), Note(659.3, 0.2, 0.1), Note(1046.5, 0.3, 0.3, 0.7))
        Sfx.TICK -> listOf(Note(1200.0, 0.0, 0.03, 0.3, 1))
        Sfx.WARNING -> listOf(Note(740.0, 0.0, 0.09, 0.5, 1), Note(740.0, 0.14, 0.09, 0.5, 1))
        Sfx.SUBMIT -> listOf(Note(587.3, 0.0, 0.08), Note(880.0, 0.07, 0.08), Note(1174.7, 0.14, 0.2))
        Sfx.JOKER -> (0 until 6).map { Note(500.0 + it * 140, it * 0.035, 0.12, 0.45) }
        Sfx.REVEAL -> listOf(Note(698.5, 0.0, 0.12, 0.5))
        Sfx.VOTE -> listOf(Note(440.0, 0.0, 0.06), Note(660.0, 0.05, 0.1))
        Sfx.SCORE -> listOf(Note(1318.5, 0.0, 0.07, 0.4), Note(1760.0, 0.05, 0.1, 0.35))
        Sfx.WIN -> listOf(Note(523.3, 0.0, 0.14), Note(659.3, 0.13, 0.14), Note(784.0, 0.26, 0.14), Note(1046.5, 0.39, 0.45, 0.7))
        Sfx.LEVEL_UP -> listOf(Note(392.0, 0.0, 0.1), Note(587.3, 0.09, 0.1), Note(784.0, 0.18, 0.1), Note(1174.7, 0.27, 0.4, 0.7))
        Sfx.ERROR -> listOf(Note(330.0, 0.0, 0.12, 0.5, 1), Note(247.0, 0.11, 0.18, 0.5, 1))
    }

    fun render(sfx: Sfx): ShortArray = mix(notes(sfx))

    /** ~12 s soft pentatonic loop for menus. */
    fun musicLoop(): ShortArray {
        val scale = doubleArrayOf(261.6, 293.7, 329.6, 392.0, 440.0, 523.3)
        val pattern = intArrayOf(0, 2, 4, 3, 5, 4, 2, 1, 0, 3, 4, 5, 4, 2, 3, 1)
        val beat = 0.375
        val list = mutableListOf<Note>()
        repeat(2) { bar ->
            pattern.forEachIndexed { i, idx ->
                val t = (bar * pattern.size + i) * beat
                list += Note(scale[idx] * 2, t, beat * 0.9, 0.18)
                if (i % 4 == 0) list += Note(scale[pattern[(i + bar) % pattern.size]] / 2, t, beat * 3.6, 0.16)
            }
        }
        return mix(list, totalSeconds = 2 * pattern.size * beat)
    }

    private fun mix(notes: List<Note>, totalSeconds: Double? = null): ShortArray {
        val total = totalSeconds ?: (notes.maxOf { it.start + it.dur } + 0.05)
        val n = (total * RATE).toInt()
        val buf = DoubleArray(n)
        for (note in notes) {
            val s0 = (note.start * RATE).toInt()
            val len = (note.dur * RATE).toInt()
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx >= n) break
                val t = i.toDouble() / RATE
                val attack = min(1.0, t / 0.006)
                val env = attack * exp(-4.2 * t / note.dur)
                val ph = 2 * PI * note.freq * t
                val v = if (note.wave == 1) {
                    sin(ph) + 0.3 * sin(3 * ph)
                } else {
                    sin(ph) + 0.25 * sin(2 * ph) + 0.08 * sin(4 * ph)
                }
                buf[idx] += v * env * note.gain
            }
        }
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1.0)
        return ShortArray(n) { ((buf[it] / peak) * 0.85 * Short.MAX_VALUE).toInt().toShort() }
    }
}

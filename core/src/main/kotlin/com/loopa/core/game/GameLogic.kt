package com.loopa.core.game

import com.loopa.core.model.Answer
import com.loopa.core.model.Round
import com.loopa.core.model.RoundPhase
import java.time.OffsetDateTime
import kotlin.math.max

/** Mirrors `public.normalize_answer` in the database (used for UI grouping only). */
object TurkishText {
    private val upper = "ABCÇDEFGĞHIİJKLMNOÖPQRSŞTUÜVWXYZÂÎÛâîû"
    private val lower = "abcçdefgğhıijklmnoöpqrsştuüvwxyzaiuaiu"
    private val whitespace = Regex("\\s+")

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val i = upper.indexOf(ch)
            sb.append(if (i >= 0) lower[i] else ch)
        }
        return sb.toString().trim().replace(whitespace, " ")
    }

    private const val FOLD_FROM = "çğıöşüâîûéèêëáàäãåíìïóòôõúùñýÿœæß"
    private const val FOLD_TO = "cgiosuaiueeeeaaaaaiiioooouunyyoas"

    /** Mirrors `dict_fold`: lower-case and strip Turkish/Latin diacritics (ş->s, ı->i …). */
    fun fold(input: String): String {
        val n = normalize(input).lowercase()
        val sb = StringBuilder(n.length)
        for (ch in n) {
            val i = FOLD_FROM.indexOf(ch)
            sb.append(if (i >= 0) FOLD_TO[i] else ch)
        }
        return sb.toString()
    }

    /** Mirrors `dict_key`: spelling-insensitive matching key. */
    fun key(input: String): String = fold(input).filter { it in 'a'..'z' || it in '0'..'9' }

    /** Same tolerant first-letter rule the server uses before sending an answer to a vote. */
    fun startsWithLetter(answer: String, letter: String): Boolean {
        val a = normalize(answer).lowercase().firstOrNull { it.isLetterOrDigit() } ?: return true
        return fold(a.toString()) == fold(letter)
    }

    /** Turkish upper-casing for display (i -> İ, ı -> I). */
    fun upper(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val i = lower.indexOf(ch)
            sb.append(if (i in 0 until 32) upper[i] else ch.uppercaseChar())
        }
        return sb.toString()
    }

    val LETTERS: List<String> = listOf(
        "A", "B", "C", "Ç", "D", "E", "F", "G", "H", "I", "İ", "J", "K", "L", "M",
        "N", "O", "Ö", "P", "R", "S", "Ş", "T", "U", "Ü", "V", "Y", "Z",
    )
}

/** Mirrors `xp_for_level` / `level_for_xp` in the database. */
object LevelCurve {
    /** Total XP required to reach [level]. Level L -> L+1 costs 100 + 50*(L-1). */
    fun xpForLevel(level: Int): Int {
        val l = max(level, 1) - 1
        return 100 * l + 25 * l * (l - 1)
    }

    fun levelForXp(xp: Int): Int {
        var l = 1
        while (xpForLevel(l + 1) <= xp && l < 999) l++
        return l
    }

    data class Progress(val level: Int, val intoLevel: Int, val levelSpan: Int) {
        val fraction: Float get() = if (levelSpan == 0) 0f else intoLevel.toFloat() / levelSpan
        val remaining: Int get() = levelSpan - intoLevel
    }

    fun progress(xp: Int): Progress {
        val level = levelForXp(xp)
        val start = xpForLevel(level)
        val next = xpForLevel(level + 1)
        return Progress(level, xp - start, next - start)
    }
}

object Timestamps {
    /** Parses a Postgres/ISO-8601 timestamp with offset into epoch milliseconds. */
    fun parse(iso: String?): Long? = iso?.let {
        runCatching { OffsetDateTime.parse(it.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull()
    }
}

/**
 * Estimates the offset between the device clock and the database clock.
 * Visual countdowns use [now]; the server remains the source of truth.
 */
class ServerClock(private val deviceClock: () -> Long = System::currentTimeMillis) {
    @Volatile var offsetMs: Long = 0L
        private set
    @Volatile private var bestRtt: Long = Long.MAX_VALUE
    @Volatile private var samples = 0

    fun now(): Long = deviceClock() + offsetMs

    /** Feed one round-trip sample: request sent at [sentAt], response at [receivedAt], server time [serverMs]. */
    fun addSample(sentAt: Long, receivedAt: Long, serverMs: Long) {
        val rtt = (receivedAt - sentAt).coerceAtLeast(0)
        val estimate = serverMs + rtt / 2 - receivedAt
        samples++
        // Prefer low-latency samples, but let the estimate drift slowly so clock changes are absorbed.
        if (rtt <= bestRtt || samples <= 2) {
            bestRtt = rtt
            offsetMs = estimate
        } else {
            offsetMs = (offsetMs * 9 + estimate) / 10
        }
        if (samples % 20 == 0) bestRtt = Long.MAX_VALUE
    }
}

/** Helpers to read timing out of an authoritative [Round] snapshot. */
object RoundTiming {
    fun remainingMs(round: Round, userId: String, nowMs: Long): Long {
        val deadline = Timestamps.parse(round.player(userId)?.deadlineAt ?: round.baseDeadline) ?: return 0
        return (deadline - nowMs).coerceAtLeast(0)
    }

    fun startsInMs(round: Round, nowMs: Long): Long =
        ((Timestamps.parse(round.startedAt) ?: nowMs) - nowMs).coerceAtLeast(0)

    /** Earliest instant at which the server could advance this round; used to schedule ticks. */
    fun nextTransitionAt(round: Round): Long? = when (round.phase) {
        RoundPhase.ANSWERING -> round.players.filter { !it.submitted }
            .mapNotNull { Timestamps.parse(it.deadlineAt) }.maxOrNull()?.plus(1_600)
            ?: Timestamps.parse(round.startedAt)
        RoundPhase.VOTING -> Timestamps.parse(round.votingDeadline)
        RoundPhase.REVEAL -> Timestamps.parse(round.revealDeadline)
        RoundPhase.RESULTS -> Timestamps.parse(round.resultsDeadline)
        RoundPhase.FINISHED -> null
    }

    /** Index of the category currently shown in the synchronized reveal, or categories.size when done. */
    fun revealIndex(round: Round, nowMs: Long): Int {
        val start = Timestamps.parse(round.revealStartedAt) ?: return 0
        val end = Timestamps.parse(round.revealDeadline) ?: return 0
        val n = round.categories.size
        if (n == 0) return 0
        val step = ((end - start - 1_500).coerceAtLeast(1)) / n
        return ((nowMs - start) / step).toInt().coerceIn(0, n)
    }

    fun revealStepMs(round: Round): Long {
        val start = Timestamps.parse(round.revealStartedAt) ?: return 3_000
        val end = Timestamps.parse(round.revealDeadline) ?: return 3_000
        val n = round.categories.size.coerceAtLeast(1)
        return ((end - start - 1_500).coerceAtLeast(1)) / n
    }
}

/** Groups identical normalized answers for the reveal screen. */
object AnswerGrouping {
    data class Group(val normalized: String, val display: String, val answers: List<Answer>)

    fun group(answers: List<Answer>): List<Group> = answers
        .groupBy {
            when {
                it.normalized.isBlank() -> "\u0000" + it.userId
                it.groupKey != null && it.accepted -> it.groupKey
                else -> "n" + it.normalized
            }
        }
        .map { (key, list) -> Group(key, list.first().text, list) }
        .sortedWith(compareByDescending<Group> { it.answers.any { a -> a.accepted } }.thenByDescending { it.answers.size })
}

/** Error codes raised by the backend as `LOOPA:<CODE>`. */
enum class LoopaError(val message: String) {
    NOT_AUTHENTICATED("Oturum bulunamadı. Lütfen tekrar dene."),
    NO_PROFILE("Önce bir profil oluşturmalısın."),
    INVALID_USERNAME("Kullanıcı adı 3-16 karakter olmalı ve yalnızca harf, rakam, boşluk, nokta, tire içermeli."),
    UNKNOWN_COSMETIC("Bu kozmetik bulunamadı."),
    COSMETIC_LOCKED("Bu kozmetik henüz açılmadı."),
    LEVEL_TOO_LOW("Bu öğe için seviyen yetersiz."),
    ROOM_NOT_FOUND("Oda bulunamadı. Kodu kontrol et."),
    ROOM_FULL("Oda dolu."),
    KICKED("Bu odadan çıkarıldın."),
    GAME_IN_PROGRESS("Oyun devam ediyor."),
    NOT_HOST("Bu işlemi yalnızca oda sahibi yapabilir."),
    NOT_IN_ROOM("Bu odada değilsin."),
    HOST_MUST_PLAY("Oda sahibi izleyici olamaz."),
    SPECTATORS_DISABLED("Bu odada izleyici modu kapalı."),
    INVALID_SETTINGS("Geçersiz oda ayarı."),
    MAX_BELOW_PLAYERS("Oyuncu sınırı mevcut oyuncu sayısının altında olamaz."),
    INVALID_CATEGORIES("Geçersiz kategori seçimi."),
    INVALID_CATEGORY_NAME("Kategori adı 2-20 karakter olmalı."),
    TOO_FEW_CATEGORIES("En az 3 kategori seçmelisin."),
    TOO_MANY_CATEGORIES("En fazla 12 kategori (6 özel) seçebilirsin."),
    TOO_MANY_EXCLUDED_LETTERS("Çok fazla harf hariç tutuldu."),
    CANNOT_KICK_SELF("Kendini odadan çıkaramazsın."),
    PLAYER_NOT_FOUND("Oyuncu bulunamadı."),
    NOT_ENOUGH_PLAYERS("Başlamak için en az 2 oyuncu gerekli."),
    PLAYERS_NOT_READY("Tüm oyuncular hazır olmalı."),
    CODE_GENERATION_FAILED("Oda kodu üretilemedi, tekrar dene."),
    ROUND_NOT_FOUND("Tur bulunamadı."),
    NOT_A_PARTICIPANT("Bu turda oyuncu değilsin."),
    ANSWERS_LOCKED("Cevapların kilitlendi."),
    ROUND_NOT_STARTED("Tur henüz başlamadı."),
    TIME_UP("Süre doldu."),
    INVALID_ANSWERS("Geçersiz cevap verisi."),
    JOKER_NOT_NOW("Joker şu anda kullanılamaz."),
    JOKER_NOT_OWNED("Bu joker sende değil."),
    JOKER_ALREADY_USED_THIS_ROUND("Bu turda zaten bir joker kullandın."),
    INVALID_TARGET("Geçersiz hedef oyuncu."),
    JOKER_FINAL_SECONDS("Yavaşlatma son 5 saniyede kullanılamaz."),
    TARGET_ALREADY_SLOWED("Bu oyuncu bu tur zaten yavaşlatıldı."),
    INVALID_CATEGORY("Geçersiz kategori."),
    ANSWER_NOT_FOUND("Cevap bulunamadı."),
    VOTING_CLOSED("Oylama kapandı."),
    CANNOT_VOTE_OWN("Kendi cevabına oy veremezsin."),
    ALREADY_VOTED("Bu cevaba zaten oy verdin."),
    MATCH_NOT_FOUND("Maç bulunamadı."),
    ANONYMOUS_DISABLED("Sunucuda misafir (anonim) girişi kapalı. Supabase → Authentication → Anonymous sign-ins açılmalı."),
    AUTH_RATE_LIMITED("Çok fazla giriş denemesi. Lütfen biraz sonra tekrar dene."),
    IDENTITY_ALREADY_LINKED("Bu Google hesabı zaten başka bir LOOPA hesabına bağlı. Misafir hesabın olduğu gibi korunuyor."),
    MANUAL_LINKING_DISABLED("Sunucuda hesap bağlama kapalı (Supabase → Authentication → Manual linking)."),
    GOOGLE_NOT_CONFIGURED("Google ile giriş henüz sunucuda yapılandırılmadı."),
    AUTH_CANCELLED("Google girişi iptal edildi."),
    OAUTH_FAILED("Google girişi tamamlanamadı. Lütfen tekrar dene."),
    NOT_SIGNED_IN("Önce oturum açmalısın."),
    NETWORK("Bağlantı hatası. İnternetini kontrol et."),
    NOT_CONFIGURED("Sunucu yapılandırılmamış."),
    UNKNOWN("Beklenmeyen bir hata oluştu.");

    companion object {
        private val pattern = Regex("LOOPA:([A-Z_]+)")
        /** Maps GoTrue error codes from an OAuth redirect (`error_code` / `error` query parameters). */
        fun fromOAuthError(code: String?, description: String?): LoopaError {
            val text = listOfNotNull(code, description).joinToString(" ").lowercase()
            return when {
                "identity_already_exists" in text || "already linked" in text -> IDENTITY_ALREADY_LINKED
                "manual_linking_disabled" in text || "manual linking" in text -> MANUAL_LINKING_DISABLED
                "provider_disabled" in text || "unsupported provider" in text || "provider is not enabled" in text -> GOOGLE_NOT_CONFIGURED
                "access_denied" in text || "cancel" in text -> AUTH_CANCELLED
                else -> OAUTH_FAILED
            }
        }

        /** Maps Supabase Auth error codes (GoTrue) that can appear in exception messages. */
        fun fromAuthMessage(message: String?): LoopaError? = when {
            message == null -> null
            message.contains("identity_already_exists") || message.contains("already linked", ignoreCase = true) -> IDENTITY_ALREADY_LINKED
            message.contains("manual_linking_disabled") || message.contains("Manual linking is disabled", ignoreCase = true) -> MANUAL_LINKING_DISABLED
            message.contains("Unsupported provider", ignoreCase = true) || message.contains("provider is not enabled", ignoreCase = true) -> GOOGLE_NOT_CONFIGURED
            message.contains("anonymous_provider_disabled") || message.contains("Anonymous sign-ins are disabled", ignoreCase = true) -> ANONYMOUS_DISABLED
            message.contains("over_request_rate_limit") || message.contains("rate limit", ignoreCase = true) -> AUTH_RATE_LIMITED
            else -> null
        }

        fun fromMessage(message: String?): LoopaError? {
            val code = message?.let { pattern.find(it)?.groupValues?.get(1) } ?: return null
            return entries.firstOrNull { it.name == code } ?: UNKNOWN
        }
    }
}

class LoopaException(val error: LoopaError, cause: Throwable? = null) : Exception(error.message, cause)

package com.loopa.core.game

import com.loopa.core.model.CatalogMode
import com.loopa.core.model.GameCatalog

/** Catalogue category of a game. Only categories used by at least one game are shown. */
enum class GameCategory(val label: String) {
    KELIME("Kelime"), PARTI("Parti"), BILGI("Bilgi"), CIZIM("Çizim");
}

/**
 * Preferred screen orientation of a game. All current games are verified in portrait and adapt
 * to landscape, so they are FLEXIBLE; a future game can declare PORTRAIT or LANDSCAPE here.
 */
enum class GameOrientation { PORTRAIT, LANDSCAPE, FLEXIBLE }

/** Static, client-side description of the games. Access always comes from the server catalogue. */
data class GameModeInfo(
    val id: String,
    val name: String,
    val tagline: String,
    val players: String,
    val category: GameCategory = GameCategory.PARTI,
    /** App version that added the game; drives the "Yeni" section. */
    val addedIn: String = "0.5.0",
    val orientation: GameOrientation = GameOrientation.FLEXIBLE,
    /** Played in the same room, out loud (no voice or camera in the app). */
    val sameRoom: Boolean = false,
) {
    /** Minimum players, read from [players] ("3-10 oyuncu"); the server enforces the real limit. */
    val minPlayers: Int get() = players.substringBefore('-').trim().toIntOrNull() ?: 2
}

object GameModes {
    const val ISIM_SEHIR = "isim_sehir"

    /**
     * Games this app version can actually play. A mode the server marks as available but that is
     * not in this set is shown as "Yakında" and can never be started from this client.
     */
    val implemented: Set<String> by lazy { all.map { it.id }.toSet() }

    val all: List<GameModeInfo> = listOf(
        GameModeInfo(ISIM_SEHIR, "İsim Şehir", "Harf gelsin, kategorileri en hızlı sen doldur.", "2-10 oyuncu", GameCategory.KELIME, addedIn = "0.1.0"),
        GameModeInfo("gizli_kelime", "Gizli Kelime", "Kelimeyi sözlü anlat, takımın bilsin. Aynı ortamda oynanır.", "3-10 oyuncu", GameCategory.PARTI, sameRoom = true),
        GameModeInfo("kelime_zinciri", "Kelime Zinciri", "Son harften yeni kelime türet, zinciri koparma.", "2-10 oyuncu", GameCategory.KELIME),
        GameModeInfo("bilgi_arenasi", "Bilgi Arenası", "Soruları en hızlı ve doğru cevaplayan kazanır.", "2-10 oyuncu", GameCategory.BILGI),
        GameModeInfo("iki_dogru_bir_yalan", "İki Doğru Bir Yalan", "Üç cümleden hangisi yalan? Bul bakalım.", "3-10 oyuncu", GameCategory.PARTI),
        GameModeInfo("ciz_tahmin", "Çiz ve Tahmin Et", "Sen çiz, diğerleri tahmin etsin.", "3-10 oyuncu", GameCategory.CIZIM),
        GameModeInfo("en_muhtemel_kim", "En Muhtemel Kim?", "Kim en çok yapar? Oyla, sonuçlara gül.", "3-10 oyuncu", GameCategory.PARTI),
        GameModeInfo("anlat_bakalim", "Anlat Bakalım", "Yasaklı kelimeleri kullanmadan sözlü anlat. Aynı ortamda oynanır.", "4-10 oyuncu", GameCategory.KELIME, sameRoom = true),
        GameModeInfo("cizim_telefonu", "Çizim Telefonu", "Yaz, çiz, tahmin et: zincir nereye varacak?", "4-10 oyuncu", GameCategory.CIZIM),
        GameModeInfo("sessiz_sinema", "Sessiz Sinema", "Konuşmadan anlat. Aynı ortamda oynanır, kamera yok.", "4-10 oyuncu", GameCategory.PARTI, sameRoom = true),
    )

    /** Categories that at least one game uses, in catalogue order. */
    val categories: List<GameCategory> get() = GameCategory.entries.filter { c -> all.any { it.category == c } }

    /** Search by name or tagline, Turkish case- and accent-insensitive. */
    fun search(query: String, category: GameCategory? = null, games: List<GameModeInfo> = all): List<GameModeInfo> {
        val q = TurkishText.key(query)
        return games.filter { g ->
            (category == null || g.category == category) &&
                (q.isEmpty() || TurkishText.key(g.name).contains(q) || TurkishText.key(g.tagline).contains(q))
        }
    }

    /** Games added in the newest release (empty when every game is old). */
    fun newest(): List<GameModeInfo> {
        val latest = all.maxOf { it.addedIn }
        return all.filter { it.addedIn == latest && latest != all.minOf { m -> m.addedIn } }
    }

    fun info(id: String?): GameModeInfo = all.firstOrNull { it.id == id } ?: all.first()
    fun name(id: String?): String = info(id).name
}

/** What a game card shows and allows. */
enum class GameCardKind { PLAYABLE, COMING_SOON, LOCKED_PLUS, UNAVAILABLE }

data class GameCardStatus(
    val kind: GameCardKind,
    /** Short label on the card ("Her zaman ücretsiz", "Yakında", "Plus" …). */
    val badge: String,
    /** One or two sentences explaining the state, especially why a game is locked. */
    val explanation: String,
    /** True only when this client can create a room for the game right now. */
    val canStart: Boolean,
    /** Free in the current rotation week (only when the server says the rotation is active). */
    val freeThisWeek: Boolean = false,
    /** Free in next week's rotation (preview). */
    val freeNextWeek: Boolean = false,
)

object GameCards {
    /**
     * Card state of [info] from the server catalogue. [catalog] null means the catalogue could not
     * be loaded (offline or an older server): then nothing is claimed about access, except that
     * İsim Şehir is always free.
     */
    fun status(info: GameModeInfo, catalog: GameCatalog?, implemented: Set<String> = GameModes.implemented): GameCardStatus {
        val mode: CatalogMode? = catalog?.mode(info.id)
        val rotation = catalog?.rotation
        val thisWeek = rotation?.active == true && info.id in rotation.freeModes
        val nextWeek = rotation?.active == true && rotation.nextWeekSlot != null && info.id in rotation.nextFreeModes
        val isIsimSehir = info.id == GameModes.ISIM_SEHIR

        if (isIsimSehir && (mode == null || mode.access.allowed)) {
            return GameCardStatus(GameCardKind.PLAYABLE, "Her zaman ücretsiz",
                "Herkes için her hafta ücretsiz.", canStart = true)
        }
        val isImplemented = info.id in implemented
        if (!isImplemented || mode?.status == "coming_soon") {
            val when_ = when {
                thisWeek -> " Yayınlandığında bu hafta ücretsiz oyunlar arasında olacak."
                nextWeek -> " Gelecek hafta ücretsiz oyunlar arasında."
                else -> ""
            }
            return GameCardStatus(GameCardKind.COMING_SOON, "Yakında",
                "Bu oyun henüz hazırlanıyor; şimdilik başlatılamaz.$when_",
                canStart = false, freeThisWeek = thisWeek, freeNextWeek = nextWeek)
        }
        if (mode == null) {
            return GameCardStatus(GameCardKind.UNAVAILABLE, "Bilgi yok",
                "Erişim bilgisi sunucudan alınamadı. Bağlantını kontrol edip tekrar dene.", canStart = false)
        }
        if (mode.access.allowed) {
            val badge = when (mode.access.unlockedBy) {
                "always_free" -> "Her zaman ücretsiz"
                "rotation" -> "Bu hafta ücretsiz"
                "admin" -> "Admin"
                else -> if (mode.status == "early_access") "Erken erişim" else "Plus"
            }
            val text = when (mode.access.unlockedBy) {
                "rotation" -> "Bu hafta herkes için ücretsiz."
                "admin" -> "Admin hesabınla erişebilirsin."
                else -> "LOOPA Plus üyeliğinle erişebilirsin."
            }
            return GameCardStatus(GameCardKind.PLAYABLE, badge, text, canStart = true,
                freeThisWeek = thisWeek, freeNextWeek = nextWeek)
        }
        if (mode.access.reason == "PLUS_REQUIRED") {
            val text = buildString {
                append(if (mode.status == "early_access") "Erken erişimde: yalnızca LOOPA Plus üyeleri oda kurabilir."
                       else "Bu hafta ücretsiz oyunlar arasında değil. LOOPA Plus ile her hafta oda kurabilirsin.")
                if (nextWeek) append(" Gelecek hafta herkese ücretsiz!")
                append(" Bir Plus üyesinin odasına katılarak da oynayabilirsin.")
            }
            return GameCardStatus(GameCardKind.LOCKED_PLUS, "Plus", text, canStart = false, freeNextWeek = nextWeek)
        }
        return GameCardStatus(GameCardKind.UNAVAILABLE, "Kapalı", "Bu oyun şu anda kullanılamıyor.", canStart = false)
    }
}

/** Display helpers for server timestamps. Only formats; never computes durations or validity. */
object PlusFormat {
    private val tr = java.util.Locale.forLanguageTag("tr-TR")
    private val dateFmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", tr)
    private val dateTimeFmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", tr)

    private fun parse(iso: String?, zone: java.time.ZoneId): java.time.ZonedDateTime? =
        iso?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }

    fun date(iso: String?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? = parse(iso, zone)?.format(dateFmt)
    fun dateTime(iso: String?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? = parse(iso, zone)?.format(dateTimeFmt)
}

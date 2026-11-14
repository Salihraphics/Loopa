package com.loopa.core.release

/**
 * "What's New" content for every release, in one place. Adding the notes of a new version is one
 * more [ReleaseNotes] entry in [WhatsNew.releases]; the screen, the paging and the "seen" logic
 * are shared. Only list what really ships in that version.
 */
enum class WhatsNewHero { SOCIAL, GAMES, GENERIC }

/** Illustration of a feature card (drawn from the palette by the app). */
enum class FeatureArt { FRIENDS, SEARCH, INVITE, CHAT, BELL, PRIVACY, GAMES, SPARK }

/** Palette accent of a card; resolved to a LOOPA pastel by the UI. */
enum class Accent { LAVENDER, MINT, PEACH, BUTTER, SKY }

/** Backend capabilities a feature depends on. Features whose backend is missing are not announced. */
enum class ServerFeature { SOCIAL, PLATFORM_080 }

data class WhatsNewFeature(
    val title: String,
    val description: String,
    val art: FeatureArt,
    val accent: Accent,
    val requires: ServerFeature? = null,
)

data class ReleaseNotes(
    val versionName: String,
    val title: String,
    val subtitle: String,
    val hero: WhatsNewHero,
    val features: List<WhatsNewFeature>,
    val ctaText: String = "Keşfetmeye başla",
) {
    /** The notes without features that need a backend capability this server does not have. */
    fun availableOn(server: Set<ServerFeature>): ReleaseNotes = copy(features = features.filter { it.requires == null || it.requires in server })
}

sealed interface WhatsNewDecision {
    /** Show these notes (and remember the current version as seen). */
    data class Show(val notes: ReleaseNotes) : WhatsNewDecision
    /** Nothing to show for this version, but remember it as seen (fresh install, patch without notes). */
    data object MarkSeen : WhatsNewDecision
    /** Already seen: do nothing. */
    data object None : WhatsNewDecision
}

object WhatsNew {
    val releases: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            versionName = "0.8.0",
            title = "9 yeni oyun ve LOOPA Coin!",
            subtitle = "Tavla, 101 Okey, Kurt Adam, Krallıklar ve daha fazlası. Görevler, seriler, turnuvalar.",
            hero = WhatsNewHero.GAMES,
            features = listOf(
                WhatsNewFeature("Masa ve kart oyunları", "Tavla, 101 Okey, Kızma Birader, Amiral Battı (1v1 / 2v2), 21 (bahis yok) ve Milyoner.",
                    FeatureArt.GAMES, Accent.PEACH, ServerFeature.PLATFORM_080),
                WhatsNewFeature("Renk Kartları", "TEMİZLE, AYNA, GAMBLE ve ELİNİ GÖRELİM kartlarıyla özgün bir renk eşleme oyunu.",
                    FeatureArt.SPARK, Accent.BUTTER, ServerFeature.PLATFORM_080),
                WhatsNewFeature("Kurt Adam ve Krallıklar", "6-20 kişilik moderatörsüz Kurt Adam; 4-20 kişilik altıgen haritada strateji ve diplomasi.",
                    FeatureArt.GAMES, Accent.LAVENDER, ServerFeature.PLATFORM_080),
                WhatsNewFeature("LOOPA Coin, görevler, seriler", "Maçlardan ve günlük / haftalık görevlerden coin kazan, kozmetik al. Galibiyet serisi çarpanı ve taç.",
                    FeatureArt.SPARK, Accent.MINT, ServerFeature.PLATFORM_080),
                WhatsNewFeature("Turnuvalar ve sıralamalar", "1v1, takım ve herkes tek turnuvalar; haftalık ve tüm zamanlar sıralaması, rozetler ve ünvanlar.",
                    FeatureArt.FRIENDS, Accent.SKY, ServerFeature.PLATFORM_080),
                WhatsNewFeature("Yeni oda akışı", "Oyunu seç, oda açılsın, oda sahibi ayarlasın, herkes hazır olsun. Oyun çarkı tercihlere göre sunucuda döner.",
                    FeatureArt.INVITE, Accent.PEACH, ServerFeature.PLATFORM_080),
            ),
        ),
        ReleaseNotes(
            versionName = "0.7.0",
            title = "LOOPA artık daha sosyal!",
            subtitle = "Arkadaşlarını bul, odana davet et, oyun aralarında mesajlaş.",
            hero = WhatsNewHero.SOCIAL,
            features = listOf(
                WhatsNewFeature("Arkadaşlık sistemi", "Kullanıcı adıyla oyuncu ara, arkadaşlık isteği gönder, gelen istekleri kabul et ya da reddet.",
                    FeatureArt.FRIENDS, Accent.MINT, ServerFeature.SOCIAL),
                WhatsNewFeature("Oda davetleri", "Lobide “Arkadaşlarını davet et”e dokun. Davet 15 dakika geçerli; arkadaşın tek dokunuşla odana katılır.",
                    FeatureArt.INVITE, Accent.BUTTER, ServerFeature.SOCIAL),
                WhatsNewFeature("Birebir mesajlaşma", "Arkadaşlarınla özel sohbet et. Mesajlar anında gelir; gönderildi ve görüldü bilgisini görürsün.",
                    FeatureArt.CHAT, Accent.SKY, ServerFeature.SOCIAL),
                WhatsNewFeature("Sosyal bildirimler", "Yeni istekler, kabul edilen istekler, davetler ve mesajlar uygulama içindeki bildirim listesinde.",
                    FeatureArt.BELL, Accent.LAVENDER, ServerFeature.SOCIAL),
                WhatsNewFeature("Gizlilik senin elinde", "Çevrim içi durumunu göstermek isteyip istemediğini Ayarlar’dan seç. İstemediğin kişiyi engelleyebilirsin.",
                    FeatureArt.PRIVACY, Accent.PEACH, ServerFeature.SOCIAL),
            ),
        ),
    )

    /** Compares dotted numeric versions ("0.10.0" > "0.9.2"); suffixes like "-debug" are ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** The notes of exactly [version], if any (Settings → Yenilikler). */
    fun notesFor(version: String, list: List<ReleaseNotes> = releases): ReleaseNotes? =
        list.firstOrNull { compare(it.versionName, version) == 0 }

    /** The newest notes at or below [version] (Settings → Yenilikler on a patch release). */
    fun latestUpTo(version: String, list: List<ReleaseNotes> = releases): ReleaseNotes? =
        list.filter { compare(it.versionName, version) <= 0 }.maxWithOrNull { a, b -> compare(a.versionName, b.versionName) }

    /**
     * What to do on start.
     *  - [lastSeen] null and not onboarded yet: a fresh install. New players get no "what's new".
     *  - [lastSeen] null but onboarded: an update from a version before the What's New system.
     *  - Several versions skipped: only the newest notes in (lastSeen, current] are shown.
     */
    fun decide(current: String, lastSeen: String?, onboarded: Boolean, list: List<ReleaseNotes> = releases): WhatsNewDecision {
        if (lastSeen == null && !onboarded) return WhatsNewDecision.MarkSeen
        if (lastSeen != null && compare(lastSeen, current) >= 0) return WhatsNewDecision.None
        val notes = list.filter { (lastSeen == null || compare(it.versionName, lastSeen) > 0) && compare(it.versionName, current) <= 0 }
            .maxWithOrNull { a, b -> compare(a.versionName, b.versionName) }
        return if (notes != null) WhatsNewDecision.Show(notes) else WhatsNewDecision.MarkSeen
    }
}

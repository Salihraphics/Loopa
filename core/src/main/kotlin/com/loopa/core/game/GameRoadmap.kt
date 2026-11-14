package com.loopa.core.game

/**
 * The planned games 11–23. Planning data only: none of them is in [GameModes.all], so none is
 * shown or startable. When one is built it moves to [GameModes.all] with its server engine.
 */
data class PlannedGame(
    val number: Int,
    val id: String,
    val name: String,
    val genre: String,
    val kind: GameKind,
    val players: IntRange,
    val orientation: GameOrientation = GameOrientation.FLEXIBLE,
    /** What the shared infrastructure still lacks for this game. */
    val needs: List<String> = emptyList(),
)

object GameRoadmap {
    val planned: List<PlannedGame> = listOf(
        PlannedGame(11, "kurt_adam", "Kurt Adam", "Sosyal çıkarım", GameKind.HIDDEN_ROLE, 5..12,
            needs = listOf("rol başına gizli durum (mode_secrets per player)", "gece/gündüz fazları", "elenmiş oyuncu izleyici modu")),
        PlannedGame(12, "araba_yarisi", "Araba Yarışı", "Yarış", GameKind.REALTIME, 2..6, GameOrientation.LANDSCAPE,
            needs = listOf("Realtime broadcast ile girdi akışı", "istemci simülasyonu + sunucu sonuç doğrulaması", "düşük gecikme testi")),
        PlannedGame(13, "adam_asmaca", "Adam Asmaca", "Kelime", GameKind.TURN_BASED, 2..8,
            needs = listOf("mevcut mode engine yeterli (gizli kelime + tahminler)")),
        PlannedGame(14, "okey", "Okey", "Masa oyunu", GameKind.CARD, 4..4, GameOrientation.LANDSCAPE,
            needs = listOf("oyuncu başına gizli el", "sıra kilidi", "taş dizilimi doğrulaması (sunucu)")),
        PlannedGame(15, "kizma_birader", "Kızma Birader", "Masa oyunu", GameKind.BOARD, 2..4,
            needs = listOf("sunucu zar atışı", "tahta durumu + hamle doğrulaması")),
        PlannedGame(16, "bilardo", "Bilardo", "Beceri / Spor", GameKind.REALTIME, 2..2, GameOrientation.LANDSCAPE,
            needs = listOf("deterministik fizik (aynı girdi → aynı sonuç)", "atış girdisinin sunucuda yeniden hesaplanması")),
        PlannedGame(17, "yirmi_bir", "21", "Kart oyunu", GameKind.CARD, 2..7,
            needs = listOf("sunucu deste karıştırma", "gizli el", "krupiye mantığı sunucuda")),
        PlannedGame(18, "kurusun_rulet", "Kurşun Ruleti (özgün)", "Gerilim / Parti", GameKind.TURN_BASED, 2..4,
            needs = listOf("gizli sıra durumu (sunucu)", "özgün görsel ve kurallar")),
        PlannedGame(19, "amiral_batti", "Amiral Battı", "Strateji", GameKind.BOARD, 2..2,
            needs = listOf("oyuncu başına gizli tahta", "atış doğrulaması")),
        PlannedGame(20, "renk_kartlari", "Renk Kartları (özgün)", "Kart oyunu", GameKind.CARD, 2..8,
            needs = listOf("özgün kart seti ve isim", "gizli el", "sıra yönü / atlatma kuralları")),
        PlannedGame(21, "dortlu_satranc", "4 Kişilik Satranç", "Strateji", GameKind.BOARD, 4..4,
            needs = listOf("hamle doğrulayıcı (sunucu)", "süre kontrolü", "uzun maç devam ettirme")),
        PlannedGame(22, "diplomasi", "Diplomasi / Bölge", "Strateji", GameKind.STRATEGY, 3..7, GameOrientation.LANDSCAPE,
            needs = listOf("eşzamanlı emir çözümleyici", "oyuncular arası özel mesaj (0.7 mesajlaşması temel alınabilir)", "uzun süreli maç")),
        PlannedGame(23, "altigen_4x", "Altıgen Harita 4X", "Büyük strateji", GameKind.STRATEGY, 2..6, GameOrientation.LANDSCAPE,
            needs = listOf("altıgen harita modeli", "tur çözümleyici", "kayıtlı oyun / devam ettirme", "performans (büyük durum)")),
    )

    /** Total games once the roadmap is done. */
    val targetCount: Int get() = GameModes.all.size + planned.size
}

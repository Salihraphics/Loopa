package com.loopa.core.game

/**
 * The planned games after the 20 playable ones (numbers 21–24). Planning data only: none of them is in [GameModes.all], so none is
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
        PlannedGame(21, "araba_yarisi", "Araba Yarışı", "Yarış", GameKind.REALTIME, 2..6, GameOrientation.LANDSCAPE,
            needs = listOf("Realtime broadcast ile girdi akışı", "istemci simülasyonu + sunucu sonuç doğrulaması", "düşük gecikme testi")),
        PlannedGame(22, "adam_asmaca", "Adam Asmaca", "Kelime", GameKind.TURN_BASED, 2..8,
            needs = listOf("mevcut mode engine yeterli (gizli kelime + tahminler)")),
        PlannedGame(23, "bilardo", "Bilardo", "Beceri / Spor", GameKind.REALTIME, 2..2, GameOrientation.LANDSCAPE,
            needs = listOf("deterministik fizik (aynı girdi → aynı sonuç)", "atış girdisinin sunucuda yeniden hesaplanması")),
        PlannedGame(24, "dortlu_satranc", "4 Kişilik Satranç", "Strateji", GameKind.BOARD, 4..4,
            needs = listOf("hamle doğrulayıcı (sunucu)", "süre kontrolü", "uzun maç devam ettirme")),
    )

    /** Total games once the roadmap is done. */
    val targetCount: Int get() = GameModes.all.size + planned.size
}

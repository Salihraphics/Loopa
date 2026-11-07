package com.loopa.core.game

import com.loopa.core.model.PlayerStats

/** Achievements are derived deterministically from server statistics (no extra trust needed). */
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val progress: Int,
    val goal: Int,
) {
    val unlocked: Boolean get() = progress >= goal
}

object Achievements {
    fun evaluate(stats: PlayerStats?, level: Int): List<Achievement> {
        val s = stats ?: PlayerStats()
        return listOf(
            Achievement("first_match", "İlk Adım", "İlk maçını tamamla", s.matchesPlayed, 1),
            Achievement("first_win", "Şampiyon", "Bir maç kazan", s.matchWins, 1),
            Achievement("podium", "Kürsü Müdavimi", "5 kez ilk üçe gir", s.matchWins + s.secondPlaces + s.thirdPlaces, 5),
            Achievement("rounds_10", "Tur Avcısı", "10 tur kazan", s.roundWins, 10),
            Achievement("speedy", "Şimşek", "10 kez erken bitirme bonusu al", s.earlyBonuses, 10),
            Achievement("joker_master", "Joker Ustası", "20 joker kullan", s.jokersUsed, 20),
            Achievement("points_1000", "Bin Puan", "Toplam 1000 puan topla", s.totalPoints.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 1000),
            Achievement("veteran", "Gedikli", "50 maç oyna", s.matchesPlayed, 50),
            Achievement("level_10", "Yükselen Yıldız", "10. seviyeye ulaş", level, 10),
        )
    }
}

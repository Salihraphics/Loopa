package com.loopa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The four (and only four) LOOPA jokers. Effects are applied server-side. */
@Serializable
enum class JokerType(
    val displayName: String,
    val description: String,
    val needsTarget: Boolean,
    val needsCategory: Boolean,
    val probabilityPercent: Int,
) {
    @SerialName("extra_time")
    EXTRA_TIME("Ek Süre", "Kalan sürene 10 saniye ekler.", needsTarget = false, needsCategory = false, probabilityPercent = 40),

    @SerialName("time_thief")
    TIME_THIEF("Zaman Hırsızı", "Bir rakipten 5 saniye çalıp kendi sürene ekler.", needsTarget = true, needsCategory = false, probabilityPercent = 30),

    @SerialName("slowdown")
    SLOWDOWN("Yavaşlatma", "Bir rakibin süresinden 10 saniye siler. Son 5 saniyede kullanılamaz.", needsTarget = true, needsCategory = false, probabilityPercent = 20),

    @SerialName("double_points")
    DOUBLE_POINTS("2X Puan", "Seçtiğin bir kategorinin puanını ikiye katlar (erken bitirme bonusu hariç).", needsTarget = false, needsCategory = true, probabilityPercent = 10);

    companion object {
        const val MAX_INVENTORY = 3
        const val MAX_PER_ROUND = 1
        const val SLOWDOWN_BLOCKED_FINAL_SECONDS = 5
    }
}

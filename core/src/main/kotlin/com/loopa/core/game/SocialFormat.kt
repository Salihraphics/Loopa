package com.loopa.core.game

import com.loopa.core.model.Presence

/** Turkish texts for presence and relative times in the social screens (pure, unit-tested). */
object SocialFormat {
    /** "Çevrim içi", "5 dk önce görüldü" ..., or null when the friend does not share it. */
    fun presence(p: Presence, nowMs: Long = System.currentTimeMillis()): String? = when {
        !p.visible -> null
        p.online -> "Çevrim içi"
        else -> Timestamps.parse(p.lastSeenAt)?.let { "${ago(it, nowMs)} görüldü" }
    }

    /** "az önce", "5 dk önce", "3 sa önce", "dün", "4 gün önce", or the date for older times. */
    fun ago(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "az önce"
            s < 3600 -> "${s / 60} dk önce"
            s < 86_400 -> "${s / 3600} sa önce"
            s < 2 * 86_400 -> "dün"
            s < 7 * 86_400 -> "${s / 86_400} gün önce"
            else -> java.time.Instant.ofEpochMilli(thenMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .let { "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year) }
        }
    }

    fun ago(iso: String?, nowMs: Long = System.currentTimeMillis()): String =
        Timestamps.parse(iso)?.let { ago(it, nowMs) } ?: ""

    /** Clock time of a message ("14:05"). */
    fun clock(iso: String?): String = Timestamps.parse(iso)?.let {
        val t = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
        "%02d:%02d".format(t.hour, t.minute)
    } ?: ""

    /** "12 dk kaldı" for an invite, or null when it has expired. */
    fun remaining(expiresIso: String?, nowMs: Long = System.currentTimeMillis()): String? {
        val end = Timestamps.parse(expiresIso) ?: return null
        val s = (end - nowMs) / 1000
        return when {
            s <= 0 -> null
            s < 60 -> "1 dk'dan az kaldı"
            else -> "${(s + 59) / 60} dk kaldı"
        }
    }

    const val MESSAGE_MAX = 1000
}

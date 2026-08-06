package dev.connectx.strategy.api

/** Curated public TLS targets plus a fully manual custom-host option. */
enum class ExternalTlsEvidencePreset(
    val id: String,
    val displayName: String,
    val hostname: String?,
) {
    TELEGRAM("telegram", "Telegram", "web.telegram.org"),
    YOUTUBE("youtube", "YouTube", "www.youtube.com"),
    DISCORD("discord", "Discord", "discord.com"),
    CUSTOM("custom", "Свой домен", null),
    ;

    companion object {
        fun fromId(id: String?): ExternalTlsEvidencePreset =
            entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

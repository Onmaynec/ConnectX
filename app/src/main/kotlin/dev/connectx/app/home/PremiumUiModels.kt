package dev.connectx.app.home

import androidx.compose.ui.graphics.Color
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionUiState
import dev.connectx.core.model.EngineMode

internal object CxColors {
    val Background = Color(0xFF07090D)
    val Surface = Color(0xFF0E1117)
    val SurfaceRaised = Color(0xFF131720)
    val Border = Color(0xFF242936)
    val BorderSoft = Color(0xFF191D26)
    val Purple = Color(0xFF8B5CF6)
    val PurpleStrong = Color(0xFF7C3AED)
    val PurpleSoft = Color(0xFF2A1748)
    val Text = Color(0xFFF7F7FA)
    val TextMuted = Color(0xFF9298A7)
    val Green = Color(0xFF35D07F)
    val Red = Color(0xFFFF5B64)
    val Yellow = Color(0xFFF5C451)
}

internal enum class Destination(
    val title: String,
    val shortLabel: String,
    val icon: String,
) {
    HOME("ConnectX", "Главная", "⌂"),
    ROUTES("Маршруты", "Маршруты", "⌘"),
    STATS("Статистика", "Статистика", "▥"),
    LOGS("Логи", "Логи", "≡"),
    SETTINGS("Настройки", "Настройки", "⚙"),
}

internal enum class DetailScreen {
    NONE,
    MODE,
    STRATEGY,
    DIAGNOSTICS,
    QUICK_ACTIONS,
    ABOUT,
}

internal data class SelectionItem(
    val title: String,
    val subtitle: String,
    val badge: String? = null,
)

internal data class RouteApp(
    val name: String,
    val group: String,
    val monogram: String,
)

internal data class UiLogLine(
    val time: String,
    val title: String,
    val subtitle: String? = null,
    val color: Color,
)

internal fun buildUiLogs(uiState: ConnectionUiState): List<UiLogLine> = buildList {
    add(
        UiLogLine(
            time = "сейчас",
            title = statusTitle(uiState),
            subtitle = statusSubtitle(uiState),
            color = statusColor(uiState.state),
        ),
    )
    add(
        UiLogLine(
            time = "—",
            title = "Режим: ${engineModeTitle(uiState.mode)}",
            color = CxColors.Purple,
        ),
    )
    uiState.diagnostics.lastResult?.let { result ->
        add(
            UiLogLine(
                time = "—",
                title = "Native diagnostics",
                subtitle = result,
                color = if (uiState.diagnostics.available == true) CxColors.Green else CxColors.Yellow,
            ),
        )
    }
    uiState.probe.lastSuccess?.let { success ->
        add(
            UiLogLine(
                time = "—",
                title = "TCP Lab: ${if (success) "успешно" else "ошибка"}",
                subtitle = uiState.probe.error ?: uiState.probe.latencyMillis?.let { "$it ms" },
                color = if (success) CxColors.Green else CxColors.Red,
            ),
        )
    }
    uiState.udpProbe.lastSuccess?.let { success ->
        add(
            UiLogLine(
                time = "—",
                title = "UDP Lab: ${if (success) "успешно" else "ошибка"}",
                subtitle = uiState.udpProbe.error ?: uiState.udpProbe.latencyMillis?.let { "$it ms" },
                color = if (success) CxColors.Green else CxColors.Red,
            ),
        )
    }
    uiState.dnsProbe.lastSuccess?.let { success ->
        add(
            UiLogLine(
                time = "—",
                title = "DNS Lab: ${if (success) "успешно" else "ошибка"}",
                subtitle = uiState.dnsProbe.error ?: uiState.dnsProbe.answer,
                color = if (success) CxColors.Green else CxColors.Red,
            ),
        )
    }
    uiState.strategyProbe.evaluationDecision?.let { decision ->
        add(
            UiLogLine(
                time = "—",
                title = "A/B/A evaluation: $decision",
                subtitle = uiState.strategyProbe.evaluationReason,
                color = CxColors.Purple,
            ),
        )
    }
    uiState.errorMessage?.let { error ->
        add(
            UiLogLine(
                time = "—",
                title = "Ошибка",
                subtitle = error,
                color = CxColors.Red,
            ),
        )
    }
}

internal fun diagnosticsActionEnabled(uiState: ConnectionUiState): Boolean =
    uiState.diagnostics.available == true &&
        uiState.state in setOf(ConnectionState.OFF, ConnectionState.ERROR)

internal fun nativeAvailabilityTitle(uiState: ConnectionUiState): String = when (uiState.diagnostics.available) {
    true -> "Доступен"
    false -> "Недоступен"
    null -> "Проверяется"
}

internal fun diagnosticSummary(
    success: Boolean?,
    latency: Long?,
    error: String?,
): String = when (success) {
    true -> latency?.let { "Успешно · $it ms" } ?: "Успешно"
    false -> error ?: "Проверка завершилась ошибкой"
    null -> "Запустить локальную проверку"
}

internal fun evaluationSummary(uiState: ConnectionUiState): String =
    uiState.strategyProbe.evaluationDecision?.let { decision ->
        val reason = uiState.strategyProbe.evaluationReason
        if (reason.isNullOrBlank()) decision else "$decision · $reason"
    } ?: "Baseline → strategy → recovery"

internal fun statusTitle(uiState: ConnectionUiState): String = when (uiState.state) {
    ConnectionState.OFF -> "Отключено"
    ConnectionState.PERMISSION_REQUIRED -> "Нужно системное разрешение"
    ConnectionState.STARTING -> "Запуск локального движка"
    ConnectionState.LOCAL_TUN_ACTIVE -> "Локальная обработка активна"
    ConnectionState.STOPPING -> "Остановка"
    ConnectionState.ERROR -> "Ошибка запуска"
}

internal fun statusSubtitle(uiState: ConnectionUiState): String = when (uiState.state) {
    ConnectionState.OFF -> "Обычный трафик устройства не перехватывается"
    ConnectionState.PERMISSION_REQUIRED -> "Подтвердите создание локального TUN в системном диалоге"
    ConnectionState.STARTING -> "Подготовка ${engineModeTitle(uiState.mode)}"
    ConnectionState.LOCAL_TUN_ACTIVE -> when (uiState.mode) {
        EngineMode.FOUNDATION -> "Сетевое ядро готово; ordinary traffic capture не реализован"
        else -> "Выполняется изолированный TEST-NET Lab-режим"
    }
    ConnectionState.STOPPING -> "Закрытие TUN, relay и native ресурсов"
    ConnectionState.ERROR -> uiState.errorMessage ?: "Не удалось запустить локальную обработку"
}

internal fun engineModeTitle(mode: EngineMode): String = when (mode) {
    EngineMode.FOUNDATION -> "Foundation"
    EngineMode.NATIVE_SELF_TEST -> "Native self-test"
    EngineMode.NATIVE_TCP_PROBE -> "TCP Lab"
    EngineMode.NATIVE_UDP_PROBE -> "UDP Lab"
    EngineMode.NATIVE_DNS_PROBE -> "DNS Lab"
    EngineMode.NATIVE_TLS_SPLIT_PROBE -> "TLS split Lab"
    EngineMode.NATIVE_STRATEGY_EVALUATION -> "A/B/A evaluation"
}

internal fun statusColor(state: ConnectionState): Color = when (state) {
    ConnectionState.LOCAL_TUN_ACTIVE -> CxColors.Green
    ConnectionState.ERROR -> CxColors.Red
    ConnectionState.STARTING,
    ConnectionState.STOPPING,
    ConnectionState.PERMISSION_REQUIRED,
    -> CxColors.Yellow

    ConnectionState.OFF -> CxColors.TextMuted
}

internal fun ConnectionUiState.latestUploadedBytes(): Long? = listOf(
    strategyProbe.uploadedBytes,
    dnsProbe.uploadedBytes,
    udpProbe.uploadedBytes,
    probe.uploadedBytes,
).firstOrNull { it != null }

internal fun ConnectionUiState.latestDownloadedBytes(): Long? = listOf(
    strategyProbe.downloadedBytes,
    dnsProbe.downloadedBytes,
    udpProbe.downloadedBytes,
    probe.downloadedBytes,
).firstOrNull { it != null }

internal fun ConnectionUiState.latestLatencyMillis(): Long? = listOf(
    strategyProbe.strategyLatencyMillis,
    strategyProbe.latencyMillis,
    dnsProbe.latencyMillis,
    udpProbe.latencyMillis,
    probe.latencyMillis,
).firstOrNull { it != null }

internal fun ConnectionUiState.latestConnectionCount(): Long? = listOf(
    strategyProbe.relayConnections,
    probe.relayConnections,
    dnsProbe.relayAssociations,
    udpProbe.relayAssociations,
).firstOrNull { it != null }

internal fun Long?.formatBytesOrDash(): String {
    val value = this ?: return "—"
    return when {
        value < 1_024L -> "$value B"
        value < 1_048_576L -> String.format("%.1f KB", value / 1_024.0)
        else -> String.format("%.1f MB", value / 1_048_576.0)
    }
}

internal val busyStates = setOf(
    ConnectionState.STARTING,
    ConnectionState.PERMISSION_REQUIRED,
    ConnectionState.STOPPING,
)

internal val busyOrActiveStates = busyStates + ConnectionState.LOCAL_TUN_ACTIVE

internal val modeItems = listOf(
    SelectionItem(
        title = "Smart",
        subtitle = "Автоматический подбор параметров для стабильного соединения",
        badge = "Рекомендуется",
    ),
    SelectionItem(
        title = "Balanced",
        subtitle = "Баланс скорости и стабильности",
    ),
    SelectionItem(
        title = "Speed",
        subtitle = "Минимальная дополнительная задержка",
    ),
    SelectionItem(
        title = "Stability",
        subtitle = "Повышенная устойчивость в сложных сетях",
    ),
    SelectionItem(
        title = "Custom",
        subtitle = "Ручная настройка параметров",
    ),
)

internal val strategyItems = listOf(
    SelectionItem(
        title = "Auto",
        subtitle = "Автоматический выбор поддерживаемой стратегии",
    ),
    SelectionItem(
        title = "Strategy A",
        subtitle = "Универсальный UI-профиль",
    ),
    SelectionItem(
        title = "Strategy B",
        subtitle = "Профиль для сложных сетевых условий",
    ),
    SelectionItem(
        title = "Strategy C",
        subtitle = "Экспериментальный UI-профиль",
    ),
)

internal val accentPreviewColors = listOf(
    CxColors.Purple,
    Color(0xFF2EA8FF),
    CxColors.Green,
    CxColors.Red,
    CxColors.Yellow,
)

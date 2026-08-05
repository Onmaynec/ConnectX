package dev.connectx.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.connectx.app.BuildConfig
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionUiState
import dev.connectx.core.model.EngineMode

@Composable
internal fun DiagnosticsScreen(
    uiState: ConnectionUiState,
    onBack: () -> Unit,
    onNativeSelfTest: () -> Unit,
    onNativeTcpProbe: () -> Unit,
    onNativeUdpProbe: () -> Unit,
    onNativeDnsProbe: () -> Unit,
    onNativeTlsSplitProbe: () -> Unit,
    onStrategyEvaluation: () -> Unit,
) {
    val actionsEnabled = diagnosticsActionEnabled(uiState)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackTitle("Диагностика", onBack) }
        item {
            InfoBanner(
                title = "Локальный TEST-NET стенд",
                text = "Эти проверки подтверждают lifecycle, TUN, JNI, relay и байтовую целостность на 192.0.2.0/24. Они не доказывают обход блокировок YouTube, Telegram или ChatGPT в реальной сети.",
            )
        }
        item {
            CxCard {
                Text(
                    text = "Native bridge",
                    color = CxColors.Text,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                DiagnosticLine("Доступность", nativeAvailabilityTitle(uiState))
                DiagnosticLine("Версия", uiState.diagnostics.version ?: "—")
                DiagnosticLine("ABI", uiState.diagnostics.abi ?: "—")
                uiState.diagnostics.lastResult?.let {
                    DiagnosticLine("Результат", it)
                }
            }
        }
        item {
            CxCard(contentPadding = 0.dp) {
                DiagnosticActionRow(
                    title = "Native self-test",
                    subtitle = "Проверка загрузки bridge и lifecycle",
                    enabled = actionsEnabled,
                    running = uiState.mode == EngineMode.NATIVE_SELF_TEST && uiState.state in busyOrActiveStates,
                    onClick = onNativeSelfTest,
                )
                HorizontalDivider(color = CxColors.BorderSoft)
                DiagnosticActionRow(
                    title = "TCP через TUN",
                    subtitle = diagnosticSummary(
                        success = uiState.probe.lastSuccess,
                        latency = uiState.probe.latencyMillis,
                        error = uiState.probe.error,
                    ),
                    enabled = actionsEnabled,
                    running = uiState.probe.running,
                    onClick = onNativeTcpProbe,
                )
                HorizontalDivider(color = CxColors.BorderSoft)
                DiagnosticActionRow(
                    title = "UDP через TUN",
                    subtitle = diagnosticSummary(
                        success = uiState.udpProbe.lastSuccess,
                        latency = uiState.udpProbe.latencyMillis,
                        error = uiState.udpProbe.error,
                    ),
                    enabled = actionsEnabled,
                    running = uiState.udpProbe.running,
                    onClick = onNativeUdpProbe,
                )
                HorizontalDivider(color = CxColors.BorderSoft)
                DiagnosticActionRow(
                    title = "DNS через TUN",
                    subtitle = diagnosticSummary(
                        success = uiState.dnsProbe.lastSuccess,
                        latency = uiState.dnsProbe.latencyMillis,
                        error = uiState.dnsProbe.error,
                    ),
                    enabled = actionsEnabled,
                    running = uiState.dnsProbe.running,
                    onClick = onNativeDnsProbe,
                )
                HorizontalDivider(color = CxColors.BorderSoft)
                DiagnosticActionRow(
                    title = "TLS write-split Lab",
                    subtitle = diagnosticSummary(
                        success = uiState.strategyProbe.lastSuccess,
                        latency = uiState.strategyProbe.latencyMillis,
                        error = uiState.strategyProbe.error,
                    ),
                    enabled = actionsEnabled,
                    running = uiState.strategyProbe.running && uiState.mode == EngineMode.NATIVE_TLS_SPLIT_PROBE,
                    onClick = onNativeTlsSplitProbe,
                )
                HorizontalDivider(color = CxColors.BorderSoft)
                DiagnosticActionRow(
                    title = "A/B/A strategy evaluation",
                    subtitle = evaluationSummary(uiState),
                    enabled = actionsEnabled,
                    running = uiState.strategyProbe.running && uiState.mode == EngineMode.NATIVE_STRATEGY_EVALUATION,
                    onClick = onStrategyEvaluation,
                )
            }
        }
        item {
            CxCard {
                Text(
                    text = "Последние реальные метрики",
                    color = CxColors.Text,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                DiagnosticLine("Отправлено", uiState.latestUploadedBytes().formatBytesOrDash())
                DiagnosticLine("Получено", uiState.latestDownloadedBytes().formatBytesOrDash())
                DiagnosticLine("Задержка", uiState.latestLatencyMillis()?.let { "$it ms" } ?: "—")
                DiagnosticLine("Relay-соединения", uiState.latestConnectionCount()?.toString() ?: "—")
            }
        }
    }
}

@Composable
internal fun DiagnosticActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled && !running,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (running) CxColors.PurpleSoft else CxColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (running) "…" else "◇",
                color = if (enabled) CxColors.Purple else CxColors.TextMuted,
                fontSize = 18.sp,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) CxColors.Text else CxColors.TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (running) "Выполняется" else subtitle,
                color = CxColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        Text(
            text = "›",
            color = CxColors.TextMuted,
            fontSize = 20.sp,
        )
    }
}

@Composable
internal fun DiagnosticLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = CxColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.weight(.38f),
        )
        Text(
            text = value,
            color = CxColors.Text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(.62f),
        )
    }
}

@Composable
internal fun QuickActionsScreen(
    uiState: ConnectionUiState,
    onToggle: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    var darkThemePreview by rememberSaveable { mutableStateOf(true) }
    var selectedAccent by rememberSaveable { mutableStateOf(0) }
    val active = uiState.state == ConnectionState.LOCAL_TUN_ACTIVE

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackTitle("Быстрые действия", onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickActionCard(
                    title = if (active) "Остановить\nсоединение" else "Соединение\nвыключено",
                    subtitle = "Локальная обработка трафика",
                    symbol = "⏻",
                    symbolColor = CxColors.Red,
                    enabled = active,
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                )
                QuickActionCard(
                    title = "Очистить\nстатистику",
                    subtitle = "Хранилище ещё не подключено",
                    symbol = "↻",
                    symbolColor = CxColors.TextMuted,
                    enabled = false,
                    onClick = { },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickActionCard(
                    title = "Перезапустить\nдвижок",
                    subtitle = "Команда появится с engine contract",
                    symbol = "⟳",
                    symbolColor = CxColors.TextMuted,
                    enabled = false,
                    onClick = { },
                    modifier = Modifier.weight(1f),
                )
                QuickActionCard(
                    title = "Диагностика",
                    subtitle = "Проверить TUN, JNI и relay",
                    symbol = "⌁",
                    symbolColor = CxColors.Purple,
                    enabled = true,
                    onClick = onDiagnostics,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            CxCard {
                Text(
                    text = "Тема",
                    color = CxColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    accentPreviewColors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (selectedAccent == index) 2.dp else 1.dp,
                                    color = if (selectedAccent == index) CxColors.Text else CxColors.Border,
                                    shape = CircleShape,
                                )
                                .clickable { selectedAccent = index }
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Выбор акцента пока является UI preview.",
                    color = CxColors.TextMuted,
                    fontSize = 10.sp,
                )
            }
        }
        item {
            CxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Тёмная тема",
                            color = CxColors.Text,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "ConnectX сейчас использует фиксированную тёмную тему",
                            color = CxColors.TextMuted,
                            fontSize = 10.sp,
                        )
                    }
                    CxSwitch(
                        checked = darkThemePreview,
                        onChecked = { darkThemePreview = it },
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickActionCard(
    title: String,
    subtitle: String,
    symbol: String,
    symbolColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(146.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CxColors.Surface)
            .border(1.dp, CxColors.BorderSoft, RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                onClick = onClick,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CxColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = symbol,
                color = symbolColor,
                fontSize = 19.sp,
            )
        }
        Text(
            text = title,
            color = if (enabled) CxColors.Text else CxColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        )
        Text(
            text = subtitle,
            color = CxColors.TextMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { BackTitle("О ConnectX", onBack) }
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(CxColors.SurfaceRaised)
                        .border(1.dp, CxColors.Border, RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "X",
                        color = CxColors.Purple,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Connect",
                        color = CxColors.Text,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "X",
                        color = CxColors.Purple,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = CxColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        item {
            CxCard {
                Text(
                    text = "Локальная обработка трафика",
                    color = CxColors.Text,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "ConnectX — локальный инструмент исследования обработки сетевого трафика. Android VpnService используется как системный механизм TUN. Приложение не является удалённым VPN-сервисом, не предоставляет серверы и не расшифровывает HTTPS.",
                    color = CxColors.TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        item {
            CxCard(contentPadding = 0.dp) {
                SettingRow("Проверить обновления", "GitHub Releases") { }
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("GitHub", "Открыть репозиторий") { }
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("Поддержка", "Связаться с разработчиком") { }
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("Лицензия", "Открыть соглашение") { }
            }
        }
    }
}

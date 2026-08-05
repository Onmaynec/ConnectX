package dev.connectx.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.connectx.core.model.ConnectionUiState

@Composable
internal fun RoutesScreen() {
    var filteringPreview by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val apps = remember {
        listOf(
            RouteApp("Telegram", "Пользовательское", "T"),
            RouteApp("YouTube", "Пользовательское", "Y"),
            RouteApp("ChatGPT", "Пользовательское", "C"),
            RouteApp("Chrome", "Пользовательское", "G"),
            RouteApp("Google Play services", "Системное", "P"),
            RouteApp("Android System", "Системное", "A"),
        )
    }
    var selectedApps by remember {
        mutableStateOf(setOf("Telegram", "YouTube", "ChatGPT"))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Маршруты") }
        item {
            PreviewNotice(
                "UI preview: текущий native engine не перехватывает обычный трафик Telegram, YouTube, ChatGPT и других приложений. Эти настройки пока не применяются к сети.",
            )
        }
        item {
            CxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Фильтрация трафика",
                            color = CxColors.Text,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Предпросмотр будущих per-app маршрутов",
                            color = CxColors.TextMuted,
                            fontSize = 10.sp,
                        )
                    }
                    CxSwitch(
                        checked = filteringPreview,
                        onChecked = { filteringPreview = it },
                    )
                }
            }
        }
        item {
            SegmentedControl(
                labels = listOf("Приложения", "Домены"),
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        }
        if (selectedTab == 0) {
            item { SearchPreview() }
            items(apps, key = { it.name }) { app ->
                AppRouteRow(
                    app = app,
                    enabled = app.name in selectedApps,
                    onChange = { enabled ->
                        selectedApps = if (enabled) {
                            selectedApps + app.name
                        } else {
                            selectedApps - app.name
                        }
                    },
                )
            }
        } else {
            item {
                EmptyState(
                    title = "Доменные правила",
                    subtitle = "Backend-контракт доменных маршрутов ещё не реализован. Здесь не показываются фиктивные домены.",
                )
            }
        }
    }
}

@Composable
internal fun SearchPreview() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CxColors.Surface)
            .border(1.dp, CxColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "⌕",
            color = CxColors.TextMuted,
            fontSize = 17.sp,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "Поиск приложений",
            color = CxColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
internal fun StatisticsScreen(uiState: ConnectionUiState) {
    val uploadedBytes = uiState.latestUploadedBytes()
    val downloadedBytes = uiState.latestDownloadedBytes()
    val latency = uiState.latestLatencyMillis()
    val connections = uiState.latestConnectionCount()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Статистика") }
        item {
            PreviewNotice(
                "Показаны только реальные данные последней локальной Lab-проверки. Статистика обычного трафика пока не собирается.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    label = "Отправлено",
                    value = uploadedBytes.formatBytesOrDash(),
                    arrow = "↑",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = "Получено",
                    value = downloadedBytes.formatBytesOrDash(),
                    arrow = "↓",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            TrafficChart(
                hasTelemetry = uploadedBytes != null || downloadedBytes != null,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallStat(
                    title = "Lab-соединения",
                    value = connections?.toString() ?: "—",
                    modifier = Modifier.weight(1f),
                )
                SmallStat(
                    title = "Стратегия",
                    value = uiState.strategyProbe.segments?.let { "$it writes" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                SmallStat(
                    title = "Задержка",
                    value = latency?.let { "$it ms" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            CxCard {
                Text(
                    text = "Протоколы",
                    color = CxColors.Text,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                ProtocolBar("TLS", 0f, "—")
                ProtocolBar("HTTP/2", 0f, "—")
                ProtocolBar("QUIC", 0f, "—")
                ProtocolBar("Другие", 0f, "—")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Распределение протоколов появится только после подключения безопасной metadata telemetry без содержимого пакетов.",
                    color = CxColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

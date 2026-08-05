package dev.connectx.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import dev.connectx.app.BuildConfig
import dev.connectx.core.model.ConnectionUiState

@Composable
internal fun LogsScreen(uiState: ConnectionUiState) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val logs = remember(uiState) { buildUiLogs(uiState) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Логи") }
        item {
            SegmentedControl(
                labels = listOf("Все", "Подключение", "Система"),
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        }
        item {
            PreviewNotice(
                "Лог-хранилище пока не подключено. Ниже отображается безопасная сводка текущего ConnectionUiState без URL, токенов и содержимого пакетов.",
            )
        }
        item {
            CxCard(contentPadding = 0.dp) {
                logs.forEachIndexed { index, log ->
                    LogRow(log)
                    if (index != logs.lastIndex) {
                        HorizontalDivider(color = CxColors.BorderSoft)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LogRow(log: UiLogLine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = log.time,
            color = CxColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.size(width = 52.dp, height = 18.dp),
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(log.color),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = log.title,
                color = CxColors.Text,
                fontSize = 12.sp,
            )
            log.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = CxColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    onDiagnostics: () -> Unit,
    openAbout: () -> Unit,
) {
    var autostartPreview by rememberSaveable { mutableStateOf(false) }
    var notificationsPreview by rememberSaveable { mutableStateOf(true) }
    var nativeDiagnosticsPreview by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Настройки") }
        item {
            PreviewNotice(
                "Переключатели этого раздела пока являются UI preview и не сохраняются в DataStore. Реальная native диагностика доступна отдельной строкой ниже.",
            )
        }
        item {
            CxCard(contentPadding = 0.dp) {
                ToggleRow(
                    title = "Автозапуск",
                    subtitle = "Запускать ConnectX при старте системы",
                    checked = autostartPreview,
                    onChecked = { autostartPreview = it },
                )
                HorizontalDivider(color = CxColors.BorderSoft)
                ToggleRow(
                    title = "Постоянное уведомление",
                    subtitle = "Показывать состояние обработки",
                    checked = notificationsPreview,
                    onChecked = { notificationsPreview = it },
                )
                HorizontalDivider(color = CxColors.BorderSoft)
                ToggleRow(
                    title = "Native диагностика",
                    subtitle = "Показывать подробные локальные проверки",
                    checked = nativeDiagnosticsPreview,
                    onChecked = { nativeDiagnosticsPreview = it },
                )
            }
        }
        item {
            CxCard(contentPadding = 0.dp) {
                SettingRow("Режим сети", "Auto") { }
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("Обновления", "GitHub Releases") { }
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("Язык", "Система") { }
            }
        }
        item {
            CxCard(contentPadding = 0.dp) {
                SettingRow("Диагностика движка", "Открыть", onDiagnostics)
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("GitHub", "Onmaynec/ConnectX") { }
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("Лицензия", "Открыть") { }
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("О ConnectX", "v${BuildConfig.VERSION_NAME}", openAbout)
            }
        }
    }
}

@Composable
internal fun SelectionScreen(
    title: String,
    description: String,
    items: List<SelectionItem>,
    selectedTitle: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackTitle(title, onBack) }
        item {
            PreviewNotice(
                "Выбор сохраняется только в UI текущего процесса и не выдаётся за работающую engine-функцию.",
            )
        }
        items(items, key = { it.title }) { item ->
            SelectionCard(
                item = item,
                selected = item.title == selectedTitle,
                onClick = { onSelect(item.title) },
            )
        }
        item {
            InfoBanner(
                title = "Как это работает",
                text = description,
            )
        }
    }
}

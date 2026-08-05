package dev.connectx.app.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionUiState

@Composable
internal fun DashboardScreen(
    uiState: ConnectionUiState,
    selectedMode: String,
    selectedStrategy: String,
    onToggle: () -> Unit,
    openMode: () -> Unit,
    openStrategy: () -> Unit,
    openQuickActions: () -> Unit,
) {
    val active = uiState.state == ConnectionState.LOCAL_TUN_ACTIVE
    val busy = uiState.state in busyStates
    val uploadedBytes = uiState.latestUploadedBytes()
    val downloadedBytes = uiState.latestDownloadedBytes()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BrandHeader(openQuickActions) }
        item { StatusBlock(uiState) }
        item { SessionBlock(uiState) }
        item {
            PowerControl(
                active = active,
                busy = busy,
                onClick = onToggle,
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
            CxCard(contentPadding = 0.dp) {
                SettingRow("Режим", selectedMode, openMode)
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("Стратегия", selectedStrategy, openStrategy)
                HorizontalDivider(color = CxColors.BorderSoft)
                SettingRow("Фильтрация", "Не подключена") { }
            }
        }
        item {
            InfoBanner(
                title = "Локальная архитектура",
                text = "ConnectX использует Android VpnService как механизм локального TUN. Текущая версия не перехватывает обычный трафик приложений и не отправляет его на сервер ConnectX.",
            )
        }
        uiState.errorMessage?.let { message ->
            item { ErrorBanner(message) }
        }
    }
}

@Composable
internal fun BrandHeader(onMenuClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Connect",
                color = CxColors.Text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "X",
                color = CxColors.Purple,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onMenuClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "☰",
                color = CxColors.TextMuted,
                fontSize = 22.sp,
            )
        }
    }
}

@Composable
internal fun StatusBlock(uiState: ConnectionUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor(uiState.state)),
            )
            Spacer(Modifier.size(9.dp))
            Text(
                text = statusTitle(uiState),
                color = CxColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = statusSubtitle(uiState),
            color = CxColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
internal fun SessionBlock(uiState: ConnectionUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Текущий режим",
            color = CxColors.TextMuted,
            fontSize = 11.sp,
        )
        Text(
            text = engineModeTitle(uiState.mode),
            color = CxColors.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun PowerControl(
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val ringColor by animateColorAsState(
        targetValue = when {
            busy -> CxColors.Yellow
            active -> CxColors.Purple
            else -> CxColors.Border
        },
        label = "power-ring",
    )
    val progress by animateFloatAsState(
        targetValue = if (active || busy) 1f else 0.72f,
        label = "power-progress",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(174.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(136.dp)
                .clickable(
                    enabled = !busy,
                    onClick = onClick,
                ),
        ) {
            drawCircle(CxColors.SurfaceRaised)
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(
                    width = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                ),
            )
            drawCircle(
                color = CxColors.BorderSoft,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "⏻",
                color = if (active) CxColors.Text else CxColors.TextMuted,
                fontSize = 42.sp,
            )
            Text(
                text = when {
                    busy -> "Подождите"
                    active -> "Отключить"
                    else -> "Включить"
                },
                color = CxColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun MetricCard(
    label: String,
    value: String,
    arrow: String,
    modifier: Modifier = Modifier,
) {
    CxCard(modifier = modifier) {
        Text(
            text = label,
            color = CxColors.TextMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = CxColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = arrow,
                color = CxColors.Purple,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
internal fun TrafficChart(hasTelemetry: Boolean) {
    CxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Трафик",
                color = CxColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (hasTelemetry) "Последняя Lab-проверка" else "Нет данных",
                color = CxColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
        ) {
            val values = if (hasTelemetry) {
                listOf(.18f, .22f, .2f, .28f, .24f, .34f, .3f, .38f, .35f, .46f, .4f, .52f)
            } else {
                List(12) { .08f }
            }
            val step = size.width / values.lastIndex.coerceAtLeast(1)
            val path = Path()
            values.forEachIndexed { index, value ->
                val point = Offset(index * step, size.height * (1f - value))
                if (index == 0) {
                    path.moveTo(point.x, point.y)
                } else {
                    path.lineTo(point.x, point.y)
                }
            }
            drawPath(
                path = path,
                color = if (hasTelemetry) CxColors.Purple else CxColors.Border,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                ),
            )
        }
        if (!hasTelemetry) {
            Text(
                text = "Счётчики обычного трафика ещё не подключены к UI-модели.",
                color = CxColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

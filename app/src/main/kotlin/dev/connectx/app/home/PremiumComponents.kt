package dev.connectx.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CxBottomBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CxColors.Background)
            .border(
                width = 1.dp,
                color = CxColors.BorderSoft,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            )
            .navigationBarsPadding()
            .padding(horizontal = 5.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        Destination.entries.forEach { item ->
            val selectedItem = selected == item
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item.icon,
                    color = if (selectedItem) CxColors.Purple else CxColors.TextMuted,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.shortLabel,
                    color = if (selectedItem) CxColors.Purple else CxColors.TextMuted,
                    fontSize = 8.sp,
                    fontWeight = if (selectedItem) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CxCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CxColors.Surface)
            .border(1.dp, CxColors.BorderSoft, RoundedCornerShape(16.dp))
            .padding(contentPadding),
        content = content,
    )
}

@Composable
internal fun SettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = CxColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = CxColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "›",
            color = CxColors.TextMuted,
            fontSize = 20.sp,
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = CxColors.Text,
                fontSize = 13.sp,
            )
            Text(
                text = subtitle,
                color = CxColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        CxSwitch(
            checked = checked,
            onChecked = onChecked,
        )
    }
}

@Composable
internal fun CxSwitch(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onChecked,
        colors = SwitchDefaults.colors(
            checkedThumbColor = CxColors.Text,
            checkedTrackColor = CxColors.Purple,
            uncheckedThumbColor = CxColors.TextMuted,
            uncheckedTrackColor = CxColors.SurfaceRaised,
            uncheckedBorderColor = CxColors.Border,
        ),
    )
}

@Composable
internal fun ScreenTitle(title: String) {
    Text(
        text = title,
        color = CxColors.Text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
internal fun BackTitle(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = CxColors.Text,
                fontSize = 32.sp,
            )
        }
        Text(
            text = title,
            color = CxColors.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun SegmentedControl(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CxColors.Surface)
            .border(1.dp, CxColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected == index) CxColors.PurpleStrong else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected == index) CxColors.Text else CxColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun SelectionCard(
    item: SelectionItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) CxColors.PurpleSoft else CxColors.Surface)
            .border(
                width = 1.dp,
                color = if (selected) CxColors.Purple else CxColors.BorderSoft,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) CxColors.PurpleStrong else CxColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "◇",
                color = CxColors.Text,
                fontSize = 20.sp,
            )
        }
        Spacer(Modifier.size(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    color = CxColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                item.badge?.let { badge ->
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = badge,
                        color = CxColors.Purple,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CxColors.PurpleSoft)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.subtitle,
                color = CxColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Box(
            modifier = Modifier
                .size(21.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = if (selected) CxColors.Purple else CxColors.TextMuted,
                    shape = CircleShape,
                )
                .background(if (selected) CxColors.Purple else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun AppRouteRow(
    app: RouteApp,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CxColors.Surface)
            .border(1.dp, CxColors.BorderSoft, RoundedCornerShape(14.dp))
            .clickable { onChange(!enabled) }
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(CxColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.monogram,
                color = CxColors.Purple,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.name,
                color = CxColors.Text,
                fontSize = 13.sp,
            )
            Text(
                text = app.group,
                color = CxColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (enabled) CxColors.Purple else CxColors.SurfaceRaised)
                .border(
                    width = 1.dp,
                    color = if (enabled) CxColors.Purple else CxColors.Border,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (enabled) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
internal fun ProtocolBar(
    title: String,
    progress: Float,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Text(
            text = title,
            color = CxColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.size(width = 54.dp, height = 18.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(CircleShape)
                .background(CxColors.BorderSoft),
        ) {
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(CxColors.Purple),
                )
            }
        }
        Text(
            text = value,
            color = CxColors.TextMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.size(width = 42.dp, height = 18.dp),
        )
    }
}

@Composable
internal fun SmallStat(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    CxCard(modifier = modifier) {
        Text(
            text = title,
            color = CxColors.TextMuted,
            fontSize = 9.sp,
            lineHeight = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = CxColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun EmptyState(
    title: String,
    subtitle: String,
) {
    CxCard {
        Text(
            text = title,
            color = CxColors.Text,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = subtitle,
            color = CxColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
internal fun PreviewNotice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CxColors.PurpleSoft)
            .border(1.dp, CxColors.Purple, RoundedCornerShape(14.dp))
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "UI",
            color = CxColors.Purple,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(CxColors.SurfaceRaised)
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            color = CxColors.Text,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun InfoBanner(
    title: String,
    text: String,
) {
    CxCard {
        Text(
            text = title,
            color = CxColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            color = CxColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
internal fun ErrorBanner(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF261216))
            .border(1.dp, CxColors.Red, RoundedCornerShape(14.dp))
            .padding(13.dp),
    ) {
        Text(
            text = "Ошибка",
            color = CxColors.Red,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            color = CxColors.Text,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

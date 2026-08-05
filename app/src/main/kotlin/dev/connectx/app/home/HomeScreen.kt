package dev.connectx.app.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.connectx.core.model.ConnectionUiState

@Composable
fun HomeScreen(
    uiState: ConnectionUiState,
    onToggle: () -> Unit,
    onNativeSelfTest: () -> Unit,
    onNativeTcpProbe: () -> Unit,
    onNativeUdpProbe: () -> Unit,
    onNativeDnsProbe: () -> Unit,
    onNativeTlsSplitProbe: () -> Unit,
    onStrategyEvaluation: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var detail by rememberSaveable { mutableStateOf(DetailScreen.NONE) }
    var selectedMode by rememberSaveable { mutableStateOf("Smart") }
    var selectedStrategy by rememberSaveable { mutableStateOf("Auto") }

    Scaffold(
        containerColor = CxColors.Background,
        bottomBar = {
            CxBottomBar(
                selected = destination,
                onSelect = { selected ->
                    destination = selected
                    detail = DetailScreen.NONE
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CxColors.Background)
                .padding(padding)
                .statusBarsPadding(),
        ) {
            AnimatedContent(
                targetState = detail,
                label = "connectx-detail",
            ) { screen ->
                when (screen) {
                    DetailScreen.MODE -> SelectionScreen(
                        title = "Режим",
                        description = "Режимы пока являются UI-профилями. Они не меняют native engine до появления отдельного engine contract.",
                        items = modeItems,
                        selectedTitle = selectedMode,
                        onSelect = { selectedMode = it },
                        onBack = { detail = DetailScreen.NONE },
                    )

                    DetailScreen.STRATEGY -> SelectionScreen(
                        title = "Стратегия",
                        description = "Production-трафик пока не подключён к strategy API. Доступные сейчас TLS split и A/B/A проверки работают только на локальном TEST-NET стенде.",
                        items = strategyItems,
                        selectedTitle = selectedStrategy,
                        onSelect = { selectedStrategy = it },
                        onBack = { detail = DetailScreen.NONE },
                    )

                    DetailScreen.DIAGNOSTICS -> DiagnosticsScreen(
                        uiState = uiState,
                        onBack = { detail = DetailScreen.NONE },
                        onNativeSelfTest = onNativeSelfTest,
                        onNativeTcpProbe = onNativeTcpProbe,
                        onNativeUdpProbe = onNativeUdpProbe,
                        onNativeDnsProbe = onNativeDnsProbe,
                        onNativeTlsSplitProbe = onNativeTlsSplitProbe,
                        onStrategyEvaluation = onStrategyEvaluation,
                    )

                    DetailScreen.QUICK_ACTIONS -> QuickActionsScreen(
                        uiState = uiState,
                        onToggle = onToggle,
                        onDiagnostics = { detail = DetailScreen.DIAGNOSTICS },
                        onBack = { detail = DetailScreen.NONE },
                    )

                    DetailScreen.ABOUT -> AboutScreen(
                        onBack = { detail = DetailScreen.NONE },
                    )

                    DetailScreen.NONE -> when (destination) {
                        Destination.HOME -> DashboardScreen(
                            uiState = uiState,
                            selectedMode = selectedMode,
                            selectedStrategy = selectedStrategy,
                            onToggle = onToggle,
                            openMode = { detail = DetailScreen.MODE },
                            openStrategy = { detail = DetailScreen.STRATEGY },
                            openQuickActions = { detail = DetailScreen.QUICK_ACTIONS },
                        )

                        Destination.ROUTES -> RoutesScreen()
                        Destination.STATS -> StatisticsScreen(uiState)
                        Destination.LOGS -> LogsScreen(uiState)
                        Destination.SETTINGS -> SettingsScreen(
                            onDiagnostics = { detail = DetailScreen.DIAGNOSTICS },
                            openAbout = { detail = DetailScreen.ABOUT },
                        )
                    }
                }
            }
        }
    }
}

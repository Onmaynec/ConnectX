package dev.connectx.app.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.connectx.core.designsystem.ConnectXTheme
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionUiState

@Preview(
    name = "ConnectX — Off",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun ConnectXOffPreview() {
    ConnectXTheme {
        HomeScreen(
            uiState = ConnectionUiState(),
            onToggle = { },
            onNativeSelfTest = { },
            onNativeTcpProbe = { },
            onNativeUdpProbe = { },
            onNativeDnsProbe = { },
            onNativeTlsSplitProbe = { },
            onStrategyEvaluation = { },
        )
    }
}

@Preview(
    name = "ConnectX — Active",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun ConnectXActivePreview() {
    ConnectXTheme {
        HomeScreen(
            uiState = ConnectionUiState(
                state = ConnectionState.LOCAL_TUN_ACTIVE,
            ),
            onToggle = { },
            onNativeSelfTest = { },
            onNativeTcpProbe = { },
            onNativeUdpProbe = { },
            onNativeDnsProbe = { },
            onNativeTlsSplitProbe = { },
            onStrategyEvaluation = { },
        )
    }
}

@Preview(
    name = "ConnectX — Error",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun ConnectXErrorPreview() {
    ConnectXTheme {
        HomeScreen(
            uiState = ConnectionUiState(
                state = ConnectionState.ERROR,
                errorMessage = "Не удалось запустить локальный TUN",
            ),
            onToggle = { },
            onNativeSelfTest = { },
            onNativeTcpProbe = { },
            onNativeUdpProbe = { },
            onNativeDnsProbe = { },
            onNativeTlsSplitProbe = { },
            onStrategyEvaluation = { },
        )
    }
}

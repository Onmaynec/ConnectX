package dev.connectx.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionUiState

@Composable
fun HomeScreen(
    uiState: ConnectionUiState,
    onToggle: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "ConnectX",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Локальная обработка трафика",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = statusTitle(uiState.state),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onToggle,
                    enabled = uiState.state !in setOf(
                        ConnectionState.STARTING,
                        ConnectionState.PERMISSION_REQUIRED,
                        ConnectionState.STOPPING,
                    ),
                    modifier = Modifier.size(184.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.state == ConnectionState.LOCAL_TUN_ACTIVE) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Text(
                        text = actionTitle(uiState.state),
                        textAlign = TextAlign.Center,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                uiState.errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Box(modifier = Modifier.padding(20.dp)) {
                    Column {
                        Text(
                            text = "Foundation · v0.1.0",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Эта версия проверяет разрешение Android и безопасный жизненный цикл локального TUN. Она не направляет трафик на сервер ConnectX и пока не включает DPI-обфускацию.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun statusTitle(state: ConnectionState): String = when (state) {
    ConnectionState.OFF -> "Выключено"
    ConnectionState.PERMISSION_REQUIRED -> "Нужно системное разрешение"
    ConnectionState.STARTING -> "Создание локального TUN"
    ConnectionState.LOCAL_TUN_ACTIVE -> "Локальный TUN активен"
    ConnectionState.STOPPING -> "Остановка"
    ConnectionState.ERROR -> "Ошибка"
}

private fun actionTitle(state: ConnectionState): String = when (state) {
    ConnectionState.LOCAL_TUN_ACTIVE -> "Выключить"
    ConnectionState.STARTING,
    ConnectionState.PERMISSION_REQUIRED,
    -> "Подготовка"

    ConnectionState.STOPPING -> "Остановка"
    ConnectionState.OFF,
    ConnectionState.ERROR,
    -> "Включить"
}

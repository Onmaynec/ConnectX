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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.connectx.app.BuildConfig
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionUiState
import dev.connectx.core.model.EngineMode
import dev.connectx.strategy.api.TlsClientHelloSplitStrategy

private val labStrategyDescriptor = TlsClientHelloSplitStrategy().descriptor

@Composable
fun HomeScreen(
    uiState: ConnectionUiState,
    onToggle: () -> Unit,
    onNativeSelfTest: () -> Unit,
    onNativeTcpProbe: () -> Unit,
    onNativeUdpProbe: () -> Unit,
    onNativeDnsProbe: () -> Unit,
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
                    text = statusTitle(uiState),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
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
                            text = "Native diagnostics · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = diagnosticsText(uiState),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onNativeSelfTest,
                            enabled = diagnosticsActionEnabled(uiState),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = when (uiState.diagnostics.available) {
                                    true -> "Запустить native self-test"
                                    false -> "Native bridge недоступен"
                                    null -> "Проверка native bridge"
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onNativeTcpProbe,
                            enabled = diagnosticsActionEnabled(uiState),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (uiState.probe.running) {
                                    "TCP probe выполняется"
                                } else {
                                    "Проверить TCP-путь через TUN"
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onNativeUdpProbe,
                            enabled = diagnosticsActionEnabled(uiState),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (uiState.udpProbe.running) {
                                    "UDP probe выполняется"
                                } else {
                                    "Проверить UDP-путь через TUN"
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onNativeDnsProbe,
                            enabled = diagnosticsActionEnabled(uiState),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (uiState.dnsProbe.running) {
                                    "DNS probe выполняется"
                                } else {
                                    "Проверить DNS-путь через TUN"
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun diagnosticsActionEnabled(uiState: ConnectionUiState): Boolean =
    uiState.diagnostics.available == true &&
        uiState.state in setOf(ConnectionState.OFF, ConnectionState.ERROR)

private fun statusTitle(uiState: ConnectionUiState): String = when (uiState.state) {
    ConnectionState.OFF -> "Выключено"
    ConnectionState.PERMISSION_REQUIRED -> "Нужно системное разрешение"
    ConnectionState.STARTING -> when (uiState.mode) {
        EngineMode.NATIVE_SELF_TEST -> "Запуск native self-test"
        EngineMode.NATIVE_TCP_PROBE -> "Подготовка TCP probe"
        EngineMode.NATIVE_UDP_PROBE -> "Подготовка UDP probe"
        EngineMode.NATIVE_DNS_PROBE -> "Подготовка DNS probe"
        EngineMode.FOUNDATION -> "Запуск сетевого ядра"
    }

    ConnectionState.LOCAL_TUN_ACTIVE -> when (uiState.mode) {
        EngineMode.NATIVE_SELF_TEST -> "Native self-test активен"
        EngineMode.NATIVE_TCP_PROBE -> "TCP-пакет проходит через TUN"
        EngineMode.NATIVE_UDP_PROBE -> "UDP-пакет проходит через TUN"
        EngineMode.NATIVE_DNS_PROBE -> "DNS-запрос проходит через TUN"
        EngineMode.FOUNDATION -> "Сетевое ядро готово"
    }

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

private fun diagnosticsText(uiState: ConnectionUiState): String {
    val diagnostics = uiState.diagnostics
    val availability = when (diagnostics.available) {
        true -> "Библиотека загружена"
        false -> "Библиотека недоступна"
        null -> "Библиотека ещё не проверена"
    }
    val nativeDetails = listOfNotNull(
        diagnostics.version?.let { "Версия: $it" },
        diagnostics.abi?.let { "ABI: $it" },
        diagnostics.lastResult,
    )
    val tcpProbeDetails = when (uiState.probe.lastSuccess) {
        true -> listOfNotNull(
            uiState.probe.latencyMillis?.let { "TCP probe: ${it} мс" },
            if (
                uiState.probe.uploadedBytes != null &&
                uiState.probe.downloadedBytes != null
            ) {
                "TCP relay: ↑${uiState.probe.uploadedBytes} Б · ↓${uiState.probe.downloadedBytes} Б"
            } else {
                null
            },
            uiState.probe.relayConnections?.let { "TCP-соединения relay: $it" },
        )

        false -> listOfNotNull(uiState.probe.error?.let { "TCP probe: $it" })
        null -> emptyList()
    }
    val udpProbeDetails = when (uiState.udpProbe.lastSuccess) {
        true -> listOfNotNull(
            uiState.udpProbe.latencyMillis?.let { "UDP probe: ${it} мс" },
            if (
                uiState.udpProbe.uploadedBytes != null &&
                uiState.udpProbe.downloadedBytes != null
            ) {
                "UDP relay: ↑${uiState.udpProbe.uploadedBytes} Б · ↓${uiState.udpProbe.downloadedBytes} Б"
            } else {
                null
            },
            uiState.udpProbe.relayAssociations?.let { "UDP associations: $it" },
            uiState.udpProbe.datagrams?.let { "UDP datagrams: $it" },
        )

        false -> listOfNotNull(uiState.udpProbe.error?.let { "UDP probe: $it" })
        null -> emptyList()
    }
    val dnsProbeDetails = when (uiState.dnsProbe.lastSuccess) {
        true -> listOfNotNull(
            uiState.dnsProbe.latencyMillis?.let { "DNS probe: ${it} мс" },
            uiState.dnsProbe.answer?.let { "Ответ connectx.invalid: $it" },
            if (
                uiState.dnsProbe.uploadedBytes != null &&
                uiState.dnsProbe.downloadedBytes != null
            ) {
                "DNS relay: ↑${uiState.dnsProbe.uploadedBytes} Б · ↓${uiState.dnsProbe.downloadedBytes} Б"
            } else {
                null
            },
            uiState.dnsProbe.relayAssociations?.let { "DNS associations: $it" },
            uiState.dnsProbe.datagrams?.let { "DNS datagrams: $it" },
            if (uiState.dnsProbe.queries != null && uiState.dnsProbe.responses != null) {
                "DNS responder: ${uiState.dnsProbe.queries} запрос · ${uiState.dnsProbe.responses} ответ"
            } else {
                null
            },
        )

        false -> listOfNotNull(uiState.dnsProbe.error?.let { "DNS probe: $it" })
        null -> emptyList()
    }

    val strategy =
        "Strategy lab: ${labStrategyDescriptor.id} · доступна в APK · выключена по умолчанию"
    val safety =
        "Диагностика использует только 192.0.2.0/24; системный DNS и обычный интернет-трафик не перехватываются."
    return (
        listOf(availability) +
            nativeDetails +
            tcpProbeDetails +
            udpProbeDetails +
            dnsProbeDetails +
            strategy +
            safety
        )
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n")
}

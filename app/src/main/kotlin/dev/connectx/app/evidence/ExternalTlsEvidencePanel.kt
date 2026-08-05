package dev.connectx.app.evidence

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class ExternalTlsEvidenceStatus {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    RUNNING,
    STOPPING,
    COMPLETED,
    ERROR,
}

data class ExternalTlsEvidenceUiState(
    val hostnameInput: String = "",
    val status: ExternalTlsEvidenceStatus = ExternalTlsEvidenceStatus.IDLE,
    val normalizedHostname: String? = null,
    val resolvedIpv4: String? = null,
    val targetPort: Int? = null,
    val baselineLatencyMillis: Long? = null,
    val strategyLatencyMillis: Long? = null,
    val recoveryLatencyMillis: Long? = null,
    val baselineRecordKind: String? = null,
    val strategyRecordKind: String? = null,
    val recoveryRecordKind: String? = null,
    val decision: String? = null,
    val reason: String? = null,
    val gateState: String? = null,
    val error: String? = null,
) {
    val isBusy: Boolean
        get() = status in setOf(
            ExternalTlsEvidenceStatus.REQUESTING_PERMISSION,
            ExternalTlsEvidenceStatus.STARTING,
            ExternalTlsEvidenceStatus.RUNNING,
            ExternalTlsEvidenceStatus.STOPPING,
        )

    val canExportRedactedReport: Boolean
        get() = status == ExternalTlsEvidenceStatus.COMPLETED &&
            decision != null &&
            reason != null

    fun redactedReportText(): String = buildRedactedEvidenceReport(
        ExternalTlsEvidenceReportData(
            targetPort = targetPort,
            baselineLatencyMillis = baselineLatencyMillis,
            strategyLatencyMillis = strategyLatencyMillis,
            recoveryLatencyMillis = recoveryLatencyMillis,
            baselineRecordKind = baselineRecordKind,
            strategyRecordKind = strategyRecordKind,
            recoveryRecordKind = recoveryRecordKind,
            decision = decision,
            reason = reason,
            gateState = gateState,
        ),
    )
}

@Composable
fun ExternalTlsEvidencePanel(
    state: ExternalTlsEvidenceUiState,
    globalBusy: Boolean,
    onHostnameChanged: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.status) {
        if (
            state.status == ExternalTlsEvidenceStatus.COMPLETED ||
            state.status == ExternalTlsEvidenceStatus.ERROR
        ) {
            dialogOpen = true
        }
    }

    FilledTonalButton(
        onClick = { dialogOpen = true },
        enabled = !globalBusy || state.isBusy,
        modifier = modifier,
    ) {
        Text(
            text = when (state.status) {
                ExternalTlsEvidenceStatus.REQUESTING_PERMISSION -> "TLS · разрешение"
                ExternalTlsEvidenceStatus.STARTING -> "TLS · запуск"
                ExternalTlsEvidenceStatus.RUNNING -> "TLS · A/B/A"
                ExternalTlsEvidenceStatus.STOPPING -> "TLS · остановка"
                ExternalTlsEvidenceStatus.COMPLETED -> "TLS · результат"
                ExternalTlsEvidenceStatus.ERROR -> "TLS · ошибка"
                ExternalTlsEvidenceStatus.IDLE -> "TLS evidence"
            },
        )
    }

    if (!dialogOpen) return

    AlertDialog(
        onDismissRequest = {
            if (!state.isBusy) dialogOpen = false
        },
        title = {
            Text(
                text = "Restricted-network TLS Evidence",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.hostnameInput,
                    onValueChange = onHostnameChanged,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hostname") },
                    placeholder = { Text("example.org") },
                    singleLine = true,
                    supportingText = {
                        Text("Только hostname; порт всегда 443. URL и IP запрещены.")
                    },
                )

                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.status == ExternalTlsEvidenceStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                resultLines(state).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.canExportRedactedReport) {
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, state.redactedReportText())
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    "Поделиться обезличенным отчётом",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Поделиться обезличенным отчётом")
                    }
                }

                Text(
                    text = "Проверка отправляет только локально созданный TLS ClientHello. " +
                        "Она не отправляет HTTP-запрос, не входит в аккаунт, не читает тело ответа " +
                        "и не расшифровывает HTTPS. Успех относится только к текущей сети.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        },
        confirmButton = {
            if (state.isBusy) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.status != ExternalTlsEvidenceStatus.STOPPING,
                ) {
                    Text("Остановить")
                }
            } else {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = state.hostnameInput.isNotBlank() && !globalBusy,
                ) {
                    Text("Запустить A/B/A")
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { dialogOpen = false },
                enabled = !state.isBusy,
            ) {
                Text("Закрыть")
            }
        },
    )
}

private fun statusText(state: ExternalTlsEvidenceUiState): String = when (state.status) {
    ExternalTlsEvidenceStatus.IDLE -> "Готово к ручной проверке."
    ExternalTlsEvidenceStatus.REQUESTING_PERMISSION -> "Ожидание системного VPN-разрешения."
    ExternalTlsEvidenceStatus.STARTING -> "Проверка hostname, DNS и запуск TEST-NET TUN."
    ExternalTlsEvidenceStatus.RUNNING -> "Baseline → TLS split → recovery выполняются."
    ExternalTlsEvidenceStatus.STOPPING -> "Закрытие socket, native stack, TUN и relay."
    ExternalTlsEvidenceStatus.COMPLETED -> "A/B/A evidence завершена."
    ExternalTlsEvidenceStatus.ERROR -> state.error ?: "Проверка завершилась ошибкой."
}

private fun resultLines(state: ExternalTlsEvidenceUiState): List<String> = listOfNotNull(
    state.normalizedHostname?.let { "Hostname: $it" },
    state.resolvedIpv4?.let { ip ->
        "Pinned target: $ip:${state.targetPort ?: 443}"
    },
    phaseLine(
        name = "Baseline",
        latency = state.baselineLatencyMillis,
        recordKind = state.baselineRecordKind,
    ),
    phaseLine(
        name = "Strategy",
        latency = state.strategyLatencyMillis,
        recordKind = state.strategyRecordKind,
    ),
    phaseLine(
        name = "Recovery",
        latency = state.recoveryLatencyMillis,
        recordKind = state.recoveryRecordKind,
    ),
    state.decision?.let { "Decision: $it" },
    state.reason?.let { "Reason: $it" },
    state.gateState?.let { "Session gate: $it" },
)

private fun phaseLine(
    name: String,
    latency: Long?,
    recordKind: String?,
): String? {
    if (latency == null && recordKind == null) return null
    return listOfNotNull(
        name,
        latency?.let { "$it мс" },
        recordKind,
    ).joinToString(" · ")
}

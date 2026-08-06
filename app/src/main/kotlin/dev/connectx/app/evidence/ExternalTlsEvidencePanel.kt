package dev.connectx.app.evidence

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import dev.connectx.strategy.api.ExternalTlsEvidenceAssessor
import dev.connectx.strategy.api.ExternalTlsEvidenceClass
import dev.connectx.strategy.api.ExternalTlsEvidenceConfidence
import dev.connectx.strategy.api.ExternalTlsEvidencePreset
import dev.connectx.strategy.api.ExternalTlsEvidenceRecommendation
import dev.connectx.strategy.api.ExternalTlsEvidenceSampleSummary

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
    val hostnameInput: String = ExternalTlsEvidencePreset.TELEGRAM.hostname.orEmpty(),
    val selectedPresetId: String = ExternalTlsEvidencePreset.TELEGRAM.id,
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
    val baselineSuccesses: Int = 0,
    val baselineFailures: Int = 0,
    val strategySuccesses: Int = 0,
    val strategyFailures: Int = 0,
    val recoverySuccesses: Int = 0,
    val recoveryFailures: Int = 0,
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
        get() = status == ExternalTlsEvidenceStatus.COMPLETED && decision != null && reason != null

    fun redactedReportText(): String = buildRedactedEvidenceReport(
        ExternalTlsEvidenceReportData(
            presetId = selectedPresetId,
            targetPort = targetPort,
            baselineLatencyMillis = baselineLatencyMillis,
            strategyLatencyMillis = strategyLatencyMillis,
            recoveryLatencyMillis = recoveryLatencyMillis,
            baselineRecordKind = baselineRecordKind,
            strategyRecordKind = strategyRecordKind,
            recoveryRecordKind = recoveryRecordKind,
            baselineSuccesses = baselineSuccesses,
            baselineFailures = baselineFailures,
            strategySuccesses = strategySuccesses,
            strategyFailures = strategyFailures,
            recoverySuccesses = recoverySuccesses,
            recoveryFailures = recoveryFailures,
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
    onPresetSelected: (ExternalTlsEvidencePreset) -> Unit,
    onHostnameChanged: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.status) {
        if (state.status in setOf(ExternalTlsEvidenceStatus.COMPLETED, ExternalTlsEvidenceStatus.ERROR)) {
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
                ExternalTlsEvidenceStatus.REQUESTING_PERMISSION -> "Стратегия · разрешение"
                ExternalTlsEvidenceStatus.STARTING -> "Стратегия · запуск"
                ExternalTlsEvidenceStatus.RUNNING -> "Стратегия · 3×A/B/A"
                ExternalTlsEvidenceStatus.STOPPING -> "Стратегия · остановка"
                ExternalTlsEvidenceStatus.COMPLETED -> "Стратегия · результат"
                ExternalTlsEvidenceStatus.ERROR -> "Стратегия · ошибка"
                ExternalTlsEvidenceStatus.IDLE -> "Проверить стратегию"
            },
        )
    }

    if (!dialogOpen) return

    AlertDialog(
        onDismissRequest = { if (!state.isBusy) dialogOpen = false },
        title = { Text("Проверка TLS-стратегии", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Цель", style = MaterialTheme.typography.labelLarge)
                presetRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { preset ->
                            FilterChip(
                                selected = state.selectedPresetId == preset.id,
                                onClick = { onPresetSelected(preset) },
                                enabled = !state.isBusy,
                                label = { Text(preset.displayName) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.hostnameInput,
                    onValueChange = onHostnameChanged,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hostname") },
                    placeholder = { Text("example.org") },
                    singleLine = true,
                    supportingText = { Text("Один публичный hostname; TCP/443. URL и IP запрещены.") },
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

                decisionText(state)?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall)
                }

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
                                Intent.createChooser(shareIntent, "Поделиться обезличенным отчётом"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Поделиться обезличенным отчётом") }
                }

                Text(
                    text = "Alpha.6 выполняет по три попытки baseline, TLS split и recovery, классифицирует доказательность и проверяет отчёт по allow-list schema. " +
                        "Она не отправляет HTTP, не входит в аккаунт, не читает тело ответа и " +
                        "не расшифровывает HTTPS. Результат относится только к текущей сети.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        },
        confirmButton = {
            if (state.isBusy) {
                OutlinedButton(onClick = onStop, enabled = state.status != ExternalTlsEvidenceStatus.STOPPING) {
                    Text("Остановить")
                }
            } else {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = state.hostnameInput.isNotBlank() && !globalBusy,
                ) { Text("Запустить 3×A/B/A") }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { dialogOpen = false }, enabled = !state.isBusy) {
                Text("Закрыть")
            }
        },
    )
}

private val presetRows = listOf(
    listOf(ExternalTlsEvidencePreset.TELEGRAM, ExternalTlsEvidencePreset.YOUTUBE),
    listOf(ExternalTlsEvidencePreset.DISCORD, ExternalTlsEvidencePreset.CUSTOM),
)

private fun statusText(state: ExternalTlsEvidenceUiState): String = when (state.status) {
    ExternalTlsEvidenceStatus.IDLE -> "Готово к ручной проверке текущей сети."
    ExternalTlsEvidenceStatus.REQUESTING_PERMISSION -> "Ожидание системного VPN-разрешения."
    ExternalTlsEvidenceStatus.STARTING -> "Проверка hostname, DNS и запуск TEST-NET TUN."
    ExternalTlsEvidenceStatus.RUNNING -> "Три baseline → три TLS split → три recovery."
    ExternalTlsEvidenceStatus.STOPPING -> "Закрытие socket, native stack, TUN и relay."
    ExternalTlsEvidenceStatus.COMPLETED -> "Повторная A/B/A-проверка завершена."
    ExternalTlsEvidenceStatus.ERROR -> state.error ?: "Проверка завершилась ошибкой."
}

private fun decisionText(state: ExternalTlsEvidenceUiState): String? = when (state.reason) {
    "STRATEGY_RESTORED_RESTRICTED_BASELINE" ->
        "Стратегия помогла: baseline и recovery недоступны, TLS split стабильно отвечает."
    "PASSED_WITHIN_LATENCY_BUDGET" ->
        "Соединение доступно и без стратегии; TLS split не ухудшил результат."
    "STRATEGY_DID_NOT_RESTORE_RESTRICTED_BASELINE" ->
        "Стратегия не помогла восстановить TLS-соединение."
    "RESTRICTED_BASELINE_NOT_REPRODUCED" ->
        "Сеть нестабильна: блокировка baseline не повторилась после стратегии."
    "STRATEGY_LATENCY_REGRESSION", "STRATEGY_FAILURE_BUDGET_EXCEEDED" ->
        "Стратегия ухудшила проверку; выполнен rollback."
    else -> state.decision?.let { "Решение: $it" }
}

private fun resultLines(state: ExternalTlsEvidenceUiState): List<String> = listOfNotNull(
    ExternalTlsEvidencePreset.fromId(state.selectedPresetId).displayName.let { "Цель: $it" },
    state.normalizedHostname?.let { "Hostname: $it" },
    state.resolvedIpv4?.let { ip -> "Pinned target: $ip:${state.targetPort ?: 443}" },
    phaseLine("Baseline", state.baselineLatencyMillis, state.baselineRecordKind, state.baselineSuccesses, state.baselineFailures),
    phaseLine("Strategy", state.strategyLatencyMillis, state.strategyRecordKind, state.strategySuccesses, state.strategyFailures),
    phaseLine("Recovery", state.recoveryLatencyMillis, state.recoveryRecordKind, state.recoverySuccesses, state.recoveryFailures),
    evidenceAssessmentLine(state),
    evidenceRecommendationLine(state),
    state.reason?.let { "Reason: $it" },
    state.gateState?.let { "Session gate: $it" },
)

private fun evidenceAssessmentLine(state: ExternalTlsEvidenceUiState): String {
    val assessment = ExternalTlsEvidenceAssessor.assess(state.toEvidenceSummary())
    val classText = when (assessment.evidenceClass) {
        ExternalTlsEvidenceClass.STRATEGY_HELP_CONFIRMED ->
            "помощь стратегии подтверждена текущей серией"
        ExternalTlsEvidenceClass.AVAILABLE_WITHOUT_STRATEGY ->
            "цель доступна без стратегии"
        ExternalTlsEvidenceClass.STRATEGY_NOT_HELPFUL ->
            "стратегия не помогла или ухудшила результат"
        ExternalTlsEvidenceClass.INCONCLUSIVE ->
            "данных недостаточно для вывода"
    }
    val confidenceText = when (assessment.confidence) {
        ExternalTlsEvidenceConfidence.HIGH -> "высокая"
        ExternalTlsEvidenceConfidence.MEDIUM -> "средняя"
        ExternalTlsEvidenceConfidence.LOW -> "низкая"
    }
    return "Доказательность: $classText · уверенность $confidenceText"
}

private fun evidenceRecommendationLine(state: ExternalTlsEvidenceUiState): String {
    val recommendation = ExternalTlsEvidenceAssessor
        .assess(state.toEvidenceSummary())
        .recommendation
    val text = when (recommendation) {
        ExternalTlsEvidenceRecommendation.ATTACH_FOR_MANUAL_REVIEW ->
            "сохранить обезличенный отчёт и повторить на том же устройстве"
        ExternalTlsEvidenceRecommendation.STRATEGY_NOT_REQUIRED ->
            "не включать стратегию: baseline уже доступен"
        ExternalTlsEvidenceRecommendation.KEEP_STRATEGY_DISABLED ->
            "оставить стратегию выключенной"
        ExternalTlsEvidenceRecommendation.REPEAT_ON_SAME_NETWORK ->
            "повторить полную 3×A/B/A-проверку без смены сети"
    }
    return "Следующий шаг: $text"
}

private fun ExternalTlsEvidenceUiState.toEvidenceSummary() =
    ExternalTlsEvidenceSampleSummary(
        samplesPerPhase = 3,
        baselineSuccesses = baselineSuccesses,
        baselineFailures = baselineFailures,
        strategySuccesses = strategySuccesses,
        strategyFailures = strategyFailures,
        recoverySuccesses = recoverySuccesses,
        recoveryFailures = recoveryFailures,
        decision = decision,
        reason = reason,
    )

private fun phaseLine(
    name: String,
    latency: Long?,
    recordKind: String?,
    successes: Int,
    failures: Int,
): String? {
    if (latency == null && recordKind == null && successes == 0 && failures == 0) return null
    return listOfNotNull(
        name,
        "успех $successes/3",
        failures.takeIf { it > 0 }?.let { "ошибки $it" },
        latency?.let { "median $it мс" },
        recordKind,
    ).joinToString(" · ")
}

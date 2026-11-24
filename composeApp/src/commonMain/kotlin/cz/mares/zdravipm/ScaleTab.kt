package cz.mares.zdravipm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ScaleTabContent(
    uiState: HealthUiState,
    bridge: HealthBridge,
    onOpenSettings: () -> Unit = {}
) {
    var showManualDialog by remember { mutableStateOf(false) }
    val cfg = uiState.config
    val hasTuyaKeys = cfg.tuyaAccessId.isNotBlank() && cfg.tuyaAccessSecret.isNotBlank()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MonitorWeight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tesla Smart Scale BF1321",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = uiState.scaleStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val refWeight = cfg.referenceWeightKg
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (refWeight != null) {
                                val minW = (refWeight - cfg.weightToleranceKg).coerceAtLeast(20.0)
                                val maxW = refWeight + cfg.weightToleranceKg
                                Text(
                                    text = "Plovoucí filtr: ${FormatUtils.oneDecimal(minW)} až ${FormatUtils.oneDecimal(maxW)} kg",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Referenční váha ${FormatUtils.twoDecimals(refWeight)} kg (±${FormatUtils.oneDecimal(cfg.weightToleranceKg)} kg). Ostatní členové domácnosti mimo toto okno se automaticky ignorují.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "Váha čeká na první kalibraci",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = "Načti všechna měření z váhy a jedním klepnutím vyber svoji váhu. Od ní se pak automaticky posouvá okno ±${FormatUtils.oneDecimal(cfg.weightToleranceKg)} kg.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!hasTuyaKeys) {
                            Button(
                                onClick = onOpenSettings,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Nastavit Tuya klíče")
                            }
                        } else {
                            Button(
                                onClick = {
                                    bridge.fetchScaleFromTuya(forceCalibrationList = cfg.referenceWeightKg == null)
                                },
                                enabled = !uiState.isScaleSyncing,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when {
                                        uiState.isScaleSyncing -> "Načítám..."
                                        cfg.referenceWeightKg == null -> "Načíst ke kalibraci"
                                        else -> "Načíst váhu"
                                    }
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { showManualDialog = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("+ Ručně")
                        }
                    }

                    if (hasTuyaKeys && cfg.referenceWeightKg != null) {
                        OutlinedButton(
                            onClick = { bridge.fetchScaleFromTuya(forceCalibrationList = true) },
                            enabled = !uiState.isScaleSyncing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Zobrazit všechna měření z váhy (překalibrovat)")
                        }
                    }
                }
            }
        }

        if (uiState.isCalibrationMode || uiState.calibrationCandidates.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Kalibrace: vyber svoji váhu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Klepni na záznam, který patří tobě. Uloží se do Health Connect a nastaví plovoucí filtr ±${FormatUtils.oneDecimal(cfg.weightToleranceKg)} kg:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        if (uiState.calibrationCandidates.isEmpty()) {
                            Text(
                                text = "V cloudu Tuya nebyla za poslední období nalezena žádná vážení.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            uiState.calibrationCandidates.forEach { candidate ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${FormatUtils.twoDecimals(candidate.weightKg)} kg" +
                                                    (candidate.impedanceOhms?.let { "  •  $it Ω" } ?: ""),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = FormatUtils.formatDateTimeCz(candidate.timestampMillis),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Button(
                                            onClick = { bridge.selectCalibrationCandidate(candidate) },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Moje váha")
                                        }
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = { bridge.cancelCalibrationMode() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Zavřít výběr")
                        }
                    }
                }
            }
        }

        val latest = uiState.scaleList.firstOrNull()
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Poslední vážení a složení těla",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (latest == null) {
                        Text(
                            text = "Zatím žádné zkalibrované vážení v telefonu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = FormatUtils.twoDecimals(latest.weightKg),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "kg",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = FormatUtils.formatDateTimeCz(latest.timestampMillis) +
                                (latest.impedanceOhms?.let { " • Odpor $it Ω" } ?: "") +
                                " • ${latest.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f))

                        CompositionRow(
                            leftLabel = "BMI",
                            leftVal = FormatUtils.oneDecimal(latest.bmi),
                            rightLabel = "Tělesný tuk",
                            rightVal = latest.bodyFatPercent?.let { "${FormatUtils.oneDecimal(it)} %" } ?: "—"
                        )
                        CompositionRow(
                            leftLabel = "Svalová hmota",
                            leftVal = latest.muscleMassKg?.let { "${FormatUtils.oneDecimal(it)} kg" } ?: "—",
                            rightLabel = "Beztuková hmota",
                            rightVal = latest.leanBodyMassKg?.let { "${FormatUtils.oneDecimal(it)} kg" } ?: "—"
                        )
                        CompositionRow(
                            leftLabel = "Tělesná voda",
                            leftVal = if (latest.bodyWaterKg != null && latest.bodyWaterPercent != null) {
                                "${FormatUtils.oneDecimal(latest.bodyWaterKg)} kg (${FormatUtils.oneDecimal(latest.bodyWaterPercent)} %)"
                            } else {
                                "—"
                            },
                            rightLabel = "Kostní hmota",
                            rightVal = latest.boneMassKg?.let { "${FormatUtils.twoDecimals(it)} kg" } ?: "—"
                        )
                        CompositionRow(
                            leftLabel = "Bazální metab. (BMR)",
                            leftVal = latest.bmrKcal?.let { "$it kcal" } ?: "—",
                            rightLabel = "Viscerální tuk",
                            rightVal = latest.visceralFatIndex?.toString() ?: "—"
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Historie vážení v telefonu (${uiState.scaleList.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(uiState.scaleList, key = { it.id }) { entry ->
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${FormatUtils.twoDecimals(entry.weightKg)} kg" +
                                (entry.bodyFatPercent?.let { "  •  Tuk ${FormatUtils.oneDecimal(it)} %" } ?: "") +
                                "  •  BMI ${FormatUtils.oneDecimal(entry.bmi)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (entry.muscleMassKg != null || entry.bodyWaterPercent != null || entry.boneMassKg != null) {
                            val parts = buildList {
                                entry.muscleMassKg?.let { add("Svaly ${FormatUtils.oneDecimal(it)} kg") }
                                entry.bodyWaterPercent?.let { add("Voda ${FormatUtils.oneDecimal(it)} %") }
                                entry.boneMassKg?.let { add("Kosti ${FormatUtils.twoDecimals(it)} kg") }
                                entry.bmrKcal?.let { add("BMR $it kcal") }
                            }
                            Text(
                                text = parts.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${FormatUtils.formatDateTimeCz(entry.timestampMillis)} (${entry.source})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (entry.syncedToHealthConnect) Icons.Default.CheckCircle else Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (entry.syncedToHealthConnect) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = if (entry.syncedToHealthConnect) {
                                    "V Health Connect (váha + složení těla)"
                                } else {
                                    "Čeká na zápis do Health Connect"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.syncedToHealthConnect) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    IconButton(onClick = { bridge.deleteScaleEntry(entry.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Smazat")
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualWeightDialog(
            defaultWeight = cfg.referenceWeightKg ?: 171.50,
            onDismiss = { showManualDialog = false },
            onConfirm = { weight, ohms ->
                bridge.addManualWeight(weight, ohms)
                showManualDialog = false
            }
        )
    }
}

@Composable
private fun CompositionRow(
    leftLabel: String,
    leftVal: String,
    rightLabel: String,
    rightVal: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = leftLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
            )
            Text(
                text = leftVal,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rightLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
            )
            Text(
                text = rightVal,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ManualWeightDialog(
    defaultWeight: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double, Int?) -> Unit
) {
    var weightText by remember { mutableStateOf(FormatUtils.twoDecimals(defaultWeight)) }
    var ohmsText by remember { mutableStateOf("867") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zapsat váhu ručně") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("Hmotnost (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = ohmsText,
                    onValueChange = { ohmsText = it },
                    label = { Text("Odpor Body_R v Ω (volitelné)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = weightText.replace(',', '.').trim().toDoubleOrNull()
                    val r = ohmsText.trim().toIntOrNull()
                    if (w != null && w in 20.0..350.0) {
                        onConfirm(w, r)
                    }
                }
            ) {
                Text("Uložit a odeslat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit") }
        }
    )
}

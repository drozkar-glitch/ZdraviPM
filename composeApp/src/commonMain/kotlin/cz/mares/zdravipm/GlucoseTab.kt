package cz.mares.zdravipm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun GlucoseTabContent(
    uiState: HealthUiState,
    bridge: HealthBridge
) {
    var showManualDialog by remember { mutableStateOf(false) }

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
                            imageVector = if (uiState.pairedMeterAddress != null) {
                                Icons.Default.BluetoothConnected
                            } else {
                                Icons.Default.Bluetooth
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.pairedMeterName ?: "Contour Plus One",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = uiState.bleStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (uiState.pairedMeterAddress == null) {
                            Button(
                                onClick = {
                                    bridge.requestBluetoothAndNotificationPermissions()
                                    bridge.scanAndPairContourMeter()
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(if (uiState.isBleScanning) "Hledám..." else "Spárovat glukometr")
                            }
                        } else {
                            Button(
                                onClick = { bridge.syncContourNow() },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Načíst glukometr")
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Trvalé hlídání glukometru na pozadí",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Automatický přenos po změření bez otevírání aplikace",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.config.backgroundServiceEnabled,
                            onCheckedChange = { bridge.setBackgroundServiceEnabled(it) }
                        )
                    }
                }
            }
        }

        val latest = uiState.glucoseList.firstOrNull()
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Poslední glykémie",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (latest == null) {
                        Text(
                            text = "Zatím žádné měření v telefonu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        val inRange = latest.mmolPerLiter in uiState.config.targetGlucoseMin..uiState.config.targetGlucoseMax
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = FormatUtils.oneDecimal(latest.mmolPerLiter),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "mmol/L",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "${latest.mealRelation.labelCz} • ${FormatUtils.formatDateTimeCz(latest.timestampMillis)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (inRange) {
                                "V cílovém rozmezí"
                            } else {
                                "Mimo cílové rozmezí (${FormatUtils.oneDecimal(uiState.config.targetGlucoseMin)} - ${FormatUtils.oneDecimal(uiState.config.targetGlucoseMax)})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (inRange) Color(0xFF1B8755) else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Historie glykémií v telefonu (${uiState.glucoseList.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(uiState.glucoseList, key = { it.id }) { entry ->
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${FormatUtils.oneDecimal(entry.mmolPerLiter)} mmol/L  •  ${entry.mealRelation.labelCz}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
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
                                text = if (entry.syncedToHealthConnect) "V Health Connect" else "Čeká na zápis do Health Connect",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.syncedToHealthConnect) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    IconButton(onClick = { bridge.deleteGlucoseEntry(entry.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Smazat")
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualGlucoseDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { value, meal ->
                bridge.addManualGlucose(value, meal)
                showManualDialog = false
            }
        )
    }
}

@Composable
private fun ManualGlucoseDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double, MealRelation) -> Unit
) {
    var textValue by remember { mutableStateOf("5,6") }
    var selectedMeal by remember { mutableStateOf(MealRelation.FASTING) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zapsat glykémii ručně") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("Hodnota (mmol/L)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                MealRelation.entries.forEach { meal ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedMeal == meal,
                            onClick = { selectedMeal = meal }
                        )
                        Text(meal.labelCz)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = textValue.replace(',', '.').trim().toDoubleOrNull()
                    if (parsed != null && parsed in 1.0..35.0) {
                        onConfirm(parsed, selectedMeal)
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

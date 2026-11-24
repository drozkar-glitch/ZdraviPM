package cz.mares.zdravipm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsTabContent(
    uiState: HealthUiState,
    bridge: HealthBridge
) {
    val cfg = uiState.config
    val credentialBridge = rememberCredentialBridge()
    val scope = rememberCoroutineScope()

    var accessId by remember(cfg.tuyaAccessId) { mutableStateOf(cfg.tuyaAccessId) }
    var accessSecret by remember(cfg.tuyaAccessSecret) { mutableStateOf(cfg.tuyaAccessSecret) }
    var deviceId by remember(cfg.tuyaDeviceId) { mutableStateOf(cfg.tuyaDeviceId) }
    var endpoint by remember(cfg.tuyaEndpoint) { mutableStateOf(cfg.tuyaEndpoint) }
    var showSecret by remember { mutableStateOf(false) }

    var heightText by remember(cfg.heightCm) { mutableStateOf(cfg.heightCm.toString()) }
    var birthYearText by remember(cfg.birthYear) { mutableStateOf(cfg.birthYear.toString()) }
    var isMale by remember(cfg.isMale) { mutableStateOf(cfg.isMale) }
    var writeBodyComp by remember(cfg.writeBodyCompositionToHc) { mutableStateOf(cfg.writeBodyCompositionToHc) }
    var toleranceText by remember(cfg.weightToleranceKg) { mutableStateOf(FormatUtils.oneDecimal(cfg.weightToleranceKg)) }
    var gluMinText by remember(cfg.targetGlucoseMin) { mutableStateOf(FormatUtils.oneDecimal(cfg.targetGlucoseMin)) }
    var gluMaxText by remember(cfg.targetGlucoseMax) { mutableStateOf(FormatUtils.oneDecimal(cfg.targetGlucoseMax)) }
    var savedBanner by remember { mutableStateOf(false) }

    val pendingCount = uiState.glucoseList.count { !it.syncedToHealthConnect } +
        uiState.scaleList.count { !it.syncedToHealthConnect }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            imageVector = if (uiState.healthConnectGranted) Icons.Default.CheckCircle else Icons.Default.HealthAndSafety,
                            contentDescription = null,
                            tint = if (uiState.healthConnectGranted) Color(0xFF1B8755) else MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Health Connect a Samsung Health",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = uiState.healthConnectDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (uiState.lastSyncMessage.isNotBlank()) {
                        Text(
                            text = uiState.lastSyncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!uiState.healthConnectGranted) {
                        Button(
                            onClick = { bridge.requestHealthConnectPermissions() },
                            enabled = uiState.healthConnectAvailable,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Povolit zápis do Health Connect")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { bridge.syncAllPendingToHealthConnect() },
                            enabled = uiState.healthConnectAvailable,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (pendingCount > 0) "Odeslat čekající ($pendingCount)" else "Synchronizovat vše")
                        }

                        OutlinedButton(
                            onClick = { bridge.openHealthConnectSystemSettings() },
                            enabled = uiState.healthConnectAvailable,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Otevřít HC")
                        }
                    }

                    Text(
                        text = "Tip pro Samsung Health: V Samsung Health otevři Nastavení -> Health Connect a zapni čtení pro Glukózu v krvi, Hmotnost a Složení těla.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Osobní parametry a filtr váhy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it },
                            label = { Text("Výška (cm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = birthYearText,
                            onValueChange = { birthYearText = it },
                            label = { Text("Rok narození") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Pohlaví pro výpočet BIA:", style = MaterialTheme.typography.bodyMedium)
                        FilterChip(
                            selected = isMale,
                            onClick = { isMale = true },
                            label = { Text("Muž") }
                        )
                        FilterChip(
                            selected = !isMale,
                            onClick = { isMale = false },
                            label = { Text("Žena") }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Zapisovat složení těla do Health Connect",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Tuk %, beztuková a kostní hmota, tělesná voda, BMR a výška",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = writeBodyComp,
                            onCheckedChange = { writeBodyComp = it }
                        )
                    }

                    OutlinedTextField(
                        value = toleranceText,
                        onValueChange = { toleranceText = it },
                        label = { Text("Plovoucí tolerance váhy (± kg od poslední váhy)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (cfg.referenceWeightKg != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Aktuální kalibrace: ${FormatUtils.twoDecimals(cfg.referenceWeightKg)} kg",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { bridge.resetWeightCalibration() }) {
                                Text("Vynulovat kalibraci")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = gluMinText,
                            onValueChange = { gluMinText = it },
                            label = { Text("Cíl glukóza min") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = gluMaxText,
                            onValueChange = { gluMaxText = it },
                            label = { Text("Cíl glukóza max") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Tuya IoT Core (váha Tesla BF1321)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (credentialBridge.isSupported) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    credentialBridge.loadCredentials()?.let { (savedId, savedSecret) ->
                                        accessId = savedId
                                        accessSecret = savedSecret
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("🔑 Vyplnit uložený účet")
                        }
                    }

                    OutlinedTextField(
                        value = accessId,
                        onValueChange = { accessId = it },
                        label = { Text("Tuya Access ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = accessSecret,
                        onValueChange = { accessSecret = it },
                        label = { Text("Tuya Access Secret") },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        label = { Text("Device ID váhy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("Tuya Endpoint (EU)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            val updated = cfg.copy(
                                tuyaAccessId = accessId.trim(),
                                tuyaAccessSecret = accessSecret.trim(),
                                tuyaDeviceId = deviceId.trim().ifBlank { "00821002c45bbee6ebbe" },
                                tuyaEndpoint = endpoint.trim().ifBlank { "https://openapi.tuyaeu.com" },
                                heightCm = heightText.trim().toIntOrNull()?.coerceIn(110, 230) ?: cfg.heightCm,
                                birthYear = birthYearText.trim().toIntOrNull()?.coerceIn(1925, 2020) ?: cfg.birthYear,
                                isMale = isMale,
                                writeBodyCompositionToHc = writeBodyComp,
                                weightToleranceKg = toleranceText.replace(',', '.').trim().toDoubleOrNull()?.coerceIn(2.0, 50.0)
                                    ?: cfg.weightToleranceKg,
                                targetGlucoseMin = gluMinText.replace(',', '.').trim().toDoubleOrNull()?.coerceIn(2.0, 10.0)
                                    ?: cfg.targetGlucoseMin,
                                targetGlucoseMax = gluMaxText.replace(',', '.').trim().toDoubleOrNull()?.coerceIn(4.0, 20.0)
                                    ?: cfg.targetGlucoseMax
                            )
                            bridge.saveScaleConfig(updated)
                            savedBanner = true
                            if (credentialBridge.isSupported && updated.tuyaAccessId.isNotBlank() && updated.tuyaAccessSecret.isNotBlank()) {
                                scope.launch {
                                    credentialBridge.saveCredentials(updated.tuyaAccessId, updated.tuyaAccessSecret)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Uložit veškeré nastavení")
                    }

                    if (savedBanner) {
                        Text(
                            text = "Nastavení bylo uloženo v telefonu.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B8755)
                        )
                    }

                    if (uiState.tuyaDiagnosticLog.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = uiState.tuyaDiagnosticLog,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        if (uiState.pairedMeterAddress != null) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.pairedMeterName ?: "Contour Plus One",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "MAC: ${uiState.pairedMeterAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { bridge.unpairContourMeter() },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Odpárovat")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "© 2026 Created by Pavel Mareš",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }
    }
}

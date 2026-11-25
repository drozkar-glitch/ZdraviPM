package cz.mares.zdravipm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import kotlin.math.roundToInt

private fun adaptSettingsHeight(current: Int, parsed: Double): Int = parsed.roundToInt()
private fun adaptSettingsHeight(current: Double, parsed: Double): Double = parsed
private fun formatSettingsHeight(v: Int): String = v.toString()
private fun formatSettingsHeight(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else FormatUtils.oneDecimal(v)

@Composable
fun SettingsTabContent(
    uiState: HealthUiState,
    bridge: HealthBridge
) {
    val cfg = uiState.config
    val credentialBridge = rememberCredentialBridge()
    val scope = rememberCoroutineScope()

    var heightText by remember(cfg.heightCm) { mutableStateOf(formatSettingsHeight(cfg.heightCm)) }
    var birthYearText by remember(cfg.birthYear) { mutableStateOf(cfg.birthYear.toString()) }
    var isMale by remember(cfg.isMale) { mutableStateOf(cfg.isMale) }
    var toleranceText by remember(cfg.weightToleranceKg) {
        mutableStateOf(FormatUtils.oneDecimal(cfg.weightToleranceKg))
    }
    var minGlucoseText by remember(cfg.targetGlucoseMin) {
        mutableStateOf(FormatUtils.oneDecimal(cfg.targetGlucoseMin))
    }
    var maxGlucoseText by remember(cfg.targetGlucoseMax) {
        mutableStateOf(FormatUtils.oneDecimal(cfg.targetGlucoseMax))
    }
    var writeComposition by remember(cfg.writeBodyCompositionToHc) {
        mutableStateOf(cfg.writeBodyCompositionToHc)
    }
    var bgWatchEnabled by remember(cfg.backgroundServiceEnabled) {
        mutableStateOf(cfg.backgroundServiceEnabled)
    }

    var accessIdText by remember(cfg.tuyaAccessId) { mutableStateOf(cfg.tuyaAccessId) }
    var accessSecretText by remember(cfg.tuyaAccessSecret) { mutableStateOf(cfg.tuyaAccessSecret) }
    var deviceIdText by remember(cfg.tuyaDeviceId) {
        mutableStateOf(cfg.tuyaDeviceId.ifBlank { "00821002c45bbee6ebbe" })
    }
    var endpointText by remember(cfg.tuyaEndpoint) {
        mutableStateOf(cfg.tuyaEndpoint.ifBlank { "https://openapi.tuyaeu.com" })
    }
    var showSecret by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf("") }

    val pendingCount = uiState.glucoseList.count { !it.syncedToHealthConnect } +
        uiState.scaleList.count { !it.syncedToHealthConnect }

    val saveAllSettings = {
        val parsedH = heightText.replace(',', '.').trim().toDoubleOrNull()?.coerceIn(100.0, 230.0)
            ?: cfg.heightCm.toDouble()
        val parsedYear = birthYearText.trim().toIntOrNull()?.coerceIn(1920, 2025) ?: cfg.birthYear
        val parsedTol = toleranceText.replace(',', '.').trim().toDoubleOrNull()?.coerceIn(2.0, 40.0)
            ?: cfg.weightToleranceKg
        val parsedMinG = minGlucoseText.replace(',', '.').trim().toDoubleOrNull()?.coerceIn(2.0, 10.0)
            ?: cfg.targetGlucoseMin
        val parsedMaxG = maxGlucoseText.replace(',', '.').trim().toDoubleOrNull()?.coerceIn(4.0, 20.0)
            ?: cfg.targetGlucoseMax

        val cleanId = accessIdText.trim()
        val cleanSecret = accessSecretText.trim()
        val cleanDev = deviceIdText.trim().ifBlank { "00821002c45bbee6ebbe" }
        val cleanEnd = endpointText.trim().ifBlank { "https://openapi.tuyaeu.com" }

        val updated = cfg.copy(
            tuyaAccessId = cleanId,
            tuyaAccessSecret = cleanSecret,
            tuyaDeviceId = cleanDev,
            tuyaEndpoint = cleanEnd,
            heightCm = adaptSettingsHeight(cfg.heightCm, parsedH),
            birthYear = parsedYear,
            isMale = isMale,
            weightToleranceKg = parsedTol,
            targetGlucoseMin = parsedMinG,
            targetGlucoseMax = parsedMaxG,
            writeBodyCompositionToHc = writeComposition,
            backgroundServiceEnabled = bgWatchEnabled
        )
        bridge.saveScaleConfig(updated)
        bridge.setBackgroundServiceEnabled(bgWatchEnabled)
        saveFeedback = "Nastavení bylo uloženo v telefonu."

        if (credentialBridge.isSupported && cleanId.isNotBlank() && cleanSecret.isNotBlank()) {
            scope.launch {
                credentialBridge.saveCredentials(cleanId, cleanSecret)
            }
        }
    }

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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.healthConnectGranted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (uiState.healthConnectGranted) Color(0xFF1B8755) else MaterialTheme.colorScheme.error
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
                        val isErr = uiState.lastSyncMessage.contains("Chyba", ignoreCase = true)
                        Text(
                            text = uiState.lastSyncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!uiState.healthConnectGranted) {
                        Button(
                            onClick = { bridge.requestHealthConnectPermissions() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Povolit přístup v Health Connect")
                        }
                    }

                    Button(
                        onClick = { bridge.syncAllPendingToHealthConnect() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (pendingCount > 0) {
                                "Odeslat čekající ($pendingCount)"
                            } else {
                                "Synchronizovat s Health Connect"
                            },
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = { bridge.openHealthConnectSystemSettings() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Otevřít Health Connect", maxLines = 1)
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Osobní parametry a filtrování",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = isMale,
                            onClick = { isMale = true },
                            label = { Text("Muž") },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        FilterChip(
                            selected = !isMale,
                            onClick = { isMale = false },
                            label = { Text("Žena") },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }

                    OutlinedTextField(
                        value = toleranceText,
                        onValueChange = { toleranceText = it },
                        label = { Text("Tolerance plovoucího filtru váhy (± kg)") },
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
                                text = "Referenční váha: ${FormatUtils.twoDecimals(cfg.referenceWeightKg)} kg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { bridge.resetWeightCalibration() }) {
                                Text("Reset kalibrace")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = minGlucoseText,
                            onValueChange = { minGlucoseText = it },
                            label = { Text("Cukr min (mmol/L)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxGlucoseText,
                            onValueChange = { maxGlucoseText = it },
                            label = { Text("Cukr max (mmol/L)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Zapisovat složení těla do HC",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Tuk, svalová a kostní hmota, voda a bazální metabolismus",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = writeComposition,
                            onCheckedChange = { writeComposition = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Tiché hlídání glukometru na pozadí",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Automatický přenos při zapnutí Contouru bez ikony v liště",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bgWatchEnabled,
                            onCheckedChange = {
                                bgWatchEnabled = it
                                bridge.setBackgroundServiceEnabled(it)
                            }
                        )
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                        accessIdText = savedId
                                        accessSecretText = savedSecret
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("🔑 Vyplnit uložený účet")
                        }
                    }

                    OutlinedTextField(
                        value = accessIdText,
                        onValueChange = { accessIdText = it },
                        label = { Text("Tuya Access ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = accessSecretText,
                        onValueChange = { accessSecretText = it },
                        label = { Text("Tuya Access Secret") },
                        singleLine = true,
                        visualTransformation = if (showSecret) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showSecret) "Skrýt" else "Zobrazit"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = deviceIdText,
                        onValueChange = { deviceIdText = it },
                        label = { Text("Device ID váhy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = endpointText,
                        onValueChange = { endpointText = it },
                        label = { Text("Tuya Endpoint (EU)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = saveAllSettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Uložit veškeré nastavení", fontWeight = FontWeight.Bold)
                    }

                    if (saveFeedback.isNotBlank()) {
                        Text(
                            text = saveFeedback,
                            style = MaterialTheme.typography.bodyMedium,
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
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = uiState.pairedMeterName ?: "Glukometr nespárován",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (!uiState.pairedMeterAddress.isNullOrBlank()) {
                                "MAC: ${uiState.pairedMeterAddress}"
                            } else {
                                "Spáruj Contour Plus One na kartě Cukr"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!uiState.pairedMeterAddress.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { bridge.unpairContourMeter() },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }
    }
}

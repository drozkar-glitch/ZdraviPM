package cz.mares.zdravipm

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

interface HealthBridge {
    val state: StateFlow<HealthUiState>

    fun requestBluetoothAndNotificationPermissions()
    fun requestHealthConnectPermissions()
    fun openHealthConnectSystemSettings()

    fun scanAndPairContourMeter()
    fun syncContourNow()
    fun unpairContourMeter()
    fun setBackgroundServiceEnabled(enabled: Boolean)
    fun addManualGlucose(mmolPerLiter: Double, mealRelation: MealRelation)

    fun saveScaleConfig(newConfig: UserScaleConfig)
    fun fetchScaleFromTuya(forceCalibrationList: Boolean = false)
    fun selectCalibrationCandidate(candidate: RawScaleCandidate)
    fun cancelCalibrationMode()
    fun resetWeightCalibration()
    fun addManualWeight(weightKg: Double, impedanceOhms: Int? = null)

    fun syncAllPendingToHealthConnect()
    fun deleteGlucoseEntry(id: String)
    fun deleteScaleEntry(id: String)
}

@Composable
expect fun rememberHealthBridge(): HealthBridge

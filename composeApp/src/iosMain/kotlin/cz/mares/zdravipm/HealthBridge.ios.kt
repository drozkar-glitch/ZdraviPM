package cz.mares.zdravipm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

private class IosHealthBridge : HealthBridge {
    private val _state = MutableStateFlow(
        HealthUiState(
            bleStatusText = "Offline deník (iOS režim)",
            scaleStatusText = "Ruční záznam hmotnosti a složení těla",
            healthConnectAvailable = false,
            healthConnectGranted = false,
            healthConnectDetail = "Android Health Connect je dostupný na zařízeních Android."
        )
    )
    override val state: StateFlow<HealthUiState> = _state.asStateFlow()

    private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    override fun requestBluetoothAndNotificationPermissions() = Unit
    override fun requestHealthConnectPermissions() = Unit
    override fun openHealthConnectSystemSettings() = Unit
    override fun scanAndPairContourMeter() = Unit
    override fun syncContourNow() = Unit
    override fun unpairContourMeter() = Unit

    override fun setBackgroundServiceEnabled(enabled: Boolean) {
        _state.update { it.copy(config = it.config.copy(backgroundServiceEnabled = enabled)) }
    }

    override fun addManualGlucose(mmolPerLiter: Double, mealRelation: MealRelation) {
        val ts = nowMillis()
        val entry = GlucoseEntry(
            id = "ios_glu_$ts",
            timestampMillis = ts,
            mmolPerLiter = mmolPerLiter,
            mealRelation = mealRelation,
            sequenceNumber = 0,
            source = "Ruční zápis",
            syncedToHealthConnect = false
        )
        _state.update {
            it.copy(glucoseList = (listOf(entry) + it.glucoseList).sortedByDescending { e -> e.timestampMillis })
        }
    }

    override fun saveScaleConfig(newConfig: UserScaleConfig) {
        _state.update { it.copy(config = newConfig) }
    }

    override fun fetchScaleFromTuya(forceCalibrationList: Boolean) = Unit

    override fun selectCalibrationCandidate(candidate: RawScaleCandidate) {
        addManualWeight(candidate.weightKg, candidate.impedanceOhms)
        _state.update {
            it.copy(
                isCalibrationMode = false,
                calibrationCandidates = emptyList(),
                config = it.config.copy(referenceWeightKg = candidate.weightKg)
            )
        }
    }

    override fun cancelCalibrationMode() {
        _state.update { it.copy(isCalibrationMode = false, calibrationCandidates = emptyList()) }
    }

    override fun resetWeightCalibration() {
        _state.update { it.copy(config = it.config.copy(referenceWeightKg = null)) }
    }

    override fun addManualWeight(weightKg: Double, impedanceOhms: Int?) {
        val ts = nowMillis()
        val cfg = _state.value.config
        val comp = BodyCompositionCalculator.calculate(
            weightKg = weightKg,
            impedanceOhms = impedanceOhms,
            heightCm = cfg.heightCm,
            birthYear = cfg.birthYear,
            isMale = cfg.isMale
        )
        val entry = ScaleEntry(
            id = "ios_scale_$ts",
            timestampMillis = ts,
            weightKg = weightKg,
            impedanceOhms = impedanceOhms,
            bmi = comp.bmi,
            bodyFatPercent = comp.bodyFatPercent,
            leanBodyMassKg = comp.leanBodyMassKg,
            muscleMassKg = comp.muscleMassKg,
            bodyWaterKg = comp.bodyWaterKg,
            bodyWaterPercent = comp.bodyWaterPercent,
            boneMassKg = comp.boneMassKg,
            bmrKcal = comp.bmrKcal,
            visceralFatIndex = comp.visceralFatIndex,
            source = "Ruční zápis",
            syncedToHealthConnect = false
        )
        _state.update {
            it.copy(
                config = it.config.copy(referenceWeightKg = weightKg),
                scaleList = (listOf(entry) + it.scaleList).sortedByDescending { e -> e.timestampMillis }
            )
        }
    }

    override fun syncAllPendingToHealthConnect() = Unit

    override fun deleteGlucoseEntry(id: String) {
        _state.update { it.copy(glucoseList = it.glucoseList.filterNot { e -> e.id == id }) }
    }

    override fun deleteScaleEntry(id: String) {
        _state.update { it.copy(scaleList = it.scaleList.filterNot { e -> e.id == id }) }
    }
}

@Composable
actual fun rememberHealthBridge(): HealthBridge = remember { IosHealthBridge() }

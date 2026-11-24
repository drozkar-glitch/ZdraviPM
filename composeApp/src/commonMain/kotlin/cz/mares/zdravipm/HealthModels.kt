package cz.mares.zdravipm

import kotlin.math.abs
import kotlin.math.roundToLong

enum class MealRelation(val labelCz: String) {
    GENERAL("Obecné"),
    FASTING("Nalačno"),
    BEFORE_MEAL("Před jídlem"),
    AFTER_MEAL("Po jídle")
}

data class GlucoseEntry(
    val id: String,
    val timestampMillis: Long,
    val mmolPerLiter: Double,
    val mealRelation: MealRelation,
    val sequenceNumber: Int,
    val source: String,
    val syncedToHealthConnect: Boolean
)

data class ScaleEntry(
    val id: String,
    val timestampMillis: Long,
    val weightKg: Double,
    val impedanceOhms: Int?,
    val bmi: Double,
    val bodyFatPercent: Double?,
    val leanBodyMassKg: Double?,
    val muscleMassKg: Double?,
    val bodyWaterKg: Double?,
    val bodyWaterPercent: Double?,
    val boneMassKg: Double?,
    val bmrKcal: Int?,
    val visceralFatIndex: Int?,
    val source: String,
    val syncedToHealthConnect: Boolean
)

data class RawScaleCandidate(
    val id: String,
    val timestampMillis: Long,
    val weightKg: Double,
    val impedanceOhms: Int?
)

data class UserScaleConfig(
    val tuyaAccessId: String = "",
    val tuyaAccessSecret: String = "",
    val tuyaDeviceId: String = "00821002c45bbee6ebbe",
    val tuyaEndpoint: String = "https://openapi.tuyaeu.com",
    val referenceWeightKg: Double? = null,
    val weightToleranceKg: Double = 10.0,
    val heightCm: Int = 180,
    val birthYear: Int = 1978,
    val isMale: Boolean = true,
    val writeBodyCompositionToHc: Boolean = true,
    val backgroundServiceEnabled: Boolean = true,
    val targetGlucoseMin: Double = 3.9,
    val targetGlucoseMax: Double = 7.8
)

data class HealthUiState(
    val bleStatusText: String = "Nepřipojeno ke glukometru",
    val pairedMeterName: String? = null,
    val pairedMeterAddress: String? = null,
    val isBleScanning: Boolean = false,
    val scaleStatusText: String = "Připraveno k synchronizaci váhy",
    val isScaleSyncing: Boolean = false,
    val tuyaDiagnosticLog: String = "",
    val healthConnectAvailable: Boolean = false,
    val healthConnectGranted: Boolean = false,
    val healthConnectDetail: String = "Kontroluji Health Connect...",
    val lastSyncMessage: String = "",
    val config: UserScaleConfig = UserScaleConfig(),
    val glucoseList: List<GlucoseEntry> = emptyList(),
    val scaleList: List<ScaleEntry> = emptyList(),
    val isCalibrationMode: Boolean = false,
    val calibrationCandidates: List<RawScaleCandidate> = emptyList()
)

object FormatUtils {
    fun oneDecimal(value: Double): String {
        val scaled = (value * 10.0).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val absVal = abs(scaled)
        return "$sign${absVal / 10},${absVal % 10}"
    }

    fun twoDecimals(value: Double): String {
        val scaled = (value * 100.0).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val absVal = abs(scaled)
        val frac = (absVal % 100).toString().padStart(2, '0')
        return "$sign${absVal / 100},$frac"
    }

    fun formatDateTimeCz(timestampMillis: Long): String {
        val utcSec = timestampMillis / 1000L
        val offsetSec = if (isPragueSummerTime(utcSec)) 7200L else 3600L
        val localSec = utcSec + offsetSec
        val days = localSec.floorDiv(86400L)
        val secOfDay = (localSec - days * 86400L).toInt()

        val z = days + 719468L
        val era = (if (z >= 0L) z else z - 146096L) / 146097L
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val day = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
        val month = (mp + (if (mp < 10L) 3L else -9L)).toInt()
        val year = (y + (if (month <= 2) 1L else 0L)).toInt()

        val hour = (secOfDay / 3600).toString().padStart(2, '0')
        val minute = ((secOfDay % 3600) / 60).toString().padStart(2, '0')
        return "$day. $month. $year $hour:$minute"
    }

    private fun isPragueSummerTime(utcSec: Long): Boolean {
        val days = utcSec.floorDiv(86400L)
        val z = days + 719468L
        val era = (if (z >= 0L) z else z - 146096L) / 146097L
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val month = (mp + (if (mp < 10L) 3L else -9L)).toInt()
        val year = (y + (if (month <= 2) 1L else 0L)).toInt()

        if (month < 3 || month > 10) return false
        if (month in 4..9) return true

        val startUtc = lastSundayAtOneUtc(year, 3)
        val endUtc = lastSundayAtOneUtc(year, 10)
        return utcSec in startUtc until endUtc
    }

    private fun lastSundayAtOneUtc(year: Int, month: Int): Long {
        val y = year - (if (month <= 2) 1 else 0)
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = month + (if (month > 2) -3 else 9)
        val doy = (153 * mp + 2) / 5 + 31 - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val daysMarchOrOct31 = era * 146097L + doe - 719468L
        val dow = (daysMarchOrOct31 + 4L).mod(7)
        val lastSundayDays = daysMarchOrOct31 - dow
        return lastSundayDays * 86400L + 3600L
    }
}

package cz.mares.zdravipm

import kotlin.math.roundToInt

data class BodyCompositionResult(
    val bmi: Double,
    val bodyFatPercent: Double?,
    val leanBodyMassKg: Double?,
    val muscleMassKg: Double?,
    val bodyWaterKg: Double?,
    val bodyWaterPercent: Double?,
    val boneMassKg: Double?,
    val bmrKcal: Int?,
    val visceralFatIndex: Int?
)

object BodyCompositionCalculator {
    fun calculate(
        weightKg: Double,
        impedanceOhms: Int?,
        heightCm: Int,
        birthYear: Int,
        isMale: Boolean
    ): BodyCompositionResult {
        val safeWeight = weightKg.coerceIn(20.0, 350.0)
        val safeHeightCm = heightCm.coerceIn(110, 230)
        val heightM = safeHeightCm / 100.0
        val age = (2026 - birthYear).coerceIn(10, 100)
        val bmi = safeWeight / (heightM * heightM)

        val validImpedance = impedanceOhms?.takeIf { it in 150..1600 }
        if (validImpedance == null) {
            return BodyCompositionResult(
                bmi = bmi,
                bodyFatPercent = null,
                leanBodyMassKg = null,
                muscleMassKg = null,
                bodyWaterKg = null,
                bodyWaterPercent = null,
                boneMassKg = null,
                bmrKcal = null,
                visceralFatIndex = null
            )
        }

        val h2OverR = (safeHeightCm * safeHeightCm).toDouble() / validImpedance.toDouble()
        val rawLbm = if (isMale) {
            0.485 * h2OverR + 0.338 * safeWeight + 5.32 - (0.05 * (age - 30).coerceAtLeast(0))
        } else {
            0.475 * h2OverR + 0.295 * safeWeight + 3.80 - (0.04 * (age - 30).coerceAtLeast(0))
        }

        val minLbm = safeWeight * 0.34
        val maxLbm = safeWeight * 0.94
        val leanBodyMassKg = rawLbm.coerceIn(minLbm, maxLbm)

        val fatMassKg = (safeWeight - leanBodyMassKg).coerceAtLeast(1.0)
        val bodyFatPercent = ((fatMassKg / safeWeight) * 100.0).coerceIn(5.0, 66.0)

        val boneMassKg = (leanBodyMassKg * (if (isMale) 0.042 else 0.039)).coerceIn(1.8, 5.2)
        val muscleMassKg = (leanBodyMassKg - boneMassKg).coerceAtLeast(10.0)

        val bodyWaterKg = (leanBodyMassKg * 0.732).coerceIn(10.0, safeWeight * 0.80)
        val bodyWaterPercent = ((bodyWaterKg / safeWeight) * 100.0).coerceIn(25.0, 75.0)

        val bmrDouble = if (isMale) {
            10.0 * safeWeight + 6.25 * safeHeightCm - 5.0 * age + 5.0
        } else {
            10.0 * safeWeight + 6.25 * safeHeightCm - 5.0 * age - 161.0
        }
        val bmrKcal = bmrDouble.roundToInt().coerceIn(800, 5000)

        val rawVisceral = if (isMale) {
            (bmi * 0.52 + age * 0.09 - 9.5).roundToInt()
        } else {
            (bmi * 0.44 + age * 0.08 - 9.8).roundToInt()
        }
        val visceralFatIndex = rawVisceral.coerceIn(1, 30)

        return BodyCompositionResult(
            bmi = bmi,
            bodyFatPercent = bodyFatPercent,
            leanBodyMassKg = leanBodyMassKg,
            muscleMassKg = muscleMassKg,
            bodyWaterKg = bodyWaterKg,
            bodyWaterPercent = bodyWaterPercent,
            boneMassKg = boneMassKg,
            bmrKcal = bmrKcal,
            visceralFatIndex = visceralFatIndex
        )
    }
}

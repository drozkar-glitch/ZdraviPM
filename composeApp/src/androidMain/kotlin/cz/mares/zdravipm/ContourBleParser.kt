package cz.mares.zdravipm

import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

data class ParsedContourMeasurement(
    val sequenceNumber: Int,
    val timestampMillis: Long,
    val mmolPerLiter: Double,
    val contextFollows: Boolean
)

object ContourBleParser {
    val GLUCOSE_SERVICE_UUID: UUID = UUID.fromString("00001808-0000-1000-8000-00805f9b34fb")
    val GLUCOSE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a18-0000-1000-8000-00805f9b34fb")
    val GLUCOSE_CONTEXT_UUID: UUID = UUID.fromString("00002a34-0000-1000-8000-00805f9b34fb")
    val RACP_UUID: UUID = UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)

    private fun s16le(bytes: ByteArray, offset: Int): Int {
        val v = u16le(bytes, offset)
        return if (v >= 32768) v - 65536 else v
    }

    private fun decodeSfloat(raw: Int): Double? {
        val mantRaw = raw and 0x0FFF
        if (mantRaw == 0x07FF || mantRaw == 0x0800 || mantRaw == 0x07FE || mantRaw == 0x0802) {
            return null
        }
        val mant = if (mantRaw >= 2048) mantRaw - 4096 else mantRaw
        val expRaw = (raw ushr 12) and 0x0F
        val exp = if (expRaw >= 8) expRaw - 16 else expRaw
        return mant.toDouble() * 10.0.pow(exp.toDouble())
    }

    fun parseMeasurement(bytes: ByteArray): ParsedContourMeasurement? {
        if (bytes.size < 10) return null
        val flags = u8(bytes[0])
        val seq = u16le(bytes, 1)
        val year = u16le(bytes, 3)
        val month = u8(bytes[5])
        val day = u8(bytes[6])
        val hour = u8(bytes[7])
        val minute = u8(bytes[8])
        val second = u8(bytes[9])

        var idx = 10
        val offsetMinutes = if ((flags and 0x01) != 0) {
            if (bytes.size < idx + 2) return null
            val off = s16le(bytes, idx)
            idx += 2
            off
        } else {
            0
        }

        if ((flags and 0x02) == 0 || bytes.size < idx + 3) return null
        val rawConcentration = u16le(bytes, idx)
        val baseValue = decodeSfloat(rawConcentration) ?: return null

        val isMolPerLiter = (flags and 0x04) != 0
        val rawMmol = if (isMolPerLiter) {
            baseValue * 1000.0
        } else {
            (baseValue * 100000.0) / 18.0182
        }
        val roundedMmol = (rawMmol * 10.0).roundToInt() / 10.0
        if (roundedMmol.isNaN() || roundedMmol !in 0.6..40.0) return null

        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.MILLISECOND, 0)
            set(
                year.coerceIn(2015, 2040),
                (month - 1).coerceIn(0, 11),
                day.coerceIn(1, 31),
                hour.coerceIn(0, 23),
                minute.coerceIn(0, 59),
                second.coerceIn(0, 59)
            )
            add(Calendar.MINUTE, offsetMinutes)
        }

        return ParsedContourMeasurement(
            sequenceNumber = seq,
            timestampMillis = cal.timeInMillis,
            mmolPerLiter = roundedMmol,
            contextFollows = (flags and 0x10) != 0
        )
    }

    fun parseContextMeal(bytes: ByteArray): Pair<Int, MealRelation>? {
        if (bytes.size < 4) return null
        val flags = u8(bytes[0])
        val seq = u16le(bytes, 1)
        var idx = 3

        if ((flags and 0x80) != 0) {
            idx += 1
        }
        if ((flags and 0x01) != 0) {
            idx += 3
        }
        if ((flags and 0x02) == 0 || bytes.size <= idx) return null

        val mealCode = u8(bytes[idx])
        val relation = when (mealCode) {
            1 -> MealRelation.BEFORE_MEAL
            2 -> MealRelation.AFTER_MEAL
            3 -> MealRelation.FASTING
            else -> MealRelation.GENERAL
        }
        return seq to relation
    }
}

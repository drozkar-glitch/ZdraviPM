package cz.mares.zdravipm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

object LocalHealthStore {
    private const val PREF_NAME = "zdravipm_local_store"
    private const val KEY_CONFIG = "user_scale_config"
    private const val KEY_METER_NAME = "paired_meter_name"
    private const val KEY_METER_ADDR = "paired_meter_addr"
    private const val KEY_GLUCOSE_LIST = "glucose_entries_json"
    private const val KEY_SCALE_LIST = "scale_entries_json"
    private const val KEY_LAST_TUYA_DIAG = "last_tuya_diag"

    private val lock = Any()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun notifyChanged() {
        _version.value = _version.value + 1L
    }

    private fun adaptHeight(current: Int, parsed: Double): Int = parsed.roundToInt()
    private fun adaptHeight(current: Double, parsed: Double): Double = parsed

    fun loadConfig(context: Context): UserScaleConfig {
        synchronized(lock) {
            val base = UserScaleConfig()
            val raw = prefs(context).getString(KEY_CONFIG, null) ?: return base
            return try {
                val obj = JSONObject(raw)
                val parsedHeight = obj.optDouble("heightCm", base.heightCm.toDouble())
                val refW = if (obj.has("referenceWeightKg") && !obj.isNull("referenceWeightKg")) {
                    obj.optDouble("referenceWeightKg").takeIf { !it.isNaN() && it > 0.0 }
                } else {
                    base.referenceWeightKg
                }
                base.copy(
                    tuyaAccessId = obj.optString("tuyaAccessId", base.tuyaAccessId),
                    tuyaAccessSecret = obj.optString("tuyaAccessSecret", base.tuyaAccessSecret),
                    tuyaDeviceId = obj.optString("tuyaDeviceId", base.tuyaDeviceId),
                    tuyaEndpoint = obj.optString("tuyaEndpoint", base.tuyaEndpoint),
                    heightCm = adaptHeight(base.heightCm, parsedHeight),
                    birthYear = obj.optInt("birthYear", base.birthYear),
                    isMale = obj.optBoolean("isMale", base.isMale),
                    referenceWeightKg = refW,
                    weightToleranceKg = obj.optDouble("weightToleranceKg", base.weightToleranceKg),
                    targetGlucoseMin = obj.optDouble("targetGlucoseMin", base.targetGlucoseMin),
                    targetGlucoseMax = obj.optDouble("targetGlucoseMax", base.targetGlucoseMax),
                    writeBodyCompositionToHc = obj.optBoolean(
                        "writeBodyCompositionToHc",
                        base.writeBodyCompositionToHc
                    ),
                    backgroundServiceEnabled = obj.optBoolean(
                        "backgroundServiceEnabled",
                        base.backgroundServiceEnabled
                    )
                )
            } catch (_: Throwable) {
                base
            }
        }
    }

    fun saveConfig(context: Context, config: UserScaleConfig) {
        synchronized(lock) {
            val obj = JSONObject().apply {
                put("tuyaAccessId", config.tuyaAccessId)
                put("tuyaAccessSecret", config.tuyaAccessSecret)
                put("tuyaDeviceId", config.tuyaDeviceId)
                put("tuyaEndpoint", config.tuyaEndpoint)
                put("heightCm", config.heightCm.toDouble())
                put("birthYear", config.birthYear)
                put("isMale", config.isMale)
                if (config.referenceWeightKg != null) {
                    put("referenceWeightKg", config.referenceWeightKg)
                } else {
                    put("referenceWeightKg", JSONObject.NULL)
                }
                put("weightToleranceKg", config.weightToleranceKg)
                put("targetGlucoseMin", config.targetGlucoseMin)
                put("targetGlucoseMax", config.targetGlucoseMax)
                put("writeBodyCompositionToHc", config.writeBodyCompositionToHc)
                put("backgroundServiceEnabled", config.backgroundServiceEnabled)
            }
            prefs(context).edit().putString(KEY_CONFIG, obj.toString()).apply()
        }
        notifyChanged()
    }

    fun getPairedMeter(context: Context): Pair<String?, String?> {
        synchronized(lock) {
            val p = prefs(context)
            return p.getString(KEY_METER_NAME, null) to p.getString(KEY_METER_ADDR, null)
        }
    }

    fun setPairedMeter(context: Context, name: String?, address: String?) {
        synchronized(lock) {
            val editor = prefs(context).edit()
            if (name.isNullOrBlank() || address.isNullOrBlank()) {
                editor.remove(KEY_METER_NAME).remove(KEY_METER_ADDR)
            } else {
                editor.putString(KEY_METER_NAME, name).putString(KEY_METER_ADDR, address)
            }
            editor.apply()
        }
        notifyChanged()
    }

    fun clearPairedMeter(context: Context) = setPairedMeter(context, null, null)

    fun loadLastTuyaDiag(context: Context): String {
        synchronized(lock) {
            return prefs(context).getString(KEY_LAST_TUYA_DIAG, "").orEmpty()
        }
    }

    fun setLastTuyaDiag(context: Context, diag: String) {
        synchronized(lock) {
            prefs(context).edit().putString(KEY_LAST_TUYA_DIAG, diag).apply()
        }
        notifyChanged()
    }

    fun loadGlucoseList(context: Context): List<GlucoseEntry> {
        synchronized(lock) {
            val raw = prefs(context).getString(KEY_GLUCOSE_LIST, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val list = ArrayList<GlucoseEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val mealStr = obj.optString("mealRelation", "GENERAL")
                    val meal = MealRelation.entries.firstOrNull {
                        it.name.equals(mealStr, ignoreCase = true)
                    } ?: MealRelation.GENERAL
                    list.add(
                        GlucoseEntry(
                            id = obj.optString("id", "g_${obj.optLong("timestampMillis")}"),
                            timestampMillis = obj.optLong("timestampMillis", 0L),
                            mmolPerLiter = obj.optDouble("mmolPerLiter", 0.0),
                            mealRelation = meal,
                            sequenceNumber = obj.optInt("sequenceNumber", 0),
                            source = obj.optString("source", "Contour Plus One"),
                            syncedToHealthConnect = obj.optBoolean("syncedToHealthConnect", false)
                        )
                    )
                }
                list.sortedByDescending { it.timestampMillis }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private fun saveGlucoseListInternal(context: Context, list: List<GlucoseEntry>) {
        val sorted = list.sortedByDescending { it.timestampMillis }
        val arr = JSONArray()
        for (item in sorted) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("timestampMillis", item.timestampMillis)
                    put("mmolPerLiter", item.mmolPerLiter)
                    put("mealRelation", item.mealRelation.name)
                    put("sequenceNumber", (item.sequenceNumber as? Int) ?: 0)
                    put("source", item.source)
                    put("syncedToHealthConnect", item.syncedToHealthConnect)
                }
            )
        }
        prefs(context).edit().putString(KEY_GLUCOSE_LIST, arr.toString()).apply()
    }

    fun upsertGlucoseEntries(context: Context, entries: List<GlucoseEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val current = loadGlucoseList(context).toMutableList()
            for (incoming in entries) {
                val idx = current.indexOfFirst { existing ->
                    existing.id == incoming.id ||
                        (abs(existing.timestampMillis - incoming.timestampMillis) <= 15_000L &&
                            abs(existing.mmolPerLiter - incoming.mmolPerLiter) < 0.05)
                }
                if (idx >= 0) {
                    val existing = current[idx]
                    current[idx] = incoming.copy(
                        id = existing.id,
                        syncedToHealthConnect = existing.syncedToHealthConnect
                    )
                } else {
                    current.add(incoming)
                }
            }
            saveGlucoseListInternal(context, current)
        }
        notifyChanged()
    }

    fun markGlucoseSynced(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        synchronized(lock) {
            val updated = loadGlucoseList(context).map {
                if (it.id in idSet) it.copy(syncedToHealthConnect = true) else it
            }
            saveGlucoseListInternal(context, updated)
        }
        notifyChanged()
    }

    fun deleteGlucose(context: Context, id: String) {
        synchronized(lock) {
            val filtered = loadGlucoseList(context).filterNot { it.id == id }
            saveGlucoseListInternal(context, filtered)
        }
        notifyChanged()
    }

    fun loadScaleList(context: Context): List<ScaleEntry> {
        synchronized(lock) {
            val raw = prefs(context).getString(KEY_SCALE_LIST, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val list = ArrayList<ScaleEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(
                        ScaleEntry(
                            id = obj.optString("id", "s_${obj.optLong("timestampMillis")}"),
                            timestampMillis = obj.optLong("timestampMillis", 0L),
                            weightKg = obj.optDouble("weightKg", 0.0),
                            impedanceOhms = optNullableInt(obj, "impedanceOhms"),
                            bmi = obj.optDouble("bmi", 0.0),
                            bodyFatPercent = optNullableDouble(obj, "bodyFatPercent"),
                            leanBodyMassKg = optNullableDouble(obj, "leanBodyMassKg"),
                            muscleMassKg = optNullableDouble(obj, "muscleMassKg"),
                            bodyWaterKg = optNullableDouble(obj, "bodyWaterKg"),
                            bodyWaterPercent = optNullableDouble(obj, "bodyWaterPercent"),
                            boneMassKg = optNullableDouble(obj, "boneMassKg"),
                            bmrKcal = optNullableInt(obj, "bmrKcal"),
                            visceralFatIndex = optNullableInt(obj, "visceralFatIndex"),
                            source = obj.optString("source", "Tesla BF1321"),
                            syncedToHealthConnect = obj.optBoolean("syncedToHealthConnect", false)
                        )
                    )
                }
                list.sortedByDescending { it.timestampMillis }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private fun saveScaleListInternal(context: Context, list: List<ScaleEntry>) {
        val sorted = list.sortedByDescending { it.timestampMillis }
        val arr = JSONArray()
        for (item in sorted) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("timestampMillis", item.timestampMillis)
                    put("weightKg", item.weightKg)
                    putNullableInt(this, "impedanceOhms", item.impedanceOhms)
                    put("bmi", item.bmi)
                    putNullableDouble(this, "bodyFatPercent", item.bodyFatPercent)
                    putNullableDouble(this, "leanBodyMassKg", item.leanBodyMassKg)
                    putNullableDouble(this, "muscleMassKg", item.muscleMassKg)
                    putNullableDouble(this, "bodyWaterKg", item.bodyWaterKg)
                    putNullableDouble(this, "bodyWaterPercent", item.bodyWaterPercent)
                    putNullableDouble(this, "boneMassKg", item.boneMassKg)
                    putNullableInt(this, "bmrKcal", item.bmrKcal)
                    putNullableInt(this, "visceralFatIndex", item.visceralFatIndex)
                    put("source", item.source)
                    put("syncedToHealthConnect", item.syncedToHealthConnect)
                }
            )
        }
        prefs(context).edit().putString(KEY_SCALE_LIST, arr.toString()).apply()
    }

    fun upsertScaleEntries(context: Context, entries: List<ScaleEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val current = loadScaleList(context).toMutableList()
            for (incoming in entries) {
                val idx = current.indexOfFirst { existing ->
                    existing.id == incoming.id ||
                        abs(existing.timestampMillis - incoming.timestampMillis) <= 25_000L
                }
                if (idx >= 0) {
                    val existing = current[idx]
                    val gainedImpedance = existing.impedanceOhms == null && incoming.impedanceOhms != null
                    current[idx] = when {
                        gainedImpedance -> incoming.copy(
                            id = existing.id,
                            syncedToHealthConnect = false
                        )
                        incoming.impedanceOhms == null && existing.impedanceOhms != null -> existing
                        else -> incoming.copy(
                            id = existing.id,
                            syncedToHealthConnect = existing.syncedToHealthConnect
                        )
                    }
                } else {
                    current.add(incoming)
                }
            }
            saveScaleListInternal(context, current)
        }
        notifyChanged()
    }

    fun markScaleSynced(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        synchronized(lock) {
            val updated = loadScaleList(context).map {
                if (it.id in idSet) it.copy(syncedToHealthConnect = true) else it
            }
            saveScaleListInternal(context, updated)
        }
        notifyChanged()
    }

    fun deleteScale(context: Context, id: String) {
        synchronized(lock) {
            val filtered = loadScaleList(context).filterNot { it.id == id }
            saveScaleListInternal(context, filtered)
        }
        notifyChanged()
    }

    private fun optNullableDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optDouble(key)
        return if (v.isNaN()) null else v
    }

    private fun optNullableInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optInt(key)
    }

    private fun putNullableDouble(obj: JSONObject, key: String, value: Double?) {
        if (value == null || value.isNaN()) obj.put(key, JSONObject.NULL) else obj.put(key, value)
    }

    private fun putNullableInt(obj: JSONObject, key: String, value: Int?) {
        if (value == null) obj.put(key, JSONObject.NULL) else obj.put(key, value)
    }
}

package cz.mares.zdravipm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object LocalHealthStore {
    private const val PREFS_NAME = "zdravipm_local_store"
    private const val KEY_CONFIG = "user_scale_config"
    private const val KEY_METER_NAME = "paired_meter_name"
    private const val KEY_METER_ADDR = "paired_meter_addr"
    private const val KEY_GLUCOSE_LIST = "glucose_list_json"
    private const val KEY_SCALE_LIST = "scale_list_json"

    private val lock = Any()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun notifyChanged() {
        _version.value = _version.value + 1L
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): UserScaleConfig = synchronized(lock) {
        val raw = prefs(context).getString(KEY_CONFIG, null) ?: return UserScaleConfig()
        return try {
            val o = JSONObject(raw)
            val hasRef = o.optBoolean("hasRefWeight", false)
            UserScaleConfig(
                tuyaAccessId = o.optString("tuyaAccessId", ""),
                tuyaAccessSecret = o.optString("tuyaAccessSecret", ""),
                tuyaDeviceId = o.optString("tuyaDeviceId", "00821002c45bbee6ebbe")
                    .ifBlank { "00821002c45bbee6ebbe" },
                tuyaEndpoint = o.optString("tuyaEndpoint", "https://openapi.tuyaeu.com")
                    .ifBlank { "https://openapi.tuyaeu.com" },
                referenceWeightKg = if (hasRef) o.optDouble("referenceWeightKg", 171.5) else null,
                weightToleranceKg = o.optDouble("weightToleranceKg", 10.0),
                heightCm = o.optInt("heightCm", 180),
                birthYear = o.optInt("birthYear", 1978),
                isMale = o.optBoolean("isMale", true),
                writeBodyCompositionToHc = o.optBoolean("writeBodyCompositionToHc", true),
                backgroundServiceEnabled = o.optBoolean("backgroundServiceEnabled", true),
                targetGlucoseMin = o.optDouble("targetGlucoseMin", 3.9),
                targetGlucoseMax = o.optDouble("targetGlucoseMax", 7.8)
            )
        } catch (_: Throwable) {
            UserScaleConfig()
        }
    }

    fun saveConfig(context: Context, config: UserScaleConfig) = synchronized(lock) {
        val o = JSONObject().apply {
            put("tuyaAccessId", config.tuyaAccessId)
            put("tuyaAccessSecret", config.tuyaAccessSecret)
            put("tuyaDeviceId", config.tuyaDeviceId)
            put("tuyaEndpoint", config.tuyaEndpoint)
            put("hasRefWeight", config.referenceWeightKg != null)
            if (config.referenceWeightKg != null) {
                put("referenceWeightKg", config.referenceWeightKg)
            }
            put("weightToleranceKg", config.weightToleranceKg)
            put("heightCm", config.heightCm)
            put("birthYear", config.birthYear)
            put("isMale", config.isMale)
            put("writeBodyCompositionToHc", config.writeBodyCompositionToHc)
            put("backgroundServiceEnabled", config.backgroundServiceEnabled)
            put("targetGlucoseMin", config.targetGlucoseMin)
            put("targetGlucoseMax", config.targetGlucoseMax)
        }
        prefs(context).edit().putString(KEY_CONFIG, o.toString()).apply()
        notifyChanged()
    }

    fun getPairedMeter(context: Context): Pair<String?, String?> = synchronized(lock) {
        val p = prefs(context)
        val name = p.getString(KEY_METER_NAME, null)
        val addr = p.getString(KEY_METER_ADDR, null)
        name to addr
    }

    fun setPairedMeter(context: Context, name: String?, address: String?) = synchronized(lock) {
        val editor = prefs(context).edit()
        if (address == null) {
            editor.remove(KEY_METER_NAME).remove(KEY_METER_ADDR)
        } else {
            editor.putString(KEY_METER_NAME, name ?: "Contour Plus One")
                .putString(KEY_METER_ADDR, address)
        }
        editor.apply()
        notifyChanged()
    }

    fun loadGlucoseList(context: Context): List<GlucoseEntry> = synchronized(lock) {
        val raw = prefs(context).getString(KEY_GLUCOSE_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<GlucoseEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val mealName = o.optString("mealRelation", MealRelation.GENERAL.name)
                val meal = MealRelation.entries.firstOrNull { it.name == mealName } ?: MealRelation.GENERAL
                list.add(
                    GlucoseEntry(
                        id = o.getString("id"),
                        timestampMillis = o.getLong("timestampMillis"),
                        mmolPerLiter = o.getDouble("mmolPerLiter"),
                        mealRelation = meal,
                        sequenceNumber = o.optInt("sequenceNumber", 0),
                        source = o.optString("source", "Contour Plus One"),
                        syncedToHealthConnect = o.optBoolean("syncedToHealthConnect", false)
                    )
                )
            }
            list.sortedByDescending { it.timestampMillis }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveGlucoseListInternal(context: Context, list: List<GlucoseEntry>) {
        val arr = JSONArray()
        list.sortedByDescending { it.timestampMillis }.take(1500).forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("timestampMillis", e.timestampMillis)
                    put("mmolPerLiter", e.mmolPerLiter)
                    put("mealRelation", e.mealRelation.name)
                    put("sequenceNumber", e.sequenceNumber)
                    put("source", e.source)
                    put("syncedToHealthConnect", e.syncedToHealthConnect)
                }
            )
        }
        prefs(context).edit().putString(KEY_GLUCOSE_LIST, arr.toString()).apply()
    }

    fun upsertGlucoseEntries(context: Context, incoming: List<GlucoseEntry>): List<GlucoseEntry> =
        synchronized(lock) {
            val current = loadGlucoseList(context).toMutableList()
            for (item in incoming) {
                val existingIdx = current.indexOfFirst { ex ->
                    ex.id == item.id || (
                        item.sequenceNumber > 0 &&
                            ex.sequenceNumber == item.sequenceNumber &&
                            abs(ex.timestampMillis - item.timestampMillis) < 120_000L
                        )
                }
                if (existingIdx >= 0) {
                    val prev = current[existingIdx]
                    val mergedMeal = if (item.mealRelation != MealRelation.GENERAL) item.mealRelation else prev.mealRelation
                    current[existingIdx] = prev.copy(
                        mealRelation = mergedMeal,
                        syncedToHealthConnect = prev.syncedToHealthConnect || item.syncedToHealthConnect
                    )
                } else {
                    current.add(item)
                }
            }
            val sorted = current.sortedByDescending { it.timestampMillis }
            saveGlucoseListInternal(context, sorted)
            notifyChanged()
            sorted
        }

    fun markGlucoseSynced(context: Context, syncedIds: Set<String>) = synchronized(lock) {
        if (syncedIds.isEmpty()) return
        val updated = loadGlucoseList(context).map { e ->
            if (e.id in syncedIds) e.copy(syncedToHealthConnect = true) else e
        }
        saveGlucoseListInternal(context, updated)
        notifyChanged()
    }

    fun deleteGlucose(context: Context, id: String) = synchronized(lock) {
        val updated = loadGlucoseList(context).filterNot { it.id == id }
        saveGlucoseListInternal(context, updated)
        notifyChanged()
    }

    fun loadScaleList(context: Context): List<ScaleEntry> = synchronized(lock) {
        val raw = prefs(context).getString(KEY_SCALE_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<ScaleEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ScaleEntry(
                        id = o.getString("id"),
                        timestampMillis = o.getLong("timestampMillis"),
                        weightKg = o.getDouble("weightKg"),
                        impedanceOhms = if (o.has("impedanceOhms")) o.getInt("impedanceOhms") else null,
                        bmi = o.optDouble("bmi", 0.0),
                        bodyFatPercent = if (o.has("bodyFatPercent")) o.getDouble("bodyFatPercent") else null,
                        leanBodyMassKg = if (o.has("leanBodyMassKg")) o.getDouble("leanBodyMassKg") else null,
                        muscleMassKg = if (o.has("muscleMassKg")) o.getDouble("muscleMassKg") else null,
                        bodyWaterKg = if (o.has("bodyWaterKg")) o.getDouble("bodyWaterKg") else null,
                        bodyWaterPercent = if (o.has("bodyWaterPercent")) o.getDouble("bodyWaterPercent") else null,
                        boneMassKg = if (o.has("boneMassKg")) o.getDouble("boneMassKg") else null,
                        bmrKcal = if (o.has("bmrKcal")) o.getInt("bmrKcal") else null,
                        visceralFatIndex = if (o.has("visceralFatIndex")) o.getInt("visceralFatIndex") else null,
                        source = o.optString("source", "Tesla BF1321"),
                        syncedToHealthConnect = o.optBoolean("syncedToHealthConnect", false)
                    )
                )
            }
            list.sortedByDescending { it.timestampMillis }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveScaleListInternal(context: Context, list: List<ScaleEntry>) {
        val arr = JSONArray()
        list.sortedByDescending { it.timestampMillis }.take(1000).forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("timestampMillis", e.timestampMillis)
                    put("weightKg", e.weightKg)
                    e.impedanceOhms?.let { put("impedanceOhms", it) }
                    put("bmi", e.bmi)
                    e.bodyFatPercent?.let { put("bodyFatPercent", it) }
                    e.leanBodyMassKg?.let { put("leanBodyMassKg", it) }
                    e.muscleMassKg?.let { put("muscleMassKg", it) }
                    e.bodyWaterKg?.let { put("bodyWaterKg", it) }
                    e.bodyWaterPercent?.let { put("bodyWaterPercent", it) }
                    e.boneMassKg?.let { put("boneMassKg", it) }
                    e.bmrKcal?.let { put("bmrKcal", it) }
                    e.visceralFatIndex?.let { put("visceralFatIndex", it) }
                    put("source", e.source)
                    put("syncedToHealthConnect", e.syncedToHealthConnect)
                }
            )
        }
        prefs(context).edit().putString(KEY_SCALE_LIST, arr.toString()).apply()
    }

    fun upsertScaleEntries(context: Context, incoming: List<ScaleEntry>): List<ScaleEntry> =
        synchronized(lock) {
            val current = loadScaleList(context).toMutableList()
            for (item in incoming) {
                val existingIdx = current.indexOfFirst { ex ->
                    ex.id == item.id || abs(ex.timestampMillis - item.timestampMillis) < 20_000L
                }
                if (existingIdx >= 0) {
                    val prev = current[existingIdx]
                    current[existingIdx] = item.copy(
                        id = prev.id,
                        syncedToHealthConnect = prev.syncedToHealthConnect || item.syncedToHealthConnect
                    )
                } else {
                    current.add(item)
                }
            }
            val sorted = current.sortedByDescending { it.timestampMillis }
            saveScaleListInternal(context, sorted)
            notifyChanged()
            sorted
        }

    fun markScaleSynced(context: Context, syncedIds: Set<String>) = synchronized(lock) {
        if (syncedIds.isEmpty()) return
        val updated = loadScaleList(context).map { e ->
            if (e.id in syncedIds) e.copy(syncedToHealthConnect = true) else e
        }
        saveScaleListInternal(context, updated)
        notifyChanged()
    }

    fun deleteScale(context: Context, id: String) = synchronized(lock) {
        val updated = loadScaleList(context).filterNot { it.id == id }
        saveScaleListInternal(context, updated)
        notifyChanged()
    }
}

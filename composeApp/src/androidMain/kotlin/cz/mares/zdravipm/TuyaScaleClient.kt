package cz.mares.zdravipm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.pow

data class TuyaFetchOutcome(
    val success: Boolean,
    val candidates: List<RawScaleCandidate>,
    val message: String,
    val diagnostic: String
)

private data class WeightSpecMeta(
    val scale: Int?,
    val unit: String?,
    val weightCodes: List<String>,
    val ohmsCodes: List<String>
)

object TuyaScaleClient {
    private const val EMPTY_BODY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun fetchRecentScaleReadings(config: UserScaleConfig): TuyaFetchOutcome {
        val accessId = config.tuyaAccessId.replace("\\s+".toRegex(), "")
        val secret = config.tuyaAccessSecret.replace("\\s+".toRegex(), "")
        val deviceId = config.tuyaDeviceId.replace("\\s+".toRegex(), "")
            .ifBlank { "00821002c45bbee6ebbe" }
        val rawEndpoint = config.tuyaEndpoint.replace("\\s+".toRegex(), "")
            .ifBlank { "https://openapi.tuyaeu.com" }
            .trimEnd('/')
        val baseUrl = if (rawEndpoint.startsWith("http")) rawEndpoint else "https://$rawEndpoint"

        if (accessId.isBlank() || secret.isBlank()) {
            return TuyaFetchOutcome(
                success = false,
                candidates = emptyList(),
                message = "Vyplň v Nastavení Tuya Access ID a Access Secret.",
                diagnostic = "Chybí přístupové klíče Tuya IoT Core."
            )
        }

        return try {
            val tokenParams = TreeMap<String, String>().apply { put("grant_type", "1") }
            val tokenResp = executeSignedGet(
                baseUrl = baseUrl,
                path = "/v1.0/token",
                queryParams = tokenParams,
                accessId = accessId,
                accessSecret = secret,
                accessToken = null,
                timeSkewMs = 0L
            )

            if (!tokenResp.optBoolean("success", false)) {
                val errMsg = tokenResp.optString("msg", "Chyba přihlášení k Tuya API")
                val errCode = tokenResp.optInt("code", -1)
                return TuyaFetchOutcome(
                    success = false,
                    candidates = emptyList(),
                    message = "Tuya odmítla přihlášení ($errCode): $errMsg",
                    diagnostic = "Endpoint: $baseUrl\nToken chyba ($errCode): $errMsg"
                )
            }

            val serverTime = tokenResp.optLong("t", 0L)
            val timeSkewMs = if (serverTime > 1_600_000_000_000L) {
                serverTime - System.currentTimeMillis()
            } else {
                0L
            }

            val token = tokenResp.getJSONObject("result").getString("access_token")
            val diagLines = ArrayList<String>()
            val apiErrors = ArrayList<String>()
            var anyDataEndpointOk = false

            val meta = fetchWeightSpecMeta(baseUrl, deviceId, accessId, secret, token, timeSkewMs)
            val specScale = meta.scale
            val specUnit = meta.unit
            diagLines.add(
                "Device: $deviceId (scale=${specScale ?: "auto"}, unit=${specUnit ?: "kg"})"
            )

            val allWeightEvents = ArrayList<Pair<Long, Double>>()
            val allOhmsEvents = ArrayList<Pair<Long, Int>>()
            val weightCodes = LinkedHashSet<String>()
            val ohmsCodes = LinkedHashSet<String>()

            meta.weightCodes.forEach { weightCodes.add(it) }
            meta.ohmsCodes.forEach { ohmsCodes.add(it) }

            // 1. IoT Core v2.0 shadow/properties - aktualni stav vahy a presne nazvy DP
            val shadowResp = executeSignedGet(
                baseUrl = baseUrl,
                path = "/v2.0/cloud/thing/$deviceId/shadow/properties",
                queryParams = TreeMap(),
                accessId = accessId,
                accessSecret = secret,
                accessToken = token,
                timeSkewMs = timeSkewMs
            )
            if (shadowResp.optBoolean("success", false)) {
                anyDataEndpointOk = true
                val rawResult = shadowResp.opt("result")
                val props = when (rawResult) {
                    is JSONObject -> rawResult.optJSONArray("properties") ?: JSONArray()
                    is JSONArray -> rawResult
                    else -> JSONArray()
                }
                val fallbackShadowTs = normalizeTimestamp(shadowResp.optLong("t", System.currentTimeMillis()))
                val dpSummary = ArrayList<String>()

                for (i in 0 until props.length()) {
                    val p = props.optJSONObject(i) ?: continue
                    val code = p.optString("code", "").trim()
                    val rawVal = p.opt("value")?.toString()?.trim().orEmpty()
                    val propTime = normalizeTimestamp(p.optLong("time", 0L))
                        .takeIf { it > 0L } ?: fallbackShadowTs

                    if (code.isNotEmpty()) {
                        dpSummary.add("$code=$rawVal")
                    }
                    if (isWeightCode(code)) {
                        weightCodes.add(code)
                        parseWeightKg(rawVal, specScale, specUnit)?.let { kg ->
                            allWeightEvents.add(propTime to kg)
                        }
                    } else if (isOhmsCode(code)) {
                        ohmsCodes.add(code)
                        parseOhms(rawVal)?.let { ohms ->
                            allOhmsEvents.add(propTime to ohms)
                        }
                    }
                }
                diagLines.add(
                    "Shadow DPs (${props.length()}): ${dpSummary.joinToString(", ").ifBlank { "žádné" }}"
                )
            } else {
                val code = shadowResp.optInt("code", -1)
                val msg = shadowResp.optString("msg", "")
                apiErrors.add("shadow ($code: $msg)")
                diagLines.add("shadow/properties ($code): $msg")
            }

            // 2. Zalozni status pres IoT Core v1.0/iot-03 pokud shadow neuspelo
            if (!anyDataEndpointOk) {
                for (statusPath in listOf(
                    "/v1.0/iot-03/devices/$deviceId/status",
                    "/v1.0/devices/$deviceId/status"
                )) {
                    val stResp = executeSignedGet(
                        baseUrl = baseUrl,
                        path = statusPath,
                        queryParams = TreeMap(),
                        accessId = accessId,
                        accessSecret = secret,
                        accessToken = token,
                        timeSkewMs = timeSkewMs
                    )
                    if (stResp.optBoolean("success", false)) {
                        anyDataEndpointOk = true
                        val stArr = stResp.optJSONArray("result") ?: JSONArray()
                        val fallbackTs = normalizeTimestamp(stResp.optLong("t", System.currentTimeMillis()))
                        val stSummary = ArrayList<String>()
                        for (i in 0 until stArr.length()) {
                            val item = stArr.optJSONObject(i) ?: continue
                            val code = item.optString("code", "").trim()
                            val rawVal = item.opt("value")?.toString()?.trim().orEmpty()
                            if (code.isNotEmpty()) stSummary.add("$code=$rawVal")
                            if (isWeightCode(code)) {
                                weightCodes.add(code)
                                parseWeightKg(rawVal, specScale, specUnit)?.let {
                                    allWeightEvents.add(fallbackTs to it)
                                }
                            } else if (isOhmsCode(code)) {
                                ohmsCodes.add(code)
                                parseOhms(rawVal)?.let {
                                    allOhmsEvents.add(fallbackTs to it)
                                }
                            }
                        }
                        diagLines.add("$statusPath: ${stSummary.joinToString(", ").ifBlank { "prázdné" }}")
                        break
                    }
                }
            }

            if (weightCodes.isEmpty()) {
                weightCodes.add("weight")
                weightCodes.add("weight_s")
            }
            if (ohmsCodes.isEmpty()) {
                ohmsCodes.add("Body_R")
            }

            // 3. Historie vazeni pres IoT Core v2.0 report-logs (bez carky v parametru codes)
            val now = System.currentTimeMillis() + timeSkewMs
            val sixDaysMs = 6L * 24L * 3600L * 1000L
            var v2ReportLogsOk = false

            for (wCode in weightCodes.take(3)) {
                val wParams = TreeMap<String, String>().apply {
                    put("codes", wCode)
                    put("end_time", now.toString())
                    put("size", "50")
                    put("start_time", (now - sixDaysMs).toString())
                }
                val wResp = executeSignedGet(
                    baseUrl = baseUrl,
                    path = "/v2.0/cloud/thing/$deviceId/report-logs",
                    queryParams = wParams,
                    accessId = accessId,
                    accessSecret = secret,
                    accessToken = token,
                    timeSkewMs = timeSkewMs
                )
                if (wResp.optBoolean("success", false)) {
                    anyDataEndpointOk = true
                    v2ReportLogsOk = true
                    val wLogs = extractLogsArray(wResp.opt("result"))
                    parseLogsIntoLists(wLogs, specScale, specUnit, allWeightEvents, allOhmsEvents)
                    diagLines.add("v2 report-logs [$wCode]: ${wLogs.length()} záznamů")
                } else {
                    val c = wResp.optInt("code", -1)
                    val m = wResp.optString("msg", "")
                    apiErrors.add("report-logs ($c: $m)")
                    diagLines.add("v2 report-logs [$wCode] ($c): $m")
                    break
                }
            }

            if (v2ReportLogsOk) {
                for (rCode in ohmsCodes.take(2)) {
                    val rParams = TreeMap<String, String>().apply {
                        put("codes", rCode)
                        put("end_time", now.toString())
                        put("size", "50")
                        put("start_time", (now - sixDaysMs).toString())
                    }
                    val rResp = executeSignedGet(
                        baseUrl = baseUrl,
                        path = "/v2.0/cloud/thing/$deviceId/report-logs",
                        queryParams = rParams,
                        accessId = accessId,
                        accessSecret = secret,
                        accessToken = token,
                        timeSkewMs = timeSkewMs
                    )
                    if (rResp.optBoolean("success", false)) {
                        val rLogs = extractLogsArray(rResp.opt("result"))
                        parseLogsIntoLists(rLogs, specScale, specUnit, allWeightEvents, allOhmsEvents)
                    }
                }
            } else {
                // 4. Zalozni historie pres iot-03/report-logs nebo v1.0/devices/logs
                val primaryWCode = weightCodes.first()
                val iot03Params = TreeMap<String, String>().apply {
                    put("codes", primaryWCode)
                    put("end_time", now.toString())
                    put("size", "50")
                    put("start_time", (now - sixDaysMs).toString())
                }
                val iot03Resp = executeSignedGet(
                    baseUrl = baseUrl,
                    path = "/v1.0/iot-03/devices/$deviceId/report-logs",
                    queryParams = iot03Params,
                    accessId = accessId,
                    accessSecret = secret,
                    accessToken = token,
                    timeSkewMs = timeSkewMs
                )
                if (iot03Resp.optBoolean("success", false)) {
                    anyDataEndpointOk = true
                    val arr = extractLogsArray(iot03Resp.opt("result"))
                    parseLogsIntoLists(arr, specScale, specUnit, allWeightEvents, allOhmsEvents)
                    diagLines.add("iot-03 report-logs: ${arr.length()} záznamů")
                } else {
                    val v1Params = TreeMap<String, String>().apply {
                        put("end_time", now.toString())
                        put("size", "100")
                        put("start_time", (now - sixDaysMs).toString())
                        put("type", "7")
                    }
                    val v1Resp = executeSignedGet(
                        baseUrl = baseUrl,
                        path = "/v1.0/devices/$deviceId/logs",
                        queryParams = v1Params,
                        accessId = accessId,
                        accessSecret = secret,
                        accessToken = token,
                        timeSkewMs = timeSkewMs
                    )
                    if (v1Resp.optBoolean("success", false)) {
                        anyDataEndpointOk = true
                        val arr = extractLogsArray(v1Resp.opt("result"))
                        parseLogsIntoLists(arr, specScale, specUnit, allWeightEvents, allOhmsEvents)
                        diagLines.add("v1.0 logs: ${arr.length()} záznamů")
                    } else {
                        diagLines.add(
                            "v1.0 logs (${v1Resp.optInt("code", -1)}): ${v1Resp.optString("msg", "")}"
                        )
                    }
                }
            }

            val sortedWeights = allWeightEvents.sortedByDescending { it.first }
            val merged = ArrayList<RawScaleCandidate>()
            for ((wTime, wKg) in sortedWeights) {
                if (merged.any { abs(it.timestampMillis - wTime) < 5_000L }) continue
                val matchingOhms = allOhmsEvents
                    .filter { abs(it.first - wTime) <= 25_000L }
                    .minByOrNull { abs(it.first - wTime) }
                    ?.second
                merged.add(
                    RawScaleCandidate(
                        id = "tuya_${wTime / 1000L}",
                        timestampMillis = wTime,
                        weightKg = wKg,
                        impedanceOhms = matchingOhms
                    )
                )
            }

            if (merged.isEmpty() && !anyDataEndpointOk) {
                val firstErr = apiErrors.firstOrNull() ?: "Tuya nevrátila oprávnění k zařízení"
                return TuyaFetchOutcome(
                    success = false,
                    candidates = emptyList(),
                    message = "Tuya odmítla čtení váhy: $firstErr",
                    diagnostic = diagLines.joinToString("\n")
                )
            }

            val msg = if (merged.isEmpty()) {
                "Spojení s Tuya IoT Core je funkční, ale váha zatím nemá uloženou nenulovou hmotnost (zvaž se naboso a načti znovu)."
            } else {
                "Načteno ${merged.size} vážení z Tuya IoT Core."
            }

            TuyaFetchOutcome(
                success = true,
                candidates = merged,
                message = msg,
                diagnostic = diagLines.joinToString("\n")
            )
        } catch (t: Throwable) {
            TuyaFetchOutcome(
                success = false,
                candidates = emptyList(),
                message = "Chyba spojení s Tuya: ${t.message ?: "neznámá chyba"}",
                diagnostic = "Výjimka: ${t::class.simpleName}: ${t.message}"
            )
        }
    }

    private fun fetchWeightSpecMeta(
        baseUrl: String,
        deviceId: String,
        accessId: String,
        secret: String,
        token: String,
        timeSkewMs: Long
    ): WeightSpecMeta {
        var foundScale: Int? = null
        var foundUnit: String? = null
        val wCodes = LinkedHashSet<String>()
        val rCodes = LinkedHashSet<String>()

        try {
            val modelResp = executeSignedGet(
                baseUrl = baseUrl,
                path = "/v2.0/cloud/thing/$deviceId/model",
                queryParams = TreeMap(),
                accessId = accessId,
                accessSecret = secret,
                accessToken = token,
                timeSkewMs = timeSkewMs
            )
            if (modelResp.optBoolean("success", false)) {
                val res = modelResp.optJSONObject("result")
                val rawModel = res?.opt("model")
                val modelJson = when (rawModel) {
                    is JSONObject -> rawModel
                    is String -> if (rawModel.isNotBlank()) JSONObject(rawModel) else null
                    else -> res
                }
                val services = modelJson?.optJSONArray("services")
                if (services != null) {
                    for (s in 0 until services.length()) {
                        val props = services.optJSONObject(s)?.optJSONArray("properties") ?: continue
                        for (p in 0 until props.length()) {
                            val item = props.optJSONObject(p) ?: continue
                            val code = item.optString("code", "").trim()
                            if (isWeightCode(code)) {
                                wCodes.add(code)
                                val typeSpec = item.optJSONObject("typeSpec")
                                if (typeSpec != null) {
                                    if (foundScale == null && typeSpec.has("scale")) {
                                        foundScale = typeSpec.optInt("scale")
                                    }
                                    val u = typeSpec.optString("unit", "").trim()
                                    if (foundUnit == null && u.isNotEmpty()) {
                                        foundUnit = u
                                    }
                                }
                            } else if (isOhmsCode(code)) {
                                rCodes.add(code)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        if (foundScale == null) {
            for (specPath in listOf(
                "/v1.0/iot-03/devices/$deviceId/specification",
                "/v1.0/iot-03/devices/$deviceId/specifications",
                "/v1.0/devices/$deviceId/specifications"
            )) {
                try {
                    val resp = executeSignedGet(
                        baseUrl = baseUrl,
                        path = specPath,
                        queryParams = TreeMap(),
                        accessId = accessId,
                        accessSecret = secret,
                        accessToken = token,
                        timeSkewMs = timeSkewMs
                    )
                    if (!resp.optBoolean("success", false)) continue
                    val statusArr = resp.optJSONObject("result")?.optJSONArray("status") ?: continue
                    for (i in 0 until statusArr.length()) {
                        val item = statusArr.optJSONObject(i) ?: continue
                        val code = item.optString("code", "").trim()
                        if (isWeightCode(code)) {
                            wCodes.add(code)
                            val rawValues = item.opt("values")
                            val valuesJson = when (rawValues) {
                                is JSONObject -> rawValues
                                is String -> if (rawValues.isNotBlank()) JSONObject(rawValues) else null
                                else -> null
                            }
                            if (valuesJson != null) {
                                if (foundScale == null && valuesJson.has("scale")) {
                                    foundScale = valuesJson.optInt("scale")
                                }
                                val u = valuesJson.optString("unit", "").trim()
                                if (foundUnit == null && u.isNotEmpty()) {
                                    foundUnit = u
                                }
                            }
                        } else if (isOhmsCode(code)) {
                            rCodes.add(code)
                        }
                    }
                    if (foundScale != null) break
                } catch (_: Throwable) {
                }
            }
        }

        return WeightSpecMeta(
            scale = foundScale,
            unit = foundUnit,
            weightCodes = wCodes.toList(),
            ohmsCodes = rCodes.toList()
        )
    }

    private fun isWeightCode(code: String): Boolean {
        val c = code.lowercase()
        if (c.isEmpty()) return false
        val excluded = listOf("unit", "trend", "state", "limit", "max", "min", "target", "alarm", "remind", "cal")
        if (excluded.any { c.contains(it) }) return false
        return c == "weight" || c == "weight_s" || c == "weight_kg" || c == "weight_g" || c == "body_weight" || c.startsWith("weight")
    }

    private fun isOhmsCode(code: String): Boolean {
        val c = code.lowercase()
        if (c.isEmpty()) return false
        return c == "body_r" || c.contains("body_r") || c.contains("impedance") || c == "resistance"
    }

    private fun normalizeTimestamp(rawTime: Long): Long {
        if (rawTime <= 0L) return 0L
        return if (rawTime < 100_000_000_000L) rawTime * 1000L else rawTime
    }

    private fun extractLogsArray(resultObj: Any?): JSONArray {
        return when (resultObj) {
            is JSONArray -> resultObj
            is JSONObject -> resultObj.optJSONArray("logs")
                ?: resultObj.optJSONArray("list")
                ?: JSONArray()
            else -> JSONArray()
        }
    }

    private fun parseLogsIntoLists(
        logsArray: JSONArray,
        specScale: Int?,
        specUnit: String?,
        weightOut: MutableList<Pair<Long, Double>>,
        ohmsOut: MutableList<Pair<Long, Int>>
    ) {
        for (i in 0 until logsArray.length()) {
            val row = logsArray.optJSONObject(i) ?: continue
            val code = row.optString("code", "").trim()
            val rawTime = row.optLong("event_time", 0L)
                .takeIf { it > 0L }
                ?: row.optLong("time", 0L).takeIf { it > 0L }
                ?: row.optLong("t", 0L)
            val eventTime = normalizeTimestamp(rawTime)
            val rawValue = row.opt("value")?.toString()?.trim().orEmpty()
            if (eventTime <= 0L || rawValue.isEmpty()) continue

            if (isWeightCode(code)) {
                parseWeightKg(rawValue, specScale, specUnit)?.let { kg ->
                    weightOut.add(eventTime to kg)
                }
            } else if (isOhmsCode(code)) {
                parseOhms(rawValue)?.let { ohms ->
                    ohmsOut.add(eventTime to ohms)
                }
            }
        }
    }

    private fun parseOhms(rawValue: String): Int? {
        val digits = rawValue.filter { it.isDigit() }
        return digits.toIntOrNull()?.takeIf { it in 150..1600 }
    }

    private fun parseWeightKg(rawValue: String, specScale: Int?, specUnit: String?): Double? {
        val cleaned = rawValue
            .replace("kg", "", ignoreCase = true)
            .replace("g", "", ignoreCase = true)
            .trim()
        if (cleaned.isEmpty()) return null

        val unitLower = specUnit?.lowercase()?.trim().orEmpty()
        if (cleaned.contains('.') || cleaned.contains(',')) {
            val decimalVal = cleaned.replace(',', '.').toDoubleOrNull() ?: return null
            val kg = if (unitLower == "g") decimalVal / 1000.0 else decimalVal
            return kg.takeIf { it in 10.0..350.0 }
        }

        val rawNum = cleaned.toDoubleOrNull() ?: return null
        if (rawNum <= 0.0) return null

        if (unitLower == "g") {
            val grams = if (specScale != null && specScale > 0) {
                rawNum / 10.0.pow(specScale.toDouble())
            } else {
                rawNum
            }
            val kgFromGrams = grams / 1000.0
            if (kgFromGrams in 10.0..350.0) return kgFromGrams
        }

        if (specScale != null && specScale > 0) {
            val scaledKg = rawNum / 10.0.pow(specScale.toDouble())
            if (scaledKg in 10.0..350.0) return scaledKg
        }

        val autoKg = when {
            rawNum >= 30_000.0 -> rawNum / 1000.0
            rawNum >= 2_500.0 -> rawNum / 100.0
            rawNum > 350.0 -> rawNum / 10.0
            else -> rawNum
        }
        return autoKg.takeIf { it in 10.0..350.0 }
    }

    private fun executeSignedGet(
        baseUrl: String,
        path: String,
        queryParams: TreeMap<String, String>,
        accessId: String,
        accessSecret: String,
        accessToken: String?,
        timeSkewMs: Long
    ): JSONObject {
        val queryString = queryParams.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { "${it.key}=${it.value}" }
        val fullPath = if (queryString.isEmpty()) path else "$path?$queryString"
        val timestamp = (System.currentTimeMillis() + timeSkewMs).toString()
        val stringToSign = "GET\n$EMPTY_BODY_SHA256\n\n$fullPath"
        val payload = if (accessToken == null) {
            accessId + timestamp + stringToSign
        } else {
            accessId + accessToken + timestamp + stringToSign
        }
        val signature = hmacSha256UpperHex(payload, accessSecret)

        val conn = (URL("$baseUrl$fullPath").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("client_id", accessId)
            setRequestProperty("sign", signature)
            setRequestProperty("t", timestamp)
            setRequestProperty("sign_method", "HMAC-SHA256")
            if (accessToken != null) {
                setRequestProperty("access_token", accessToken)
            }
        }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return if (body.isNotBlank()) JSONObject(body) else JSONObject().put("success", false)
    }

    private fun hmacSha256UpperHex(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            sb.append(v.toString(16).padStart(2, '0').uppercase())
        }
        return sb.toString()
    }
}

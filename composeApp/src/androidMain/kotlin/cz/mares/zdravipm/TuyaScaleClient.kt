package cz.mares.zdravipm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

object TuyaScaleClient {
    private const val EMPTY_BODY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun fetchRecentScaleReadings(config: UserScaleConfig): TuyaFetchOutcome {
        val accessId = config.tuyaAccessId.trim()
        val secret = config.tuyaAccessSecret.trim()
        val deviceId = config.tuyaDeviceId.trim()
        val baseUrl = config.tuyaEndpoint.trim().trimEnd('/')

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
                accessToken = null
            )

            if (!tokenResp.optBoolean("success", false)) {
                val errMsg = tokenResp.optString("msg", "Chyba přihlášení k Tuya API")
                val errCode = tokenResp.optInt("code", -1)
                return TuyaFetchOutcome(
                    success = false,
                    candidates = emptyList(),
                    message = "Tuya odmítla přihlášení ($errCode): $errMsg",
                    diagnostic = "Token odpověď: $tokenResp"
                )
            }

            val token = tokenResp.getJSONObject("result").getString("access_token")
            val specScale = fetchWeightScale(baseUrl, deviceId, accessId, secret, token)

            val now = System.currentTimeMillis()
            val sevenDaysMs = 7L * 24L * 3600L * 1000L
            val allWeightEvents = ArrayList<Pair<Long, Double>>()
            val allOhmsEvents = ArrayList<Pair<Long, Int>>()
            val diagLines = ArrayList<String>()

            // Nacteme poslednich 14 dni ve dvou 7dennich oknech z Device Logs (type=7)
            for (windowIdx in 0..1) {
                val winEnd = now - windowIdx * sevenDaysMs
                val winStart = winEnd - sevenDaysMs
                val logParams = TreeMap<String, String>().apply {
                    put("end_time", winEnd.toString())
                    put("size", "100")
                    put("start_time", winStart.toString())
                    put("type", "7")
                }
                val logsResp = executeSignedGet(
                    baseUrl = baseUrl,
                    path = "/v1.0/devices/$deviceId/logs",
                    queryParams = logParams,
                    accessId = accessId,
                    accessSecret = secret,
                    accessToken = token
                )
                if (logsResp.optBoolean("success", false)) {
                    val logsArray = extractLogsArray(logsResp.opt("result"))
                    diagLines.add("Okno ${windowIdx + 1}: přijato ${logsArray.length()} DP záznamů.")
                    parseLogsIntoLists(logsArray, specScale, allWeightEvents, allOhmsEvents)
                } else if (windowIdx == 0) {
                    diagLines.add("v1.0 logs info: ${logsResp.optString("msg")}")
                }
            }

            // Pojistka pres v2.0 IoT Core report-logs pokud by v1.0 vratilo prazdny seznam
            if (allWeightEvents.isEmpty()) {
                val v2Params = TreeMap<String, String>().apply {
                    put("codes", "weight,Body_R")
                    put("end_time", now.toString())
                    put("size", "50")
                    put("start_time", (now - sevenDaysMs).toString())
                }
                val v2Resp = executeSignedGet(
                    baseUrl = baseUrl,
                    path = "/v2.0/cloud/thing/$deviceId/report-logs",
                    queryParams = v2Params,
                    accessId = accessId,
                    accessSecret = secret,
                    accessToken = token
                )
                if (v2Resp.optBoolean("success", false)) {
                    val logsArray = extractLogsArray(v2Resp.opt("result"))
                    diagLines.add("v2.0 report-logs: ${logsArray.length()} záznamů.")
                    parseLogsIntoLists(logsArray, specScale, allWeightEvents, allOhmsEvents)
                }
            }

            val sortedWeights = allWeightEvents.sortedByDescending { it.first }
            val merged = ArrayList<RawScaleCandidate>()
            for ((wTime, wKg) in sortedWeights) {
                if (merged.any { abs(it.timestampMillis - wTime) < 5_000L }) continue
                val matchingOhms = allOhmsEvents
                    .filter { abs(it.first - wTime) <= 15_000L }
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

            TuyaFetchOutcome(
                success = true,
                candidates = merged,
                message = "Načteno ${merged.size} vážení z Tuya IoT Core.",
                diagnostic = (listOf("Device ID: $deviceId, desetinné měřítko: ${specScale ?: "auto"}") + diagLines)
                    .joinToString("\n")
            )
        } catch (t: Throwable) {
            TuyaFetchOutcome(
                success = false,
                candidates = emptyList(),
                message = "Chyba spojení s Tuya: ${t.message ?: "neznámá chyba"}",
                diagnostic = "Výjimka při komunikaci: ${t::class.simpleName}: ${t.message}"
            )
        }
    }

    private fun fetchWeightScale(
        baseUrl: String,
        deviceId: String,
        accessId: String,
        secret: String,
        token: String
    ): Int? {
        return try {
            val resp = executeSignedGet(
                baseUrl = baseUrl,
                path = "/v1.0/devices/$deviceId/specifications",
                queryParams = TreeMap(),
                accessId = accessId,
                accessSecret = secret,
                accessToken = token
            )
            if (!resp.optBoolean("success", false)) return null
            val res = resp.optJSONObject("result") ?: return null
            val statusArr = res.optJSONArray("status") ?: return null
            for (i in 0 until statusArr.length()) {
                val item = statusArr.getJSONObject(i)
                if (item.optString("code").equals("weight", ignoreCase = true)) {
                    val valuesJson = JSONObject(item.optString("values", "{}"))
                    if (valuesJson.has("scale")) return valuesJson.getInt("scale")
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
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
        weightOut: MutableList<Pair<Long, Double>>,
        ohmsOut: MutableList<Pair<Long, Int>>
    ) {
        for (i in 0 until logsArray.length()) {
            val row = logsArray.optJSONObject(i) ?: continue
            val code = row.optString("code", "")
            val eventTime = row.optLong("event_time", 0L)
            val rawValue = row.opt("value")?.toString()?.trim().orEmpty()
            if (eventTime <= 0L || rawValue.isEmpty()) continue

            val normalizedTime = if (eventTime < 100_000_000_000L) eventTime * 1000L else eventTime
            if (code.equals("weight", ignoreCase = true) || code.equals("weight_kg", ignoreCase = true)) {
                parseWeightKg(rawValue, specScale)?.let { kg ->
                    weightOut.add(normalizedTime to kg)
                }
            } else if (code.equals("Body_R", ignoreCase = true) || code.contains("impedance", ignoreCase = true)) {
                val digits = rawValue.filter { it.isDigit() }
                digits.toIntOrNull()?.takeIf { it in 150..1600 }?.let { ohms ->
                    ohmsOut.add(normalizedTime to ohms)
                }
            }
        }
    }

    private fun parseWeightKg(rawValue: String, specScale: Int?): Double? {
        val cleaned = rawValue
            .replace("kg", "", ignoreCase = true)
            .replace("g", "", ignoreCase = true)
            .trim()
        if (cleaned.contains('.') || cleaned.contains(',')) {
            return cleaned.replace(',', '.').toDoubleOrNull()?.takeIf { it in 10.0..350.0 }
        }
        val rawNum = cleaned.toDoubleOrNull() ?: return null
        val kg = when {
            specScale != null && specScale > 0 -> rawNum / 10.0.pow(specScale.toDouble())
            rawNum >= 2500.0 -> rawNum / 100.0
            rawNum >= 400.0 -> rawNum / 10.0
            else -> rawNum
        }
        return kg.takeIf { it in 10.0..350.0 }
    }

    private fun executeSignedGet(
        baseUrl: String,
        path: String,
        queryParams: TreeMap<String, String>,
        accessId: String,
        accessSecret: String,
        accessToken: String?
    ): JSONObject {
        val queryString = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val fullPath = if (queryString.isEmpty()) path else "$path?$queryString"
        val timestamp = System.currentTimeMillis().toString()
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
            sb.append( v.toString(16).padStart(2, '0').uppercase() )
        }
        return sb.toString()
    }
}

package cz.mares.zdravipm

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private class AndroidHealthBridge(
    private val appContext: Context
) : HealthBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var zadostBleOpravneni: (() -> Unit)? = null
    var zadostHcOpravneni: (() -> Unit)? = null

    private val _state = MutableStateFlow(nactiVychoziStav())
    override val state: StateFlow<HealthUiState> = _state.asStateFlow()

    init {
        scope.launch {
            LocalHealthStore.version.collect {
                obnovLokalniData()
            }
        }
        scope.launch {
            ContourSyncService.bleStatus.collect { msg ->
                _state.update { it.copy(bleStatusText = msg) }
            }
        }
        scope.launch {
            ContourSyncService.isScanning.collect { scanning ->
                _state.update { it.copy(isBleScanning = scanning) }
            }
        }
    }

    private fun nactiVychoziStav(): HealthUiState {
        val (meterName, meterAddr) = LocalHealthStore.getPairedMeter(appContext)
        return HealthUiState(
            pairedMeterName = meterName,
            pairedMeterAddress = meterAddr,
            config = LocalHealthStore.loadConfig(appContext),
            glucoseList = LocalHealthStore.loadGlucoseList(appContext),
            scaleList = LocalHealthStore.loadScaleList(appContext)
        )
    }

    private fun obnovLokalniData() {
        val (meterName, meterAddr) = LocalHealthStore.getPairedMeter(appContext)
        _state.update {
            it.copy(
                pairedMeterName = meterName,
                pairedMeterAddress = meterAddr,
                config = LocalHealthStore.loadConfig(appContext),
                glucoseList = LocalHealthStore.loadGlucoseList(appContext),
                scaleList = LocalHealthStore.loadScaleList(appContext)
            )
        }
    }

    fun zkontrolujHealthConnect(synchronizovatCekajici: Boolean) {
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                HealthConnectManager.checkStatus(appContext)
            }
            _state.update {
                it.copy(
                    healthConnectAvailable = status.available,
                    healthConnectGranted = status.allCoreGranted,
                    healthConnectDetail = status.detailText
                )
            }
            if (synchronizovatCekajici && status.allCoreGranted) {
                val syncRes = withContext(Dispatchers.IO) {
                    HealthConnectManager.syncRecords(appContext, forceAll = false)
                }
                if (syncRes.glucoseWritten > 0 || syncRes.scaleWritten > 0) {
                    _state.update { it.copy(lastSyncMessage = syncRes.message) }
                }
            }
        }
    }

    override fun requestBluetoothAndNotificationPermissions() {
        zadostBleOpravneni?.invoke()
    }

    override fun requestHealthConnectPermissions() {
        zadostHcOpravneni?.invoke()
    }

    override fun openHealthConnectSystemSettings() {
        HealthConnectManager.openHealthConnectSettings(appContext)
    }

    override fun scanAndPairContourMeter() {
        if (!ContourSyncService.maBleOpravneni(appContext)) {
            zadostBleOpravneni?.invoke()
            return
        }
        ContourSyncService.scanAndPair(appContext)
    }

    override fun syncContourNow() {
        if (!ContourSyncService.maBleOpravneni(appContext)) {
            zadostBleOpravneni?.invoke()
            return
        }
        ContourSyncService.syncNow(appContext)
    }

    override fun unpairContourMeter() {
        LocalHealthStore.setPairedMeter(appContext, null, null)
        ContourSyncService.stopService(appContext)
        _state.update { it.copy(bleStatusText = "Glukometr byl odpárován.") }
    }

    override fun setBackgroundServiceEnabled(enabled: Boolean) {
        val novaCfg = LocalHealthStore.loadConfig(appContext).copy(backgroundServiceEnabled = enabled)
        LocalHealthStore.saveConfig(appContext, novaCfg)
        val (_, adr) = LocalHealthStore.getPairedMeter(appContext)
        if (enabled && !adr.isNullOrBlank()) {
            ContourSyncService.startIfPermitted(appContext)
        } else if (!enabled) {
            ContourSyncService.stopService(appContext)
        }
    }

    override fun addManualGlucose(mmolPerLiter: Double, mealRelation: MealRelation) {
        val cas = System.currentTimeMillis()
        val polozka = GlucoseEntry(
            id = "man_glu_$cas",
            timestampMillis = cas,
            mmolPerLiter = mmolPerLiter,
            mealRelation = mealRelation,
            sequenceNumber = 0,
            source = "Ruční zápis",
            syncedToHealthConnect = false
        )
        LocalHealthStore.upsertGlucoseEntries(appContext, listOf(polozka))
        syncAllPendingToHealthConnect()
    }

    override fun saveScaleConfig(newConfig: UserScaleConfig) {
        LocalHealthStore.saveConfig(appContext, newConfig)
    }

    override fun fetchScaleFromTuya(forceCalibrationList: Boolean) {
        val cfg = LocalHealthStore.loadConfig(appContext)
        _state.update {
            it.copy(
                isScaleSyncing = true,
                scaleStatusText = "Komunikuji s Tuya IoT Core..."
            )
        }

        scope.launch {
            val vysledek = withContext(Dispatchers.IO) {
                TuyaScaleClient.fetchRecentScaleReadings(cfg)
            }
            if (!vysledek.success) {
                _state.update {
                    it.copy(
                        isScaleSyncing = false,
                        scaleStatusText = vysledek.message,
                        tuyaDiagnosticLog = vysledek.diagnostic
                    )
                }
                return@launch
            }

            // Rezim kalibrace (kdyz jeste neni vybrana vychozi vaha nebo uzivatel klepl na prekalibrovani)
            if (forceCalibrationList || cfg.referenceWeightKg == null) {
                _state.update {
                    it.copy(
                        isScaleSyncing = false,
                        isCalibrationMode = true,
                        calibrationCandidates = vysledek.candidates,
                        scaleStatusText = "Vyber ze seznamu svoji váhu pro kalibraci filtru ±${FormatUtils.oneDecimal(cfg.weightToleranceKg)} kg.",
                        tuyaDiagnosticLog = vysledek.diagnostic
                    )
                }
                return@launch
            }

            // Chronologicky posuvny filtr +-10 kg od nejstarsiho po nejnovejsi vazeni
            val serazeneOdNejstarsiho = vysledek.candidates.sortedBy { it.timestampMillis }
            val existujici = LocalHealthStore.loadScaleList(appContext)
            var nejnovejsiUlozenyCas = existujici.maxOfOrNull { it.timestampMillis } ?: 0L
            var aktualniReference = cfg.referenceWeightKg
            val prijatePolozky = ArrayList<ScaleEntry>()
            var preskocenoCizich = 0

            for (kandidat in serazeneOdNejstarsiho) {
                val uzMame = existujici.any { abs(it.timestampMillis - kandidat.timestampMillis) < 20_000L }
                if (uzMame) continue

                if (abs(kandidat.weightKg - aktualniReference) <= cfg.weightToleranceKg) {
                    prijatePolozky.add(vytvorZaznamVahy(kandidat, cfg, "Tesla BF1321"))
                    if (kandidat.timestampMillis >= nejnovejsiUlozenyCas) {
                        aktualniReference = kandidat.weightKg
                        nejnovejsiUlozenyCas = kandidat.timestampMillis
                    }
                } else {
                    preskocenoCizich++
                }
            }

            if (aktualniReference != cfg.referenceWeightKg) {
                LocalHealthStore.saveConfig(appContext, cfg.copy(referenceWeightKg = aktualniReference))
            }
            if (prijatePolozky.isNotEmpty()) {
                LocalHealthStore.upsertScaleEntries(appContext, prijatePolozky)
            }

            val hcVysledek = withContext(Dispatchers.IO) {
                HealthConnectManager.syncRecords(appContext, forceAll = false)
            }

            val souhrn = buildString {
                append("Uloženo ${prijatePolozky.size} tvých vážení")
                if (preskocenoCizich > 0) {
                    append(", přeskočeno $preskocenoCizich mimo ±${FormatUtils.oneDecimal(cfg.weightToleranceKg)} kg")
                }
                append(".")
            }

            _state.update {
                it.copy(
                    isScaleSyncing = false,
                    scaleStatusText = souhrn,
                    lastSyncMessage = hcVysledek.message,
                    tuyaDiagnosticLog = vysledek.diagnostic + "\n" + souhrn
                )
            }
        }
    }

    override fun selectCalibrationCandidate(candidate: RawScaleCandidate) {
        val cfg = LocalHealthStore.loadConfig(appContext)
        val vsechnyKandidaty = _state.value.calibrationCandidates.sortedBy { it.timestampMillis }
        var posuvnaRef = candidate.weightKg
        val prijate = ArrayList<ScaleEntry>()

        prijate.add(vytvorZaznamVahy(candidate, cfg, "Tesla BF1321"))

        for (dalsi in vsechnyKandidaty) {
            if (dalsi.timestampMillis <= candidate.timestampMillis) continue
            if (abs(dalsi.weightKg - posuvnaRef) <= cfg.weightToleranceKg) {
                prijate.add(vytvorZaznamVahy(dalsi, cfg, "Tesla BF1321"))
                posuvnaRef = dalsi.weightKg
            }
        }

        LocalHealthStore.saveConfig(appContext, cfg.copy(referenceWeightKg = posuvnaRef))
        LocalHealthStore.upsertScaleEntries(appContext, prijate)

        _state.update {
            it.copy(
                isCalibrationMode = false,
                calibrationCandidates = emptyList(),
                scaleStatusText = "Zkalibrováno na ${FormatUtils.twoDecimals(posuvnaRef)} kg (±${FormatUtils.oneDecimal(cfg.weightToleranceKg)} kg)."
            )
        }
        syncAllPendingToHealthConnect()
    }

    override fun cancelCalibrationMode() {
        _state.update { it.copy(isCalibrationMode = false, calibrationCandidates = emptyList()) }
    }

    override fun resetWeightCalibration() {
        val cfg = LocalHealthStore.loadConfig(appContext)
        LocalHealthStore.saveConfig(appContext, cfg.copy(referenceWeightKg = null))
        _state.update { it.copy(scaleStatusText = "Kalibrace váhy vynulována.") }
    }

    override fun addManualWeight(weightKg: Double, impedanceOhms: Int?) {
        val cfg = LocalHealthStore.loadConfig(appContext)
        val cas = System.currentTimeMillis()
        val kandidat = RawScaleCandidate(
            id = "man_w_$cas",
            timestampMillis = cas,
            weightKg = weightKg,
            impedanceOhms = impedanceOhms
        )
        val zaznam = vytvorZaznamVahy(kandidat, cfg, "Ruční zápis")
        LocalHealthStore.saveConfig(appContext, cfg.copy(referenceWeightKg = weightKg))
        LocalHealthStore.upsertScaleEntries(appContext, listOf(zaznam))
        syncAllPendingToHealthConnect()
    }

    override fun syncAllPendingToHealthConnect() {
        scope.launch {
            val status = withContext(Dispatchers.IO) { HealthConnectManager.checkStatus(appContext) }
            if (!status.allCoreGranted) {
                _state.update {
                    it.copy(
                        healthConnectAvailable = status.available,
                        healthConnectGranted = false,
                        healthConnectDetail = status.detailText,
                        lastSyncMessage = "Nejprve povol oprávnění Health Connect."
                    )
                }
                zadostHcOpravneni?.invoke()
                return@launch
            }

            val res = withContext(Dispatchers.IO) {
                HealthConnectManager.syncRecords(appContext, forceAll = true)
            }
            val poSyncu = withContext(Dispatchers.IO) { HealthConnectManager.checkStatus(appContext) }
            _state.update {
                it.copy(
                    healthConnectAvailable = poSyncu.available,
                    healthConnectGranted = poSyncu.allCoreGranted,
                    healthConnectDetail = poSyncu.detailText,
                    lastSyncMessage = res.message
                )
            }
        }
    }

    override fun deleteGlucoseEntry(id: String) {
        LocalHealthStore.deleteGlucose(appContext, id)
    }

    override fun deleteScaleEntry(id: String) {
        LocalHealthStore.deleteScale(appContext, id)
    }

    private fun vytvorZaznamVahy(
        kandidat: RawScaleCandidate,
        cfg: UserScaleConfig,
        zdroj: String
    ): ScaleEntry {
        val comp = BodyCompositionCalculator.calculate(
            weightKg = kandidat.weightKg,
            impedanceOhms = kandidat.impedanceOhms,
            heightCm = cfg.heightCm,
            birthYear = cfg.birthYear,
            isMale = cfg.isMale
        )
        return ScaleEntry(
            id = kandidat.id,
            timestampMillis = kandidat.timestampMillis,
            weightKg = kandidat.weightKg,
            impedanceOhms = kandidat.impedanceOhms,
            bmi = comp.bmi,
            bodyFatPercent = comp.bodyFatPercent,
            leanBodyMassKg = comp.leanBodyMassKg,
            muscleMassKg = comp.muscleMassKg,
            bodyWaterKg = comp.bodyWaterKg,
            bodyWaterPercent = comp.bodyWaterPercent,
            boneMassKg = comp.boneMassKg,
            bmrKcal = comp.bmrKcal,
            visceralFatIndex = comp.visceralFatIndex,
            source = zdroj,
            syncedToHealthConnect = false
        )
    }
}

@Composable
actual fun rememberHealthBridge(): HealthBridge {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val bridge = remember(appContext) { AndroidHealthBridge(appContext) }

    val bleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (ContourSyncService.maBleOpravneni(appContext)) {
            val (_, adr) = LocalHealthStore.getPairedMeter(appContext)
            if (adr.isNullOrBlank()) {
                ContourSyncService.scanAndPair(appContext)
            } else {
                ContourSyncService.startIfPermitted(appContext)
            }
        }
    }

    val hcLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) {
        bridge.zkontrolujHealthConnect(synchronizovatCekajici = true)
    }

    DisposableEffect(bridge, bleLauncher, hcLauncher) {
        bridge.zadostBleOpravneni = {
            val perms = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            bleLauncher.launch(perms)
        }

        bridge.zadostHcOpravneni = {
            try {
                hcLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
            } catch (_: Throwable) {
                HealthConnectManager.openHealthConnectSettings(appContext)
            }
        }

        val app = appContext as? Application
        val sledovaniAktivity = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                bridge.zkontrolujHealthConnect(synchronizovatCekajici = true)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app?.registerActivityLifecycleCallbacks(sledovaniAktivity)

        onDispose {
            app?.unregisterActivityLifecycleCallbacks(sledovaniAktivity)
        }
    }

    LaunchedEffect(bridge) {
        bridge.zkontrolujHealthConnect(synchronizovatCekajici = true)
    }

    return bridge
}

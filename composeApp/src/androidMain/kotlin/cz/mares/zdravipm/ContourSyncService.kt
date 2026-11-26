package cz.mares.zdravipm

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ContourSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appCtx = applicationContext
        when (intent?.action) {
            ACTION_SCAN_AND_PAIR -> spustAktivniParovaniInterni(appCtx)
            ACTION_SYNC_NOW -> spustOkamzitySyncInterni(appCtx)
            ACTION_STOP_ALL -> zastavVseInterni(appCtx)
            else -> ensureBackgroundWatch(appCtx)
        }
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_PASSIVE_BLE_FOUND = "cz.mares.zdravipm.PASSIVE_BLE_FOUND"
        private const val ACTION_SCAN_AND_PAIR = "cz.mares.zdravipm.SCAN_AND_PAIR"
        private const val ACTION_SYNC_NOW = "cz.mares.zdravipm.SYNC_NOW"
        private const val ACTION_STOP_ALL = "cz.mares.zdravipm.STOP_ALL"

        private const val PASSIVE_REQUEST_CODE = 7804
        private const val MAX_STRIP_SESSION_MS = 95_000L
        private const val LIVE_POLL_INTERVAL_MS = 6_000L
        private const val FLUSH_DEBOUNCE_MS = 950L
        private const val PAIRING_SCAN_TIMEOUT_MS = 25_000L
        private const val MANUAL_SCAN_TIMEOUT_MS = 14_000L

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val gattLock = Any()

        private val _bleStatus = MutableStateFlow("Připraveno ke spojení s glukometrem")
        val bleStatus: StateFlow<String> = _bleStatus.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        @Volatile
        private var appContextRef: Context? = null

        @Volatile
        private var activeGatt: BluetoothGatt? = null

        @Volatile
        private var activeGattAddress: String? = null

        @Volatile
        private var isActiveConnected: Boolean = false

        @Volatile
        private var isDirectConnecting: Boolean = false

        @Volatile
        private var isStandbyAutoWaiting: Boolean = false

        @Volatile
        private var activeScanCallback: ScanCallback? = null

        @Volatile
        private var racpReady: Boolean = false

        @Volatile
        private var racpBusy: Boolean = false

        @Volatile
        private var lastRecordOperatorSupported: Boolean = true

        @Volatile
        private var initialAllRecordsRequested: Boolean = false

        private val sessionActiveFlag = AtomicBoolean(false)
        private val pendingMeasurements = ConcurrentHashMap<Int, ParsedContourMeasurement>()
        private val pendingMealContexts = ConcurrentHashMap<Int, MealRelation>()
        private val wakeupCompletionCallbacks = ArrayList<() -> Unit>()

        private var flushJob: Job? = null
        private var livePollJob: Job? = null
        private var sessionTimeoutJob: Job? = null
        private var scanTimeoutJob: Job? = null
        private var wakeupFallbackJob: Job? = null

        fun isBleSessionActive(): Boolean = sessionActiveFlag.get()

        fun maBleOpravneni(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scanOk = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
                val connectOk = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                scanOk && connectOk
            } else {
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }

        fun startIfPermitted(context: Context) {
            val appCtx = context.applicationContext
            appContextRef = appCtx
            if (!maBleOpravneni(appCtx)) {
                _bleStatus.value = "Povol oprávnění Blízká zařízení (Bluetooth)"
                return
            }
            ensureBackgroundWatch(appCtx)
        }

        fun registerPassiveWatch(context: Context) {
            ensureBackgroundWatch(context.applicationContext)
        }

        @SuppressLint("MissingPermission")
        fun ensureBackgroundWatch(context: Context) {
            val appCtx = context.applicationContext
            appContextRef = appCtx
            val cfg = LocalHealthStore.loadConfig(appCtx)
            val (pairedName, pairedAddr) = LocalHealthStore.getPairedMeter(appCtx)
            if (!cfg.backgroundServiceEnabled || pairedAddr.isNullOrBlank() || !maBleOpravneni(appCtx)) {
                return
            }

            val adapter = getBluetoothAdapter(appCtx) ?: return
            if (!adapter.isEnabled) {
                _bleStatus.value = "Bluetooth je vypnutý"
                return
            }

            zaregistrujPendingIntentSken(appCtx, adapter.bluetoothLeScanner, pairedAddr)
            zajistiStandbyAutoConnect(appCtx, adapter, pairedAddr, pairedName)
        }

        @SuppressLint("MissingPermission")
        fun unregisterPassiveWatch(context: Context) {
            val appCtx = context.applicationContext
            appContextRef = appCtx
            try {
                val scanner = getBluetoothAdapter(appCtx)?.bluetoothLeScanner
                val pi = buildPassivePendingIntent(appCtx, PendingIntent.FLAG_NO_CREATE)
                if (scanner != null && pi != null && maBleOpravneni(appCtx)) {
                    scanner.stopScan(pi)
                    pi.cancel()
                }
            } catch (_: Throwable) {
            }
            zavriGattBezpecne()
            uvolniSessionDrzeni(appCtx)
        }

        fun stopService(context: Context) {
            val appCtx = context.applicationContext
            zastavVseInterni(appCtx)
            try {
                appCtx.stopService(Intent(appCtx, ContourSyncService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun scanAndPair(context: Context) {
            val appCtx = context.applicationContext
            appContextRef = appCtx
            spustAktivniParovaniInterni(appCtx)
        }

        fun syncNow(context: Context) {
            val appCtx = context.applicationContext
            appContextRef = appCtx
            spustOkamzitySyncInterni(appCtx)
        }

        @SuppressLint("MissingPermission")
        fun syncQuietlyFromWakeup(
            context: Context,
            address: String,
            onComplete: () -> Unit = {}
        ) {
            val appCtx = context.applicationContext
            appContextRef = appCtx
            synchronized(wakeupCompletionCallbacks) {
                wakeupCompletionCallbacks.add(onComplete)
            }

            if (!maBleOpravneni(appCtx)) {
                dokonciWakeupCallbacky()
                return
            }
            val adapter = getBluetoothAdapter(appCtx)
            if (adapter == null || !adapter.isEnabled) {
                dokonciWakeupCallbacky()
                return
            }

            sessionActiveFlag.set(true)
            TuyaScaleSyncJobService.holdProcessForBleSession(appCtx, MAX_STRIP_SESSION_MS + 10_000L)
            planujStropRelace(appCtx)

            mainHandler.postDelayed({ dokonciWakeupCallbacky() }, 2_500L)

            synchronized(gattLock) {
                if (isActiveConnected || isDirectConnecting) {
                    if (racpReady && !racpBusy) {
                        vyzadejPosledniNeboVsechnyZaznamy()
                    }
                    return
                }
            }

            wakeupFallbackJob?.cancel()
            wakeupFallbackJob = serviceScope.launch {
                val melStandby = synchronized(gattLock) { isStandbyAutoWaiting && activeGatt != null }
                if (melStandby) {
                    delay(1_200L)
                    val uzSpojeno = synchronized(gattLock) { isActiveConnected || isDirectConnecting }
                    if (uzSpojeno) return@launch
                }
                pripojNaprimoKeGlukometru(appCtx, adapter, address, vyzadatParovani = false)
            }
        }

        @SuppressLint("MissingPermission")
        private fun spustAktivniParovaniInterni(appCtx: Context) {
            if (!maBleOpravneni(appCtx)) {
                _bleStatus.value = "Nejprve povol oprávnění Bluetooth (Blízká zařízení)"
                return
            }
            val adapter = getBluetoothAdapter(appCtx)
            if (adapter == null || !adapter.isEnabled) {
                _bleStatus.value = "Zapni v telefonu Bluetooth"
                return
            }

            zastavAktivniSken(adapter)
            zavriGattBezpecne()

            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                _bleStatus.value = "Bluetooth LE skener není dostupný"
                return
            }

            _isScanning.value = true
            _bleStatus.value = "Hledám zapnutý Contour Plus One v okolí..."

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val dev = result?.device ?: return
                    val addr = dev.address ?: return
                    val name = try {
                        dev.name ?: result.scanRecord?.deviceName ?: ""
                    } catch (_: Throwable) {
                        ""
                    }
                    val hasGlucoseUuid = result.scanRecord?.serviceUuids?.any {
                        it.uuid == ContourBleParser.GLUCOSE_SERVICE_UUID
                    } == true

                    if (!name.contains("Contour", ignoreCase = true) && !hasGlucoseUuid) return

                    zastavAktivniSken(adapter)
                    val cleanName = name.ifBlank { "Contour Plus One" }
                    LocalHealthStore.setPairedMeter(appCtx, cleanName, addr)
                    _bleStatus.value = "Nalezen $cleanName, spojuji a páruji..."

                    sessionActiveFlag.set(true)
                    TuyaScaleSyncJobService.holdProcessForBleSession(appCtx, MAX_STRIP_SESSION_MS + 10_000L)
                    planujStropRelace(appCtx)
                    pripojNaprimoKeGlukometru(appCtx, adapter, addr, vyzadatParovani = true)
                }

                override fun onScanFailed(errorCode: Int) {
                    _isScanning.value = false
                    _bleStatus.value = "Chyba skenování Bluetooth (kód $errorCode)"
                }
            }

            activeScanCallback = callback
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(null, settings, callback)
            } catch (t: Throwable) {
                _isScanning.value = false
                _bleStatus.value = "Nelze spustit hledání: ${t.message}"
                return
            }

            scanTimeoutJob?.cancel()
            scanTimeoutJob = serviceScope.launch {
                delay(PAIRING_SCAN_TIMEOUT_MS)
                if (_isScanning.value) {
                    zastavAktivniSken(adapter)
                    _bleStatus.value = "Glukometr nenalezen. Zapni Contour podržením tlačítka a zkus to znovu."
                    ensureBackgroundWatch(appCtx)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun spustOkamzitySyncInterni(appCtx: Context) {
            if (!maBleOpravneni(appCtx)) {
                _bleStatus.value = "Povol oprávnění Bluetooth pro synchronizaci"
                return
            }
            val (_, pairedAddr) = LocalHealthStore.getPairedMeter(appCtx)
            if (pairedAddr.isNullOrBlank()) {
                spustAktivniParovaniInterni(appCtx)
                return
            }
            val adapter = getBluetoothAdapter(appCtx)
            if (adapter == null || !adapter.isEnabled) {
                _bleStatus.value = "Zapni v telefonu Bluetooth"
                return
            }

            synchronized(gattLock) {
                if (isActiveConnected && racpReady) {
                    _bleStatus.value = "Glukometr je připojen, vyčítám měření..."
                    vyzadejVsechnyZaznamyPresRacp()
                    return
                }
            }

            _isScanning.value = true
            _bleStatus.value = "Spojuji se s glukometrem Contour..."
            sessionActiveFlag.set(true)
            TuyaScaleSyncJobService.holdProcessForBleSession(appCtx, MAX_STRIP_SESSION_MS + 10_000L)
            planujStropRelace(appCtx)

            pripojNaprimoKeGlukometru(appCtx, adapter, pairedAddr, vyzadatParovani = false)

            scanTimeoutJob?.cancel()
            scanTimeoutJob = serviceScope.launch {
                delay(MANUAL_SCAN_TIMEOUT_MS)
                if (_isScanning.value && !isActiveConnected) {
                    _isScanning.value = false
                    _bleStatus.value = "Čekám na zapnutí glukometru na pozadí..."
                    ensureBackgroundWatch(appCtx)
                }
            }
        }

        private fun zastavVseInterni(appCtx: Context) {
            val adapter = getBluetoothAdapter(appCtx)
            if (adapter != null) {
                zastavAktivniSken(adapter)
            }
            unregisterPassiveWatch(appCtx)
            _isScanning.value = false
            _bleStatus.value = "Hlídání glukometru vypnuto"
        }

        @SuppressLint("MissingPermission")
        private fun zastavAktivniSken(adapter: BluetoothAdapter) {
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            val cb = activeScanCallback
            activeScanCallback = null
            _isScanning.value = false
            if (cb != null && adapter.isEnabled) {
                try {
                    adapter.bluetoothLeScanner?.stopScan(cb)
                } catch (_: Throwable) {
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun zaregistrujPendingIntentSken(
            appCtx: Context,
            scanner: BluetoothLeScanner?,
            pairedAddress: String
        ) {
            if (scanner == null) return
            try {
                val filters = ArrayList<ScanFilter>(2)
                if (BluetoothAdapter.checkBluetoothAddress(pairedAddress.uppercase())) {
                    filters.add(
                        ScanFilter.Builder()
                            .setDeviceAddress(pairedAddress.uppercase())
                            .build()
                    )
                }
                filters.add(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(ContourBleParser.GLUCOSE_SERVICE_UUID))
                        .build()
                )

                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build()

                val pi = buildPassivePendingIntent(
                    appCtx,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutablePendingIntentFlag()
                ) ?: return

                scanner.startScan(filters, settings, pi)
            } catch (_: Throwable) {
            }
        }

        private fun buildPassivePendingIntent(context: Context, flags: Int): PendingIntent? {
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = ACTION_PASSIVE_BLE_FOUND
                setPackage(context.packageName)
            }
            return PendingIntent.getBroadcast(context, PASSIVE_REQUEST_CODE, intent, flags)
        }

        private fun mutablePendingIntentFlag(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        }

        @SuppressLint("MissingPermission")
        private fun zajistiStandbyAutoConnect(
            appCtx: Context,
            adapter: BluetoothAdapter,
            address: String,
            pairedName: String?
        ) {
            val cleanAddr = address.uppercase()
            if (!BluetoothAdapter.checkBluetoothAddress(cleanAddr)) return

            synchronized(gattLock) {
                if (activeGatt != null && activeGattAddress.equals(cleanAddr, ignoreCase = true)) {
                    if (isActiveConnected || isDirectConnecting || isStandbyAutoWaiting) {
                        return
                    }
                }
                uzavriGattUvnitrZamku()
                try {
                    val device = adapter.getRemoteDevice(cleanAddr)
                    activeGattAddress = cleanAddr
                    isStandbyAutoWaiting = true
                    isDirectConnecting = false
                    isActiveConnected = false
                    activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(appCtx, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(appCtx, true, gattCallback)
                    }
                    val label = pairedName?.ifBlank { null } ?: "Contour Plus One"
                    if (!_isScanning.value) {
                        _bleStatus.value = "Tiché hlídání aktivní ($label)"
                    }
                } catch (_: Throwable) {
                    isStandbyAutoWaiting = false
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun pripojNaprimoKeGlukometru(
            appCtx: Context,
            adapter: BluetoothAdapter,
            address: String,
            vyzadatParovani: Boolean
        ) {
            val cleanAddr = address.uppercase()
            if (!BluetoothAdapter.checkBluetoothAddress(cleanAddr)) return

            synchronized(gattLock) {
                if (isActiveConnected && activeGattAddress.equals(cleanAddr, ignoreCase = true)) {
                    return
                }
                uzavriGattUvnitrZamku()
                try {
                    val device = adapter.getRemoteDevice(cleanAddr)
                    if (vyzadatParovani && device.bondState == BluetoothDevice.BOND_NONE) {
                        try {
                            device.createBond()
                        } catch (_: Throwable) {
                        }
                    }
                    activeGattAddress = cleanAddr
                    isDirectConnecting = true
                    isStandbyAutoWaiting = false
                    isActiveConnected = false
                    activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(appCtx, false, gattCallback)
                    }
                } catch (t: Throwable) {
                    isDirectConnecting = false
                    _isScanning.value = false
                    _bleStatus.value = "Chyba připojení ke glukometru: ${t.message}"
                }
            }
        }

        private val gattCallback = object : BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val ctx = appContextRef ?: return

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        synchronized(gattLock) {
                            if (activeGatt != null && activeGatt !== gatt) {
                                try {
                                    gatt.close()
                                } catch (_: Throwable) {
                                }
                                return
                            }
                            activeGatt = gatt
                            isActiveConnected = true
                            isDirectConnecting = false
                            isStandbyAutoWaiting = false
                            racpReady = false
                            racpBusy = false
                            initialAllRecordsRequested = false
                        }

                        _isScanning.value = false
                        _bleStatus.value = "Připojeno ke glukometru, čtu data..."
                        sessionActiveFlag.set(true)
                        TuyaScaleSyncJobService.holdProcessForBleSession(ctx, MAX_STRIP_SESSION_MS + 10_000L)
                        TuyaScaleSyncJobService.triggerOpportunisticCheckIfDue(ctx)
                        planujStropRelace(ctx)

                        mainHandler.postDelayed({
                            try {
                                gatt.discoverServices()
                            } catch (_: Throwable) {
                            }
                        }, 250L)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val byloSpojeno = synchronized(gattLock) {
                            if (activeGatt !== gatt && activeGatt != null) {
                                try {
                                    gatt.close()
                                } catch (_: Throwable) {
                                }
                                return
                            }
                            val prev = isActiveConnected
                            uzavriGattUvnitrZamku()
                            prev
                        }

                        livePollJob?.cancel()
                        livePollJob = null
                        _isScanning.value = false

                        serviceScope.launch {
                            if (byloSpojeno || pendingMeasurements.isNotEmpty()) {
                                ulozASynchronizujNactene(ctx, konecRelace = true)
                            }
                            uvolniSessionDrzeni(ctx)
                            dokonciWakeupCallbacky()
                            delay(2_200L)
                            ensureBackgroundWatch(ctx)
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                zapniNotifikaceKrok1Mereni(gatt, pokus = 0)
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val charUuid = descriptor.characteristic?.uuid ?: return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    mainHandler.postDelayed({
                        when (charUuid) {
                            ContourBleParser.GLUCOSE_MEASUREMENT_UUID -> zapniNotifikaceKrok1Mereni(gatt, 1)
                            ContourBleParser.GLUCOSE_CONTEXT_UUID -> zapniNotifikaceKrok2Kontext(gatt)
                            ContourBleParser.RACP_UUID -> zapniIndikaceKrok3Racp(gatt)
                        }
                    }, 650L)
                    return
                }

                when (charUuid) {
                    ContourBleParser.GLUCOSE_MEASUREMENT_UUID -> zapniNotifikaceKrok2Kontext(gatt)
                    ContourBleParser.GLUCOSE_CONTEXT_UUID -> zapniIndikaceKrok3Racp(gatt)
                    ContourBleParser.RACP_UUID -> {
                        racpReady = true
                        racpBusy = false
                        initialAllRecordsRequested = true
                        _bleStatus.value = "Glukometr připojen — stahuji měření a čekám na proužek..."
                        vyzadejVsechnyZaznamyPresRacp()
                        spustPrubeznouKontroluProuzku(gatt)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                zpracujPrichoziPaket(characteristic.uuid, value.copyOf())
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                val raw = characteristic.value?.copyOf() ?: return
                zpracujPrichoziPaket(characteristic.uuid, raw)
            }
        }

        private fun zpracujPrichoziPaket(charUuid: UUID?, bytes: ByteArray) {
            if (charUuid == null || bytes.isEmpty()) return
            val ctx = appContextRef ?: return

            when (charUuid) {
                ContourBleParser.GLUCOSE_MEASUREMENT_UUID -> {
                    val parsed = ContourBleParser.parseMeasurement(bytes) ?: return
                    pendingMeasurements[parsed.sequenceNumber] = parsed
                    naplanujPrubezneUlozeni(ctx)
                }

                ContourBleParser.GLUCOSE_CONTEXT_UUID -> {
                    val ctxPair = ContourBleParser.parseContextMeal(bytes) ?: return
                    pendingMealContexts[ctxPair.first] = ctxPair.second
                    naplanujPrubezneUlozeni(ctx)
                }

                ContourBleParser.RACP_UUID -> {
                    racpBusy = false
                    if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0x06) {
                        val rspCode = bytes[3].toInt() and 0xFF
                        if (rspCode != 0x01 && rspCode != 0x06 && lastRecordOperatorSupported) {
                            lastRecordOperatorSupported = false
                            vyzadejVsechnyZaznamyPresRacp()
                            return
                        }
                    }
                    naplanujPrubezneUlozeni(ctx, zpozdeniMs = 350L)
                }
            }
        }

        private fun naplanujPrubezneUlozeni(ctx: Context, zpozdeniMs: Long = FLUSH_DEBOUNCE_MS) {
            flushJob?.cancel()
            flushJob = serviceScope.launch {
                delay(zpozdeniMs)
                ulozASynchronizujNactene(ctx, konecRelace = false)
            }
        }

        private suspend fun ulozASynchronizujNactene(ctx: Context, konecRelace: Boolean) {
            val snapshot = ArrayList<ParsedContourMeasurement>(pendingMeasurements.values)
            if (snapshot.isNotEmpty()) {
                val entries = snapshot.map { m ->
                    val meal = pendingMealContexts[m.sequenceNumber] ?: MealRelation.GENERAL
                    GlucoseEntry(
                        id = "contour_${m.sequenceNumber}_${m.timestampMillis / 1000L}",
                        timestampMillis = m.timestampMillis,
                        mmolPerLiter = m.mmolPerLiter,
                        mealRelation = meal,
                        sequenceNumber = m.sequenceNumber,
                        source = "Contour Plus One",
                        syncedToHealthConnect = false
                    )
                }

                val existing = LocalHealthStore.loadGlucoseList(ctx)
                val noveNeboZmenene = entries.filter { inc ->
                    val match = existing.firstOrNull { ex ->
                        ex.id == inc.id ||
                            (kotlin.math.abs(ex.timestampMillis - inc.timestampMillis) <= 15_000L &&
                                kotlin.math.abs(ex.mmolPerLiter - inc.mmolPerLiter) < 0.05)
                    }
                    match == null || (inc.mealRelation != MealRelation.GENERAL && match.mealRelation != inc.mealRelation)
                }

                if (noveNeboZmenene.isNotEmpty()) {
                    LocalHealthStore.upsertGlucoseEntries(ctx, noveNeboZmenene)
                    HealthConnectManager.syncRecords(ctx, forceAll = false)
                    val latestVal = noveNeboZmenene.maxByOrNull { it.timestampMillis }?.mmolPerLiter
                    if (latestVal != null) {
                        _bleStatus.value = if (konecRelace) {
                            "Načteno ${FormatUtils.oneDecimal(latestVal)} mmol/L a uloženo do HC"
                        } else {
                            "Uloženo ${FormatUtils.oneDecimal(latestVal)} mmol/L (spojení aktivní)"
                        }
                    }
                } else if (!konecRelace && isActiveConnected) {
                    _bleStatus.value = "Glukometr připojen — čekám na kapku krve na proužku..."
                }

                if (konecRelace) {
                    pendingMeasurements.clear()
                    pendingMealContexts.clear()
                }
            } else if (konecRelace) {
                val hasPending = LocalHealthStore.loadGlucoseList(ctx).any { !it.syncedToHealthConnect }
                if (hasPending) {
                    HealthConnectManager.syncRecords(ctx, forceAll = false)
                }
            }
        }

        private fun spustPrubeznouKontroluProuzku(gatt: BluetoothGatt) {
            livePollJob?.cancel()
            livePollJob = serviceScope.launch {
                while (isActiveConnected && activeGatt === gatt) {
                    delay(LIVE_POLL_INTERVAL_MS)
                    if (!isActiveConnected || activeGatt !== gatt) break
                    if (racpReady && !racpBusy) {
                        vyzadejPosledniNeboVsechnyZaznamy()
                    }
                }
            }
        }

        private fun planujStropRelace(ctx: Context) {
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = serviceScope.launch {
                delay(MAX_STRIP_SESSION_MS)
                if (isActiveConnected || isDirectConnecting) {
                    ulozASynchronizujNactene(ctx, konecRelace = true)
                    zavriGattBezpecne()
                    uvolniSessionDrzeni(ctx)
                    delay(1_500L)
                    ensureBackgroundWatch(ctx)
                } else {
                    uvolniSessionDrzeni(ctx)
                }
            }
        }

        private fun uvolniSessionDrzeni(ctx: Context) {
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = null
            sessionActiveFlag.set(false)
            TuyaScaleSyncJobService.releaseBleSessionHold(ctx)
        }

        @SuppressLint("MissingPermission")
        private fun zapniNotifikaceKrok1Mereni(gatt: BluetoothGatt, pokus: Int) {
            val service = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID) ?: return
            val measChar = service.getCharacteristic(ContourBleParser.GLUCOSE_MEASUREMENT_UUID) ?: return
            if (gatt.device?.bondState == BluetoothDevice.BOND_BONDING && pokus < 4) {
                mainHandler.postDelayed({ zapniNotifikaceKrok1Mereni(gatt, pokus + 1) }, 700L)
                return
            }
            gatt.setCharacteristicNotification(measChar, true)
            val cccd = measChar.getDescriptor(ContourBleParser.CCCD_UUID)
            if (cccd != null) {
                zapisDescriptorBezpecne(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                zapniNotifikaceKrok2Kontext(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        private fun zapniNotifikaceKrok2Kontext(gatt: BluetoothGatt) {
            val service = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID) ?: return
            val ctxChar = service.getCharacteristic(ContourBleParser.GLUCOSE_CONTEXT_UUID)
            if (ctxChar != null) {
                gatt.setCharacteristicNotification(ctxChar, true)
                val cccd = ctxChar.getDescriptor(ContourBleParser.CCCD_UUID)
                if (cccd != null) {
                    zapisDescriptorBezpecne(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    return
                }
            }
            zapniIndikaceKrok3Racp(gatt)
        }

        @SuppressLint("MissingPermission")
        private fun zapniIndikaceKrok3Racp(gatt: BluetoothGatt) {
            val service = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID) ?: return
            val racpChar = service.getCharacteristic(ContourBleParser.RACP_UUID) ?: return
            gatt.setCharacteristicNotification(racpChar, true)
            val cccd = racpChar.getDescriptor(ContourBleParser.CCCD_UUID) ?: return
            zapisDescriptorBezpecne(gatt, cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        }

        private fun vyzadejPosledniNeboVsechnyZaznamy() {
            if (!initialAllRecordsRequested || !lastRecordOperatorSupported) {
                vyzadejVsechnyZaznamyPresRacp()
            } else {
                odesliRacpPrikaz(byteArrayOf(0x01, 0x06))
            }
        }

        private fun vyzadejVsechnyZaznamyPresRacp() {
            odesliRacpPrikaz(byteArrayOf(0x01, 0x01))
        }

        @SuppressLint("MissingPermission")
        private fun odesliRacpPrikaz(command: ByteArray) {
            val gatt = synchronized(gattLock) { activeGatt } ?: return
            if (!isActiveConnected || !racpReady) return
            val service = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID) ?: return
            val racpChar = service.getCharacteristic(ContourBleParser.RACP_UUID) ?: return
            racpBusy = true
            mainHandler.post {
                val ok = zapisCharakteristikuBezpecne(gatt, racpChar, command)
                if (!ok) {
                    racpBusy = false
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun zapisDescriptorBezpecne(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            hodnota: ByteArray
        ): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, hodnota) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        descriptor.value = hodnota
                        gatt.writeDescriptor(descriptor)
                    }
                }
            } catch (_: Throwable) {
                false
            }
        }

        @SuppressLint("MissingPermission")
        private fun zapisCharakteristikuBezpecne(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            hodnota: ByteArray
        ): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        hodnota,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        characteristic.value = hodnota
                        gatt.writeCharacteristic(characteristic)
                    }
                }
            } catch (_: Throwable) {
                false
            }
        }

        private fun zavriGattBezpecne() {
            synchronized(gattLock) {
                uzavriGattUvnitrZamku()
            }
        }

        @SuppressLint("MissingPermission")
        private fun uzavriGattUvnitrZamku() {
            val g = activeGatt
            activeGatt = null
            activeGattAddress = null
            isActiveConnected = false
            isDirectConnecting = false
            isStandbyAutoWaiting = false
            racpReady = false
            racpBusy = false
            if (g != null) {
                try {
                    g.disconnect()
                } catch (_: Throwable) {
                }
                try {
                    g.close()
                } catch (_: Throwable) {
                }
            }
        }

        private fun dokonciWakeupCallbacky() {
            val callbacks = synchronized(wakeupCompletionCallbacks) {
                val copy = ArrayList(wakeupCompletionCallbacks)
                wakeupCompletionCallbacks.clear()
                copy
            }
            for (cb in callbacks) {
                try {
                    cb()
                } catch (_: Throwable) {
                }
            }
        }

        private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return mgr?.adapter
        }
    }
}

package cz.mares.zdravipm

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
private object ContourBleController {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hlavniHandler = Handler(Looper.getMainLooper())

    private var aktivniSluzba: ContourSyncService? = null
    private var aktivniGatt: BluetoothGatt? = null
    private var posledniKontext: Context? = null
    private var probihaSken = false
    private var probihaSpojeni = false
    private var posledniHotovySyncMs = 0L

    private val frontaDeskriptoru = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private val namereneVzorky = LinkedHashMap<Int, ParsedContourMeasurement>()
    private val jidloPodleSekvence = HashMap<Int, MealRelation>()
    private val cekajiciCallbacky = ArrayList<() -> Unit>()

    private val ulozeniZpozdeno = Runnable {
        posledniKontext?.let { ulozNamerenaDataAUkonci(it, odpojitHned = true) }
    }
    private val zastaveniSkenu = Runnable {
        posledniKontext?.let { zastavBleSken(it, ukoncitBezNalezu = true) }
    }
    private val ochrannyTimeout = Runnable {
        bezpecneUkonciVse()
    }

    fun zaregistrujSluzbu(service: ContourSyncService?) {
        aktivniSluzba = service
    }

    fun pridejCallbackDokonceni(onComplete: (() -> Unit)?) {
        if (onComplete == null) return
        synchronized(cekajiciCallbacky) {
            cekajiciCallbacky.add(onComplete)
        }
    }

    private fun nactiAdapter(context: Context): BluetoothAdapter? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    fun spustHledaniGlukometru(context: Context) {
        val appCtx = context.applicationContext
        posledniKontext = appCtx

        val adapter = nactiAdapter(appCtx)
        if (adapter == null || !adapter.isEnabled) {
            ContourSyncService.nastavBleStatus("Zapni v telefonu Bluetooth.")
            bezpecneUkonciVse()
            return
        }

        val existujici = adapter.bondedDevices?.firstOrNull { dev ->
            dev.name?.contains("Contour", ignoreCase = true) == true
        }
        if (existujici != null) {
            val nazev = existujici.name ?: "Contour Plus One"
            LocalHealthStore.setPairedMeter(appCtx, nazev, existujici.address)
            ContourSyncService.registerPassiveWatch(appCtx)
            ContourSyncService.nastavBleStatus("Nalezen spárovaný $nazev • načítám záznamy...")
            pripojKeGlukometru(appCtx, existujici.address, rucniDotaz = true)
            return
        }

        zastavBleSken(appCtx, ukoncitBezNalezu = false)
        val skener = adapter.bluetoothLeScanner
        if (skener == null) {
            ContourSyncService.nastavBleStatus("BLE skener není dostupný.")
            bezpecneUkonciVse()
            return
        }

        probihaSken = true
        ContourSyncService.nastavSkenovani(true)
        ContourSyncService.nastavBleStatus("Hledám zapnutý glukometr Contour Plus One...")

        val nastaveni = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        skener.startScan(null, nastaveni, skenCallback)
        hlavniHandler.postDelayed(zastaveniSkenu, 20_000L)
    }

    fun zastavBleSken(context: Context, ukoncitBezNalezu: Boolean) {
        hlavniHandler.removeCallbacks(zastaveniSkenu)
        if (probihaSken) {
            try {
                nactiAdapter(context)?.bluetoothLeScanner?.stopScan(skenCallback)
            } catch (_: Throwable) {
            }
            probihaSken = false
            ContourSyncService.nastavSkenovani(false)
            if (ukoncitBezNalezu) {
                val (_, adr) = LocalHealthStore.getPairedMeter(context)
                if (adr.isNullOrBlank()) {
                    ContourSyncService.nastavBleStatus("Glukometr nenalezen. Zapni Contour a zkus to znovu.")
                }
                bezpecneUkonciVse()
            }
        }
    }

    private val skenCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val appCtx = posledniKontext ?: return
            val dev = result?.device ?: return
            val jmeno = dev.name ?: result.scanRecord?.deviceName ?: ""
            val sluzby = result.scanRecord?.serviceUuids.orEmpty()
            val maSluzbuGlukozy = sluzby.contains(ParcelUuid(ContourBleParser.GLUCOSE_SERVICE_UUID))
            val jeContour = jmeno.contains("Contour", ignoreCase = true) || maSluzbuGlukozy
            if (!jeContour) return

            val ulozeneJmeno = jmeno.ifBlank { "Contour Plus One" }
            zastavBleSken(appCtx, ukoncitBezNalezu = false)
            LocalHealthStore.setPairedMeter(appCtx, ulozeneJmeno, dev.address)
            ContourSyncService.registerPassiveWatch(appCtx)
            ContourSyncService.nastavBleStatus("Spárováno: $ulozeneJmeno. Čtu měření...")
            pripojKeGlukometru(appCtx, dev.address, rucniDotaz = true)
        }
    }

    fun pripojKeGlukometru(context: Context, adresa: String, rucniDotaz: Boolean) {
        val appCtx = context.applicationContext
        posledniKontext = appCtx

        val nyni = System.currentTimeMillis()
        if (!rucniDotaz) {
            if (probihaSpojeni && aktivniGatt != null) {
                return
            }
            if (nyni - posledniHotovySyncMs < 15_000L) {
                vyrizenoCallbacky()
                return
            }
        }

        val adapter = nactiAdapter(appCtx)
        if (adapter == null || !adapter.isEnabled) {
            ContourSyncService.nastavBleStatus("Bluetooth je vypnutý.")
            bezpecneUkonciVse()
            return
        }

        val zarizeni: BluetoothDevice = try {
            adapter.getRemoteDevice(adresa)
        } catch (_: Throwable) {
            ContourSyncService.nastavBleStatus("Neplatná Bluetooth adresa glukometru.")
            bezpecneUkonciVse()
            return
        }

        uzavriGatt()
        probihaSpojeni = true
        hlavniHandler.removeCallbacks(ochrannyTimeout)
        hlavniHandler.postDelayed(ochrannyTimeout, if (rucniDotaz) 22_000L else 28_000L)

        ContourSyncService.nastavBleStatus(
            if (rucniDotaz) {
                "Spojuji se s glukometrem ($adresa)..."
            } else {
                "Glukometr detekován • stahuji měření..."
            }
        )

        aktivniGatt = zarizeni.connectGatt(
            appCtx,
            false,
            gattObsluha,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun odpojAZastav(context: Context) {
        hlavniHandler.removeCallbacksAndMessages(null)
        zastavBleSken(context, ukoncitBezNalezu = false)
        uzavriGatt()
        probihaSpojeni = false
        bezpecneUkonciVse()
    }

    private fun uzavriGatt() {
        frontaDeskriptoru.clear()
        try {
            aktivniGatt?.disconnect()
            aktivniGatt?.close()
        } catch (_: Throwable) {
        }
        aktivniGatt = null
    }

    private val gattObsluha = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ContourSyncService.nastavBleStatus("Glukometr připojen • čtu záznamy...")
                val objevSluzby = Runnable {
                    try {
                        gatt.discoverServices()
                    } catch (_: Throwable) {
                        bezpecneUkonciVse()
                    }
                }
                hlavniHandler.postDelayed(objevSluzby, 300L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val priOdpojeni = Runnable {
                    val ctx = posledniKontext
                    if (ctx != null) {
                        ulozNamerenaDataAUkonci(ctx, odpojitHned = true)
                    } else {
                        bezpecneUkonciVse()
                    }
                }
                hlavniHandler.post(priOdpojeni)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                hlavniHandler.post(Runnable { bezpecneUkonciVse() })
                return
            }
            val sluzba = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID) ?: run {
                ContourSyncService.nastavBleStatus("Služba glukózy 0x1808 nebyla nalezena.")
                hlavniHandler.post(Runnable { bezpecneUkonciVse() })
                return
            }

            frontaDeskriptoru.clear()
            synchronized(namereneVzorky) {
                namereneVzorky.clear()
                jidloPodleSekvence.clear()
            }

            sluzba.getCharacteristic(ContourBleParser.GLUCOSE_MEASUREMENT_UUID)?.let { ch ->
                gatt.setCharacteristicNotification(ch, true)
                ch.getDescriptor(ContourBleParser.CCCD_UUID)?.let { d ->
                    frontaDeskriptoru.addLast(d to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }

            sluzba.getCharacteristic(ContourBleParser.GLUCOSE_CONTEXT_UUID)?.let { ch ->
                gatt.setCharacteristicNotification(ch, true)
                ch.getDescriptor(ContourBleParser.CCCD_UUID)?.let { d ->
                    frontaDeskriptoru.addLast(d to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }

            sluzba.getCharacteristic(ContourBleParser.RACP_UUID)?.let { ch ->
                gatt.setCharacteristicNotification(ch, true)
                ch.getDescriptor(ContourBleParser.CCCD_UUID)?.let { d ->
                    frontaDeskriptoru.addLast(d to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                }
            }

            zapisDalsiDeskriptor(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val dalsiKrok = Runnable {
                if (frontaDeskriptoru.isNotEmpty()) {
                    zapisDalsiDeskriptor(gatt)
                } else {
                    vyzadejZaznamyPresRacp(gatt)
                }
            }
            hlavniHandler.postDelayed(dalsiKrok, 120L)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            zpracujPaketGlukometru(characteristic.uuid.toString(), value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val data = characteristic.value ?: return
                zpracujPaketGlukometru(characteristic.uuid.toString(), data)
            }
        }
    }

    private fun zapisDalsiDeskriptor(gatt: BluetoothGatt) {
        val dalsi = frontaDeskriptoru.removeFirstOrNull() ?: return
        val (deskriptor, hodnota) = dalsi
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(deskriptor, hodnota)
            } else {
                @Suppress("DEPRECATION")
                run {
                    deskriptor.value = hodnota
                    gatt.writeDescriptor(deskriptor)
                }
            }
        } catch (_: Throwable) {
            bezpecneUkonciVse()
        }
    }

    private fun vyzadejZaznamyPresRacp(gatt: BluetoothGatt) {
        val racp = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID)
            ?.getCharacteristic(ContourBleParser.RACP_UUID) ?: return
        val prikazVsechnyZaznamy = byteArrayOf(0x01, 0x01)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    racp,
                    prikazVsechnyZaznamy,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                run {
                    racp.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    racp.value = prikazVsechnyZaznamy
                    gatt.writeCharacteristic(racp)
                }
            }
        } catch (_: Throwable) {
            bezpecneUkonciVse()
        }
    }

    private fun zpracujPaketGlukometru(uuidText: String, bajty: ByteArray) {
        when {
            uuidText.equals(ContourBleParser.GLUCOSE_MEASUREMENT_UUID.toString(), ignoreCase = true) -> {
                ContourBleParser.parseMeasurement(bajty)?.let { vzorek ->
                    synchronized(namereneVzorky) {
                        namereneVzorky[vzorek.sequenceNumber] = vzorek
                    }
                    naplanujUlozeni(1_800L)
                }
            }
            uuidText.equals(ContourBleParser.GLUCOSE_CONTEXT_UUID.toString(), ignoreCase = true) -> {
                ContourBleParser.parseContextMeal(bajty)?.let { (sekvence, jidlo) ->
                    synchronized(namereneVzorky) {
                        jidloPodleSekvence[sekvence] = jidlo
                    }
                    naplanujUlozeni(1_200L)
                }
            }
            uuidText.equals(ContourBleParser.RACP_UUID.toString(), ignoreCase = true) -> {
                naplanujUlozeni(450L)
            }
        }
    }

    private fun naplanujUlozeni(zpozdeniMs: Long) {
        hlavniHandler.removeCallbacks(ulozeniZpozdeno)
        hlavniHandler.postDelayed(ulozeniZpozdeno, zpozdeniMs)
    }

    private fun ulozNamerenaDataAUkonci(context: Context, odpojitHned: Boolean) {
        hlavniHandler.removeCallbacks(ulozeniZpozdeno)
        val appCtx = context.applicationContext
        val davka: List<GlucoseEntry>
        val (jmenoGlukometru, _) = LocalHealthStore.getPairedMeter(appCtx)
        val zdroj = jmenoGlukometru ?: "Contour Plus One"

        synchronized(namereneVzorky) {
            if (namereneVzorky.isEmpty()) {
                if (odpojitHned) {
                    ContourSyncService.nastavBleStatus("Tiché hlídání při zapnutí glukometru (bez ikony v liště)")
                    bezpecneUkonciVse()
                }
                return
            }
            davka = namereneVzorky.values.map { v ->
                val jidlo = jidloPodleSekvence[v.sequenceNumber] ?: MealRelation.GENERAL
                GlucoseEntry(
                    id = "cnt_${v.sequenceNumber}_${v.timestampMillis / 1000L}",
                    timestampMillis = v.timestampMillis,
                    mmolPerLiter = v.mmolPerLiter,
                    mealRelation = jidlo,
                    sequenceNumber = v.sequenceNumber,
                    source = zdroj,
                    syncedToHealthConnect = false
                )
            }
            namereneVzorky.clear()
            jidloPodleSekvence.clear()
        }

        posledniHotovySyncMs = System.currentTimeMillis()
        LocalHealthStore.upsertGlucoseEntries(appCtx, davka)
        ContourSyncService.nastavBleStatus("Přijato ${davka.size} měření • zapisuji do Health Connect...")

        engineScope.launch {
            val vysledek = HealthConnectManager.syncRecords(appCtx, forceAll = false)
            ContourSyncService.nastavBleStatus(
                if (vysledek.glucoseWritten > 0) {
                    "Přeneseno do Health Connect (${vysledek.glucoseWritten}× záznam) • tiché hlídání"
                } else {
                    "Uloženo v telefonu (${davka.size}×) • tiché hlídání aktivní"
                }
            )
            hlavniHandler.post(Runnable { bezpecneUkonciVse() })
        }
    }

    private fun bezpecneUkonciVse() {
        hlavniHandler.removeCallbacks(ochrannyTimeout)
        uzavriGatt()
        probihaSpojeni = false
        try {
            aktivniSluzba?.stopSelf()
        } catch (_: Throwable) {
        }
        vyrizenoCallbacky()
    }

    private fun vyrizenoCallbacky() {
        val kopie: List<() -> Unit>
        synchronized(cekajiciCallbacky) {
            kopie = cekajiciCallbacky.toList()
            cekajiciCallbacky.clear()
        }
        kopie.forEach { cb ->
            try {
                cb.invoke()
            } catch (_: Throwable) {
            }
        }
    }
}

@SuppressLint("MissingPermission")
class ContourSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContourBleController.zaregistrujSluzbu(this)
        vycistiStarouNotifikaci(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ContourBleController.zaregistrujSluzbu(this)
        if (!maBleOpravneni(this)) {
            nastavBleStatus("Povol v telefonu oprávnění Bluetooth (Blízká zařízení).")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SCAN_PAIR -> ContourBleController.spustHledaniGlukometru(this)

            ACTION_SYNC_NOW -> {
                val (_, adr) = LocalHealthStore.getPairedMeter(this)
                if (adr.isNullOrBlank()) {
                    ContourBleController.spustHledaniGlukometru(this)
                } else {
                    registerPassiveWatch(this)
                    ContourBleController.pripojKeGlukometru(this, adr, rucniDotaz = true)
                }
            }

            ACTION_WAKE_SYNC -> {
                val adr = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                    ?: LocalHealthStore.getPairedMeter(this).second
                if (!adr.isNullOrBlank()) {
                    ContourBleController.pripojKeGlukometru(this, adr, rucniDotaz = false)
                } else {
                    stopSelf()
                }
            }

            ACTION_DISCONNECT -> {
                unregisterPassiveWatch(this)
                ContourBleController.odpojAZastav(this)
                stopSelf()
            }

            else -> {
                registerPassiveWatch(this)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ContourBleController.zaregistrujSluzbu(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PASSIVE_BLE_FOUND = "cz.mares.zdravipm.PASSIVE_BLE_FOUND"
        private const val ACTION_SCAN_PAIR = "cz.mares.zdravipm.SCAN_PAIR"
        private const val ACTION_SYNC_NOW = "cz.mares.zdravipm.SYNC_NOW"
        private const val ACTION_WAKE_SYNC = "cz.mares.zdravipm.WAKE_SYNC"
        private const val ACTION_DISCONNECT = "cz.mares.zdravipm.DISCONNECT"
        private const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val PASSIVE_PI_REQ_CODE = 4209

        private val _bleStatus = MutableStateFlow("Tiché hlídání glukometru (bez ikony v liště)")
        val bleStatus: StateFlow<String> = _bleStatus.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        internal fun nastavBleStatus(msg: String) {
            _bleStatus.value = msg
        }

        internal fun nastavSkenovani(scanning: Boolean) {
            _isScanning.value = scanning
        }

        private fun vycistiStarouNotifikaci(context: Context) {
            try {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                mgr?.cancel(4201)
                mgr?.deleteNotificationChannel("zdravipm_contour_sync")
            } catch (_: Throwable) {
            }
        }

        fun maBleOpravneni(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }

        private fun vytvorPasivniPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, BootReceiver::class.java).apply {
                action = ACTION_PASSIVE_BLE_FOUND
            }
            val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(
                context.applicationContext,
                PASSIVE_PI_REQ_CODE,
                intent,
                mutability
            )
        }

        fun registerPassiveWatch(context: Context) {
            val appCtx = context.applicationContext
            vycistiStarouNotifikaci(appCtx)
            if (!maBleOpravneni(appCtx)) return

            val cfg = LocalHealthStore.loadConfig(appCtx)
            val (_, adr) = LocalHealthStore.getPairedMeter(appCtx)
            if (!cfg.backgroundServiceEnabled || adr.isNullOrBlank()) {
                unregisterPassiveWatch(appCtx)
                return
            }

            try {
                val mgr = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val scanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
                val pendingIntent = vytvorPasivniPendingIntent(appCtx)
                try {
                    scanner.stopScan(pendingIntent)
                } catch (_: Throwable) {
                }

                val filters = buildList {
                    if (BluetoothAdapter.checkBluetoothAddress(adr)) {
                        add(ScanFilter.Builder().setDeviceAddress(adr).build())
                    }
                    add(
                        ScanFilter.Builder()
                            .setServiceUuid(ParcelUuid(ContourBleParser.GLUCOSE_SERVICE_UUID))
                            .build()
                    )
                }

                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build()

                scanner.startScan(filters, settings, pendingIntent)
                _bleStatus.value = "Tiché hlídání při zapnutí glukometru (bez ikony v liště)"
            } catch (_: Throwable) {
            }
        }

        fun unregisterPassiveWatch(context: Context) {
            try {
                val appCtx = context.applicationContext
                val mgr = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val scanner = mgr?.adapter?.bluetoothLeScanner ?: return
                scanner.stopScan(vytvorPasivniPendingIntent(appCtx))
            } catch (_: Throwable) {
            }
        }

        fun startIfPermitted(context: Context) {
            registerPassiveWatch(context)
        }

        fun scanAndPair(context: Context) {
            if (!maBleOpravneni(context)) {
                _bleStatus.value = "Nejprve povol oprávnění Bluetooth (Blízká zařízení)."
                return
            }
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, ContourSyncService::class.java).apply {
                action = ACTION_SCAN_PAIR
            }
            try {
                appCtx.startService(intent)
            } catch (_: Throwable) {
                ContourBleController.spustHledaniGlukometru(appCtx)
            }
        }

        fun syncNow(context: Context) {
            if (!maBleOpravneni(context)) {
                _bleStatus.value = "Nejprve povol oprávnění Bluetooth."
                return
            }
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, ContourSyncService::class.java).apply {
                action = ACTION_SYNC_NOW
            }
            try {
                appCtx.startService(intent)
            } catch (_: Throwable) {
                val (_, adr) = LocalHealthStore.getPairedMeter(appCtx)
                if (adr.isNullOrBlank()) {
                    ContourBleController.spustHledaniGlukometru(appCtx)
                } else {
                    registerPassiveWatch(appCtx)
                    ContourBleController.pripojKeGlukometru(appCtx, adr, rucniDotaz = true)
                }
            }
        }

        fun syncQuietlyFromWakeup(
            context: Context,
            address: String,
            onComplete: (() -> Unit)? = null
        ) {
            if (!maBleOpravneni(context)) {
                onComplete?.invoke()
                return
            }
            val appCtx = context.applicationContext
            ContourBleController.pridejCallbackDokonceni(onComplete)
            val intent = Intent(appCtx, ContourSyncService::class.java).apply {
                action = ACTION_WAKE_SYNC
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            }
            try {
                appCtx.startService(intent)
            } catch (_: Throwable) {
                ContourBleController.pripojKeGlukometru(appCtx, address, rucniDotaz = false)
            }
        }

        fun stopService(context: Context) {
            val appCtx = context.applicationContext
            unregisterPassiveWatch(appCtx)
            ContourBleController.odpojAZastav(appCtx)
            try {
                appCtx.stopService(Intent(appCtx, ContourSyncService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}

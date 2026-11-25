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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class ContourSyncService : Service() {

    private val sluzbaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hlavniHandler = Handler(Looper.getMainLooper())
    private var aktivniGatt: BluetoothGatt? = null
    private var probihaSken = false
    private var posledniSpusteniMs = 0L

    private val frontaDeskriptoru = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private val namereneVzorky = LinkedHashMap<Int, ParsedContourMeasurement>()
    private val jidloPodleSekvence = HashMap<Int, MealRelation>()

    private val ulozeniZpozdeno = Runnable { ulozNamerenaDataAUkonci(odpojitHned = false) }
    private val zastaveniSkenu = Runnable { zastavBleSken(ukoncitBezNalezu = true) }
    private val ochrannyTimeout = Runnable { bezpecneUkonciSluzbu() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        odstranStaryNotifikacniKanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!maBleOpravneni(this)) {
            _bleStatus.value = "Povol v telefonu oprávnění Bluetooth (Blízká zařízení)."
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SCAN_PAIR -> spustHledaniGlukometru()

            ACTION_SYNC_NOW -> {
                val (_, adr) = LocalHealthStore.getPairedMeter(this)
                if (adr.isNullOrBlank()) {
                    spustHledaniGlukometru()
                } else {
                    registerPassiveWatch(this)
                    pripojKeGlukometru(adr, rucni Dotaz = true)
                }
            }

            ACTION_WAKE_SYNC -> {
                val nyni = System.currentTimeMillis()
                if (nyni - posledniSpusteniMs < 4_000L && aktivniGatt != null) {
                    return START_NOT_STICKY
                }
                posledniSpusteniMs = nyni
                val adr = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                    ?: LocalHealthStore.getPairedMeter(this).second
                if (!adr.isNullOrBlank()) {
                    pripojKeGlukometru(adr, rucniDotaz = false)
                } else {
                    stopSelf()
                }
            }

            ACTION_DISCONNECT -> {
                unregisterPassiveWatch(this)
                zastavBleSken(ukoncitBezNalezu = false)
                uzavriGatt()
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
        hlavniHandler.removeCallbacksAndMessages(null)
        zastavBleSken(ukoncitBezNalezu = false)
        uzavriGatt()
        sluzbaScope.cancel()
        super.onDestroy()
    }

    private fun odstranStaryNotifikacniKanal() {
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            mgr?.cancel(4201)
            mgr?.deleteNotificationChannel("zdravipm_contour_sync")
        } catch (_: Throwable) {
        }
    }

    private fun nactiAdapter(): BluetoothAdapter? {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    private fun spustHledaniGlukometru() {
        val adapter = nactiAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _bleStatus.value = "Zapni v telefonu Bluetooth."
            stopSelf()
            return
        }

        val existujici = adapter.bondedDevices?.firstOrNull { dev ->
            dev.name?.contains("Contour", ignoreCase = true) == true
        }
        if (existujici != null) {
            val nazev = existujici.name ?: "Contour Plus One"
            LocalHealthStore.setPairedMeter(this, nazev, existujici.address)
            registerPassiveWatch(this)
            _bleStatus.value = "Nalezen spárovaný $nazev – načítám záznamy..."
            pripojKeGlukometru(existujici.address, rucniDotaz = true)
            return
        }

        zastavBleSken(ukoncitBezNalezu = false)
        val skener = adapter.bluetoothLeScanner
        if (skener == null) {
            _bleStatus.value = "BLE skener není dostupný."
            stopSelf()
            return
        }

        probihaSken = true
        _isScanning.value = true
        _bleStatus.value = "Hledám zapnutý glukometr Contour Plus One..."

        val nastaveni = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        skener.startScan(null, nastaveni, skenCallback)
        hlavniHandler.postDelayed(zastaveniSkenu, 20_000L)
    }

    private fun zastavBleSken(ukoncitBezNalezu: Boolean) {
        hlavniHandler.removeCallbacks(zastaveniSkenu)
        if (probihaSken) {
            try {
                nactiAdapter()?.bluetoothLeScanner?.stopScan(skenCallback)
            } catch (_: Throwable) {
            }
            probihaSken = false
            _isScanning.value = false
            if (ukoncitBezNalezu) {
                val (_, adr) = LocalHealthStore.getPairedMeter(this)
                if (adr.isNullOrBlank()) {
                    _bleStatus.value = "Glukometr nenalezen. Zapni Contour (podrž tlačítko) a zkus to znovu."
                }
                stopSelf()
            }
        }
    }

    private val skenCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            val jmeno = dev.name ?: result.scanRecord?.deviceName ?: ""
            val sluzby = result.scanRecord?.serviceUuids.orEmpty()
            val maSluzbuGlukozy = sluzby.contains(ParcelUuid(ContourBleParser.GLUCOSE_SERVICE_UUID))
            val jeContour = jmeno.contains("Contour", ignoreCase = true) || maSluzbuGlukozy
            if (!jeContour) return

            val ulozeneJmeno = jmeno.ifBlank { "Contour Plus One" }
            zastavBleSken(ukoncitBezNalezu = false)
            LocalHealthStore.setPairedMeter(this@ContourSyncService, ulozeneJmeno, dev.address)
            registerPassiveWatch(this@ContourSyncService)
            _bleStatus.value = "Spárováno: $ulozeneJmeno. Čtu měření..."
            pripojKeGlukometru(dev.address, rucniDotaz = true)
        }
    }

    private fun pripojKeGlukometru(adresa: String, rucniDotaz: Boolean) {
        val adapter = nactiAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _bleStatus.value = "Bluetooth je vypnutý."
            stopSelf()
            return
        }

        val zarizeni: BluetoothDevice = try {
            adapter.getRemoteDevice(adresa)
        } catch (_: Throwable) {
            _bleStatus.value = "Neplatná Bluetooth adresa glukometru."
            stopSelf()
            return
        }

        uzavriGatt()
        hlavniHandler.removeCallbacks(ochrannyTimeout)
        hlavniHandler.postDelayed(ochrannyTimeout, if (rucniDotaz) 25_000L else 35_000L)

        _bleStatus.value = if (rucniDotaz) {
            "Spojuji se s glukometrem ($adresa)..."
        } else {
            "Glukometr detekován – stahuji měření..."
        }

        aktivniGatt = zarizeni.connectGatt(
            this,
            false,
            gattObsluha,
            BluetoothDevice.TRANSPORT_LE
        )
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
                _bleStatus.value = "Glukometr připojen – stahuji záznamy..."
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                hlavniHandler.post {
                    ulozNamerenaDataAUkonci(odpojitHned = true)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                hlavniHandler.post { bezpecneUkonciSluzbu() }
                return
            }
            val sluzba = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID) ?: run {
                _bleStatus.value = "Služba glukózy 0x1808 nebyla v zařízení nalezena."
                hlavniHandler.post { bezpecneUkonciSluzbu() }
                return
            }

            frontaDeskriptoru.clear()
            namereneVzorky.clear()
            jidloPodleSekvence.clear()

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
            if (frontaDeskriptoru.isNotEmpty()) {
                zapisDalsiDeskriptor(gatt)
            } else {
                vyzadejZaznamyPresRacp(gatt)
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(deskriptor, hodnota)
        } else {
            @Suppress("DEPRECATION")
            run {
                deskriptor.value = hodnota
                gatt.writeDescriptor(deskriptor)
            }
        }
    }

    private fun vyzadejZaznamyPresRacp(gatt: BluetoothGatt) {
        val racp = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID)
            ?.getCharacteristic(ContourBleParser.RACP_UUID) ?: return
        val prikazVsechnyZaznamy = byteArrayOf(0x01, 0x01)
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
    }

    private fun zpracujPaketGlukometru(uuidText: String, bajty: ByteArray) {
        when {
            uuidText.equals(ContourBleParser.GLUCOSE_MEASUREMENT_UUID.toString(), ignoreCase = true) -> {
                ContourBleParser.parseMeasurement(bajty)?.let { vzorek ->
                    synchronized(namereneVzorky) {
                        namereneVzorky[vzorek.sequenceNumber] = vzorek
                    }
                    naplanujUlozeni(zpozdeniMs = 1_800L)
                }
            }
            uuidText.equals(ContourBleParser.GLUCOSE_CONTEXT_UUID.toString(), ignoreCase = true) -> {
                ContourBleParser.parseContextMeal(bajty)?.let { (sekvence, jidlo) ->
                    synchronized(namereneVzorky) {
                        jidloPodleSekvence[sekvence] = jidlo
                    }
                    naplanujUlozeni(zpozdeniMs = 1_200L)
                }
            }
            uuidText.equals(ContourBleParser.RACP_UUID.toString(), ignoreCase = true) -> {
                naplanujUlozeni(zpozdeniMs = 450L)
            }
        }
    }

    private fun naplanujUlozeni(zpozdeniMs: Long) {
        hlavniHandler.removeCallbacks(ulozeniZpozdeno)
        hlavniHandler.postDelayed(ulozeniZpozdeno, zpozdeniMs)
    }

    private fun ulozNamerenaDataAUkonci(odpojitHned: Boolean) {
        hlavniHandler.removeCallbacks(ulozeniZpozdeno)
        val davka: List<GlucoseEntry>
        val (jmenoGlukometru, _) = LocalHealthStore.getPairedMeter(this)
        val zdroj = jmenoGlukometru ?: "Contour Plus One"

        synchronized(namereneVzorky) {
            if (namereneVzorky.isEmpty()) {
                if (odpojitHned) {
                    _bleStatus.value = "Tiché hlídání při zapnutí glukometru (bez ikony v liště)"
                    bezpecneUkonciSluzbu()
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

        LocalHealthStore.upsertGlucoseEntries(this, davka)
        _bleStatus.value = "Přijato ${davka.size} měření – zapisuji do Health Connect..."

        sluzbaScope.launch {
            val vysledek = HealthConnectManager.syncRecords(applicationContext, forceAll = false)
            _bleStatus.value = if (vysledek.glucoseWritten > 0) {
                "Přeneseno do Health Connect (${vysledek.glucoseWritten}× záznam) • v klidu"
            } else {
                "Uloženo v telefonu (${davka.size}×) • tiché hlídání aktivní"
            }
            hlavniHandler.post { bezpecneUkonciSluzbu() }
        }
    }

    private fun bezpecneUkonciSluzbu() {
        hlavniHandler.removeCallbacks(ochrannyTimeout)
        uzavriGatt()
        stopSelf()
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
            if (!maBleOpravneni(context)) return
            val cfg = LocalHealthStore.loadConfig(context)
            val (_, adr) = LocalHealthStore.getPairedMeter(context)
            if (!cfg.backgroundServiceEnabled || adr.isNullOrBlank()) {
                unregisterPassiveWatch(context)
                return
            }

            try {
                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val scanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
                val filter = ScanFilter.Builder()
                    .setDeviceAddress(adr)
                    .build()
                val settingsBuilder = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                scanner.startScan(
                    listOf(filter),
                    settingsBuilder.build(),
                    vytvorPasivniPendingIntent(context)
                )
                _bleStatus.value = "Tiché hlídání při zapnutí glukometru (bez ikony v liště)"
            } catch (_: Throwable) {
            }
        }

        fun unregisterPassiveWatch(context: Context) {
            try {
                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val scanner = mgr?.adapter?.bluetoothLeScanner ?: return
                scanner.stopScan(vytvorPasivniPendingIntent(context))
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
            spustSluzbuTise(context, Intent(context.applicationContext, ContourSyncService::class.java).apply {
                action = ACTION_SCAN_PAIR
            })
        }

        fun syncNow(context: Context) {
            if (!maBleOpravneni(context)) {
                _bleStatus.value = "Nejprve povol oprávnění Bluetooth."
                return
            }
            spustSluzbuTise(context, Intent(context.applicationContext, ContourSyncService::class.java).apply {
                action = ACTION_SYNC_NOW
            })
        }

        fun syncQuietlyFromWakeup(context: Context, address: String) {
            if (!maBleOpravneni(context)) return
            spustSluzbuTise(context, Intent(context.applicationContext, ContourSyncService::class.java).apply {
                action = ACTION_WAKE_SYNC
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            })
        }

        fun stopService(context: Context) {
            unregisterPassiveWatch(context)
            try {
                context.stopService(Intent(context, ContourSyncService::class.java))
            } catch (_: Throwable) {
            }
        }

        private fun spustSluzbuTise(context: Context, intent: Intent) {
            try {
                context.applicationContext.startService(intent)
            } catch (t: Throwable) {
                _bleStatus.value = "Automatický přenos vyžaduje v nastavení aplikace Baterie -> Bez omezení."
            }
        }
    }
}

package cz.mares.zdravipm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

    private val frontaDeskriptoru = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private val namereneVzorky = LinkedHashMap<Int, ParsedContourMeasurement>()
    private val jidloPodleSekvence = HashMap<Int, MealRelation>()

    private val ulozeniZpozdeno = Runnable { ulozNamerenaData() }
    private val zastaveniSkenu = Runnable { zastavBleSken(ukoncitBezNalezu = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        vytvorNotifikacniKanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!maBleOpravneni(this)) {
            _bleStatus.value = "Povol v telefonu oprávnění Bluetooth (Blízká zařízení)."
            stopSelf()
            return START_NOT_STICKY
        }

        spustPopredi("Hlídám glukometr Contour Plus One")

        when (intent?.action) {
            ACTION_SCAN_PAIR -> spustHledaniGlukometru()
            ACTION_SYNC_NOW -> {
                val (_, adr) = LocalHealthStore.getPairedMeter(this)
                if (adr.isNullOrBlank()) {
                    spustHledaniGlukometru()
                } else {
                    pripojKeGlukometru(adr, aktivniVyhledani = true)
                }
            }
            ACTION_DISCONNECT -> {
                zastavBleSken(ukoncitBezNalezu = false)
                uzavriGatt()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val (_, adr) = LocalHealthStore.getPairedMeter(this)
                if (!adr.isNullOrBlank()) {
                    pripojKeGlukometru(adr, aktivniVyhledani = false)
                } else {
                    _bleStatus.value = "Glukometr zatím není spárovaný"
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hlavniHandler.removeCallbacksAndMessages(null)
        zastavBleSken(ukoncitBezNalezu = false)
        uzavriGatt()
        sluzbaScope.cancel()
        super.onDestroy()
    }

    private fun nactiAdapter(): BluetoothAdapter? {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    private fun spustHledaniGlukometru() {
        val adapter = nactiAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _bleStatus.value = "Zapni v telefonu Bluetooth."
            return
        }

        // Nejprve zkontrolujeme uz sparovana zarizeni v systemu Android
        val existujici = adapter.bondedDevices?.firstOrNull { dev ->
            dev.name?.contains("Contour", ignoreCase = true) == true
        }
        if (existujici != null) {
            val nazev = existujici.name ?: "Contour Plus One"
            LocalHealthStore.setPairedMeter(this, nazev, existujici.address)
            _bleStatus.value = "Nalezen spárovaný $nazev,ipojuji..."
            pripojKeGlukometru(existujici.address, aktivniVyhledani = false)
            return
        }

        zastavBleSken(ukoncitBezNalezu = false)
        val skener = adapter.bluetoothLeScanner
        if (skener == null) {
            _bleStatus.value = "BLE skener není dostupný."
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
            _bleStatus.value = "Spárováno: $ulozeneJmeno. Čtu měření..."
            pripojKeGlukometru(dev.address, aktivniVyhledani = true)
        }
    }

    private fun pripojKeGlukometru(adresa: String, aktivniVyhledani: Boolean) {
        val adapter = nactiAdapter() ?: return
        if (!adapter.isEnabled) {
            _bleStatus.value = "Bluetooth je vypnutý – čekám na zapnutí."
            return
        }

        val zarizeni: BluetoothDevice = try {
            adapter.getRemoteDevice(adresa)
        } catch (_: Throwable) {
            _bleStatus.value = "Neplatná Bluetooth adresa glukometru."
            return
        }

        uzavriGatt()
        _bleStatus.value = if (aktivniVyhledani) {
            "Spojuji se s glukometrem ($adresa)..."
        } else {
            "Čekám na zapnutí glukometru Contour na pozadí..."
        }

        aktivniGatt = zarizeni.connectGatt(
            this,
            !aktivniVyhledani,
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
                aktualizujNotifikaci("Glukometr Contour připojen")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                hlavniHandler.post { ulozNamerenaData() }
                _bleStatus.value = "Glukometr v klidu – připraven na další měření"
                aktualizujNotifikaci("Hlídám glukometr Contour Plus One")
                val cfg = LocalHealthStore.loadConfig(this@ContourSyncService)
                val (_, adr) = LocalHealthStore.getPairedMeter(this@ContourSyncService)
                if (cfg.backgroundServiceEnabled && !adr.isNullOrBlank()) {
                    hlavniHandler.postDelayed({
                        pripojKeGlukometru(adr, aktivniVyhledani = false)
                    }, 2_500L)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val sluzba = gatt.getService(ContourBleParser.GLUCOSE_SERVICE_UUID) ?: run {
                _bleStatus.value = "Služba glukózy 0x1808 nebyla v zařízení nalezena."
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
                    naplanujUlozeni(zpozdeniMs = 1_500L)
                }
            }
            uuidText.equals(ContourBleParser.GLUCOSE_CONTEXT_UUID.toString(), ignoreCase = true) -> {
                ContourBleParser.parseContextMeal(bajty)?.let { (sekvence, jidlo) ->
                    synchronized(namereneVzorky) {
                        jidloPodleSekvence[sekvence] = jidlo
                    }
                    naplanujUlozeni(zpozdeniMs = 1_000L)
                }
            }
            uuidText.equals(ContourBleParser.RACP_UUID.toString(), ignoreCase = true) -> {
                naplanujUlozeni(zpozdeniMs = 300L)
            }
        }
    }

    private fun naplanujUlozeni(zpozdeniMs: Long) {
        hlavniHandler.removeCallbacks(ulozeniZpozdeno)
        hlavniHandler.postDelayed(ulozeniZpozdeno, zpozdeniMs)
    }

    private fun ulozNamerenaData() {
        val davka: List<GlucoseEntry>
        val (jmenoGlukometru, _) = LocalHealthStore.getPairedMeter(this)
        val zdroj = jmenoGlukometru ?: "Contour Plus One"

        synchronized(namereneVzorky) {
            if (namereneVzorky.isEmpty()) return
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
        _bleStatus.value = "Přijato ${davka.size} měření z glukometru – zapisuji do Health Connect..."

        sluzbaScope.launch {
            val vysledek = HealthConnectManager.syncRecords(applicationContext, forceAll = false)
            _bleStatus.value = if (vysledek.glucoseWritten > 0) {
                "Přeneseno do Health Connect (${vysledek.glucoseWritten}× nový záznam)"
            } else {
                "Měření uloženo v telefonu (${davka.size}×)"
            }
        }
    }

    private fun vytvorNotifikacniKanal() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val kanal = NotificationChannel(
            CHANNEL_ID,
            "Synchronizace glukometru Contour",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tichý automatický přenos měření z glukometru do Health Connect"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(kanal)
    }

    private fun sestavNotifikaci(text: String): Notification {
        val otevritIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            otevritIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Zdraví PM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun spustPopredi(text: String) {
        val notif = sestavNotifikaci(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
        }
    }

    private fun aktualizujNotifikaci(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(NOTIF_ID, sestavNotifikaci(text))
    }

    companion object {
        private const val CHANNEL_ID = "zdravipm_contour_sync"
        private const val NOTIF_ID = 4201
        private const val ACTION_START_BG = "cz.mares.zdravipm.START_BG"
        private const val ACTION_SCAN_PAIR = "cz.mares.zdravipm.SCAN_PAIR"
        private const val ACTION_SYNC_NOW = "cz.mares.zdravipm.SYNC_NOW"
        private const val ACTION_DISCONNECT = "cz.mares.zdravipm.DISCONNECT"

        private val _bleStatus = MutableStateFlow("Připraveno pro Contour Plus One")
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

        fun startIfPermitted(context: Context) {
            if (!maBleOpravneni(context)) return
            odesliAkci(context, ACTION_START_BG)
        }

        fun scanAndPair(context: Context) {
            if (!maBleOpravneni(context)) {
                _bleStatus.value = "Nejprve povol oprávnění Bluetooth (Blízká zařízení)."
                return
            }
            odesliAkci(context, ACTION_SCAN_PAIR)
        }

        fun syncNow(context: Context) {
            if (!maBleOpravneni(context)) {
                _bleStatus.value = "Nejprve povol oprávnění Bluetooth."
                return
            }
            odesliAkci(context, ACTION_SYNC_NOW)
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, ContourSyncService::class.java))
            } catch (_: Throwable) {
            }
        }

        private fun odesliAkci(context: Context, akce: String) {
            try {
                val intent = Intent(context.applicationContext, ContourSyncService::class.java).apply {
                    action = akce
                }
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (t: Throwable) {
                _bleStatus.value = "Službu na pozadí nelze spustit: ${t.message ?: ""}"
            }
        }
    }
}

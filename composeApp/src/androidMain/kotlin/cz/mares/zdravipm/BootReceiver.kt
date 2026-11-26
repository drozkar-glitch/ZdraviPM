package cz.mares.zdravipm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class BootReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = context.applicationContext
        val cfg = LocalHealthStore.loadConfig(appCtx)
        val (_, pairedAddr) = LocalHealthStore.getPairedMeter(appCtx)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (cfg.backgroundServiceEnabled && !pairedAddr.isNullOrBlank()) {
                    ContourSyncService.ensureBackgroundWatch(appCtx)
                }
                TuyaScaleSyncJobService.syncSchedule(appCtx)
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON && cfg.backgroundServiceEnabled && !pairedAddr.isNullOrBlank()) {
                    ContourSyncService.ensureBackgroundWatch(appCtx)
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    ContourSyncService.unregisterPassiveWatch(appCtx)
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                if (!cfg.backgroundServiceEnabled) return
                if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (bondState != BluetoothDevice.BOND_BONDED) return
                }

                val dev = extractBluetoothDevice(intent) ?: return
                val devAddr = dev.address ?: return
                val devName = try {
                    dev.name.orEmpty()
                } catch (_: Throwable) {
                    ""
                }

                val matchesPaired = !pairedAddr.isNullOrBlank() && pairedAddr.equals(devAddr, ignoreCase = true)
                val matchesContourName = devName.contains("Contour", ignoreCase = true)
                if (!matchesPaired && !matchesContourName) return

                if (pairedAddr.isNullOrBlank()) {
                    LocalHealthStore.setPairedMeter(
                        appCtx,
                        devName.ifBlank { "Contour Plus One" },
                        devAddr
                    )
                }
                TuyaScaleSyncJobService.triggerOpportunisticCheckIfDue(appCtx)
                spustAsynchronniProbuzeni(appCtx, devAddr)
            }

            ContourSyncService.ACTION_PASSIVE_BLE_FOUND -> {
                if (!cfg.backgroundServiceEnabled) return
                val scanResults = extractScanResults(intent)
                var targetAddr: String? = pairedAddr

                if (!scanResults.isNullOrEmpty()) {
                    for (sr in scanResults) {
                        val d = sr.device ?: continue
                        val addr = d.address ?: continue
                        val name = try {
                            d.name ?: sr.scanRecord?.deviceName ?: ""
                        } catch (_: Throwable) {
                            ""
                        }
                        if (!pairedAddr.isNullOrBlank() && pairedAddr.equals(addr, ignoreCase = true)) {
                            targetAddr = addr
                            break
                        }
                        if (pairedAddr.isNullOrBlank() && name.contains("Contour", ignoreCase = true)) {
                            targetAddr = addr
                            break
                        }
                    }
                }

                if (targetAddr.isNullOrBlank()) return
                TuyaScaleSyncJobService.triggerOpportunisticCheckIfDue(appCtx)
                spustAsynchronniProbuzeni(appCtx, targetAddr)
            }
        }
    }

    private fun spustAsynchronniProbuzeni(appCtx: Context, address: String) {
        val pendingResult = goAsync()
        val dokonceno = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        val bezpecneDokonci: () -> Unit = {
            if (dokonceno.compareAndSet(false, true)) {
                handler.removeCallbacksAndMessages(null)
                try {
                    pendingResult.finish()
                } catch (_: Throwable) {
                }
            }
        }

        // Samotne mereni prouzku drzi nazivu TuyaScaleSyncJobService (JOB_ID_BLE_HOLD),
        // takze BroadcastReceiver muzeme bezpecne uvolnit pred 10s ANR limitem systemu.
        handler.postDelayed({ bezpecneDokonci() }, 4_500L)
        ContourSyncService.syncQuietlyFromWakeup(appCtx, address) {
            bezpecneDokonci()
        }
    }

    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun extractScanResults(intent: Intent): ArrayList<ScanResult>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                    ScanResult::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
            }
        } catch (_: Throwable) {
            null
        }
    }
}

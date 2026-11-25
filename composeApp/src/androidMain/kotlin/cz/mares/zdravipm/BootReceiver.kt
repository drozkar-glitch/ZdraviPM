package cz.mares.zdravipm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val cfg = LocalHealthStore.loadConfig(context)
        val (_, pairedAddr) = LocalHealthStore.getPairedMeter(context)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (cfg.backgroundServiceEnabled && !pairedAddr.isNullOrBlank()) {
                    ContourSyncService.registerPassiveWatch(context)
                }
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON && cfg.backgroundServiceEnabled && !pairedAddr.isNullOrBlank()) {
                    ContourSyncService.registerPassiveWatch(context)
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                if (!cfg.backgroundServiceEnabled) return
                val dev = extractBluetoothDevice(intent) ?: return
                val devAddr = dev.address ?: return
                val devName = try {
                    dev.name.orEmpty()
                } catch (_: Throwable) {
                    ""
                }

                val matchesPaired = !pairedAddr.isNullOrBlank() && pairedAddr.equals(devAddr, ignoreCase = true)
                val matchesContourName = devName.contains("Contour", ignoreCase = true)

                if (matchesPaired || matchesContourName) {
                    if (pairedAddr.isNullOrBlank()) {
                        LocalHealthStore.setPairedMeter(
                            context,
                            devName.ifBlank { "Contour Plus One" },
                            devAddr
                        )
                    }
                    ContourSyncService.syncQuietlyFromWakeup(context, devAddr)
                }
            }

            ContourSyncService.ACTION_PASSIVE_BLE_FOUND -> {
                if (!cfg.backgroundServiceEnabled || pairedAddr.isNullOrBlank()) return
                ContourSyncService.syncQuietlyFromWakeup(context, pairedAddr)
            }
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
}

package cz.mares.zdravipm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val cfg = LocalHealthStore.loadConfig(context)
            val (_, address) = LocalHealthStore.getPairedMeter(context)
            if (cfg.backgroundServiceEnabled && !address.isNullOrBlank()) {
                ContourSyncService.startIfPermitted(context)
            }
        }
    }
}

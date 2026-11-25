package cz.mares.zdravipm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cfg = LocalHealthStore.loadConfig(this)
        val (_, address) = LocalHealthStore.getPairedMeter(this)
        if (cfg.backgroundServiceEnabled && !address.isNullOrBlank()) {
            ContourSyncService.registerPassiveWatch(this)
        }

        TuyaScaleSyncJobService.syncSchedule(this)
        TuyaScaleSyncJobService.triggerOpportunisticCheckIfDue(this)

        activityScope.launch {
            LocalHealthStore.version.collect {
                TuyaScaleSyncJobService.syncSchedule(applicationContext)
            }
        }

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}

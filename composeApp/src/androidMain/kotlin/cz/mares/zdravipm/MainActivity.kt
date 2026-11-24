package cz.mares.zdravipm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cfg = LocalHealthStore.loadConfig(this)
        val (_, address) = LocalHealthStore.getPairedMeter(this)
        if (cfg.backgroundServiceEnabled && !address.isNullOrBlank()) {
            ContourSyncService.startIfPermitted(this)
        }

        setContent {
            App()
        }
    }
}

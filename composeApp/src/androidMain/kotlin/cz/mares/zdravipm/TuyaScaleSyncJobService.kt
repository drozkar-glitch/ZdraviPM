package cz.mares.zdravipm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class TuyaScaleSyncJobService : JobService() {

    private var periodicScaleJob: Job? = null
    private var bleHoldJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId ?: return false
        val appCtx = applicationContext

        if (jobId == JOB_ID_BLE_HOLD) {
            bleHoldJob?.cancel()
            bleHoldJob = bgScope.launch {
                try {
                    val startMs = System.currentTimeMillis()
                    while (ContourSyncService.isBleSessionActive() &&
                        System.currentTimeMillis() - startMs < MAX_BLE_HOLD_MS
                    ) {
                        delay(500L)
                    }
                } finally {
                    jobFinished(params, false)
                }
            }
            return true
        }

        periodicScaleJob = bgScope.launch {
            try {
                ContourSyncService.ensureBackgroundWatch(appCtx)
                performSilentScaleSync(appCtx)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId
        if (jobId == JOB_ID_BLE_HOLD) {
            bleHoldJob?.cancel()
            bleHoldJob = null
            return false
        }
        periodicScaleJob?.cancel()
        periodicScaleJob = null
        return true
    }

    companion object {
        private const val JOB_ID_SCALE_PERIODIC = 4210
        private const val JOB_ID_BLE_HOLD = 4211
        private const val THREE_HOURS_MS = 3L * 60L * 60L * 1000L
        private const val OPPORTUNISTIC_COOLDOWN_MS = 15L * 60L * 1000L
        private const val MAX_BLE_HOLD_MS = 105_000L
        private const val PREF_SYNC_META = "zdravipm_scale_bg_meta"
        private const val KEY_LAST_CHECK_MS = "last_auto_scale_check_ms"

        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isSyncRunning = AtomicBoolean(false)

        fun holdProcessForBleSession(context: Context, maxDurationMs: Long = MAX_BLE_HOLD_MS) {
            val appCtx = context.applicationContext
            val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            try {
                if (scheduler.getPendingJob(JOB_ID_BLE_HOLD) != null) return
                val component = ComponentName(appCtx, TuyaScaleSyncJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID_BLE_HOLD, component)
                    .setOverrideDeadline(0L)
                    .setMinimumLatency(0L)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (_: Throwable) {
            }
        }

        fun releaseBleSessionHold(context: Context) {
            val appCtx = context.applicationContext
            val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            try {
                scheduler.cancel(JOB_ID_BLE_HOLD)
            } catch (_: Throwable) {
            }
        }

        fun syncSchedule(context: Context) {
            val appCtx = context.applicationContext
            val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            val cfg = LocalHealthStore.loadConfig(appCtx)
            val shouldRun = cfg.backgroundServiceEnabled &&
                cfg.tuyaAccessId.isNotBlank() &&
                cfg.tuyaAccessSecret.isNotBlank()

            if (!shouldRun) {
                try {
                    scheduler.cancel(JOB_ID_SCALE_PERIODIC)
                } catch (_: Throwable) {
                }
                return
            }

            if (isSyncRunning.get()) return

            try {
                val existing = scheduler.getPendingJob(JOB_ID_SCALE_PERIODIC)
                if (existing != null && existing.isPeriodic && existing.intervalMillis == THREE_HOURS_MS) {
                    return
                }
                val component = ComponentName(appCtx, TuyaScaleSyncJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID_SCALE_PERIODIC, component)
                    .setPeriodic(THREE_HOURS_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (_: Throwable) {
            }
        }

        fun triggerOpportunisticCheckIfDue(context: Context) {
            val appCtx = context.applicationContext
            val cfg = LocalHealthStore.loadConfig(appCtx)
            if (!cfg.backgroundServiceEnabled ||
                cfg.tuyaAccessId.isBlank() ||
                cfg.tuyaAccessSecret.isBlank()
            ) {
                return
            }

            val prefs = appCtx.getSharedPreferences(PREF_SYNC_META, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
            val now = System.currentTimeMillis()
            if (now - lastCheck < OPPORTUNISTIC_COOLDOWN_MS) return

            bgScope.launch {
                performSilentScaleSync(appCtx)
            }
        }

        suspend fun performSilentScaleSync(context: Context) {
            if (!isSyncRunning.compareAndSet(false, true)) return
            val appCtx = context.applicationContext
            try {
                val cfg = LocalHealthStore.loadConfig(appCtx)
                if (!cfg.backgroundServiceEnabled ||
                    cfg.tuyaAccessId.isBlank() ||
                    cfg.tuyaAccessSecret.isBlank()
                ) {
                    return
                }

                val existingScale = LocalHealthStore.loadScaleList(appCtx)
                val startReference = cfg.referenceWeightKg ?: existingScale.firstOrNull()?.weightKg
                if (startReference == null) {
                    return
                }

                appCtx.getSharedPreferences(PREF_SYNC_META, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
                    .apply()

                val outcome = TuyaScaleClient.fetchRecentScaleReadings(cfg)
                if (outcome.diagnostic.isNotBlank()) {
                    LocalHealthStore.setLastTuyaDiag(appCtx, outcome.diagnostic)
                }

                if (outcome.success && outcome.candidates.isNotEmpty()) {
                    val tolerance = cfg.weightToleranceKg.coerceAtLeast(2.0)
                    var currentRef: Double = startReference
                    val accepted = ArrayList<ScaleEntry>()

                    for (cand in outcome.candidates.sortedBy { it.timestampMillis }) {
                        if (abs(cand.weightKg - currentRef) <= tolerance) {
                            currentRef = cand.weightKg
                            val comp = BodyCompositionCalculator.calculate(
                                weightKg = cand.weightKg,
                                impedanceOhms = cand.impedanceOhms,
                                heightCm = cfg.heightCm,
                                birthYear = cfg.birthYear,
                                isMale = cfg.isMale
                            )
                            accepted.add(
                                ScaleEntry(
                                    id = cand.id,
                                    timestampMillis = cand.timestampMillis,
                                    weightKg = cand.weightKg,
                                    impedanceOhms = cand.impedanceOhms,
                                    bmi = comp.bmi,
                                    bodyFatPercent = comp.bodyFatPercent,
                                    leanBodyMassKg = comp.leanBodyMassKg,
                                    muscleMassKg = comp.muscleMassKg,
                                    bodyWaterKg = comp.bodyWaterKg,
                                    bodyWaterPercent = comp.bodyWaterPercent,
                                    boneMassKg = comp.boneMassKg,
                                    bmrKcal = comp.bmrKcal,
                                    visceralFatIndex = comp.visceralFatIndex,
                                    source = "Tesla BF1321",
                                    syncedToHealthConnect = false
                                )
                            )
                        }
                    }

                    val entriesToSave = accepted.filter { incoming ->
                        val match = existingScale.firstOrNull { ex ->
                            ex.id == incoming.id ||
                                abs(ex.timestampMillis - incoming.timestampMillis) <= 25_000L
                        }
                        match == null || (match.impedanceOhms == null && incoming.impedanceOhms != null)
                    }

                    if (entriesToSave.isNotEmpty()) {
                        LocalHealthStore.upsertScaleEntries(appCtx, entriesToSave)
                    }

                    val previousRef = cfg.referenceWeightKg
                    if (previousRef == null || abs(previousRef - currentRef) >= 0.01) {
                        LocalHealthStore.saveConfig(appCtx, cfg.copy(referenceWeightKg = currentRef))
                    }
                }

                val hasPending = LocalHealthStore.loadScaleList(appCtx).any { !it.syncedToHealthConnect } ||
                    LocalHealthStore.loadGlucoseList(appCtx).any { !it.syncedToHealthConnect }
                if (hasPending) {
                    HealthConnectManager.syncRecords(appCtx, forceAll = false)
                }
            } catch (_: Throwable) {
            } finally {
                isSyncRunning.set(false)
            }
        }
    }
}

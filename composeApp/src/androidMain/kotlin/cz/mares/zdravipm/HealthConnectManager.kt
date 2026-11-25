package cz.mares.zdravipm

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import java.time.Instant
import java.time.ZoneId

data class HcStatusInfo(
    val available: Boolean,
    val allCoreGranted: Boolean,
    val detailText: String
)

data class HcSyncResult(
    val glucoseWritten: Int,
    val scaleWritten: Int,
    val message: String
)

object HealthConnectManager {

    private val CORE_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getWritePermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class)
    )

    val REQUIRED_PERMISSIONS: Set<String> = CORE_PERMISSIONS + setOf(
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getWritePermission(HeightRecord::class)
    )

    suspend fun checkStatus(context: Context): HcStatusInfo {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return HcStatusInfo(
                available = false,
                allCoreGranted = false,
                detailText = "Služba Health Connect není v telefonu nainstalovaná nebo vyžaduje aktualizaci."
            )
        }
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val coreOk = granted.containsAll(CORE_PERMISSIONS)
            val fullOk = granted.containsAll(REQUIRED_PERMISSIONS)
            val text = when {
                fullOk -> "Plný přístup povolen (glukóza, váha i kompletní složení těla)."
                coreOk -> "Základní přístup povolen (glukóza a váha). Pro tuk a svaly povol i ostatní položky."
                else -> "Povol oprávnění zápisu do Health Connect pro přenos do Samsung Health."
            }
            HcStatusInfo(
                available = true,
                allCoreGranted = coreOk,
                detailText = text
            )
        } catch (t: Throwable) {
            HcStatusInfo(
                available = true,
                allCoreGranted = false,
                detailText = "Kontrola oprávnění Health Connect: ${t.message ?: "nepodařila se"}"
            )
        }
    }

    fun openHealthConnectSettings(context: Context) {
        val intents = listOf(
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
            context.packageManager.getLaunchIntentForPackage("com.google.android.apps.healthdata"),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.google.android.apps.healthdata")
            )
        )
        for (candidate in intents.filterNotNull()) {
            try {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(candidate)
                return
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun syncRecords(context: Context, forceAll: Boolean = false): HcSyncResult {
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return HcSyncResult(0, 0, "Health Connect není v zařízení dostupný.")
        }

        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (t: Throwable) {
            return HcSyncResult(0, 0, "Nelze otevřít Health Connect: ${t.message}")
        }

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (t: Throwable) {
            return HcSyncResult(0, 0, "Nelze ověřit oprávnění Health Connect: ${t.message}")
        }

        val canWriteGlucose = granted.contains(HealthPermission.getWritePermission(BloodGlucoseRecord::class))
        val canWriteWeight = granted.contains(HealthPermission.getWritePermission(WeightRecord::class))
        if (!canWriteGlucose && !canWriteWeight) {
            return HcSyncResult(0, 0, "Nejprve v Nastavení povol zápis do Health Connect.")
        }

        val cfg = LocalHealthStore.loadConfig(context)
        val glucosePending = LocalHealthStore.loadGlucoseList(context)
            .filter { forceAll || !it.syncedToHealthConnect }
        val scalePending = LocalHealthStore.loadScaleList(context)
            .filter { forceAll || !it.syncedToHealthConnect }

        var glucoseOkCount = 0
        var scaleOkCount = 0
        val errors = ArrayList<String>()

        if (canWriteGlucose && glucosePending.isNotEmpty()) {
            val syncedIds = ArrayList<String>()
            for (entry in glucosePending) {
                try {
                    val time = safeRecordInstant(entry.timestampMillis)
                    val zoneOffset = ZoneId.systemDefault().rules.getOffset(time)
                    val relation = when (entry.mealRelation.name.uppercase()) {
                        "FASTING" -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
                        "BEFORE_MEAL", "PREPRANDIAL" -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
                        "AFTER_MEAL", "POSTPRANDIAL" -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
                        else -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
                    }
                    val record = BloodGlucoseRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        level = BloodGlucose.millimolesPerLiter(entry.mmolPerLiter.coerceIn(1.0, 45.0)),
                        specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD,
                        mealType = MealType.MEAL_TYPE_UNKNOWN,
                        relationToMeal = relation,
                        metadata = Metadata(
                            clientRecordId = entry.id,
                            clientRecordVersion = 1L
                        )
                    )
                    client.insertRecords(listOf(record))
                    syncedIds.add(entry.id)
                    glucoseOkCount++
                } catch (t: Throwable) {
                    if (errors.isEmpty()) {
                        errors.add("Cukr: ${t.message ?: "chyba zápisu"}")
                    }
                }
            }
            if (syncedIds.isNotEmpty()) {
                LocalHealthStore.markGlucoseSynced(context, syncedIds)
            }
        }

        if (canWriteWeight && scalePending.isNotEmpty()) {
            val syncedScaleIds = ArrayList<String>()
            var heightWrittenOnce = false

            for (entry in scalePending) {
                try {
                    val time = safeRecordInstant(entry.timestampMillis)
                    val zoneOffset = ZoneId.systemDefault().rules.getOffset(time)
                    val recVersion = if (entry.impedanceOhms != null) 2L else 1L

                    val weightRec = WeightRecord(
                        time = time,
                        zoneOffset = zoneOffset,
                        weight = Mass.kilograms(entry.weightKg.coerceIn(10.0, 350.0)),
                        metadata = Metadata(
                            clientRecordId = "${entry.id}_weight",
                            clientRecordVersion = recVersion
                        )
                    )
                    client.insertRecords(listOf(weightRec))
                    syncedScaleIds.add(entry.id)
                    scaleOkCount++

                    if (cfg.writeBodyCompositionToHc) {
                        val extraRecords = ArrayList<Record>()
                        if (entry.bodyFatPercent != null &&
                            granted.contains(HealthPermission.getWritePermission(BodyFatRecord::class))
                        ) {
                            extraRecords.add(
                                BodyFatRecord(
                                    time = time,
                                    zoneOffset = zoneOffset,
                                    percentage = Percentage(entry.bodyFatPercent.coerceIn(1.0, 75.0)),
                                    metadata = Metadata(
                                        clientRecordId = "${entry.id}_fat",
                                        clientRecordVersion = recVersion
                                    )
                                )
                            )
                        }
                        if (entry.leanBodyMassKg != null &&
                            granted.contains(HealthPermission.getWritePermission(LeanBodyMassRecord::class))
                        ) {
                            extraRecords.add(
                                LeanBodyMassRecord(
                                    time = time,
                                    zoneOffset = zoneOffset,
                                    mass = Mass.kilograms(entry.leanBodyMassKg.coerceIn(10.0, 250.0)),
                                    metadata = Metadata(
                                        clientRecordId = "${entry.id}_lbm",
                                        clientRecordVersion = recVersion
                                    )
                                )
                            )
                        }
                        if (entry.boneMassKg != null &&
                            granted.contains(HealthPermission.getWritePermission(BoneMassRecord::class))
                        ) {
                            extraRecords.add(
                                BoneMassRecord(
                                    time = time,
                                    zoneOffset = zoneOffset,
                                    mass = Mass.kilograms(entry.boneMassKg.coerceIn(0.5, 15.0)),
                                    metadata = Metadata(
                                        clientRecordId = "${entry.id}_bone",
                                        clientRecordVersion = recVersion
                                    )
                                )
                            )
                        }
                        if (entry.bodyWaterKg != null &&
                            granted.contains(HealthPermission.getWritePermission(BodyWaterMassRecord::class))
                        ) {
                            extraRecords.add(
                                BodyWaterMassRecord(
                                    time = time,
                                    zoneOffset = zoneOffset,
                                    mass = Mass.kilograms(entry.bodyWaterKg.coerceIn(10.0, 200.0)),
                                    metadata = Metadata(
                                        clientRecordId = "${entry.id}_water",
                                        clientRecordVersion = recVersion
                                    )
                                )
                            )
                        }
                        if (entry.bmrKcal != null &&
                            granted.contains(HealthPermission.getWritePermission(BasalMetabolicRateRecord::class))
                        ) {
                            extraRecords.add(
                                BasalMetabolicRateRecord(
                                    time = time,
                                    zoneOffset = zoneOffset,
                                    basalMetabolicRate = Power.kilocaloriesPerDay(
                                        entry.bmrKcal.toDouble().coerceIn(500.0, 6000.0)
                                    ),
                                    metadata = Metadata(
                                        clientRecordId = "${entry.id}_bmr",
                                        clientRecordVersion = recVersion
                                    )
                                )
                            )
                        }
                        if (!heightWrittenOnce &&
                            granted.contains(HealthPermission.getWritePermission(HeightRecord::class))
                        ) {
                            val heightMeters = (cfg.heightCm.toDouble() / 100.0).coerceIn(0.8, 2.4)
                            extraRecords.add(
                                HeightRecord(
                                    time = time,
                                    zoneOffset = zoneOffset,
                                    height = Length.meters(heightMeters),
                                    metadata = Metadata(
                                        clientRecordId = "zdravipm_user_height",
                                        clientRecordVersion = 1L
                                    )
                                )
                            )
                            heightWrittenOnce = true
                        }

                        for (extra in extraRecords) {
                            try {
                                client.insertRecords(listOf(extra))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errors.add("Váha: ${t.message ?: "chyba zápisu"}")
                }
            }
            if (syncedScaleIds.isNotEmpty()) {
                LocalHealthStore.markScaleSynced(context, syncedScaleIds)
            }
        }

        val summary = when {
            errors.isNotEmpty() && (glucoseOkCount > 0 || scaleOkCount > 0) ->
                "Zapsáno (cukr: $glucoseOkCount×, váha: $scaleOkCount×), upozornění: ${errors.first()}"
            errors.isNotEmpty() ->
                "Chyba zápisu do Health Connect: ${errors.first()}"
            glucoseOkCount == 0 && scaleOkCount == 0 ->
                "Všechny záznamy už jsou synchronizované v Health Connect."
            else ->
                "Úspěšně zapsáno do Health Connect (cukr: $glucoseOkCount×, váha a tělo: $scaleOkCount×)."
        }

        return HcSyncResult(
            glucoseWritten = glucoseOkCount,
            scaleWritten = scaleOkCount,
            message = summary
        )
    }

    private fun safeRecordInstant(rawMillis: Long): Instant {
        val maxAllowed = System.currentTimeMillis() - 2_000L
        val clamped = rawMillis.coerceIn(946_684_800_000L, maxAllowed)
        return Instant.ofEpochMilli(clamped)
    }
}

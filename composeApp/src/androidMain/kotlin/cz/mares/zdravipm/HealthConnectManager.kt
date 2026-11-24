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

    private val P_READ_GLU = HealthPermission.getReadPermission(BloodGlucoseRecord::class)
    private val P_WRITE_GLU = HealthPermission.getWritePermission(BloodGlucoseRecord::class)
    private val P_READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
    private val P_WRITE_WEIGHT = HealthPermission.getWritePermission(WeightRecord::class)
    private val P_READ_FAT = HealthPermission.getReadPermission(BodyFatRecord::class)
    private val P_WRITE_FAT = HealthPermission.getWritePermission(BodyFatRecord::class)
    private val P_READ_LBM = HealthPermission.getReadPermission(LeanBodyMassRecord::class)
    private val P_WRITE_LBM = HealthPermission.getWritePermission(LeanBodyMassRecord::class)
    private val P_READ_BONE = HealthPermission.getReadPermission(BoneMassRecord::class)
    private val P_WRITE_BONE = HealthPermission.getWritePermission(BoneMassRecord::class)
    private val P_READ_WATER = HealthPermission.getReadPermission(BodyWaterMassRecord::class)
    private val P_WRITE_WATER = HealthPermission.getWritePermission(BodyWaterMassRecord::class)
    private val P_READ_BMR = HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
    private val P_WRITE_BMR = HealthPermission.getWritePermission(BasalMetabolicRateRecord::class)
    private val P_READ_HEIGHT = HealthPermission.getReadPermission(HeightRecord::class)
    private val P_WRITE_HEIGHT = HealthPermission.getWritePermission(HeightRecord::class)

    val REQUIRED_PERMISSIONS: Set<String> = setOf(
        P_READ_GLU, P_WRITE_GLU,
        P_READ_WEIGHT, P_WRITE_WEIGHT,
        P_READ_FAT, P_WRITE_FAT,
        P_READ_LBM, P_WRITE_LBM,
        P_READ_BONE, P_WRITE_BONE,
        P_READ_WATER, P_WRITE_WATER,
        P_READ_BMR, P_WRITE_BMR,
        P_READ_HEIGHT, P_WRITE_HEIGHT
    )

    suspend fun checkStatus(context: Context): HcStatusInfo {
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return HcStatusInfo(
                available = false,
                allCoreGranted = false,
                detailText = "Služba Health Connect není v systému dostupná nebo vyžaduje aktualizaci."
            )
        }
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val hasCore = P_WRITE_GLU in granted && P_WRITE_WEIGHT in granted
            val hasAll = granted.containsAll(REQUIRED_PERMISSIONS)
            val detail = when {
                hasAll -> "Plný přístup povolen (glukóza, váha i kompletní složení těla)."
                hasCore -> "Základní zápis povolen (${granted.size}/${REQUIRED_PERMISSIONS.size} oprávnění)."
                else -> "Chybí povolení k zápisu do Health Connect. Klepni na Povolit zápis."
            }
            HcStatusInfo(
                available = true,
                allCoreGranted = hasCore,
                detailText = detail
            )
        } catch (t: Throwable) {
            HcStatusInfo(
                available = true,
                allCoreGranted = false,
                detailText = "Kontrola Health Connect: ${t.message ?: "vyžaduje povolení"}"
            )
        }
    }

    fun openHealthConnectSettings(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            },
            Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            },
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            Intent("android.health.connect.action.HEALTH_CONNECT_SETTINGS")
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Throwable) {
            }
        }
        try {
            val fallback = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        } catch (_: Throwable) {
        }
    }

    suspend fun syncRecords(context: Context, forceAll: Boolean = false): HcSyncResult {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return HcSyncResult(0, 0, "Health Connect není v telefonu dostupný.")
        }

        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val canWriteGlu = P_WRITE_GLU in granted
            val canWriteWeight = P_WRITE_WEIGHT in granted

            if (!canWriteGlu && !canWriteWeight) {
                return HcSyncResult(
                    glucoseWritten = 0,
                    scaleWritten = 0,
                    message = "Nejprve povol zápis v Health Connect."
                )
            }

            val cfg = LocalHealthStore.loadConfig(context)
            val zone = ZoneId.systemDefault()
            var gluCount = 0
            var scaleCount = 0

            if (canWriteGlu) {
                val toSendGlu = LocalHealthStore.loadGlucoseList(context)
                    .filter { forceAll || !it.syncedToHealthConnect }
                val syncedGluIds = HashSet<String>()

                for (entry in toSendGlu) {
                    val instant = Instant.ofEpochMilli(entry.timestampMillis)
                    val offset = zone.rules.getOffset(instant)
                    val relation = when (entry.mealRelation) {
                        MealRelation.FASTING -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
                        MealRelation.BEFORE_MEAL -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
                        MealRelation.AFTER_MEAL -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
                        MealRelation.GENERAL -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
                    }
                    val record = BloodGlucoseRecord(
                        time = instant,
                        zoneOffset = offset,
                        level = BloodGlucose.millimolesPerLiter(entry.mmolPerLiter),
                        specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD,
                        mealType = MealType.MEAL_TYPE_UNKNOWN,
                        relationToMeal = relation,
                        metadata = Metadata(clientRecordId = "zpm_glu_${entry.id}")
                    )
                    client.insertRecords(listOf(record))
                    syncedGluIds.add(entry.id)
                    gluCount++
                }
                LocalHealthStore.markGlucoseSynced(context, syncedGluIds)
            }

            if (canWriteWeight) {
                val toSendScale = LocalHealthStore.loadScaleList(context)
                    .filter { forceAll || !it.syncedToHealthConnect }
                val syncedScaleIds = HashSet<String>()

                for (entry in toSendScale) {
                    val instant = Instant.ofEpochMilli(entry.timestampMillis)
                    val offset = zone.rules.getOffset(instant)
                    val batch = ArrayList<Record>()

                    batch.add(
                        WeightRecord(
                            time = instant,
                            zoneOffset = offset,
                            weight = Mass.kilograms(entry.weightKg),
                            metadata = Metadata(clientRecordId = "zpm_w_${entry.id}")
                        )
                    )

                    if (cfg.writeBodyCompositionToHc) {
                        if (entry.bodyFatPercent != null && P_WRITE_FAT in granted) {
                            batch.add(
                                BodyFatRecord(
                                    time = instant,
                                    zoneOffset = offset,
                                    percentage = Percentage(entry.bodyFatPercent),
                                    metadata = Metadata(clientRecordId = "zpm_bf_${entry.id}")
                                )
                            )
                        }
                        if (entry.leanBodyMassKg != null && P_WRITE_LBM in granted) {
                            batch.add(
                                LeanBodyMassRecord(
                                    time = instant,
                                    zoneOffset = offset,
                                    mass = Mass.kilograms(entry.leanBodyMassKg),
                                    metadata = Metadata(clientRecordId = "zpm_lbm_${entry.id}")
                                )
                            )
                        }
                        if (entry.boneMassKg != null && P_WRITE_BONE in granted) {
                            batch.add(
                                BoneMassRecord(
                                    time = instant,
                                    zoneOffset = offset,
                                    mass = Mass.kilograms(entry.boneMassKg),
                                    metadata = Metadata(clientRecordId = "zpm_bone_${entry.id}")
                                )
                            )
                        }
                        if (entry.bodyWaterKg != null && P_WRITE_WATER in granted) {
                            batch.add(
                                BodyWaterMassRecord(
                                    time = instant,
                                    zoneOffset = offset,
                                    mass = Mass.kilograms(entry.bodyWaterKg),
                                    metadata = Metadata(clientRecordId = "zpm_water_${entry.id}")
                                )
                            )
                        }
                        if (entry.bmrKcal != null && P_WRITE_BMR in granted) {
                            batch.add(
                                BasalMetabolicRateRecord(
                                    time = instant,
                                    zoneOffset = offset,
                                    basalMetabolicRate = Power.kilocaloriesPerDay(entry.bmrKcal.toDouble()),
                                    metadata = Metadata(clientRecordId = "zpm_bmr_${entry.id}")
                                )
                            )
                        }
                        if (P_WRITE_HEIGHT in granted) {
                            batch.add(
                                HeightRecord(
                                    time = instant,
                                    zoneOffset = offset,
                                    height = Length.meters(cfg.heightCm.coerceIn(110, 230) / 100.0),
                                    metadata = Metadata(clientRecordId = "zpm_h_${entry.id}")
                                )
                            )
                        }
                    }

                    client.insertRecords(batch)
                    syncedScaleIds.add(entry.id)
                    scaleCount++
                }
                LocalHealthStore.markScaleSynced(context, syncedScaleIds)
            }

            val msg = if (gluCount == 0 && scaleCount == 0) {
                "Všechny záznamy jsou již v Health Connect aktuální."
            } else {
                "Zapsáno do Health Connect: $gluCount× cukr, $scaleCount× váha a složení těla."
            }
            HcSyncResult(gluCount, scaleCount, msg)
        } catch (t: Throwable) {
            HcSyncResult(0, 0, "Chyba zápisu do Health Connect: ${t.message ?: "neznámá chyba"}")
        }
    }
}

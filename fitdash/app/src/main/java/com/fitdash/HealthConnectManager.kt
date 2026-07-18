package com.fitdash

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.Gson
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)

    companion object {
        const val PKG_GADGETBRIDGE = "nodomain.freeyourgadget.gadgetbridge.nightly"
        const val PKG_ULTRAHUMAN = "com.ultrahuman.android"
        const val PKG_RESMED     = "com.resmed.myair"
        const val PKG_SAMSUNG    = "com.sec.android.app.shealth"
        const val PKG_FITBIT     = "com.fitbit.FitbitMobile"
        const val PKG_FITDASH = "com.fitdash"

        fun displayName(pkg: String): String = when (pkg) {
            PKG_FITDASH -> "UltraSignal"
            PKG_ULTRAHUMAN -> "Ultrahuman"
            PKG_GADGETBRIDGE -> "Gadgetbridge"
            PKG_SAMSUNG    -> "Samsung Health"
            PKG_FITBIT     -> "Google Health"
            PKG_RESMED     -> "ResMed"
            else           -> pkg
        }

        val ACTIVITY_PRIORITY = listOf(PKG_FITDASH, PKG_ULTRAHUMAN, PKG_SAMSUNG, PKG_FITBIT)
        val SLEEP_PRIORITY    = listOf(PKG_FITDASH, PKG_ULTRAHUMAN, PKG_FITBIT, PKG_SAMSUNG)
    }

    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    val historyPermission = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    private fun <T> selectSource(
        bySource: Map<String, List<T>>,
        priorityOrder: List<String>
    ): Map<String, List<T>> {
        for (pkg in priorityOrder) {
            if (bySource[pkg]?.isNotEmpty() == true) return mapOf(pkg to bySource[pkg]!!)
        }
        return bySource
    }

    private fun todayRange(): TimeRangeFilter {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        return TimeRangeFilter.between(start, end)
    }

    private fun dayRange(date: LocalDate): TimeRangeFilter {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        return TimeRangeFilter.between(start, end)
    }

    private fun sleepLookbackRange(): TimeRangeFilter {
        val end   = Instant.now()
        val start = end.minus(30, ChronoUnit.HOURS)
        return TimeRangeFilter.between(start, end)
    }

    private suspend fun readSteps(range: TimeRangeFilter): Long {
        val records  = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        return selected.values.flatten().sumOf { it.count }
    }

    private suspend fun readActiveCalories(range: TimeRangeFilter): Double {
        val records  = client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range)).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        return selected.values.flatten().sumOf { it.energy.inKilocalories }
    }

    private suspend fun readDistance(range: TimeRangeFilter): Double {
        val records  = client.readRecords(ReadRecordsRequest(DistanceRecord::class, range)).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        return selected.values.flatten().sumOf { it.distance.inKilometers }
    }

    private suspend fun readAvgHeartRate(range: TimeRangeFilter): Int {
        val records  = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        val samples  = selected.values.flatten().flatMap { it.samples }
        return if (samples.isEmpty()) 0 else samples.map { it.beatsPerMinute }.average().toInt()
    }

    private suspend fun readMaxHeartRate(range: TimeRangeFilter): Int {
        val records  = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        val samples  = selected.values.flatten().flatMap { it.samples }
        return samples.maxOfOrNull { it.beatsPerMinute }?.toInt() ?: 0
    }

    private suspend fun readRestingHR(): Int {
        val records  = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, todayRange())).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        val flat     = selected.values.flatten()
        return if (flat.isEmpty()) 0 else flat.map { it.beatsPerMinute }.average().toInt()
    }

    private suspend fun readSpO2(): Int {
        val records  = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, todayRange())).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        val flat     = selected.values.flatten()
        return if (flat.isEmpty()) 0 else flat.map { it.percentage.value }.average().toInt()
    }

    private suspend fun readVo2Max(): Double {
        val records = client.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, todayRange())).records
        return records.maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram ?: 0.0
    }

    private suspend fun readSleep(): SleepResult {
        val sessions = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepLookbackRange())).records
        val selected = selectSource(sessions.groupBy { it.metadata.dataOrigin.packageName }, SLEEP_PRIORITY)
        val flat     = selected.values.flatten()
        if (flat.isEmpty()) return SleepResult()
        val session      = flat.maxByOrNull { it.endTime } ?: return SleepResult()
        val totalMinutes = Duration.between(session.startTime, session.endTime).toMinutes()
        var deep = 0L; var rem = 0L; var light = 0L
        session.stages.forEach { s ->
            val mins = Duration.between(s.startTime, s.endTime).toMinutes()
            when (s.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP  -> deep  += mins
                SleepSessionRecord.STAGE_TYPE_REM   -> rem   += mins
                SleepSessionRecord.STAGE_TYPE_LIGHT -> light += mins
            }
        }
        return SleepResult(totalMinutes / 60.0, deep / 60.0, rem / 60.0, light / 60.0,
            displayName(session.metadata.dataOrigin.packageName))
    }

    private suspend fun readExerciseMinutes(): Int {
        val records  = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, todayRange())).records
        val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
        return selected.values.flatten().sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
    }

    suspend fun fetchDashboard(): DashboardPayload {
        val todayRange = todayRange()
        val sleep      = readSleep()
        val todayDate  = LocalDate.now()
        val dayNames   = listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")

        val today = TodayData(
            steps            = readSteps(todayRange),
            stepsGoal        = 10000,
            activeCalories   = readActiveCalories(todayRange).toInt(),
            distance         = readDistance(todayRange),
            heartRateCurrent = readAvgHeartRate(todayRange),
            heartRateResting = readRestingHR(),
            heartRateMax     = readMaxHeartRate(todayRange),
            spo2             = readSpO2(),
            vo2max           = readVo2Max(),
            sleepHours       = sleep.totalHours,
            sleepDeep        = sleep.deepHours,
            sleepRem         = sleep.remHours,
            sleepLight       = sleep.lightHours,
            sleepGoal        = 8.0,
            exerciseMinutes  = readExerciseMinutes(),
            exerciseGoal     = 30
        )

        val week = (6 downTo 0).map { i ->
            val date = todayDate.minusDays(i.toLong())
            WeekDay(
                date     = date.toString(),
                day      = dayNames[date.dayOfWeek.value % 7],
                steps    = readSteps(dayRange(date)),
                calories = readActiveCalories(dayRange(date))
            )
        }

        return DashboardPayload(
            permissionsGranted = true,
            today              = today,
            week               = week,
            sources            = readSourcesData()
        )
    }

    suspend fun fetchHistory(days: Int = 90): HistoryPayload {
        val today = LocalDate.now()

        val dayList = (0 until days).map { i ->
            val date     = today.minusDays(i.toLong())
            val actRange = dayRange(date)

            // Sleep: noon-to-noon window to catch overnight sessions
            val sleepStart = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
            val sleepEnd   = date.plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
            val sleepRange = TimeRangeFilter.between(sleepStart, sleepEnd)

            val steps    = readSteps(actRange)
            val calories = readActiveCalories(actRange).toInt()
            val distance = readDistance(actRange)

            val hrResting = run {
                val records  = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, actRange)).records
                val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
                val flat     = selected.values.flatten()
                if (flat.isEmpty()) 0 else flat.map { it.beatsPerMinute }.average().toInt()
            }
            val hrMax = run {
                val records  = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, actRange)).records
                val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
                val samples  = selected.values.flatten().flatMap { it.samples }
                samples.maxOfOrNull { it.beatsPerMinute }?.toInt() ?: 0
            }
            val spo2 = run {
                val records  = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, actRange)).records
                val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
                val flat     = selected.values.flatten()
                if (flat.isEmpty()) 0 else flat.map { it.percentage.value }.average().toInt()
            }
            val vo2max = run {
                val records = client.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, actRange)).records
                records.maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram ?: 0.0
            }
            val exerciseMinutes = run {
                val records  = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, actRange)).records
                val selected = selectSource(records.groupBy { it.metadata.dataOrigin.packageName }, ACTIVITY_PRIORITY)
                selected.values.flatten().sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
            }
            val sleepResult = run {
                val sessions = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepRange)).records
                val selected = selectSource(sessions.groupBy { it.metadata.dataOrigin.packageName }, SLEEP_PRIORITY)
                val session  = selected.values.flatten().maxByOrNull { it.endTime }
                if (session == null) {
                    SleepResult()
                } else {
                    val totalMinutes = Duration.between(session.startTime, session.endTime).toMinutes()
                    var deep = 0L; var rem = 0L; var light = 0L
                    session.stages.forEach { s ->
                        val mins = Duration.between(s.startTime, s.endTime).toMinutes()
                        when (s.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP  -> deep  += mins
                            SleepSessionRecord.STAGE_TYPE_REM   -> rem   += mins
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> light += mins
                        }
                    }
                    SleepResult(totalMinutes / 60.0, deep / 60.0, rem / 60.0, light / 60.0)
                }
            }

            DailyHistory(
                date            = date.toString(),
                steps           = steps,
                calories        = calories,
                distance        = distance,
                sleep           = sleepResult.totalHours,
                sleepDeep       = sleepResult.deepHours,
                sleepRem        = sleepResult.remHours,
                sleepLight      = sleepResult.lightHours,
                hrResting       = hrResting,
                hrMax           = hrMax,
                spo2            = spo2,
                vo2max          = vo2max,
                exerciseMinutes = exerciseMinutes
            )
        }

        val bestStepsDay = dayList.maxByOrNull { it.steps }
        val bestCalDay   = dayList.maxByOrNull { it.calories }
        val bestSleepDay = dayList.filter { it.sleep > 0 }.maxByOrNull { it.sleep }
        val bestHrDay    = dayList.filter { it.hrResting > 0 }.minByOrNull { it.hrResting }
        val bestVo2Day   = dayList.filter { it.vo2max > 0 }.maxByOrNull { it.vo2max }

        val bests = Bests(
            mostStepsDay        = bestStepsDay?.steps ?: 0,
            mostStepsDate       = bestStepsDay?.date ?: "",
            mostCaloriesDay     = bestCalDay?.calories ?: 0,
            mostCaloriesDate    = bestCalDay?.date ?: "",
            longestSleep        = bestSleepDay?.sleep ?: 0.0,
            longestSleepDate    = bestSleepDay?.date ?: "",
            lowestRestingHr     = bestHrDay?.hrResting ?: 0,
            lowestRestingHrDate = bestHrDay?.date ?: "",
            highestVo2max       = bestVo2Day?.vo2max ?: 0.0,
            highestVo2maxDate   = bestVo2Day?.date ?: ""
        )

        val byMonth = dayList.groupBy { it.date.substring(0, 7) }
        val months  = byMonth.map { (month, ds) ->
            val sleepDays = ds.filter { it.sleep > 0 }
            val hrDays    = ds.filter { it.hrResting > 0 }
            val spo2Days  = ds.filter { it.spo2 > 0 }
            MonthSummary(
                month       = month,
                avgSteps    = ds.map { it.steps }.average().toLong(),
                avgCalories = ds.map { it.calories }.average().toLong(),
                avgSleep    = if (sleepDays.isEmpty()) 0.0 else sleepDays.map { it.sleep }.average(),
                avgHr       = if (hrDays.isEmpty()) 0 else hrDays.map { it.hrResting }.average().toInt(),
                avgSpo2     = if (spo2Days.isEmpty()) 0 else spo2Days.map { it.spo2 }.average().toInt()
            )
        }.sortedBy { it.month }

        return HistoryPayload(days = dayList, bests = bests, months = months)
    }

    // Sources tab — raw per-source breakdown including sleep
    private suspend fun readSourcesData(): List<SourceRow> {
        val actRange   = todayRange()
        val sleepRange = sleepLookbackRange()

        val stepRecords  = client.readRecords(ReadRecordsRequest(StepsRecord::class, actRange)).records
        val calRecords   = client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, actRange)).records
        val distRecords  = client.readRecords(ReadRecordsRequest(DistanceRecord::class, actRange)).records
        val hrRecords    = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, actRange)).records
        val rhrRecords   = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, actRange)).records
        val sleepRecords = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepRange)).records

        val stepsByPkg  = stepRecords.groupBy  { it.metadata.dataOrigin.packageName }.mapValues { (_, v) -> v.sumOf { it.count } }
        val calsByPkg   = calRecords.groupBy   { it.metadata.dataOrigin.packageName }.mapValues { (_, v) -> v.sumOf { it.energy.inKilocalories } }
        val distByPkg   = distRecords.groupBy  { it.metadata.dataOrigin.packageName }.mapValues { (_, v) -> v.sumOf { it.distance.inKilometers } }
        val hrByPkg     = hrRecords.groupBy    { it.metadata.dataOrigin.packageName }
        val rhrByPkg    = rhrRecords.groupBy   { it.metadata.dataOrigin.packageName }

        // Sleep: per source, pick the most recent session and sum its duration
        val sleepByPkg  = sleepRecords.groupBy { it.metadata.dataOrigin.packageName }.mapValues { (_, sessions) ->
            val session = sessions.maxByOrNull { it.endTime }!!
            val totalMins = Duration.between(session.startTime, session.endTime).toMinutes()
            var deep = 0L; var rem = 0L; var light = 0L
            session.stages.forEach { s ->
                val m = Duration.between(s.startTime, s.endTime).toMinutes()
                when (s.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP  -> deep  += m
                    SleepSessionRecord.STAGE_TYPE_REM   -> rem   += m
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> light += m
                }
            }
            SleepSummary(totalMins / 60.0, deep / 60.0, rem / 60.0, light / 60.0)
        }

        val allPkgs           = (stepsByPkg.keys + calsByPkg.keys + distByPkg.keys + sleepByPkg.keys).toSet()
        val preferredActivityPkg = ACTIVITY_PRIORITY.firstOrNull { stepsByPkg.containsKey(it) }
            ?: stepsByPkg.keys.firstOrNull()
        val preferredSleepPkg = SLEEP_PRIORITY.firstOrNull { sleepByPkg.containsKey(it) }
            ?: sleepByPkg.keys.firstOrNull()

        return allPkgs.map { pkg ->
            val hrSamples = hrByPkg[pkg]?.flatMap { it.samples } ?: emptyList()
            val sleep     = sleepByPkg[pkg]
            SourceRow(
                appName          = displayName(pkg),
                steps            = stepsByPkg[pkg] ?: 0L,
                calories         = calsByPkg[pkg]  ?: 0.0,
                distance         = distByPkg[pkg]  ?: 0.0,
                hrResting        = rhrByPkg[pkg]?.map { it.beatsPerMinute }?.average()?.toInt() ?: 0,
                hrMax            = hrSamples.maxOfOrNull { it.beatsPerMinute }?.toInt() ?: 0,
                sleepHours       = sleep?.totalHours  ?: 0.0,
                sleepDeep        = sleep?.deepHours   ?: 0.0,
                sleepRem         = sleep?.remHours    ?: 0.0,
                sleepLight       = sleep?.lightHours  ?: 0.0,
                isPreferredActivity = (pkg == preferredActivityPkg),
                isPreferredSleep    = (pkg == preferredSleepPkg)
            )
        }.sortedWith(compareByDescending<SourceRow> { it.sleepHours > 0 || it.steps > 0 }
            .thenByDescending { it.steps })
    }

    fun toJson(payload: DashboardPayload): String {
        val json = Gson().toJson(payload)
        // Backfill rawJson on the stored payload so DriveBackupManager can use it
        return json
    }

    fun withRawJson(payload: DashboardPayload): DashboardPayload {
        val json = Gson().toJson(payload)
        return payload.copy(rawJson = json)
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class SleepResult(
        val totalHours : Double = 0.0,
        val deepHours  : Double = 0.0,
        val remHours   : Double = 0.0,
        val lightHours : Double = 0.0,
        val source     : String = ""
    )

    data class SleepSummary(
        val totalHours : Double,
        val deepHours  : Double,
        val remHours   : Double,
        val lightHours : Double
    )

    data class TodayData(
        val steps            : Long,
        val stepsGoal        : Long,
        val activeCalories   : Int,
        val distance         : Double,
        val heartRateCurrent : Int,
        val heartRateResting : Int,
        val heartRateMax     : Int,
        val spo2             : Int,
        val vo2max           : Double,
        val sleepHours       : Double,
        val sleepDeep        : Double,
        val sleepRem         : Double,
        val sleepLight       : Double,
        val sleepGoal        : Double,
        val exerciseMinutes  : Int,
        val exerciseGoal     : Int
    )

    data class WeekDay(val date: String, val day: String, val steps: Long, val calories: Double)

    data class SourceRow(
        val appName             : String,
        val steps               : Long,
        val calories            : Double,
        val distance            : Double,
        val hrResting           : Int,
        val hrMax               : Int,
        val sleepHours          : Double,
        val sleepDeep           : Double,
        val sleepRem            : Double,
        val sleepLight          : Double,
        val isPreferredActivity : Boolean,
        val isPreferredSleep    : Boolean
    )

    data class DashboardPayload(
        val permissionsGranted : Boolean,
        val today              : TodayData,
        val week               : List<WeekDay>,
        val sources            : List<SourceRow>,
        @Transient val rawJson : String = ""
    )

    data class DailyHistory(
        val date: String, val steps: Long, val calories: Int,
        val distance: Double, val sleep: Double, val sleepDeep: Double,
        val sleepRem: Double, val sleepLight: Double,
        val hrResting: Int, val hrMax: Int,
        val spo2: Int, val vo2max: Double, val exerciseMinutes: Int
    )

    data class Bests(
        val mostStepsDay: Long, val mostStepsDate: String,
        val mostCaloriesDay: Int, val mostCaloriesDate: String,
        val longestSleep: Double, val longestSleepDate: String,
        val lowestRestingHr: Int, val lowestRestingHrDate: String,
        val highestVo2max: Double, val highestVo2maxDate: String
    )

    data class MonthSummary(
        val month: String, val avgSteps: Long,
        val avgCalories: Long, val avgSleep: Double,
        val avgHr: Int, val avgSpo2: Int
    )

    data class HistoryPayload(
        val days: List<DailyHistory>, val bests: Bests, val months: List<MonthSummary>
    )
}

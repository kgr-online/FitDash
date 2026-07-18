package com.fitdash

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Polls the UltraSignal API and writes the server-computed UH ring data
 * directly into Health Connect, attributed to com.ultrahuman.android.
 *
 * This replaces the Tasker foreground-sync workaround for UH data freshness.
 *
 * Idempotent: uses stable clientRecordId values (date+type) so repeated
 * calls on the same day overwrite rather than duplicate.
 *
 * Call sync() from HealthSyncWorker or anywhere you want a forced refresh.
 */
class UltraSignalSyncer(private val context: Context) {

    companion object {
        private const val TAG = "FitDash:UltraSignal"
        private const val BASE_URL = "https://partner.ultrahuman.com/api/v1/partner/daily_metrics"
        private const val PREFS_NAME = "fitdash_ultrasignal"
        private const val PREF_API_TOKEN = "api_token"

        // HC caps HRV at 200ms — clamp to avoid write failures (same root cause as GB crash)
        private const val HRV_MAX_MS = 200L

        val writePermissions = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(Vo2MaxRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
        )
    }

    private val client = HealthConnectClient.getOrCreate(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Token management ────────────────────────────────────────────────────

    fun setApiToken(token: String) {
        prefs.edit().putString(PREF_API_TOKEN, token).apply()
    }

    fun getApiToken(): String? = prefs.getString(PREF_API_TOKEN, null)

    fun hasApiToken(): Boolean = !getApiToken().isNullOrBlank()

    // ── Main entry point ────────────────────────────────────────────────────

    /**
     * Fetches today's data from UltraSignal and writes it to HC.
     * Safe to call repeatedly — idempotent via clientRecordId.
     * Returns true if sync succeeded, false on any error.
     */
    suspend fun sync(date: LocalDate = LocalDate.now()): Boolean {
        val token = getApiToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No API token set — skipping sync")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val json = fetchFromApi(token, date)
                if (json == null) {
                    Log.w(TAG, "Empty response from UltraSignal API")
                    return@withContext false
                }
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val metrics = json
                    .getJSONObject("data")
                    .getJSONObject("metrics")
                    .optJSONArray(dateStr)

                if (metrics == null) {
                    Log.w(TAG, "No metrics for date $dateStr in response")
                    return@withContext false
                }

                // Staleness check — inspect the last HR timestamp before writing.
                // If it's >60 min old the UH app likely hasn't synced with the ring recently.
                for (i in 0 until metrics.length()) {
                    if (metrics.getJSONObject(i).getString("type") == "hr") {
                        val hrVals = metrics.getJSONObject(i).getJSONObject("object").optJSONArray("values")
                        if (hrVals != null && hrVals.length() > 0) {
                            val lastTs    = hrVals.getJSONObject(hrVals.length() - 1).optLong("timestamp", 0L)
                            val ageMinutes = (Instant.now().epochSecond - lastTs) / 60
                            if (ageMinutes > 60) {
                                Log.w(TAG, "UH data is ${ageMinutes}m old — ring may not be syncing")
                            } else {
                                Log.d(TAG, "UH data freshness: ${ageMinutes}m old — ok")
                            }
                        }
                        break
                    }
                }

                // Parse each metric type
                var wrote = 0
                for (i in 0 until metrics.length()) {
                    val item = metrics.getJSONObject(i)
                    val type = item.getString("type")
                    val obj  = item.getJSONObject("object")
                    when (type) {
                        "steps"     -> if (writeSteps(obj, date, dateStr))      wrote++
                        "hr"        -> if (writeHeartRate(obj, date, dateStr))   wrote++
                        "spo2"      -> if (writeSpO2(obj, date, dateStr))        wrote++
                        "night_rhr" -> if (writeRestingHR(obj, date, dateStr))   wrote++
                        "vo2_max"   -> if (writeVo2Max(obj, date, dateStr))      wrote++
                        "sleep"     -> if (writeSleep(obj, date, dateStr))       wrote++
                    }
                }

                Log.i(TAG, "UltraSignal sync complete for $dateStr — wrote $wrote record types")
                true
            } catch (e: Exception) {
                Log.e(TAG, "UltraSignal sync failed", e)
                false
            }
        }
    }

    // ── API call ────────────────────────────────────────────────────────────

    private fun fetchFromApi(token: String, date: LocalDate): JSONObject? {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val url = URL("$BASE_URL?date=$dateStr")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", token)
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000
            conn.connect()

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "API returned HTTP $code for $dateStr")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    // ── HC write helpers ────────────────────────────────────────────────────

    private fun metadata(clientRecordId: String): Metadata =
        Metadata.manualEntry(clientRecordId)

    /**
     * Write steps. UH sends per-interval values; we upsert a single record
     * spanning the day with the total, which is simpler and matches how
     * readSteps() sums them. clientRecordId = "uh_steps_YYYY-MM-DD".
     */
    private suspend fun writeSteps(obj: JSONObject, date: LocalDate, dateStr: String): Boolean {
        val total = obj.optDouble("total", 0.0).toLong()
        if (total <= 0) return false

        val zone  = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end   = date.plusDays(1).atStartOfDay(zone).toInstant()

        val record = StepsRecord(
            startTime    = start,
            endTime      = end,
            count        = total,
            startZoneOffset = zone.rules.getOffset(start),
            endZoneOffset   = zone.rules.getOffset(end),
            metadata     = metadata("uh_steps_$dateStr")
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Wrote steps: $total for $dateStr")
        return true
    }

    /**
     * Write heart rate time-series. Filters zero values. Groups all samples
     * into a single HeartRateRecord for the day.
     */
    private suspend fun writeHeartRate(obj: JSONObject, date: LocalDate, dateStr: String): Boolean {
        val values = obj.optJSONArray("values") ?: return false
        val zone   = ZoneId.systemDefault()

        val samples = mutableListOf<HeartRateRecord.Sample>()
        for (i in 0 until values.length()) {
            val entry = values.getJSONObject(i)
            val bpm   = entry.optInt("value", 0)
            val ts    = entry.optLong("timestamp", 0L)
            if (bpm > 0 && ts > 0) {
                samples.add(HeartRateRecord.Sample(
                    time            = Instant.ofEpochSecond(ts),
                    beatsPerMinute  = bpm.toLong()
                ))
            }
        }
        if (samples.isEmpty()) return false

        val start = samples.first().time
        val end   = samples.last().time.plusSeconds(1) // end must be after last sample

        val record = HeartRateRecord(
            startTime       = start,
            endTime         = end,
            samples         = samples,
            startZoneOffset = zone.rules.getOffset(start),
            endZoneOffset   = zone.rules.getOffset(end),
            metadata        = metadata("uh_hr_$dateStr")
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Wrote ${samples.size} HR samples for $dateStr")
        return true
    }

    /**
     * Write SpO2 time-series. Filters zero values (UH uses 0 for no-reading gaps).
     */
    private suspend fun writeSpO2(obj: JSONObject, date: LocalDate, dateStr: String): Boolean {
        val values = obj.optJSONArray("values") ?: return false
        val zone   = ZoneId.systemDefault()

        val records = mutableListOf<OxygenSaturationRecord>()
        for (i in 0 until values.length()) {
            val entry = values.getJSONObject(i)
            val pct   = entry.optInt("value", 0)
            val ts    = entry.optLong("timestamp", 0L)
            if (pct > 0 && ts > 0) {
                val instant = Instant.ofEpochSecond(ts)
                records.add(OxygenSaturationRecord(
                    time           = instant,
                    percentage     = Percentage(pct.toDouble()),
                    zoneOffset     = zone.rules.getOffset(instant),
                    metadata       = metadata("uh_spo2_${dateStr}_$ts")
                ))
            }
        }
        if (records.isEmpty()) return false

        client.insertRecords(records)
        Log.d(TAG, "Wrote ${records.size} SpO2 samples for $dateStr")
        return true
    }

    /**
     * Write resting heart rate. UH's night_rhr object contains a 7-day
     * array of daily values; we write only today's (last entry).
     */
    private suspend fun writeRestingHR(obj: JSONObject, date: LocalDate, dateStr: String): Boolean {
        val values = obj.optJSONArray("values") ?: return false
        if (values.length() == 0) return false

        // Last entry = most recent (today)
        val last  = values.getJSONObject(values.length() - 1)
        val bpm   = last.optInt("value", 0)
        val ts    = last.optLong("timestamp", 0L)
        if (bpm <= 0 || ts <= 0) return false

        val zone    = ZoneId.systemDefault()
        val instant = Instant.ofEpochSecond(ts)
        val record  = RestingHeartRateRecord(
            time           = instant,
            beatsPerMinute = bpm.toLong(),
            zoneOffset     = zone.rules.getOffset(instant),
            metadata       = metadata("uh_rhr_$dateStr")
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Wrote RHR: $bpm bpm for $dateStr")
        return true
    }

    /**
     * Write VO2 Max. Single scalar value from UH.
     */
    private suspend fun writeVo2Max(obj: JSONObject, date: LocalDate, dateStr: String): Boolean {
        val value = obj.optDouble("value", 0.0)
        if (value <= 0.0) return false

        val zone    = ZoneId.systemDefault()
        val instant = date.atTime(12, 0).atZone(zone).toInstant() // noon as nominal time
        val record  = Vo2MaxRecord(
            time                              = instant,
            vo2MillilitersPerMinuteKilogram   = value,
            measurementMethod                 = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
            zoneOffset                        = zone.rules.getOffset(instant),
            metadata                          = metadata("uh_vo2max_$dateStr")
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Wrote VO2 Max: $value for $dateStr")
        return true
    }

    /**
     * Write sleep session with full stage breakdown.
     * Uses the sleep_graph.data array for precise stage timestamps —
     * these are the server-computed values, not raw BLE inference.
     *
     * Stage mapping: UH type → HC SleepSessionRecord stage constant
     *   "deep_sleep"  → STAGE_TYPE_DEEP
     *   "light_sleep" → STAGE_TYPE_LIGHT
     *   "rem_sleep"   → STAGE_TYPE_REM
     *   "awake"       → STAGE_TYPE_AWAKE
     */
    private suspend fun writeSleep(obj: JSONObject, date: LocalDate, dateStr: String): Boolean {
        val bedtimeStart = obj.optLong("bedtime_start", 0L)
        val bedtimeEnd   = obj.optLong("bedtime_end",   0L)
        if (bedtimeStart <= 0 || bedtimeEnd <= 0) return false

        val zone         = ZoneId.systemDefault()
        val sessionStart = Instant.ofEpochSecond(bedtimeStart)
        val sessionEnd   = Instant.ofEpochSecond(bedtimeEnd)

        // Build stage list from sleep_graph.data
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        val graph  = obj.optJSONObject("sleep_graph")?.optJSONArray("data")
        if (graph != null) {
            for (i in 0 until graph.length()) {
                val entry     = graph.getJSONObject(i)
                val stageType = entry.optString("type", "")
                // Clamp to session bounds — UH graph can overshoot bedtime_end by
                // a few seconds, which causes SleepSessionRecord to throw.
                val stageStart = entry.optLong("start", 0L).coerceAtLeast(bedtimeStart)
                val stageEnd   = entry.optLong("end",   0L).coerceAtMost(bedtimeEnd)
                if (stageStart <= 0 || stageEnd <= 0 || stageEnd <= stageStart) continue

                val hcStage = when (stageType) {
                    "deep_sleep"  -> SleepSessionRecord.STAGE_TYPE_DEEP
                    "light_sleep" -> SleepSessionRecord.STAGE_TYPE_LIGHT
                    "rem_sleep"   -> SleepSessionRecord.STAGE_TYPE_REM
                    "awake"       -> SleepSessionRecord.STAGE_TYPE_AWAKE
                    else          -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
                }

                stages.add(SleepSessionRecord.Stage(
                    startTime = Instant.ofEpochSecond(stageStart),
                    endTime   = Instant.ofEpochSecond(stageEnd),
                    stage     = hcStage
                ))
            }
        }

        val record = SleepSessionRecord(
            startTime       = sessionStart,
            endTime         = sessionEnd,
            startZoneOffset = zone.rules.getOffset(sessionStart),
            endZoneOffset   = zone.rules.getOffset(sessionEnd),
            stages          = stages,
            metadata        = metadata("uh_sleep_$dateStr")
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Wrote sleep session ${stages.size} stages for $dateStr " +
                "(${sessionStart} → ${sessionEnd})")
        return true
    }

    // ── Permission check ────────────────────────────────────────────────────

    suspend fun hasWritePermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(writePermissions)
    }
}

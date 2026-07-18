package com.fitdash

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class DriveBackupManager(private val context: Context) {

    companion object {
        private const val TAG              = "FitDash:Drive"
        private const val BACKUP_FILE_NAME = "fitdash_settings_backup.json"
        private const val MIME_JSON        = "application/json"
        private const val APP_NAME         = "FitDash"

        // SharedPrefs keys
        private const val PREFS_NAME            = "fitdash_drive"
        const val PREF_LAST_BACKUP_TIME         = "last_backup_time"
        const val PREF_LAST_RESTORE_TIME        = "last_restore_time"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Auth ─────────────────────────────────────────────────────────────────────

    fun getSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    private fun buildDriveService(account: GoogleSignInAccount): Drive? {
        return try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_APPDATA)
            ).apply { selectedAccount = account.account }

            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName(APP_NAME).build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build Drive service", e)
            null
        }
    }

    // ── Prefs helpers ────────────────────────────────────────────────────────────

    fun getLastBackupTime(): Long  = prefs.getLong(PREF_LAST_BACKUP_TIME, 0L)
    fun getLastRestoreTime(): Long = prefs.getLong(PREF_LAST_RESTORE_TIME, 0L)

    fun formatTime(epochMs: Long): String {
        if (epochMs == 0L) return "Never"
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    // ── Backup ───────────────────────────────────────────────────────────────────

    /**
     * Serializes FitDash settings to JSON and uploads to Drive appdata folder.
     * Settings include: UltraSignal API token, sync interval.
     * Creates the file if it doesn't exist; replaces it if it does.
     * Returns true on success.
     */
    suspend fun backup(): Boolean = withContext(Dispatchers.IO) {
        val account = getSignedInAccount() ?: run {
            Log.w(TAG, "Backup skipped — no signed-in account")
            return@withContext false
        }
        val drive = buildDriveService(account) ?: return@withContext false

        try {
            val json = serializeSettings()
            val content = ByteArrayContent.fromString(MIME_JSON, json)

            val existingId = findBackupFileId(drive)
            if (existingId != null) {
                drive.files().update(existingId, null, content).execute()
                Log.d(TAG, "Settings backup updated")
            } else {
                val meta = File().apply {
                    name    = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                drive.files().create(meta, content).execute()
                Log.d(TAG, "Settings backup created")
            }

            prefs.edit().putLong(PREF_LAST_BACKUP_TIME, System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            false
        }
    }

    // ── Restore ──────────────────────────────────────────────────────────────────

    /**
     * Downloads settings backup from Drive and applies them directly:
     * UltraSignal token and sync interval are written to their respective prefs.
     * Returns true if restore succeeded, false otherwise.
     */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val account = getSignedInAccount() ?: run {
            Log.w(TAG, "Restore skipped — no signed-in account")
            return@withContext false
        }
        val drive = buildDriveService(account) ?: return@withContext false

        try {
            val fileId = findBackupFileId(drive) ?: run {
                Log.w(TAG, "No backup file found on Drive")
                return@withContext false
            }

            val out = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(out)
            val json = JSONObject(out.toString("UTF-8"))

            // Apply token
            val token = json.optString("ultraSignalToken", "")
            if (token.isNotBlank()) {
                UltraSignalSyncer(context).setApiToken(token)
            }

            // Apply sync interval
            val interval = json.optInt("syncIntervalMinutes", 30)
            context.getSharedPreferences("fitdash_settings", Context.MODE_PRIVATE)
                .edit().putInt("sync_interval_minutes", interval).apply()

            prefs.edit().putLong(PREF_LAST_RESTORE_TIME, System.currentTimeMillis()).apply()
            Log.d(TAG, "Settings restore succeeded — token=${token.take(10)}… interval=${interval}m")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            false
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun findBackupFileId(drive: Drive): String? {
        val result = drive.files().list()
            .setSpaces("appDataFolder")
            .setFields("files(id, name)")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .execute()
        return result.files?.firstOrNull()?.id
    }

    /**
     * Serialize FitDash settings to JSON for Drive backup.
     */
    private fun serializeSettings(): String {
        val token = UltraSignalSyncer(context).getApiToken() ?: ""
        val interval = context.getSharedPreferences("fitdash_settings", Context.MODE_PRIVATE)
            .getInt("sync_interval_minutes", 30)
        return JSONObject().apply {
            put("schemaVersion",       2)
            put("backupTime",          System.currentTimeMillis())
            put("ultraSignalToken",    token)
            put("syncIntervalMinutes", interval)
        }.toString()
    }
}

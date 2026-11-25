// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.ui.MainActivity
import java.io.File
import kotlin.concurrent.thread

/** Update check state shown in Settings. */
sealed interface UpdatePhase {
    object Idle : UpdatePhase
    object Checking : UpdatePhase
    object UpToDate : UpdatePhase
    data class Available(val info: Updater.Check) : UpdatePhase
    data class Error(val message: String) : UpdatePhase
}

/** Download state of the update APK. */
sealed interface UpdateDownload {
    object Idle : UpdateDownload
    data class Running(val received: Long, val total: Long) : UpdateDownload
    data class Done(val file: File) : UpdateDownload
    data class Failed(val message: String) : UpdateDownload
}

/** Checks for updates (on launch, periodically from BleService, or on demand) and notifies. */
object UpdateChecker {
    const val CHANNEL = "gt_bms_update"
    const val NOTIF_AVAILABLE = 3001
    const val EXTRA_OPEN_UPDATES = "open_updates"

    val phase = MutableLiveData<UpdatePhase>(UpdatePhase.Idle)
    @Volatile private var running = false

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /**
     * Silent periodic check (on launch, from BleService and from the background alarm):
     * at most once per chosen interval; notifies only when a newer version exists.
     */
    fun checkIfDue(ctx: Context, onDone: (() -> Unit)? = null) {
        val prefs = AppPreferences(ctx)
        val due = com.vanaccess.bms.BuildConfig.SELF_UPDATE && prefs.autoCheckUpdates &&
            System.currentTimeMillis() - prefs.lastUpdateCheck >= prefs.updateIntervalH * 3_600_000L - 5 * 60_000L
        if (due) check(ctx, userInitiated = false, onDone) else onDone?.invoke()
    }

    /**
     * Background check even when GT-BMS is closed: an inexact repeating alarm (battery friendly,
     * no exact-alarm permission) every interval; re-armed after reboot by [UpdateAlarmReceiver].
     */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, 20, Intent(app, UpdateAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val prefs = AppPreferences(app)
        if (!com.vanaccess.bms.BuildConfig.SELF_UPDATE || !prefs.autoCheckUpdates) return
        val every = prefs.updateIntervalH * 3_600_000L
        am.setInexactRepeating(android.app.AlarmManager.RTC, System.currentTimeMillis() + every, every, pi)
    }

    fun check(ctx: Context, userInitiated: Boolean, onDone: (() -> Unit)? = null) {
        if (!com.vanaccess.bms.BuildConfig.SELF_UPDATE || running) { onDone?.invoke(); return }
        running = true
        val app = ctx.applicationContext
        val prefs = AppPreferences(app)
        if (userInitiated) phase.postValue(UpdatePhase.Checking)
        thread(name = "gt-bms-update-check") {
            try {
                val r = Updater.check(app, prefs.allowPrerelease)
                prefs.lastUpdateCheck = System.currentTimeMillis()
                when {
                    !r.ok -> if (userInitiated) phase.postValue(UpdatePhase.Error(r.error ?: "failed"))
                    r.updateAvailable -> {
                        phase.postValue(UpdatePhase.Available(r))
                        // Background checks notify once per new version (no daily nagging)
                        if (!userInitiated && prefs.lastNotifiedVersion != r.version) {
                            notifyAvailable(app, r)
                            prefs.lastNotifiedVersion = r.version
                        }
                    }
                    else -> if (userInitiated) phase.postValue(UpdatePhase.UpToDate)
                }
            } catch (_: Exception) {
            } finally { running = false; onDone?.invoke() }
        }
    }

    private fun notifyAvailable(ctx: Context, r: Updater.Check) {
        createChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 10,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.update_available_title, r.version))
            .setContentText(ctx.getString(R.string.update_available_text))
            .setContentIntent(open)
            .setAutoCancel(true)
        r.asset?.let { a ->
            val dl = PendingIntent.getForegroundService(ctx, 11, UpdateService.startIntent(ctx, a),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, ctx.getString(R.string.update_download_install), dl)
        }
        try { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_AVAILABLE, b.build()) }
        catch (_: SecurityException) {}
    }
}

/** Fired by the periodic alarm, and after a reboot to re-arm it. */
class UpdateAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateChecker.schedule(ctx)
            com.vanaccess.bms.ble.ChargeTimer.rearm(com.vanaccess.bms.data.AppLanguage.wrap(ctx)); return
        }
        val pending = goAsync()
        UpdateChecker.checkIfDue(ctx) { pending.finish() }
    }
}

/**
 * Downloads the update APK in a foreground service so it survives leaving the app;
 * resumes from the `.part` file if interrupted. When done, the notification opens the
 * system installer.
 */
class UpdateService : Service() {

    // Notifications and alarm texts in the language chosen in Settings (Android < 13)
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.vanaccess.bms.data.AppLanguage.wrap(base))
    }

    @Volatile private var cancelRequested = false
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateChecker.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelRequested = true
                state.postValue(UpdateDownload.Idle)
                finish()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)
        if (name.isBlank() || url.isBlank() || busy) { if (!busy) stopSelf(); return START_NOT_STICKY }

        busy = true
        cancelRequested = false
        getSystemService(NotificationManager::class.java).cancel(UpdateChecker.NOTIF_AVAILABLE)
        startForegroundCompat(buildNotification(0, ongoing = true))
        thread(name = "gt-bms-update-dl") {
            state.postValue(UpdateDownload.Running(0, size))
            var lastNotified = 0L
            Updater.download(applicationContext, Updater.Asset(name, size, url), { cancelRequested }) { rec, total ->
                state.postValue(UpdateDownload.Running(rec, total))
                val now = System.currentTimeMillis()
                if (now - lastNotified >= 1000) {
                    lastNotified = now
                    notify(buildNotification(if (total > 0) (rec * 100 / total).toInt() else 0, ongoing = true))
                }
            }.fold(
                onSuccess = { file ->
                    state.postValue(UpdateDownload.Done(file))
                    notify(buildNotification(100, ongoing = false, done = file))
                },
                onFailure = { t ->
                    if (cancelRequested) state.postValue(UpdateDownload.Idle) else {
                        val msg = t.message ?: t::class.java.simpleName
                        state.postValue(UpdateDownload.Failed(msg))
                        notify(buildNotification(0, ongoing = false, error = msg))
                    }
                },
            )
            busy = false
            finish()
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(percent: Int, ongoing: Boolean, done: File? = null, error: String? = null): Notification {
        val open = if (done != null)
            PendingIntent.getActivity(this, 12, Updater.installIntent(this, done),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else PendingIntent.getActivity(this, 13,
            Intent(this, MainActivity::class.java).putExtra(UpdateChecker.EXTRA_OPEN_UPDATES, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when {
            error != null -> getString(R.string.update_failed)
            done != null -> getString(R.string.update_ready)
            else -> getString(R.string.update_downloading)
        }
        return NotificationCompat.Builder(this, UpdateChecker.CHANNEL)
            .setContentTitle(title)
            .setContentText(error ?: if (done != null) getString(R.string.update_tap_install) else null)
            .setSmallIcon(if (done != null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) {
                    setProgress(100, percent.coerceIn(0, 100), percent <= 0)
                    addAction(0, getString(R.string.btn_cancel), PendingIntent.getService(this@UpdateService, 14,
                        Intent(this@UpdateService, UpdateService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE))
                }
            }
            .build()
    }

    private fun notify(n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) } catch (_: Exception) {}
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val NOTIF_ID = 3002
        const val ACTION_START = "com.gnutux.gtbms.UPDATE_START"
        const val ACTION_STOP = "com.gnutux.gtbms.UPDATE_STOP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SIZE = "size"

        val state = MutableLiveData<UpdateDownload>(UpdateDownload.Idle)

        fun startIntent(context: Context, asset: Updater.Asset): Intent =
            Intent(context, UpdateService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_NAME, asset.name).putExtra(EXTRA_URL, asset.url).putExtra(EXTRA_SIZE, asset.size)

        fun start(context: Context, asset: Updater.Asset) {
            state.value = UpdateDownload.Idle
            ContextCompat.startForegroundService(context, startIntent(context, asset))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UpdateService::class.java).setAction(ACTION_STOP))
        }
    }
}

package com.spkprac.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.spkprac.app.MainActivity
import com.spkprac.app.R

/**
 * Keeps a live practice/chat session alive while the app is in the background and shows the
 * ongoing notification with a system chronometer (v1.3.0 spec §1).
 *
 * The service is deliberately dumb: it owns no session state and only mirrors the ViewModel, which
 * starts it once the realtime session is actually connected and stops it when the session ends.
 * Starting twice is harmless (the notification is updated in place), and stopping a service that
 * is not running is a no-op, so both entry points are idempotent.
 */
class PracticeService : Service() {

    companion object {
        const val ACTION_START = "com.spkprac.app.action.PRACTICE_START"
        const val ACTION_STOP = "com.spkprac.app.action.PRACTICE_STOP"

        /** Already-localized display name, e.g. "口语练习" / "Chat". */
        const val EXTRA_MODE = "mode"

        /** `System.currentTimeMillis()` of the session start; the notification chronometers from it. */
        const val EXTRA_STARTED_AT = "startedAt"

        private const val CHANNEL_ID = "practice"
        private const val NOTIFICATION_ID = 0x5052 // "PR"

        /**
         * Starts (or refreshes) the ongoing notification. Safe to call from the background-friendly
         * ViewModel: any failure to promote the service (e.g. Android 12+ background-start limits or
         * a revoked microphone permission) is swallowed so the practice session itself never breaks.
         */
        fun start(context: Context, modeLabel: String, startedAt: Long) {
            val intent = Intent(context, PracticeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, modeLabel)
                putExtra(EXTRA_STARTED_AT, startedAt)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * Stops the service and removes the notification. Uses [Context.stopService] instead of an
         * ACTION_STOP start intent because `startService` is not allowed from the background on
         * API 26+; stopping is always allowed and is a no-op when the service is not running.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PracticeService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSafely(intent)

            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }

            // A null intent means the system/process tried to recreate the service: never resurrect a
            // ghost "练习中" notification without a session behind it.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    /** `startForeground()` must happen well inside the 5 second ANR window: everything here is cheap. */
    private fun startSafely(intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
            .ifBlank { getString(R.string.svc_notification_default_mode) }
        val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, 0L)
            .takeIf { it > 0L } ?: System.currentTimeMillis()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(mode, startedAt),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (_: Exception) {
            // Most likely a revoked RECORD_AUDIO / FGS restriction. Drop the notification silently: the
            // session keeps working in the foreground, it just loses the background notification.
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun buildNotification(modeLabel: String, startedAt: Long): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                // Same launch semantics as tapping the launcher icon; MainActivity is singleTask, so the
                // existing task is brought forward instead of a second copy being created.
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_practice)
            .setContentTitle(getString(R.string.svc_notification_title))
            .setContentText(modeLabel)
            .setContentIntent(contentIntent)
            // The elapsed time is rendered by the system chronometer: no per-second updates needed.
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.svc_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.svc_channel_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }
}

package com.mink.guardian

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-global seam so the foreground service and the periodic worker can
 * reach the live [GuardianController] without depending on the application
 * class. The controller sets this in its constructor.
 */
object GuardianServiceHost {
    @Volatile
    var controller: GuardianController? = null
}

/**
 * Foreground service (specialUse) that keeps the guardian process alive so the
 * model stays resident and sweeps keep running while the guardian is enabled.
 * It shows a quiet ongoing notification and posts alert notifications for the
 * findings worth interrupting for.
 */
class GuardianService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        startForegroundSafely()
        startSweepLoop()
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val notification = ongoingNotification(this)
        runCatching {
            // specialUse foreground service type is only valid on API 34+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ONGOING_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(ONGOING_ID, notification)
            }
        }
    }

    private fun startSweepLoop() {
        scope.launch {
            while (isActive) {
                delay(SWEEP_LOOP_MS)
                runCatching { GuardianServiceHost.controller?.sweepNow() }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ONGOING_ID = 4201
        private const val CHANNEL_ONGOING = "mink.guardian.ongoing"
        private const val CHANNEL_ALERTS = "mink.guardian.alerts"
        private const val SWEEP_LOOP_MS = 30L * 60L * 1000L

        private fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ONGOING,
                        "Guardian status",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Shows that Mink is quietly watching this device." },
                )
            }
            if (manager.getNotificationChannel(CHANNEL_ALERTS) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ALERTS,
                        "Guardian alerts",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "What Mink notices about your privacy exposure." },
                )
            }
        }

        private fun ongoingNotification(context: Context): Notification =
            NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setContentTitle("Mink is watching")
                .setContentText("Reading what this device exposes, all on device.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        /**
         * Post an alert as a notification. Safe to call from anywhere; it
         * creates the channel first and silently no-ops if the post permission
         * is missing.
         */
        @SuppressLint("MissingPermission")
        fun postAlertNotification(context: Context, alert: GuardianAlert) {
            ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(
                    if (alert.level == AlertLevel.CRITICAL) {
                        NotificationCompat.PRIORITY_HIGH
                    } else {
                        NotificationCompat.PRIORITY_DEFAULT
                    },
                )
                .setAutoCancel(true)
                .build()
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(alert.id.hashCode(), notification)
            }
        }
    }
}

/**
 * WorkManager backstop for sweeps. The foreground service drives sweeps while
 * it is alive; this periodic worker keeps them going if the process was reaped.
 */
class GuardianSweepWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { GuardianServiceHost.controller?.sweepNow() }
        return Result.success()
    }
}

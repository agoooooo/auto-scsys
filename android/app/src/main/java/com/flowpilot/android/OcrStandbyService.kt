package com.flowpilot.android

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

internal class OcrStandbyService : Service() {
    private var captureSession: ScreenCaptureSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCaptureSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (captureSession != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let { parcelableExtra<Intent>(it, EXTRA_RESULT_DATA) }
        if (resultData == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        startForegroundOcrStandby()
        try {
            val session = ScreenCaptureSession(this, resultCode, resultData) {
                onProjectionStopped()
            }
            captureSession = session
            activeSession = session
            persistActive(true)
            notifyStateChanged()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("持续 OCR 待命中", ongoing = true))
        } catch (exception: Exception) {
            persistActive(false)
            persistError(exception.message ?: "无法启动屏幕捕获会话")
            notifyStateChanged()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCaptureSession()
        super.onDestroy()
    }

    private fun onProjectionStopped() {
        val stoppedSession = captureSession
        activeSession = null
        captureSession = null
        stoppedSession?.close()
        persistActive(false)
        notifyStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCaptureSession() {
        val session = captureSession
        captureSession = null
        if (activeSession === session) activeSession = null
        persistActive(false)
        notifyStateChanged()
        session?.close()
    }

    private fun startForegroundOcrStandby() {
        val foregroundNotification = notification("正在准备 OCR 待命会话", ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                foregroundNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, foregroundNotification)
        }
    }

    private fun notification(message: String, ongoing: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT,
            Intent(this, OcrStandbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FlowPilot OCR")
            .setContentText(message)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OCR 待命", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun persistActive(active: Boolean) {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, active)
            .apply()
    }

    private fun persistError(message: String) {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_ERROR, message)
            .apply()
    }

    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private inline fun <reified T> parcelableExtra(intent: Intent, key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, T::class.java) else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }

    companion object {
        const val ACTION_STOP = "com.flowpilot.android.action.STOP_OCR_STANDBY"
        const val ACTION_STATE_CHANGED = "com.flowpilot.android.action.OCR_STANDBY_CHANGED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "flowpilot_ocr_standby"
        const val NOTIFICATION_ID = 33
        private const val STOP_PENDING_INTENT = 330
        private const val PREFERENCES = "flowpilot_ocr_standby"
        private const val KEY_ACTIVE = "active"
        private const val KEY_ERROR = "last_error"

        @Volatile
        private var activeSession: ScreenCaptureSession? = null

        fun captureSession(): ScreenCaptureSession? = activeSession

        fun isActive(context: Context): Boolean {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val active = preferences.getBoolean(KEY_ACTIVE, false) && activeSession != null
            if (!active && preferences.getBoolean(KEY_ACTIVE, false)) {
                preferences.edit().putBoolean(KEY_ACTIVE, false).apply()
            }
            return active
        }

        fun consumeLastError(context: Context): String? {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val error = preferences.getString(KEY_ERROR, null)
            if (error != null) preferences.edit().remove(KEY_ERROR).apply()
            return error
        }

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, OcrStandbyService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OcrStandbyService::class.java).setAction(ACTION_STOP))
        }
    }
}

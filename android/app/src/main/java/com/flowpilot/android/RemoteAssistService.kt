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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RemoteAssistService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var captureSession: ScreenCaptureSession? = null
    private var sessionId: String? = null
    private var deviceTicket: String? = null
    private var apiBaseUrl: String? = null
    private var ending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endSession(startId, "设备用户结束远程协助")
            return START_NOT_STICKY
        }
        if (sessionJob?.isActive == true) return START_NOT_STICKY

        val activeSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val ticket = intent?.getStringExtra(EXTRA_DEVICE_TICKET)
        val baseUrl = intent?.getStringExtra(EXTRA_API_BASE_URL)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let { parcelableExtra<Intent>(it, EXTRA_RESULT_DATA) }
        val requester = intent?.getStringExtra(EXTRA_REQUESTER_NAME).orEmpty()
        if (activeSessionId.isNullOrBlank() || ticket.isNullOrBlank() || baseUrl.isNullOrBlank() ||
            resultCode == 0 || resultData == null
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        sessionId = activeSessionId
        deviceTicket = ticket
        apiBaseUrl = baseUrl
        ensureNotificationChannel()
        startForegroundSession(requester)
        sessionJob = serviceScope.launch {
            try {
                val capture = ScreenCaptureSession(this@RemoteAssistService, resultCode, resultData) {
                    if (!ending) endSession(startId, "Android 屏幕共享授权已结束")
                }
                captureSession = capture
                runSession(activeSessionId, ticket, capture)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateNotification("远程协助已停止：${exception.message ?: "连接失败"}")
                endSession(startId, exception.message ?: "远程协助通道异常")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ending = true
        captureSession?.close()
        captureSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runSession(
        activeSessionId: String,
        ticket: String,
        capture: ScreenCaptureSession,
    ) {
        var inputSequence = 0L
        var failures = 0
        while (serviceScope.isActive && !ending) {
            try {
                val frame = withContext(Dispatchers.IO) { capture.captureJpeg() }
                withContext(Dispatchers.IO) {
                    ApiClient(requireNotNull(apiBaseUrl)).publishRemoteAssistFrame(activeSessionId, ticket, frame)
                }
                val inputs = withContext(Dispatchers.IO) {
                    ApiClient(requireNotNull(apiBaseUrl)).remoteAssistInputs(
                        activeSessionId,
                        ticket,
                        inputSequence,
                    )
                }
                for (input in inputs) {
                    if (input.seq <= inputSequence) continue
                    val accepted = DouyinAccessibilityService.requireInstance().dispatchRemoteInput(input)
                    if (!accepted) throw IllegalStateException("Android 未能执行远程操作")
                    inputSequence = input.seq
                }
                failures = 0
                delay(FRAME_INTERVAL_MILLIS)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                if (exception.statusCode in setOf(401, 409, 410, 413, 415)) {
                    throw exception
                }
                failures += 1
                updateNotification("连接暂时中断，正在重试（$failures/5）")
                if (failures >= MAX_TRANSIENT_FAILURES) throw exception
                delay(RETRY_INTERVAL_MILLIS)
            } catch (exception: Exception) {
                failures += 1
                updateNotification("连接暂时中断，正在重试（$failures/5）")
                if (failures >= MAX_TRANSIENT_FAILURES) throw exception
                delay(RETRY_INTERVAL_MILLIS)
            }
        }
    }

    private fun startForegroundSession(requester: String) {
        val notification = notification(requester, "屏幕画面正在实时共享", ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(requester: String, status: String, ongoing: Boolean): Notification {
        val stop = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT,
            Intent(this, RemoteAssistService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            OPEN_PENDING_INTENT,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("远程协助进行中 · $requester")
            .setContentText(status)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "立即结束", stop)
            .build()
    }

    private fun updateNotification(message: String) {
        val requester = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_REQUESTER, "远程协助者").orEmpty()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(requester, message, ongoing = true))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "远程协助", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun endSession(startId: Int, reason: String) {
        if (ending) return
        ending = true
        updateNotification("正在结束协助：$reason")
        sessionJob?.cancel()
        captureSession?.close()
        captureSession = null
        val activeSessionId = sessionId
        val ticket = deviceTicket
        val baseUrl = apiBaseUrl
        serviceScope.launch {
            try {
                if (activeSessionId != null && ticket != null && baseUrl != null) {
                    withContext(Dispatchers.IO) {
                        ApiClient(baseUrl).endRemoteAssistSessionWithTicket(activeSessionId, ticket)
                    }
                }
            } catch (_: Exception) {
                updateNotification("协助已停止；服务端将在会话到期后撤销连接")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private inline fun <reified T> parcelableExtra(intent: Intent, key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, T::class.java) else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }

    companion object {
        const val ACTION_STOP = "com.flowpilot.android.action.STOP_REMOTE_ASSIST"
        const val CHANNEL_ID = "flowpilot_remote_assist"
        const val NOTIFICATION_ID = 34
        const val EXTRA_SESSION_ID = "remote_session_id"
        const val EXTRA_DEVICE_TICKET = "remote_session_device_ticket"
        const val EXTRA_API_BASE_URL = "remote_session_api_base_url"
        const val EXTRA_REQUESTER_NAME = "remote_session_requester_name"
        const val EXTRA_RESULT_CODE = "remote_session_result_code"
        const val EXTRA_RESULT_DATA = "remote_session_result_data"
        private const val STOP_PENDING_INTENT = 340
        private const val OPEN_PENDING_INTENT = 341
        private const val PREFERENCES = "flowpilot_remote_assist"
        private const val KEY_REQUESTER = "requester"
        private const val FRAME_INTERVAL_MILLIS = 700L
        private const val RETRY_INTERVAL_MILLIS = 1_000L
        private const val MAX_TRANSIENT_FAILURES = 5

        fun start(
            context: Context,
            session: RemoteAssistSession,
            ticket: String,
            apiBaseUrl: String,
            resultCode: Int,
            resultData: Intent,
        ) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY_REQUESTER, session.requestedByName)
                .apply()
            val intent = Intent(context, RemoteAssistService::class.java)
                .putExtra(EXTRA_SESSION_ID, session.id)
                .putExtra(EXTRA_DEVICE_TICKET, ticket)
                .putExtra(EXTRA_API_BASE_URL, apiBaseUrl)
                .putExtra(EXTRA_REQUESTER_NAME, session.requestedByName)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RemoteAssistService::class.java).setAction(ACTION_STOP))
        }
    }
}

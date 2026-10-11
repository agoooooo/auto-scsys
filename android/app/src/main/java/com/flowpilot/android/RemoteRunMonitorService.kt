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

internal class RemoteRunMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            pollJob?.cancel()
            setMonitoring(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (pollJob?.isActive == true) return START_STICKY

        ensureNotificationChannel()
        setMonitoring(true)
        val notification = monitorNotification("正在等待远程运行请求", ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        pollJob = serviceScope.launch { pollRemoteRuns(startId) }
        return START_STICKY
    }

    override fun onDestroy() {
        setMonitoring(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        pollJob?.cancel()
        setMonitoring(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private suspend fun pollRemoteRuns(startId: Int) {
        try {
            while (serviceScope.isActive) {
                try {
                    val session = freshSession()
                    val deviceId = session.deviceId
                        ?: throw IllegalStateException("当前账号尚未绑定此设备")
                    val requests = withContext(Dispatchers.IO) {
                        ApiClient(session.apiBaseUrl).also { client ->
                            client.heartbeat(session.token, deviceId)
                        }.pendingRemoteRuns(session.token, deviceId)
                    }
                    requests.forEach { request ->
                        val requiresCapture = requiresScreenCapture(request.capabilities)
                        val captureReady = OcrStandbyService.isActive(this@RemoteRunMonitorService)
                        val canStartFromMonitor = !requiresCapture || captureReady
                        val hasRunnableRequest = request.status == "pending" ||
                            (request.status == "running" && request.runId != null)
                        val accessibilityReady = !requiresAccessibility(request.capabilities) ||
                            DouyinAccessibilityService.isEnabled(this@RemoteRunMonitorService)
                        if (hasRunnableRequest && !accessibilityReady &&
                            !isRequestHandled(this@RemoteRunMonitorService, request.id)
                        ) {
                            showRemoteNotification(
                                request,
                                "需要启用无障碍服务：${request.taskName}",
                                "打开 FlowPilot 并开启无障碍服务；之后任务会直接运行",
                            )
                        } else if (hasRunnableRequest && !canStartFromMonitor &&
                            !isRequestHandled(this@RemoteRunMonitorService, request.id)
                        ) {
                            showRemoteNotification(
                                request,
                                "需要屏幕捕获授权：${request.taskName}",
                                "打开 FlowPilot 并在 Android 系统弹窗中授权；任务无需额外确认",
                            )
                        } else if (hasRunnableRequest && canStartFromMonitor &&
                            canStartOcrStandby(RunStatusStore(this@RemoteRunMonitorService).snapshot().status) &&
                            accessibilityReady &&
                            claimRequest(this@RemoteRunMonitorService, request.id)
                        ) {
                            var runId = request.runId
                            try {
                                if (request.status == "pending") {
                                    runId = ApiClient(session.apiBaseUrl).approveRemoteRun(
                                        session.token,
                                        request.id,
                                    )
                                }
                                if (runId == null) {
                                    throw IllegalStateException("服务器没有返回运行记录 ID")
                                }
                                TaskExecutionService.start(
                                    context = this@RemoteRunMonitorService,
                                    task = AvailableTask(
                                        id = request.taskId,
                                        name = request.taskName,
                                        description = "",
                                        version = request.version,
                                        capabilities = request.capabilities,
                                        source = request.source,
                                    ),
                                    runId = runId,
                                    captureResultCode = null,
                                    captureResultData = null,
                                    deviceId = deviceId,
                                )
                                markHandled(request.id)
                            } catch (exception: Exception) {
                                if (runId != null) {
                                    try {
                                        ApiClient(session.apiBaseUrl).updateRun(
                                            session.token,
                                            runId,
                                            "failure",
                                            "无法直接启动远程任务：${exception.message ?: "Android 不允许后台启动"}",
                                        )
                                        markHandled(request.id)
                                    } catch (reportException: Exception) {
                                        updateOngoingNotification(
                                            "无法启动或记录远程任务：${reportException.message ?: "服务端不可用"}",
                                        )
                                        releaseRequestClaim(request.id)
                                    }
                                } else {
                                    releaseRequestClaim(request.id)
                                    if (exception.message?.contains("过期") == true ||
                                        exception.message?.contains("已处理") == true
                                    ) {
                                        markHandled(request.id)
                                    }
                                    updateOngoingNotification(
                                        "无法直接启动${request.taskName}：${exception.message ?: "服务器不可用"}",
                                    )
                                }
                            }
                        } else if (request.status == "pending" &&
                            !isRequestHandled(this@RemoteRunMonitorService, request.id)
                        ) {
                            cancelRemoteNotification(request.id)
                        }
                    }
                    updateOngoingNotification("远程待命中 · ${requests.size} 个请求")
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    updateOngoingNotification("连接失败，将重试：${exception.message ?: "未知错误"}")
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private suspend fun freshSession(): StoredSession = withContext(Dispatchers.IO) {
        val store = SessionStore(this@RemoteRunMonitorService)
        val session = store.read() ?: throw IllegalStateException("登录状态已失效，请重新登录")
        if (!shouldRefreshAccessToken(
                session.tokenExpiresAt,
                System.currentTimeMillis() / 1000,
                REFRESH_LEAD_SECONDS,
            )
        ) {
            return@withContext session
        }
        val refreshed = ApiClient(session.apiBaseUrl).refreshToken(session.token)
        val expiresAt = System.currentTimeMillis() / 1000 + refreshed.second
        store.updateToken(refreshed.first, expiresAt)
        session.copy(token = refreshed.first, tokenExpiresAt = expiresAt)
    }

    private fun showRemoteNotification(request: RemoteRun, title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            request.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(request.id.hashCode(), notification)
    }

    private fun cancelRemoteNotification(requestId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(requestId.hashCode())
    }

    private fun markHandled(requestId: String) {
        markRequestHandled(this, requestId)
    }

    private fun updateOngoingNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, monitorNotification(message, ongoing = true))
    }

    private fun setMonitoring(active: Boolean) {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MONITORING, active)
            .apply()
    }

    private fun monitorNotification(message: String, ongoing: Boolean): Notification {
        val stop = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT,
            Intent(this, RemoteRunMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("FlowPilot 远程待命")
            .setContentText(message)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止待命", stop)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "远程运行待命", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.flowpilot.android.action.STOP_REMOTE_MONITOR"
        const val CHANNEL_ID = "flowpilot_remote_monitor"
        const val NOTIFICATION_ID = 31
        private const val STOP_PENDING_INTENT = 310
        private const val PREFERENCES = "flowpilot_runtime"
        private const val KEY_MONITORING = "remote_monitoring"
        private const val KEY_HANDLED_REQUESTS = "handled_remote_requests"
        private const val MAX_HANDLED_REQUESTS = 500
        private const val POLL_INTERVAL_MILLIS = 15_000L
        private const val REFRESH_LEAD_SECONDS = 5 * 60L
        private val inFlightRequests = mutableSetOf<String>()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteRunMonitorService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RemoteRunMonitorService::class.java).setAction(ACTION_STOP),
            )
        }

        @Synchronized
        fun claimRequest(context: Context, requestId: String): Boolean {
            if (requestId in inFlightRequests) return false
            if (requestId in context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getStringSet(KEY_HANDLED_REQUESTS, emptySet()).orEmpty()
            ) return false
            inFlightRequests.add(requestId)
            return true
        }

        @Synchronized
        fun isRequestAvailable(context: Context, requestId: String): Boolean =
            requestId !in inFlightRequests &&
                requestId !in context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getStringSet(KEY_HANDLED_REQUESTS, emptySet()).orEmpty()

        @Synchronized
        fun isRequestHandled(context: Context, requestId: String): Boolean =
            requestId in context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getStringSet(KEY_HANDLED_REQUESTS, emptySet()).orEmpty()

        @Synchronized
        fun releaseRequestClaim(requestId: String) {
            inFlightRequests.remove(requestId)
        }

        @Synchronized
        fun markRequestHandled(context: Context, requestId: String) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val handled = preferences.getStringSet(KEY_HANDLED_REQUESTS, emptySet()).orEmpty().toMutableSet()
            handled.add(requestId)
            inFlightRequests.remove(requestId)
            preferences.edit()
                .putStringSet(KEY_HANDLED_REQUESTS, handled.toList().takeLast(MAX_HANDLED_REQUESTS).toSet())
                .apply()
            context.getSystemService(Context.NOTIFICATION_SERVICE)
                .let { it as NotificationManager }
                .cancel(requestId.hashCode())
        }

        fun isMonitoring(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_MONITORING, false)
    }
}

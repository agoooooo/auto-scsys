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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class TaskExecutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private var executionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested.set(true)
            if (executionJob?.isActive != true) {
                stopSelf(startId)
            } else {
                setTaskNotification("停止请求已发送，将在脚本下一个检查点停止", ongoing = true)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_EXECUTE) return START_NOT_STICKY
        if (executionJob?.isActive == true) {
            rejectConcurrentRun(intent)
            return START_NOT_STICKY
        }

        stopRequested.set(false)
        startForegroundTask(intent.hasExtra(EXTRA_CAPTURE_RESULT_DATA))
        executionJob = serviceScope.launch {
            executeTask(intent, startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundTask(requiresMediaProjection: Boolean) {
        ensureNotificationChannel()
        val notification = taskNotification("正在准备任务…", ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (requiresMediaProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun executeTask(intent: Intent, startId: Int) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME).orEmpty().ifBlank { "自动化任务" }
        val taskVersion = intent.getStringExtra(EXTRA_TASK_VERSION)
        val store = RunStatusStore(this)
        var status = "failure"
        var summary = "任务未能完成"
        var capture: ScreenCaptureSession? = null
        var ownsCaptureSession = false
        var api: ApiClient? = null
        var activeSession: StoredSession? = null
        var heartbeatJob: Job? = null

        store.update(runId, taskName, taskVersion, "running", "正在执行")
        notifyRunStatusChanged()
        setTaskNotification("正在运行：$taskName", ongoing = true)

        try {
            delay(SERVICE_START_GRACE_PERIOD_MILLIS)
            val session = SessionStore(this).read()
                ?: throw IllegalStateException("登录状态已失效，请重新登录")
            activeSession = session
            api = ApiClient(session.apiBaseUrl)
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                ?: session.deviceId
                ?: throw IllegalStateException("当前账号尚未绑定此设备")
            heartbeatJob = serviceScope.launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                    try {
                        val heartbeatSession = freshSession()
                        ApiClient(heartbeatSession.apiBaseUrl).heartbeat(heartbeatSession.token, deviceId)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        RunStatusStore(this@TaskExecutionService).appendLog(
                            "设备心跳失败：${exception.message ?: "服务器不可用"}",
                        )
                    }
                }
            }
            val task = AvailableTask(
                id = intent.getStringExtra(EXTRA_TASK_ID).orEmpty(),
                name = taskName,
                description = "",
                version = taskVersion.orEmpty(),
                capabilities = emptyList(),
            )
            require(task.id.isNotBlank() && task.version.isNotBlank()) { "任务或版本信息不完整" }
            val resultCode = intent.getIntExtra(EXTRA_CAPTURE_RESULT_CODE, 0)
            val resultData = intent.parcelableExtra<Intent>(EXTRA_CAPTURE_RESULT_DATA)
            val declaredCapabilities = intent.getStringArrayExtra(EXTRA_TASK_CAPABILITIES)?.toList().orEmpty()
            if (requiresScreenCapture(declaredCapabilities)) {
                val standbyCapture = OcrStandbyService.captureSession()
                if (standbyCapture != null) {
                    capture = standbyCapture
                } else {
                    capture = resultData?.let { ScreenCaptureSession(this, resultCode, it) }
                        ?: throw IllegalStateException("OCR 待命会话已结束，请重新授权屏幕捕获")
                    ownsCaptureSession = true
                }
            }
            if (capture != null && !DouyinAccessibilityService.isEnabled(this)) {
                throw IllegalStateException("请先在系统设置中启用 FlowPilot 自动化操作服务")
            }
            if (requiresAccessibility(declaredCapabilities) && !DouyinAccessibilityService.isEnabled(this)) {
                throw IllegalStateException("此任务需要先启用 FlowPilot 无障碍服务")
            }

            val script = withContext(Dispatchers.IO) {
                val cache = ScriptCache(File(filesDir, SCRIPT_CACHE_DIRECTORY))
                val scope = ScriptCacheScope(session.apiBaseUrl, session.user.id, deviceId)
                cache.load(scope, task) ?: api.downloadScript(session.token, deviceId, task).also {
                    cache.save(scope, task, it)
                }
            }
            ensureNotStopped()
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            val result = PythonTaskRuntime.execute(
                script.source,
                NativeTaskBridge(this, capture, stopRequested),
            )
            ensureNotStopped()
            status = "success"
            summary = result.take(500)
        } catch (exception: CancellationException) {
            status = "stopped"
            summary = "用户停止了任务"
        } catch (exception: Exception) {
            status = if (stopRequested.get()) "stopped" else "failure"
            summary = if (stopRequested.get()) "用户停止了任务" else exception.message ?: "任务执行失败"
        } finally {
            heartbeatJob?.cancelAndJoin()
            try {
                if (ownsCaptureSession) capture?.close()
            } catch (exception: Exception) {
                if (status == "success") {
                    status = "failure"
                    summary = "关闭屏幕捕获时失败：${exception.message ?: "未知错误"}"
                }
            }
            val reporter = api ?: activeSession?.let { ApiClient(it.apiBaseUrl) }
            if (reporter != null && activeSession != null && runId.isNotBlank()) {
                try {
                    val serverSummary = RunResultSummary.forServer(status, summary)
                    val reportSession = freshSession()
                    withContext(Dispatchers.IO) {
                        reporter.updateRun(reportSession.token, runId, status, serverSummary)
                    }
                } catch (exception: Exception) {
                    summary = "$summary；运行结果同步失败：${exception.message ?: "服务器不可用"}"
                    if (status == "success") status = "failure"
                }
            }
            store.update(runId, taskName, taskVersion, status, summary)
            notifyRunStatusChanged()
            setTaskNotification("$taskName：${runStatusLabel(status)}", ongoing = false)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
    }

    private fun ensureNotStopped() {
        if (stopRequested.get()) throw CancellationException("任务已停止")
    }

    private suspend fun freshSession(): StoredSession = withContext(Dispatchers.IO) {
        val store = SessionStore(this@TaskExecutionService)
        val session = store.read() ?: throw IllegalStateException("登录状态已失效，请重新登录")
        if (!shouldRefreshAccessToken(
                session.tokenExpiresAt,
                System.currentTimeMillis() / 1000,
                TOKEN_REFRESH_LEAD_SECONDS,
            )
        ) {
            return@withContext session
        }
        val refreshed = ApiClient(session.apiBaseUrl).refreshToken(session.token)
        val expiresAt = System.currentTimeMillis() / 1000 + refreshed.second
        store.updateToken(refreshed.first, expiresAt)
        session.copy(token = refreshed.first, tokenExpiresAt = expiresAt)
    }

    private fun rejectConcurrentRun(intent: Intent) {
        serviceScope.launch {
            val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
            val taskName = intent.getStringExtra(EXTRA_TASK_NAME).orEmpty()
            val message = "设备上已有任务运行，本次任务未启动"
            try {
                val session = SessionStore(this@TaskExecutionService).read()
                    ?: throw IllegalStateException("登录状态已失效")
                ApiClient(session.apiBaseUrl).updateRun(session.token, runId, "failure", message)
            } catch (exception: Exception) {
                RunStatusStore(this@TaskExecutionService).appendLog(
                    "无法记录未启动任务 $taskName：${exception.message ?: "服务器不可用"}",
                )
            }
        }
    }

    private fun notifyRunStatusChanged() {
        sendBroadcast(Intent(ACTION_RUN_STATUS_CHANGED).setPackage(packageName))
    }

    private fun taskNotification(message: String, ongoing: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT,
            Intent(this, TaskExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("FlowPilot")
            .setContentText(message)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .also { builder ->
                if (ongoing) builder.addAction(0, "停止", stopIntent)
            }
            .build()
    }

    private fun setTaskNotification(message: String, ongoing: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, taskNotification(message, ongoing))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "任务运行", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private inner class NativeTaskBridge(
        private val context: Context,
        private val capture: ScreenCaptureSession?,
        private val stopRequested: AtomicBoolean,
    ) {
        private val clickGate = SingleClickGate()

        fun openDouyin(): Boolean {
            checkRunning()
            DouyinAccessibilityService.requireInstance().openDouyin()
            return true
        }

        fun waitForDouyin(timeoutSeconds: Int): Boolean {
            checkRunning()
            require(timeoutSeconds in 1..30) { "等待抖音超时时间须在 1 到 30 秒之间" }
            return DouyinAccessibilityService.requireInstance().waitForDouyin(timeoutSeconds) {
                stopRequested.get()
            }
        }

        fun ocr(): String {
            checkRunning()
            val captureSession = capture
                ?: throw IllegalStateException("此远程任务未获屏幕捕获授权，不能调用 OCR")
            DouyinAccessibilityService.requireInstance().currentScreenText()
            return captureSession.recognizeText()
        }

        fun clickText(text: String): Boolean {
            checkRunning()
            return clickGate.click {
                DouyinAccessibilityService.requireInstance().clickText(text)
            }
        }

        fun log(message: String) {
            checkRunning()
            RunStatusStore(context).appendLog(message.take(MAX_LOG_ENTRY_CHARS))
        }

        fun isStopped(): Boolean = stopRequested.get()

        private fun checkRunning() {
            if (stopRequested.get()) throw CancellationException("任务已停止")
        }
    }

    private inline fun <reified T> Intent.parcelableExtra(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    companion object {
        const val ACTION_EXECUTE = "com.flowpilot.android.action.EXECUTE_TASK"
        const val ACTION_STOP = "com.flowpilot.android.action.STOP_TASK"
        const val ACTION_RUN_STATUS_CHANGED = "com.flowpilot.android.action.RUN_STATUS_CHANGED"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_NAME = "task_name"
        const val EXTRA_TASK_VERSION = "task_version"
        const val EXTRA_TASK_CAPABILITIES = "task_capabilities"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_CAPTURE_RESULT_CODE = "capture_result_code"
        const val EXTRA_CAPTURE_RESULT_DATA = "capture_result_data"
        const val CHANNEL_ID = "flowpilot_task_execution"
        const val NOTIFICATION_ID = 32
        private const val STOP_PENDING_INTENT = 320
        private const val MAX_LOG_ENTRY_CHARS = 500
        private const val SERVICE_START_GRACE_PERIOD_MILLIS = 1_000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
        private const val TOKEN_REFRESH_LEAD_SECONDS = 5 * 60L
        private const val SCRIPT_CACHE_DIRECTORY = "verified_scripts"

        fun start(
            context: Context,
            task: AvailableTask,
            runId: String,
            captureResultCode: Int?,
            captureResultData: Intent?,
            deviceId: String,
        ) {
            val intent = Intent(context, TaskExecutionService::class.java)
                .setAction(ACTION_EXECUTE)
                .putExtra(EXTRA_RUN_ID, runId)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_TASK_NAME, task.name)
                .putExtra(EXTRA_TASK_VERSION, task.version)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_TASK_CAPABILITIES, task.capabilities.toTypedArray())
            if (captureResultCode != null && captureResultData != null) {
                intent.putExtra(EXTRA_CAPTURE_RESULT_CODE, captureResultCode)
                    .putExtra(EXTRA_CAPTURE_RESULT_DATA, captureResultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TaskExecutionService::class.java).setAction(ACTION_STOP))
        }
    }
}

internal class RunStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences("flowpilot_runtime", Context.MODE_PRIVATE)

    fun update(runId: String, taskName: String, taskVersion: String?, status: String, summary: String) {
        val previousRunId = preferences.getString(KEY_RUN_ID, null)
        val previousStatus = preferences.getString(KEY_STATUS, null)
        val startsNewRun = shouldStartNewRun(previousRunId, previousStatus, runId, status)
        val editor = preferences.edit()
        if (startsNewRun) {
            editor.remove(KEY_LOG)
            editor.putLong(KEY_STARTED_AT, System.currentTimeMillis())
        }
        editor
            .putString(KEY_RUN_ID, runId)
            .putString(KEY_TASK_NAME, taskName)
            .putString(KEY_TASK_VERSION, taskVersion)
            .putString(KEY_STATUS, status)
            .putString(KEY_SUMMARY, summary.take(500))
            .apply()
    }

    fun appendLog(message: String) {
        val existing = preferences.getString(KEY_LOG, "").orEmpty()
        preferences.edit().putString(KEY_LOG, (existing + message + "\n").takeLast(MAX_LOG_CHARS)).apply()
    }

    fun snapshot(): RunStatus =
        RunStatus(
            runId = preferences.getString(KEY_RUN_ID, null),
            taskName = preferences.getString(KEY_TASK_NAME, null),
            taskVersion = preferences.getString(KEY_TASK_VERSION, null),
            status = preferences.getString(KEY_STATUS, null),
            summary = preferences.getString(KEY_SUMMARY, null),
            startedAtMillis = preferences.getLong(KEY_STARTED_AT, 0L).takeIf { it > 0L },
            log = preferences.getString(KEY_LOG, "").orEmpty(),
        )

    private companion object {
        const val KEY_RUN_ID = "run_id"
        const val KEY_TASK_NAME = "task_name"
        const val KEY_TASK_VERSION = "task_version"
        const val KEY_STATUS = "status"
        const val KEY_SUMMARY = "summary"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_LOG = "log"
        const val MAX_LOG_CHARS = 4000
    }
}

internal data class RunStatus(
    val runId: String?,
    val taskName: String?,
    val taskVersion: String?,
    val status: String?,
    val summary: String?,
    val startedAtMillis: Long?,
    val log: String,
)

internal fun shouldStartNewRun(
    previousRunId: String?,
    previousStatus: String?,
    runId: String,
    status: String,
): Boolean = previousRunId != runId ||
    (status == "awaiting_permission" && previousStatus != "awaiting_permission") ||
    (status == "running" && previousStatus !in setOf("running", "awaiting_permission"))

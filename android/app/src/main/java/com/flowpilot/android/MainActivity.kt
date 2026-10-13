package com.flowpilot.android

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class DeviceAppDestination(val label: String) {
    DEVICE("设备"),
    TASKS("任务"),
    RUNS("运行"),
    ACCOUNT("我的"),
}

class MainActivity : ComponentActivity() {
    private val flowPilotViewModel: FlowPilotViewModel by viewModels()
    private val notificationPermissionGranted = mutableStateOf(false)
    private val batteryOptimizationExempt = mutableStateOf(false)
    private var startMonitoringAfterNotificationGrant = false
    private var startOcrStandbyAfterNotificationGrant = false
    private var startDefaultOcrAfterNotificationGrant = false
    private var startDefaultOcrAfterBatterySettings = false
    private var pendingOcrStandbyStart = false
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted.value = granted || Build.VERSION.SDK_INT < 33
        val shouldStartMonitoring = startMonitoringAfterNotificationGrant
        val shouldStartOcrStandby = startOcrStandbyAfterNotificationGrant
        val shouldStartDefaultOcr = startDefaultOcrAfterNotificationGrant
        startMonitoringAfterNotificationGrant = false
        startOcrStandbyAfterNotificationGrant = false
        startDefaultOcrAfterNotificationGrant = false
        if (granted && shouldStartDefaultOcr) {
            if (remoteMonitoring.value || startRemoteMonitoring()) {
                requestBatteryOptimizationExemption(continueToOcr = true)
            }
        }
        else if (granted && shouldStartMonitoring) startRemoteMonitoring()
        else if (granted && shouldStartOcrStandby) requestOcrStandbyCapturePermission()
        else if (!granted) flowPilotViewModel.showError("未授予通知权限，无法显示远程待命和运行状态")
    }
    private val batteryOptimizationSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryOptimizationExempt.value = isIgnoringBatteryOptimizations()
        if (startDefaultOcrAfterBatterySettings) {
            startDefaultOcrAfterBatterySettings = false
            requestDefaultOcrStandby()
        }
    }
    private val screenCapturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (pendingOcrStandbyStart) {
            pendingOcrStandbyStart = false
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                flowPilotViewModel.showError("未授予屏幕捕获权限，持续 OCR 待命未开启")
                return@registerForActivityResult
            }
            try {
                OcrStandbyService.start(this, result.resultCode, result.data!!)
                ocrStandbyActive.value = OcrStandbyService.isActive(this)
            } catch (exception: Exception) {
                flowPilotViewModel.showError("无法开启持续 OCR 待命：${exception.message ?: "未知错误"}")
                return@registerForActivityResult
            }
            return@registerForActivityResult
        }
        val pending = pendingExecution
        pendingExecution = null
        if (pending == null) return@registerForActivityResult
        pending.remoteRequestId?.let { RemoteRunMonitorService.markRequestHandled(this, it) }
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            if (pending.runId != null) {
                lifecycleScope.launch {
                    try {
                        flowPilotViewModel.failRun(pending.runId, "用户未授予 Android 屏幕捕获权限")
                    } catch (exception: Exception) {
                        flowPilotViewModel.showError("屏幕捕获被拒绝；运行结果同步失败：${exception.message}")
                    } finally {
                        flowPilotViewModel.refreshRunHistory()
                    }
                }
            }
            RunStatusStore(this).update(
                pending.runId.orEmpty(),
                pending.task.name,
                pending.task.version,
                "failure",
                "屏幕捕获授权被拒绝，任务未执行",
            )
            flowPilotViewModel.refreshRunStatus()
            flowPilotViewModel.showError("屏幕捕获是此任务所需能力，已取消运行")
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            var runId: String? = pending.runId
            try {
                val device = flowPilotViewModel.state.value.device
                    ?: throw IllegalStateException("当前账号尚未绑定此设备")
                val activeRunId = runId ?: flowPilotViewModel.createLocalRun(pending.task)
                runId = activeRunId
                pending.remoteRequestId?.let { RemoteRunMonitorService.markRequestHandled(this@MainActivity, it) }
                val douyinIntent = if (pending.task.name.contains("抖音", ignoreCase = true)) {
                    val packageName = DouyinPackagePolicy.preferredInstalledPackage {
                        packageManager.getLaunchIntentForPackage(it) != null
                    } ?: throw IllegalStateException("未检测到已安装的抖音或抖音极速版，请先安装后再运行任务")
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?: throw IllegalStateException("无法启动已安装的抖音应用")
                } else {
                    null
                }
                RunStatusStore(this@MainActivity)
                    .update(activeRunId, pending.task.name, pending.task.version, "running", "正在启动任务")
                TaskExecutionService.start(
                    context = this@MainActivity,
                    task = pending.task,
                    runId = activeRunId,
                    captureResultCode = result.resultCode,
                    captureResultData = result.data!!,
                    deviceId = device.id,
                    remoteRequestId = pending.remoteRequestId,
                )
                douyinIntent?.let(::startActivity)
                flowPilotViewModel.refreshRunStatus()
                flowPilotViewModel.refreshRunHistory()
            } catch (exception: Exception) {
                val original = exception.message ?: "无法启动任务"
                val reportError = runId?.let { id ->
                    runCatching { flowPilotViewModel.failRun(id, original) }.exceptionOrNull()
                }
                flowPilotViewModel.showError(
                    if (reportError == null) original
                    else "$original；运行结果同步失败：${reportError.message ?: "服务器不可用"}",
                )
                RunStatusStore(this@MainActivity).update(
                    runId.orEmpty(),
                    pending.task.name,
                    pending.task.version,
                    "failure",
                    original,
                )
                flowPilotViewModel.refreshRunStatus()
                flowPilotViewModel.refreshRunHistory()
            }
        }
    }

    private val accessibilityEnabled = mutableStateOf(false)
    private val remoteMonitoring = mutableStateOf(false)
    private val ocrStandbyActive = mutableStateOf(false)
    private var pendingExecution: PendingExecution? = null
    private val runStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OcrStandbyService.ACTION_STATE_CHANGED -> {
                    ocrStandbyActive.value = OcrStandbyService.isActive(this@MainActivity)
                    OcrStandbyService.consumeLastError(this@MainActivity)?.let(
                        flowPilotViewModel::showError,
                    )
                }
                else -> {
                    flowPilotViewModel.refreshRunStatus()
                    flowPilotViewModel.refreshRunHistory()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(FOREGROUND_REMOTE_POLL_INTERVAL_MILLIS)
                    flowPilotViewModel.refreshRemoteRuns()
                }
            }
        }
        setContent {
            MaterialTheme(colorScheme = FlowPilotColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FlowPilotScreen(
                        viewModel = flowPilotViewModel,
                        accessibilityEnabled = accessibilityEnabled.value,
                        notificationPermissionGranted = notificationPermissionGranted.value,
                        batteryOptimizationExempt = batteryOptimizationExempt.value,
                        remoteMonitoring = remoteMonitoring.value,
                        ocrStandbyActive = ocrStandbyActive.value,
                        onStartDefaultStandby = ::startDefaultStandby,
                        onRequestBatteryOptimizationExemption = {
                            requestBatteryOptimizationExemption()
                        },
                        onOpenAppSystemSettings = ::openAppSystemSettings,
                        onOpenManufacturerBackgroundSettings = ::openManufacturerBackgroundSettings,
                        onSetOcrStandby = ::setOcrStandby,
                        onAutoRunRemoteRequest = ::autoRunRemoteRequest,
                        onRunTask = ::beginExecution,
                        onSetRemoteMonitoring = ::setRemoteMonitoring,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onRememberLoginInfoChanged = flowPilotViewModel::setRememberLoginInfo,
                        onLogout = {
                            RemoteRunMonitorService.stop(this)
                            OcrStandbyService.stop(this)
                            flowPilotViewModel.logout()
                        },
                        onStopTask = { TaskExecutionService.stop(this) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        flowPilotViewModel.refreshRemoteRuns()
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = DouyinAccessibilityService.isEnabled(this)
        notificationPermissionGranted.value =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        batteryOptimizationExempt.value = isIgnoringBatteryOptimizations()
        remoteMonitoring.value = RemoteRunMonitorService.isMonitoring(this)
        ocrStandbyActive.value = OcrStandbyService.isActive(this)
        OcrStandbyService.consumeLastError(this)?.let(flowPilotViewModel::showError)
        flowPilotViewModel.refreshRunStatus()
        flowPilotViewModel.refreshRunHistory()
        flowPilotViewModel.refreshTasks(showLoading = false)
        flowPilotViewModel.refreshRemoteRuns()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            runStatusReceiver,
            IntentFilter().apply {
                addAction(TaskExecutionService.ACTION_RUN_STATUS_CHANGED)
                addAction(OcrStandbyService.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(runStatusReceiver)
        super.onStop()
    }

    private fun autoRunRemoteRequest(request: RemoteRun) {
        if (!RemoteRunMonitorService.claimRequest(this, request.id)) return
        lifecycleScope.launch {
            var runId = request.runId
            try {
                if (request.status == "pending") {
                    runId = flowPilotViewModel.approveRemoteRun(request.id)
                }
                if (runId == null) throw IllegalStateException("服务器没有返回运行记录 ID")
                beginExecution(request.asTask(), runId, request.id)
                if (!RemoteRunMonitorService.isRequestHandled(this@MainActivity, request.id)) {
                    RemoteRunMonitorService.markRequestHandled(this@MainActivity, request.id)
                }
                flowPilotViewModel.refreshRemoteRuns()
            } catch (exception: Exception) {
                if (runId != null) {
                    try {
                        flowPilotViewModel.failRun(
                            runId,
                            "无法直接启动远程任务：${exception.message ?: "未知错误"}",
                        )
                        RemoteRunMonitorService.markRequestHandled(this@MainActivity, request.id)
                    } catch (reportException: Exception) {
                        RemoteRunMonitorService.releaseRequestClaim(request.id)
                        flowPilotViewModel.showError(
                            "无法直接启动远程任务：${exception.message ?: "未知错误"}；失败状态同步失败：${reportException.message ?: "服务器不可用"}",
                        )
                    }
                } else {
                    RemoteRunMonitorService.releaseRequestClaim(request.id)
                    flowPilotViewModel.showError(
                        "无法直接启动远程任务：${exception.message ?: "服务器不可用"}",
                    )
                }
                flowPilotViewModel.refreshRemoteRuns()
            }
        }
    }

    private fun beginExecution(task: AvailableTask, runId: String?, remoteRequestId: String?) {
        val currentStatus = RunStatusStore(this).snapshot().status
        if (currentStatus == "running" || currentStatus == "awaiting_permission") {
            flowPilotViewModel.showError("当前已有任务运行，请先停止或等待完成")
            runId?.let { reportRemoteRunFailure(it, "设备上已有任务运行，本次远程任务未启动") }
            return
        }
        if (runId != null && requiresAccessibility(task.capabilities) && !accessibilityEnabled.value) {
            val message = "此任务需要无障碍服务，当前未启用，远程任务未启动"
            RunStatusStore(this).update(runId, task.name, task.version, "failure", message)
            RemoteRunMonitorService.markRequestHandled(this, remoteRequestId.orEmpty())
            flowPilotViewModel.showError(message)
            reportRemoteRunFailure(runId, message)
            return
        }
        if (runId != null) {
            val deviceId = flowPilotViewModel.state.value.device?.id
            if (deviceId == null) {
                reportRemoteRunFailure(runId, "当前账号尚未绑定此设备")
                flowPilotViewModel.showError("当前账号尚未绑定此设备")
                return
            }
            if (requiresNewScreenCaptureAuthorization(task.capabilities, ocrStandbyActive.value)) {
                beginScreenCaptureExecution(task, runId, remoteRequestId)
                return
            }
            if (requiresScreenCapture(task.capabilities)) {
                beginExecutionWithOcrStandby(task, runId, remoteRequestId)
                return
            }
            try {
                TaskExecutionService.start(
                    context = this,
                    task = task,
                    runId = runId,
                    captureResultCode = null,
                    captureResultData = null,
                    deviceId = deviceId,
                    remoteRequestId = remoteRequestId,
                )
                RemoteRunMonitorService.markRequestHandled(this, remoteRequestId.orEmpty())
                flowPilotViewModel.refreshRunStatus()
            } catch (exception: Exception) {
                val message = "无法启动远程任务：${exception.message ?: "Android 不允许后台启动"}"
                RunStatusStore(this).update(runId, task.name, task.version, "failure", message)
                RemoteRunMonitorService.markRequestHandled(this, remoteRequestId.orEmpty())
                flowPilotViewModel.showError(message)
                reportRemoteRunFailure(runId, message)
            }
            return
        }
        if (!accessibilityEnabled.value) {
            flowPilotViewModel.showError("请先启用 FlowPilot 自动化操作服务，再运行需要点击界面的任务")
            return
        }
        if (requiresScreenCapture(task.capabilities) && ocrStandbyActive.value) {
            beginExecutionWithOcrStandby(task, runId, remoteRequestId)
        } else {
            beginScreenCaptureExecution(task, runId, remoteRequestId)
        }
    }

    private fun beginScreenCaptureExecution(
        task: AvailableTask,
        runId: String?,
        remoteRequestId: String?,
    ) {
        if (remoteRequestId != null) {
            RemoteRunMonitorService.markRequestHandled(this, remoteRequestId)
        }
        RunStatusStore(this).update(
            runId.orEmpty(),
            task.name,
            task.version,
            "awaiting_permission",
            "等待 Android 屏幕捕获授权",
        )
        flowPilotViewModel.refreshRunStatus()
        pendingExecution = PendingExecution(task, runId, remoteRequestId)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun beginExecutionWithOcrStandby(
        task: AvailableTask,
        runId: String?,
        remoteRequestId: String?,
    ) {
        lifecycleScope.launch {
            var activeRunId = runId
            try {
                val device = flowPilotViewModel.state.value.device
                    ?: throw IllegalStateException("当前账号尚未绑定此设备")
                if (activeRunId == null) activeRunId = flowPilotViewModel.createLocalRun(task)
                TaskExecutionService.start(
                    context = this@MainActivity,
                    task = task,
                    runId = activeRunId,
                    captureResultCode = null,
                    captureResultData = null,
                    deviceId = device.id,
                    remoteRequestId = remoteRequestId,
                )
                remoteRequestId?.let {
                    RemoteRunMonitorService.markRequestHandled(this@MainActivity, it)
                }
                flowPilotViewModel.refreshRunStatus()
                flowPilotViewModel.refreshRunHistory()
            } catch (exception: Exception) {
                val message = "无法使用持续 OCR 会话启动任务：${exception.message ?: "未知错误"}"
                val reportError = activeRunId?.let { failedRunId ->
                    runCatching { flowPilotViewModel.failRun(failedRunId, message) }.exceptionOrNull()
                }
                remoteRequestId?.let {
                    RemoteRunMonitorService.markRequestHandled(this@MainActivity, it)
                }
                val finalMessage = if (reportError == null) {
                    message
                } else {
                    "$message；失败状态同步失败：${reportError.message ?: "服务器不可用"}"
                }
                RunStatusStore(this@MainActivity).update(
                    activeRunId.orEmpty(),
                    task.name,
                    task.version,
                    "failure",
                    finalMessage,
                )
                flowPilotViewModel.showError(finalMessage)
                flowPilotViewModel.refreshRunStatus()
                flowPilotViewModel.refreshRunHistory()
            }
        }
    }

    private fun reportRemoteRunFailure(runId: String, message: String) {
        lifecycleScope.launch {
            try {
                flowPilotViewModel.failRun(runId, message)
                flowPilotViewModel.refreshRunHistory()
                flowPilotViewModel.refreshRemoteRuns()
            } catch (exception: Exception) {
                flowPilotViewModel.showError(
                    "$message；失败状态同步失败：${exception.message ?: "服务器不可用"}",
                )
            }
        }
    }

    private fun requestNotificationPermission(startMonitoringAfterGrant: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            !notificationPermissionGranted.value
        ) {
            startMonitoringAfterNotificationGrant = startMonitoringAfterGrant
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (startMonitoringAfterGrant) {
            startRemoteMonitoring()
        }
    }

    private fun startDefaultStandby() {
        if (flowPilotViewModel.state.value.device == null) {
            flowPilotViewModel.showError("请先绑定此设备，远程待命才能启动")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted.value) {
            startDefaultOcrAfterNotificationGrant = true
            requestNotificationPermission()
            return
        }
        if (!remoteMonitoring.value && !startRemoteMonitoring()) return
        requestBatteryOptimizationExemption(continueToOcr = true)
    }

    private fun requestBatteryOptimizationExemption(continueToOcr: Boolean = false) {
        if (!shouldRequestBatteryOptimizationExemption(
                exemptionSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
                alreadyExempt = isIgnoringBatteryOptimizations(),
            )
        ) {
            batteryOptimizationExempt.value = isIgnoringBatteryOptimizations()
            if (continueToOcr) requestDefaultOcrStandby()
            return
        }
        startDefaultOcrAfterBatterySettings = continueToOcr
        try {
            batteryOptimizationSettings.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (exception: Exception) {
            startDefaultOcrAfterBatterySettings = false
            flowPilotViewModel.showError(
                "无法打开后台电池优化授权：${exception.message ?: "未知错误"}",
            )
            if (continueToOcr) requestDefaultOcrStandby()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(packageName)

    private fun openAppSystemSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } catch (exception: Exception) {
            flowPilotViewModel.showError(
                "无法打开 FlowPilot 系统设置：${exception.message ?: "未知错误"}",
            )
        }
    }

    private fun openManufacturerBackgroundSettings() {
        val intents = buildList {
            if (usesXiaomiAutoStartSettings(Build.MANUFACTURER)) {
                add(
                    Intent("miui.intent.action.OP_AUTO_START")
                        .setPackage("com.miui.securitycenter"),
                )
                add(
                    Intent().setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                        ),
                    ),
                )
            }
            add(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        try {
            startActivity(intent)
        } catch (exception: Exception) {
            flowPilotViewModel.showError(
                "无法打开自启动/后台运行设置：${exception.message ?: "未知错误"}",
            )
        }
    }

    private fun requestDefaultOcrStandby() {
        if (!canStartOcrStandby(RunStatusStore(this).snapshot().status)) {
            flowPilotViewModel.showError("远程待命已开启；当前任务运行中，稍后可重新开启持续 OCR 待命")
            return
        }
        if (OcrStandbyService.isActive(this)) {
            ocrStandbyActive.value = true
        } else {
            requestOcrStandbyCapturePermission()
        }
    }

    private fun setOcrStandby(enabled: Boolean) {
        if (!enabled) {
            OcrStandbyService.stop(this)
            ocrStandbyActive.value = false
            return
        }
        if (!canStartOcrStandby(RunStatusStore(this).snapshot().status)) {
            flowPilotViewModel.showError("当前任务运行中，完成或停止任务后再开启持续 OCR 待命")
            return
        }
        if (OcrStandbyService.isActive(this)) {
            ocrStandbyActive.value = true
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted.value) {
            startOcrStandbyAfterNotificationGrant = true
            requestNotificationPermission()
            return
        }
        requestOcrStandbyCapturePermission()
    }

    private fun requestOcrStandbyCapturePermission() {
        pendingOcrStandbyStart = true
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun setRemoteMonitoring(enabled: Boolean) {
        if (!enabled) {
            RemoteRunMonitorService.stop(this)
            remoteMonitoring.value = false
            return
        }
        requestNotificationPermission(startMonitoringAfterGrant = true)
    }

    private fun startRemoteMonitoring(): Boolean {
        try {
            RemoteRunMonitorService.start(this)
            remoteMonitoring.value = true
            return true
        } catch (exception: Exception) {
            flowPilotViewModel.showError(exception.message ?: "无法启动远程待命服务")
            return false
        }
    }

    private data class PendingExecution(
        val task: AvailableTask,
        val runId: String?,
        val remoteRequestId: String?,
    )

    companion object {
        private const val FOREGROUND_REMOTE_POLL_INTERVAL_MILLIS = 15_000L
    }
}

@Composable
private fun FlowPilotScreen(
    viewModel: FlowPilotViewModel,
    accessibilityEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationExempt: Boolean,
    remoteMonitoring: Boolean,
    ocrStandbyActive: Boolean,
    onStartDefaultStandby: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onOpenAppSystemSettings: () -> Unit,
    onOpenManufacturerBackgroundSettings: () -> Unit,
    onSetOcrStandby: (Boolean) -> Unit,
    onRunTask: (AvailableTask, String?, String?) -> Unit,
    onAutoRunRemoteRequest: (RemoteRun) -> Unit,
    onSetRemoteMonitoring: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRememberLoginInfoChanged: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onStopTask: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user = state.user
    val device = state.device
    val canLogout = canLogoutDuringRun(state.runStatus?.status)
    var apiBaseUrl by rememberSaveable { mutableStateOf(state.apiBaseUrl) }
    var registering by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf(state.loginEmail) }
    var password by rememberSaveable { mutableStateOf(state.loginPassword) }
    var passwordConfirmation by rememberSaveable { mutableStateOf("") }
    var rememberLoginInfo by rememberSaveable { mutableStateOf(state.rememberLoginInfo) }
    var recoveryAvailable by rememberSaveable { mutableStateOf(false) }
    var recoveringPassword by rememberSaveable { mutableStateOf(false) }
    var recoveryCodeSent by rememberSaveable { mutableStateOf(false) }
    var recoveryCode by rememberSaveable { mutableStateOf("") }
    var recoveryNewPassword by rememberSaveable { mutableStateOf("") }
    var recoveryMessage by rememberSaveable { mutableStateOf("") }
    var recoveryError by rememberSaveable { mutableStateOf(false) }
    var recoveryBusy by rememberSaveable { mutableStateOf(false) }
    val recoveryScope = rememberCoroutineScope()
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var defaultStandbyRequestedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTask by remember { mutableStateOf<AvailableTask?>(null) }
    var confirmDeviceUnbind by rememberSaveable { mutableStateOf(false) }
    var scriptReviewAttempt by remember { mutableStateOf<String?>(null) }
    var confirmScriptRun by rememberSaveable { mutableStateOf(false) }
    var profileName by rememberSaveable(user?.id) { mutableStateOf(user?.name.orEmpty()) }
    var profileEmail by rememberSaveable(user?.id) { mutableStateOf(user?.email.orEmpty()) }
    var profileCurrentPassword by rememberSaveable { mutableStateOf("") }
    var accountCurrentPassword by rememberSaveable { mutableStateOf("") }
    var accountNewPassword by rememberSaveable { mutableStateOf("") }
    var selectedDestinationName by rememberSaveable {
        mutableStateOf(DeviceAppDestination.DEVICE.name)
    }
    val selectedDestination = DeviceAppDestination.entries.firstOrNull {
        it.name == selectedDestinationName
    } ?: DeviceAppDestination.DEVICE
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            registering = false
            passwordConfirmation = ""
        }
    }
    LaunchedEffect(apiBaseUrl, user?.id) {
        recoveryAvailable = false
        if (user != null) return@LaunchedEffect
        delay(400)
        recoveryAvailable = try {
            withContext(Dispatchers.IO) {
                ApiClient(normalizeApiBaseUrl(apiBaseUrl)).passwordRecoveryEnabled()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }
    LaunchedEffect(user?.id, device?.id, state.loading) {
        if (user == null) {
            defaultStandbyRequestedFor = null
        } else {
            defaultStandbyRequestKey(user.id, device?.id, state.loading, defaultStandbyRequestedFor)
                ?.let { deviceKey ->
                    defaultStandbyRequestedFor = deviceKey
                    onStartDefaultStandby()
                }
        }
    }
    LaunchedEffect(user?.id, device?.id, ocrStandbyActive) {
        if (user != null && device != null) {
            viewModel.refreshRemoteRuns()
        } else if (user != null) {
            onSetRemoteMonitoring(false)
            if (ocrStandbyActive) onSetOcrStandby(false)
        }
    }
    LaunchedEffect(
        state.remoteRuns,
        state.remoteRunsLoading,
        state.runStatus?.status,
        accessibilityEnabled,
        user?.id,
        device?.id,
    ) {
        if (user == null || device == null || state.remoteRunsLoading) return@LaunchedEffect
        val runStatus = state.runStatus?.status
        val request = state.remoteRuns.firstOrNull { candidate ->
            RemoteRunMonitorService.isRequestAvailable(context, candidate.id) &&
                shouldDirectlyRunRemoteRequest(
                    status = candidate.status,
                    runId = candidate.runId,
                    accessibilityRequired = requiresAccessibility(candidate.capabilities),
                    accessibilityEnabled = accessibilityEnabled,
                    deviceRunStatus = runStatus,
                )
        }
        if (request != null) onAutoRunRemoteRequest(request)
    }
    LaunchedEffect(selectedTask?.id, selectedTask?.version, state.loading) {
        if (state.loading) return@LaunchedEffect
        val task = selectedTask
        if (task != null) {
            val key = "${task.id}:${task.version}"
            if (scriptReviewAttempt != key) {
                scriptReviewAttempt = key
                viewModel.inspectScript(task)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (user == null) {
            FlowPilotBrandHeader()
            TextButton(onClick = { showHelp = true }) {
                Text("帮助与关于")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (selectedDestination) {
                            DeviceAppDestination.DEVICE -> "设备"
                            DeviceAppDestination.TASKS -> "任务"
                            DeviceAppDestination.RUNS -> "运行"
                            DeviceAppDestination.ACCOUNT -> "我的"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        when (selectedDestination) {
                            DeviceAppDestination.DEVICE -> "设备状态、连接和运行权限"
                            DeviceAppDestination.TASKS -> "已分配任务与待确认发布"
                            DeviceAppDestination.RUNS -> "远程请求、运行状态与执行记录"
                            DeviceAppDestination.ACCOUNT -> "个人资料、密码与应用设置"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showHelp = true }) {
                    Text("帮助")
                }
            }
        }

        if (user == null) {
            FlowPilotHero(
                title = "安全连接自动化设备",
                subtitle = "安全连接设备，远程任务将自动运行。",
            )
            FlowPilotCard {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (registering) "创建普通用户账号" else "连接服务并登录", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (registering) "注册后可登录并绑定自己的设备" else "Android 自动化客户端",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = apiBaseUrl,
                        onValueChange = { apiBaseUrl = it },
                        label = { Text("API 地址") },
                        placeholder = {
                            Text(
                                if (apiBaseUrl == defaultApiBaseUrl(isEmulator = true)) {
                                    "http://10.0.2.2:8001"
                                } else {
                                    "http://192.168.0.101:8001"
                                },
                            )
                        },
                        supportingText = {
                            Text("真机与开发机连接同一局域网即可测试")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (registering) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("姓名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (registering) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码（至少 12 个字符）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = passwordConfirmation,
                            onValueChange = { passwordConfirmation = it },
                            label = { Text("确认密码") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (!recoveringPassword) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!registering && !recoveringPassword) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = rememberLoginInfo,
                                onCheckedChange = {
                                    rememberLoginInfo = it
                                    onRememberLoginInfoChanged(it)
                                },
                            )
                            Text("记住登录信息（加密保存账号和密码）")
                        }
                    }
                    if (recoveringPassword) {
                        if (recoveryCodeSent) {
                            OutlinedTextField(
                                value = recoveryCode,
                                onValueChange = { recoveryCode = it.filter(Char::isDigit).take(6) },
                                label = { Text("邮箱验证码") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = recoveryNewPassword,
                                onValueChange = { recoveryNewPassword = it },
                                label = { Text("新密码（至少 12 个字符）") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    recoveryScope.launch {
                                        if (recoveryBusy) return@launch
                                        recoveryBusy = true
                                        recoveryError = false
                                        recoveryMessage = ""
                                        try {
                                            withContext(Dispatchers.IO) {
                                                ApiClient(normalizeApiBaseUrl(apiBaseUrl))
                                                    .resetPassword(email.trim(), recoveryCode, recoveryNewPassword)
                                            }
                                            recoveringPassword = false
                                            recoveryCodeSent = false
                                            recoveryCode = ""
                                            recoveryNewPassword = ""
                                            password = ""
                                            recoveryMessage = "密码已重置，请使用新密码登录"
                                        } catch (exception: Exception) {
                                            recoveryError = true
                                            recoveryMessage = exception.message ?: "密码重置失败"
                                        } finally {
                                            recoveryBusy = false
                                        }
                                    }
                                },
                                enabled = !state.loading && !recoveryBusy &&
                                    recoveryCode.length == 6 && recoveryNewPassword.length >= 12,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("重置密码") }
                        } else {
                            Text(
                                "验证码将发送至此邮箱，有效期 10 分钟。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = {
                                    recoveryScope.launch {
                                        if (recoveryBusy) return@launch
                                        recoveryBusy = true
                                        recoveryError = false
                                        recoveryMessage = ""
                                        try {
                                            withContext(Dispatchers.IO) {
                                                ApiClient(normalizeApiBaseUrl(apiBaseUrl))
                                                    .requestPasswordResetCode(email.trim())
                                            }
                                            recoveryCodeSent = true
                                            recoveryMessage = "如果该邮箱对应可用账号，已发送验证码。"
                                        } catch (exception: Exception) {
                                            recoveryError = true
                                            recoveryMessage = exception.message ?: "验证码发送失败"
                                        } finally {
                                            recoveryBusy = false
                                        }
                                    }
                                },
                                enabled = !state.loading && !recoveryBusy && email.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("发送验证码") }
                        }
                        if (recoveryMessage.isNotBlank()) {
                            Text(
                                recoveryMessage,
                                color = if (recoveryError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = {
                                recoveringPassword = false
                                recoveryCodeSent = false
                                recoveryCode = ""
                                recoveryNewPassword = ""
                                recoveryMessage = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("返回登录") }
                    } else {
                        Button(
                            onClick = {
                                if (registering) {
                                    viewModel.register(apiBaseUrl, displayName, email, password, passwordConfirmation)
                                } else {
                                    viewModel.login(apiBaseUrl, email, password, rememberLoginInfo)
                                }
                            },
                            enabled = !state.loading && apiBaseUrl.isNotBlank() && email.isNotBlank() &&
                                password.isNotBlank() &&
                                (!registering || (displayName.isNotBlank() && passwordConfirmation.isNotBlank())),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (registering) "创建账号" else "登录")
                        }
                        if (!registering && recoveryAvailable) {
                            TextButton(
                                onClick = {
                                    recoveringPassword = true
                                    recoveryCodeSent = false
                                    recoveryMessage = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("忘记密码？") }
                        }
                    }
                    TextButton(
                        onClick = {
                            registering = !registering
                            passwordConfirmation = ""
                            viewModel.clearAccountNotice()
                        },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (registering) "已有账号？返回登录" else "没有账号？注册")
                    }
                }
            }
        } else {
            if (selectedDestination == DeviceAppDestination.ACCOUNT) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(user.name, style = MaterialTheme.typography.titleLarge)
                    Text(user.email, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onLogout, enabled = !state.loading && canLogout) {
                    Text("退出")
                }
            }
            if (selectedDestination == DeviceAppDestination.ACCOUNT && !canLogout) {
                Text(
                    "任务结束前暂不能退出，以确保运行状态可以同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (selectedDestination == DeviceAppDestination.DEVICE) {
              if (device == null) {
                FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("尚未绑定此手机", style = MaterialTheme.typography.titleMedium)
                        Text("绑定后才能同步授权任务和接收远程运行请求。")
                        Button(
                            onClick = viewModel::bindDevice,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("绑定此设备并同步")
                        }
                    }
                }
              } else {
                FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("已绑定设备", style = MaterialTheme.typography.titleMedium)
                            StatusBadge(
                                label = deviceStatusLabel(device.status),
                                status = device.status,
                            )
                        }
                        Text("${device.name} · ${device.model}")
                        Text("设备 ID：${device.id}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.clearRunHistoryError()
                                    viewModel.clearRemoteRunsError()
                                    viewModel.refreshTasks()
                                    viewModel.refreshRemoteRuns()
                                    viewModel.refreshRunHistory()
                                },
                                enabled = !state.loading,
                            ) {
                                Text("同步")
                            }
                            TextButton(
                                onClick = {
                                    viewModel.clearRunHistoryError()
                                    viewModel.clearRemoteRunsError()
                                    viewModel.refreshRemoteRuns()
                                    viewModel.refreshRunHistory()
                                },
                                enabled = !state.loading && !state.remoteRunsLoading,
                            ) {
                                Text(if (state.remoteRunsLoading) "刷新中" else "刷新远程请求和记录")
                            }
                        }
                        state.remoteRunsError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(
                            onClick = { confirmDeviceUnbind = true },
                            enabled = !state.loading && canLogout,
                        ) {
                            Text("解绑此设备")
                        }
                    }
                }

                FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("远程运行待命", style = MaterialTheme.typography.titleMedium)
                            StatusBadge(
                                label = if (remoteMonitoring) "已开启" else "未开启",
                                status = if (remoteMonitoring) "active" else "stopped",
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Text(
                                permissionStatusLabel("电池优化豁免", batteryOptimizationExempt),
                                color = if (batteryOptimizationExempt) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            if (!batteryOptimizationExempt) {
                                Button(
                                    onClick = onRequestBatteryOptimizationExemption,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("允许后台保活")
                                }
                            }
                            TextButton(
                                onClick = onOpenManufacturerBackgroundSettings,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("自启动/后台运行设置")
                            }
                            TextButton(
                                onClick = onOpenAppSystemSettings,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("应用设置")
                            }
                        }
                        if (!notificationPermissionGranted) {
                            Text(
                                permissionStatusLabel("通知权限", available = false),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = { onSetRemoteMonitoring(!remoteMonitoring) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (remoteMonitoring) "停止远程待命" else "开启远程待命")
                        }
                    }
                }

                FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("屏幕捕获权限", style = MaterialTheme.typography.titleMedium)
                            StatusBadge(
                                label = if (ocrStandbyActive) "已授权" else "缺少授权",
                                status = if (ocrStandbyActive) "active" else "stopped",
                            )
                        }
                        Button(
                            onClick = { onSetOcrStandby(!ocrStandbyActive) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (ocrStandbyActive) "停止 OCR 待命" else "授权并开启 OCR 待命")
                        }
                    }
                }

                if (!accessibilityEnabled) {
                    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("无障碍操作", style = MaterialTheme.typography.titleMedium)
                                StatusBadge(
                                    label = permissionStatusLabel("无障碍权限", available = false),
                                    status = "disabled",
                                )
                            }
                            TextButton(onClick = onOpenAccessibilitySettings) {
                                Text("启用无障碍权限")
                            }
                        }
                    }
                } else {
                    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("无障碍操作", style = MaterialTheme.typography.titleSmall)
                            StatusBadge(
                                label = permissionStatusLabel("无障碍权限", available = true),
                                status = "active",
                            )
                        }
                    }
                }

              }
            }

            if (selectedDestination == DeviceAppDestination.TASKS) {
              if (device == null) {
                  Text("请先在“设备”页面绑定此手机。")
              } else {
                if (state.pendingPublications.isNotEmpty()) {
                    Text("待确认的脚本发布（${state.pendingPublications.size}）", style = MaterialTheme.typography.titleLarge)
                    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            state.pendingPublications.forEachIndexed { index, publication ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(publication.taskName, style = MaterialTheme.typography.titleSmall)
                                        Text(publication.version, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        "${sourceLabel(publication.source)} · ${publication.publisher}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (publication.description.isNotBlank()) Text(publication.description, style = MaterialTheme.typography.bodySmall)
                                    if (publication.notes.isNotBlank()) {
                                        Text("版本说明：${publication.notes}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        "能力：${publication.capabilities.joinToString("、")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = { viewModel.acceptPublication(publication) },
                                        enabled = !state.loading,
                                    ) {
                                        Text("确认接收")
                                    }
                                }
                                if (index < state.pendingPublications.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }

                Text("可用任务（${state.tasks.size}）", style = MaterialTheme.typography.titleLarge)
                if (state.tasks.isEmpty()) {
                    Text("当前没有分配给此账号的已发布任务。")
                } else {
                    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            state.tasks.forEachIndexed { index, task ->
                                TaskCard(
                                    task = task,
                                    canInspectScript = canInspectTaskScript(device.status, state.runStatus?.status),
                                    onRun = {
                                        selectedTask = task
                                        confirmScriptRun = false
                                        scriptReviewAttempt = null
                                    },
                                )
                                if (index < state.tasks.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                            }
                        }
                    }
                }

              }
            }

            if (selectedDestination == DeviceAppDestination.RUNS) {
              if (device == null) {
                  Text("请先在“设备”页面绑定此手机。")
              } else {
                if (state.remoteRuns.isNotEmpty()) {
                    Text("远程请求（${state.remoteRuns.size}）", style = MaterialTheme.typography.titleLarge)
                    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            state.remoteRuns.forEachIndexed { index, request ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            request.taskName,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        StatusBadge(
                                            label = remoteRunStatusLabel(request.status),
                                            status = request.status,
                                        )
                                    }
                                    Text(
                                        "版本 ${request.version} · 来源 ${sourceLabel(request.source)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    formatEpochSeconds(request.expiresAt)?.let { expiry ->
                                        Text(
                                            "请求有效期至 $expiry",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "请求人：${request.requestedBy}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (request.capabilities.isNotEmpty()) {
                                        Text(
                                            "声明能力 · ${request.capabilities.joinToString(" · ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (request.status == "pending") {
                                        Text(
                                            when {
                                                requiresAccessibility(request.capabilities) && !accessibilityEnabled ->
                                                    "等待启用无障碍服务；启用后会直接运行。"
                                                requiresScreenCapture(request.capabilities) && !ocrStandbyActive ->
                                                    "打开 App 后会直接运行；Android 仍会要求屏幕捕获系统授权。"
                                                else -> "已收到请求，正在直接启动，无需任务审阅或逐次确认。"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else if (request.runId != null) {
                                        Text(
                                            "任务已直接启动，无需逐次任务确认。",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                if (index < state.remoteRuns.lastIndex) {
                                    HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                                }
                            }
                        }
                    }
                }

                Text("运行历史（${state.runHistory.size}）", style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.runHistoryLoading) {
                            "正在刷新运行记录…"
                        } else {
                            state.runHistoryError ?: if (state.runHistory.isEmpty()) {
                                "暂无运行记录。"
                            } else {
                                "显示最近 ${state.runHistory.size.coerceAtMost(MAX_VISIBLE_RUN_HISTORY)} 条"
                            }
                        },
                        color = if (state.runHistoryError == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            viewModel.clearRunHistoryError()
                            viewModel.refreshRunHistory()
                        },
                        enabled = !state.loading && !state.runHistoryLoading,
                    ) {
                        Text(if (state.runHistoryLoading) "刷新中" else "刷新记录")
                    }
                }
                if (state.runHistory.isNotEmpty()) {
                    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            val visibleRuns = visibleRunHistory(state.runHistory)
                            visibleRuns.forEachIndexed { index, run ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            run.taskName,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        StatusBadge(
                                            label = runStatusLabel(run.status),
                                            status = run.status,
                                        )
                                    }
                                    Text(
                                        "版本：${run.version} · ${formatServerRunTime(run.startedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    run.resultSummary.takeIf(String::isNotBlank)?.let { summary ->
                                        Text(summary, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (index < visibleRuns.lastIndex) {
                                        HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                state.runStatus?.let { runStatus ->
                    if (runStatus.status != null) {
                        FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("最近一次运行", style = MaterialTheme.typography.titleMedium)
                                    StatusBadge(
                                        label = runStatusLabel(runStatus.status),
                                        status = runStatus.status,
                                    )
                                }
                                Text(
                                    runStatus.taskName.orEmpty(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                runVersionLabel(runStatus.taskVersion)?.let { version ->
                                    Text(version, style = MaterialTheme.typography.bodySmall)
                                }
                                runStatus.startedAtMillis?.let { startedAt ->
                                    Text(
                                        "开始时间：${formatRunStartTime(startedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(runStatus.summary.orEmpty())
                                if (runStatus.log.isNotBlank()) {
                                    Text("运行日志", style = MaterialTheme.typography.titleSmall)
                                    Text(runStatus.log, style = MaterialTheme.typography.bodySmall)
                                }
                                if (runStatus.status == "running") {
                                    TextButton(onClick = onStopTask) {
                                        Text("停止任务")
                                    }
                                } else if (runStatus.status == "awaiting_permission") {
                                    Text("等待 Android 系统授权")
                                }
                              }
                            }
                        }
                    }
                }
            }
        }

        if (selectedDestination == DeviceAppDestination.ACCOUNT) {
            FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("个人资料", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("姓名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = profileEmail,
                        onValueChange = { profileEmail = it },
                        label = { Text("邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = profileCurrentPassword,
                        onValueChange = { profileCurrentPassword = it },
                        label = { Text("当前密码（修改邮箱时需要）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.updateAccountProfile(profileName, profileEmail, profileCurrentPassword)
                            profileCurrentPassword = ""
                        },
                        enabled = !state.loading && profileName.isNotBlank() && profileEmail.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存资料")
                    }
                }
            }
            FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("修改密码", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = accountCurrentPassword,
                        onValueChange = { accountCurrentPassword = it },
                        label = { Text("当前密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = accountNewPassword,
                        onValueChange = { accountNewPassword = it },
                        label = { Text("新密码（至少 12 位）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.changeAccountPassword(accountCurrentPassword, accountNewPassword)
                            accountCurrentPassword = ""
                            accountNewPassword = ""
                        },
                        enabled = !state.loading && accountCurrentPassword.isNotBlank() &&
                            accountNewPassword.length >= 12,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("更新密码")
                    }
                }
            }
            TextButton(
                onClick = onLogout,
                enabled = !state.loading && canLogout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录")
            }
        }

        if (state.loading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        state.notice?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
      }
      if (user != null) {
          NavigationBar(
              containerColor = Color.White,
              tonalElevation = 4.dp,
          ) {
              DeviceAppDestination.entries.forEach { destination ->
                  NavigationBarItem(
                      selected = selectedDestination == destination,
                      onClick = { selectedDestinationName = destination.name },
                      icon = { DeviceAppNavigationIcon(destination, selectedDestination == destination) },
                      label = { Text(destination.label) },
                      colors = NavigationBarItemDefaults.colors(
                          selectedIconColor = Color(0xFF07C160),
                          selectedTextColor = Color(0xFF07C160),
                          indicatorColor = Color(0xFFE1F5E9),
                          unselectedIconColor = Color(0xFF80868B),
                          unselectedTextColor = Color(0xFF80868B),
                      ),
                  )
              }
          }
      }
    }

    selectedTask?.let { task ->
        val review = state.scriptReview?.takeIf {
            it.task.id == task.id
        }
        val reviewedTask = review?.task ?: task
        AlertDialog(
            onDismissRequest = {
                selectedTask = null
                scriptReviewAttempt = null
                viewModel.clearScriptReview()
            },
            title = { Text("运行前检查脚本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${reviewedTask.name} · 版本 ${reviewedTask.version}")
                    Text("来源：${sourceLabel(reviewedTask.source)}")
                    if (reviewedTask.description.isNotBlank()) Text(reviewedTask.description)
                    if (reviewedTask.capabilities.isNotEmpty()) {
                        Text("声明能力：${reviewedTask.capabilities.joinToString("、")}")
                    }
                    if (!accessibilityEnabled) {
                        Text("查看脚本不需要无障碍权限；运行前请先在 Android 系统设置中手动启用 FlowPilot 无障碍服务。")
                    }
                    if (state.loading && review == null) {
                        CircularProgressIndicator()
                    } else if (review == null) {
                        Text("无法读取并校验脚本内容。请关闭后重试。")
                    } else {
                        review.cacheNotice?.let { notice ->
                            Text(notice, color = MaterialTheme.colorScheme.error)
                        }
                        if (review.script.isCached) {
                            Text("本机已校验缓存版本", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("SHA-256：${review.script.sha256}", style = MaterialTheme.typography.bodySmall)
                        Text("脚本内容", style = MaterialTheme.typography.titleSmall)
                        ScriptContent(review.script.source)
                    }
                    Text(
                        "脚本会在应用进程内运行，不是安全沙箱；屏幕捕获和抖音点击仅按 Android 已授予的权限执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = confirmScriptRun,
                            onCheckedChange = { confirmScriptRun = it },
                        )
                        Text("我已检查代码并同意运行此版本")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedTask = null
                        scriptReviewAttempt = null
                        viewModel.clearScriptReview()
                        onRunTask(reviewedTask, null, null)
                    },
                    enabled = canExecuteTaskScript(review != null, confirmScriptRun, accessibilityEnabled),
                ) {
                    Text("授权并运行")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        selectedTask = null
                        scriptReviewAttempt = null
                        viewModel.clearScriptReview()
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (confirmDeviceUnbind) {
        AlertDialog(
            onDismissRequest = { confirmDeviceUnbind = false },
            title = { Text("解绑此设备？") },
            text = {
                Text("解绑后此手机将停止接收远程请求，并从账号设备列表停用。之后可重新绑定。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeviceUnbind = false
                        viewModel.unbindDevice()
                    },
                    enabled = !state.loading,
                ) {
                    Text("确认解绑")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeviceUnbind = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("受控端帮助与关于") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    controlledAppHelpSections(BuildConfig.VERSION_NAME).forEach { section ->
                        Text(section.title, style = MaterialTheme.typography.titleSmall)
                        Text(section.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text("关闭")
                }
            },
        )
    }

}

@Composable
private fun ScriptContent(source: String) {
    val displayLines = remember(source) {
        source.lineSequence().flatMap { line ->
            if (line.isEmpty()) sequenceOf("") else line.chunked(300).asSequence()
        }.toList()
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(displayLines) { index, line ->
            Text(
                "${index + 1}: $line",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun DeviceAppNavigationIcon(destination: DeviceAppDestination, selected: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val color = if (selected) Color(0xFF07C160) else Color(0xFF8992A5)
        val stroke = 1.8.dp.toPx()
        when (destination) {
            DeviceAppDestination.DEVICE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.08f),
                    size = Size(size.width * 0.64f, size.height * 0.84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.1f),
                    style = Stroke(stroke),
                )
                drawLine(
                    color,
                    Offset(size.width * 0.42f, size.height * 0.77f),
                    Offset(size.width * 0.58f, size.height * 0.77f),
                    stroke,
                    cap = StrokeCap.Round,
                )
            }
            DeviceAppDestination.TASKS -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                    size = Size(size.width * 0.76f, size.height * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f),
                    style = Stroke(stroke),
                )
                listOf(0.37f, 0.56f, 0.75f).forEach { y ->
                    drawLine(
                        color,
                        Offset(size.width * 0.32f, size.height * y),
                        Offset(size.width * 0.72f, size.height * y),
                        stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            DeviceAppDestination.RUNS -> {
                val chart = Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.82f)
                    lineTo(size.width * 0.12f, size.height * 0.14f)
                    moveTo(size.width * 0.22f, size.height * 0.68f)
                    lineTo(size.width * 0.43f, size.height * 0.48f)
                    lineTo(size.width * 0.60f, size.height * 0.60f)
                    lineTo(size.width * 0.86f, size.height * 0.28f)
                }
                drawPath(chart, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            DeviceAppDestination.ACCOUNT -> {
                drawCircle(
                    color,
                    size.minDimension * 0.16f,
                    Offset(size.width * 0.5f, size.height * 0.31f),
                    style = Stroke(stroke),
                )
                drawArc(
                    color = color,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.19f, size.height * 0.47f),
                    size = Size(size.width * 0.62f, size.height * 0.43f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun FlowPilotBrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.width(42.dp).height(42.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF101B35),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_flowpilot_mark),
                    contentDescription = "FlowPilot",
                    modifier = Modifier.padding(7.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row {
                    Text("Flow", style = MaterialTheme.typography.titleLarge, color = FlowPilotPalette.Ink)
                    Text("Pilot", style = MaterialTheme.typography.titleLarge, color = FlowPilotPalette.Accent)
                }
                Text(
                    "ANDROID AUTOMATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = FlowPilotPalette.AccentSoft,
        ) {
            Text(
                "ANDROID",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FlowPilotHero(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF075B36), Color(0xFF07C160)),
                ),
            )
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "FLOWPILOT · 设备端自动化",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.76f),
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.84f),
        )
    }
}

@Composable
private fun FlowPilotCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

@Composable
private fun TaskCard(task: AvailableTask, canInspectScript: Boolean, onRun: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "自动化任务 · ${sourceLabel(task.source)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("版本 ${task.version.ifBlank { "未发布" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (task.description.isNotBlank()) Text(task.description, style = MaterialTheme.typography.bodySmall)
            if (task.capabilities.isNotEmpty()) {
                Text(
                    "需要：${task.capabilities.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onRun,
                    enabled = canInspectScript && task.version.isNotBlank(),
                ) {
                    Text("查看脚本")
                }
            }
    }
}

private fun RemoteRun.asTask() = AvailableTask(
    id = taskId,
    name = taskName,
    description = "",
    version = version,
    capabilities = capabilities,
    source = source,
)

private fun sourceLabel(source: String): String = when (source) {
    "admin" -> "管理员发布"
    "user" -> "用户脚本"
    else -> source.ifBlank { "未知来源" }
}

private fun deviceStatusLabel(status: String): String = when (status) {
    "online", "active" -> "在线"
    "offline" -> "离线"
    "disabled" -> "已停用"
    else -> "未知"
}

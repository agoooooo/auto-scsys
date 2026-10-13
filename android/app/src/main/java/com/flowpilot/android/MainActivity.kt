package com.flowpilot.android

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val flowPilotViewModel: FlowPilotViewModel by viewModels()
    private val notificationPermissionGranted = mutableStateOf(false)
    private var startMonitoringAfterNotificationGrant = false
    private var startOcrStandbyAfterNotificationGrant = false
    private var startDefaultOcrAfterNotificationGrant = false
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
            if (remoteMonitoring.value || startRemoteMonitoring()) requestDefaultOcrStandby()
        }
        else if (granted && shouldStartMonitoring) startRemoteMonitoring()
        else if (granted && shouldStartOcrStandby) requestOcrStandbyCapturePermission()
        else if (!granted) flowPilotViewModel.showError("未授予通知权限，无法显示远程待命和运行状态")
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
                        remoteMonitoring = remoteMonitoring.value,
                        ocrStandbyActive = ocrStandbyActive.value,
                        onStartDefaultStandby = ::startDefaultStandby,
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
        remoteMonitoring.value = RemoteRunMonitorService.isMonitoring(this)
        ocrStandbyActive.value = OcrStandbyService.isActive(this)
        OcrStandbyService.consumeLastError(this)?.let(flowPilotViewModel::showError)
        flowPilotViewModel.refreshRunStatus()
        flowPilotViewModel.refreshRunHistory()
        flowPilotViewModel.refreshTasks()
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
        requestDefaultOcrStandby()
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
    remoteMonitoring: Boolean,
    ocrStandbyActive: Boolean,
    onStartDefaultStandby: () -> Unit,
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
    var email by rememberSaveable { mutableStateOf(state.loginEmail) }
    var password by rememberSaveable { mutableStateOf(state.loginPassword) }
    var rememberLoginInfo by rememberSaveable { mutableStateOf(state.rememberLoginInfo) }
    var defaultStandbyRequestedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTask by remember { mutableStateOf<AvailableTask?>(null) }
    var confirmDeviceUnbind by rememberSaveable { mutableStateOf(false) }
    var scriptReviewAttempt by remember { mutableStateOf<String?>(null) }
    var confirmScriptRun by rememberSaveable { mutableStateOf(false) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FlowPilotBrandHeader()

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
                    Text("连接服务并登录", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Android 自动化客户端 · 演示环境",
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
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                    Button(
                        onClick = { viewModel.login(apiBaseUrl, email, password, rememberLoginInfo) },
                        enabled = !state.loading && apiBaseUrl.isNotBlank() &&
                            email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("登录")
                    }
                }
            }
        } else {
            FlowPilotHero(
                title = "欢迎回来，${user.name}",
                subtitle = "任务在设备本地执行，运行结果一目了然。",
            )
            Row(
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
            if (!canLogout) {
                Text(
                    "任务结束前暂不能退出，以确保运行状态可以同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        Text(
                            "待命期间会显示持续服务通知。所有远程请求都会自动运行，无需逐项审阅或确认；任务可随时停止。若任务需要无障碍或屏幕捕获，仍须先在 Android 系统中启用或授权。持续 OCR 待命开启时可复用当前屏幕捕获授权；否则收到请求后打开 App 并按系统提示授权。",
                        )
                        if (!notificationPermissionGranted) {
                            Text(
                                "未授予通知权限，远程待命无法启动。点击下方按钮可再次请求通知权限。",
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
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("持续 OCR 待命", style = MaterialTheme.typography.titleMedium)
                            StatusBadge(
                                label = if (ocrStandbyActive) "捕获中" else "未开启",
                                status = if (ocrStandbyActive) "active" else "stopped",
                            )
                        }
                        Text(
                            "登录并绑定设备后会自动请求屏幕捕获授权；也可在此重新授权。待命时屏幕捕获会持续运行并显示独立的 Android 前台服务通知；确认过的远程 OCR 请求可复用本次会话，减少重复系统授权。停止待命、系统回收或撤销共享后，会话失效，后续 OCR 需要重新授权。",
                        )
                        Button(
                            onClick = { onSetOcrStandby(!ocrStandbyActive) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (ocrStandbyActive) "停止持续 OCR 待命" else "授权并开启持续 OCR 待命")
                        }
                    }
                }

                if (!accessibilityEnabled) {
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
                                Text("无障碍操作", style = MaterialTheme.typography.titleMedium)
                                StatusBadge(label = "未启用", status = "disabled")
                            }
                            Text(
                                "FlowPilot 仅在已启动任务时读取抖音或抖音极速版界面文字并点击脚本指定的文字。请在系统设置中手动启用；可随时关闭。",
                            )
                            TextButton(onClick = onOpenAccessibilitySettings) {
                                Text("打开系统无障碍设置")
                            }
                        }
                    }
                } else {
                    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("无障碍操作", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "本地及远程屏幕捕获均需 Android 系统授权；远程请求会在 App 前台弹窗审阅并发起授权。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusBadge(label = "已启用", status = "active")
                        }
                    }
                }

                if (state.pendingPublications.isNotEmpty()) {
                    Text("待确认的脚本发布（${state.pendingPublications.size}）", style = MaterialTheme.typography.titleLarge)
                    state.pendingPublications.forEach { publication ->
                        FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(publication.taskName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "版本 ${publication.version} · 来源 ${sourceLabel(publication.source)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "分发者：${publication.publisher}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (publication.description.isNotBlank()) Text(publication.description)
                                if (publication.notes.isNotBlank()) {
                                    Text("版本说明：${publication.notes}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "能力：${publication.capabilities.joinToString("、")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text("确认接收后，设备才能下载并运行此版本。")
                                Button(
                                    onClick = { viewModel.acceptPublication(publication) },
                                    enabled = !state.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("确认接收脚本")
                                }
                            }
                        }
                    }
                }

                Text("可用任务（${state.tasks.size}）", style = MaterialTheme.typography.titleLarge)
                if (state.tasks.isEmpty()) {
                    Text("当前没有分配给此账号的已发布任务。")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.tasks.forEach { task ->
                            TaskCard(
                                task = task,
                                canInspectScript = canInspectTaskScript(device.status, state.runStatus?.status),
                                onRun = {
                                    selectedTask = task
                                    confirmScriptRun = false
                                    scriptReviewAttempt = null
                                },
                            )
                        }
                    }
                }

                if (state.remoteRuns.isNotEmpty()) {
                    Text("远程请求（${state.remoteRuns.size}）", style = MaterialTheme.typography.titleLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.remoteRuns.forEach { request ->
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleRunHistory(state.runHistory).forEach { run ->
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

        if (state.loading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
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
private fun FlowPilotBrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.height(26.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(13.dp, 22.dp, 17.dp).forEachIndexed { index, height ->
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(height)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                FlowPilotPalette.Purple.copy(
                                    alpha = when (index) {
                                        0 -> 0.62f
                                        2 -> 0.8f
                                        else -> 1f
                                    },
                                ),
                            ),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row {
                    Text("Flow", style = MaterialTheme.typography.titleLarge, color = FlowPilotPalette.Ink)
                    Text("Pilot", style = MaterialTheme.typography.titleLarge, color = FlowPilotPalette.Purple)
                }
                Text(
                    "ANDROID AUTOMATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                "演示环境",
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
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
                    listOf(Color(0xFF7968ED), Color(0xFF574BD0)),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 19.dp),
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

@Composable
private fun TaskCard(task: AvailableTask, canInspectScript: Boolean, onRun: () -> Unit) {
    FlowPilotCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = FlowPilotPalette.PurpleSoft,
                ) {
                    Text(
                        "⌘",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        color = FlowPilotPalette.PurpleDark,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("当前发布版本", style = MaterialTheme.typography.bodySmall)
                StatusBadge(
                    label = task.version.ifBlank { "未发布" },
                    status = if (task.version.isBlank()) "unknown" else "published",
                )
            }
            if (task.description.isNotBlank()) Text(task.description)
            if (task.capabilities.isNotEmpty()) {
                Text(
                    "所需能力 · ${task.capabilities.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
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

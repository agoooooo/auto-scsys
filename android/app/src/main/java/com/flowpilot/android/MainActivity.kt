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
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < 33) startRemoteMonitoring()
        else flowPilotViewModel.showError("未授予通知权限，无法显示远程请求和运行状态")
    }
    private val screenCapturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
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
    private val remoteIntentId = mutableStateOf<String?>(null)
    private var pendingExecution: PendingExecution? = null
    private val runStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            flowPilotViewModel.refreshRunStatus()
            flowPilotViewModel.refreshRunHistory()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRemoteIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(FOREGROUND_HEARTBEAT_INTERVAL_MILLIS)
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
                        remoteIntentId = remoteIntentId.value,
                        accessibilityEnabled = accessibilityEnabled.value,
                        remoteMonitoring = remoteMonitoring.value,
                        onRunTask = ::beginExecution,
                        onDecideRemoteRun = ::decideRemoteRun,
                        onClearRemoteTarget = { remoteIntentId.value = null },
                        onSetRemoteMonitoring = ::setRemoteMonitoring,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onRememberLoginInfoChanged = flowPilotViewModel::setRememberLoginInfo,
                        onLogout = {
                            RemoteRunMonitorService.stop(this)
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
        handleRemoteIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = DouyinAccessibilityService.isEnabled(this)
        remoteMonitoring.value = RemoteRunMonitorService.isMonitoring(this)
        flowPilotViewModel.refreshRunStatus()
        flowPilotViewModel.refreshRunHistory()
        flowPilotViewModel.refreshRemoteRuns()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            runStatusReceiver,
            IntentFilter(TaskExecutionService.ACTION_RUN_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(runStatusReceiver)
        super.onStop()
    }

    private fun handleRemoteIntent(intent: Intent?) {
        val requestId = intent?.getStringExtra(EXTRA_REMOTE_REQUEST_ID)
        val runId = intent?.getStringExtra(EXTRA_REMOTE_RUN_ID)
        if (requestId != null || runId != null) {
            remoteIntentId.value = requestId ?: runId
            flowPilotViewModel.refreshRemoteRuns()
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
            if (requiresScreenCapture(task.capabilities)) {
                beginScreenCaptureExecution(task, runId, remoteRequestId)
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
        beginScreenCaptureExecution(task, runId, remoteRequestId)
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

    private fun decideRemoteRun(request: RemoteRun, approve: Boolean, preauthorize: Boolean) {
        if (approve && !remoteRunCanBeApproved(
                accessibilityEnabled.value || !requiresAccessibility(request.capabilities),
                RunStatusStore(this).snapshot().status,
            )
        ) {
            flowPilotViewModel.showError(
                if (!accessibilityEnabled.value) {
                    "请先启用无障碍服务，再确认远程任务"
                } else {
                    "当前已有任务运行，请先停止或等待完成"
                },
            )
            return
        }
        RemoteRunMonitorService.markRequestHandled(this, request.id)
        lifecycleScope.launch {
            try {
                val runId = flowPilotViewModel.decideRemoteRun(request, approve, preauthorize)
                if (approve && runId != null) {
                    beginExecution(request.asTask(), runId, request.id)
                }
                flowPilotViewModel.refreshRemoteRuns()
                flowPilotViewModel.refreshRunAuthorizations()
            } catch (exception: Exception) {
                flowPilotViewModel.showError(exception.message ?: "无法处理远程运行请求")
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

    private fun setRemoteMonitoring(enabled: Boolean) {
        if (!enabled) {
            RemoteRunMonitorService.stop(this)
            remoteMonitoring.value = false
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startRemoteMonitoring()
        }
    }

    private fun startRemoteMonitoring() {
        try {
            RemoteRunMonitorService.start(this)
            remoteMonitoring.value = true
        } catch (exception: Exception) {
            flowPilotViewModel.showError(exception.message ?: "无法启动远程待命服务")
        }
    }

    private data class PendingExecution(
        val task: AvailableTask,
        val runId: String?,
        val remoteRequestId: String?,
    )

    companion object {
        const val EXTRA_REMOTE_REQUEST_ID = "remote_request_id"
        const val EXTRA_REMOTE_RUN_ID = "remote_run_id"
        const val EXTRA_REMOTE_TASK_ID = "remote_task_id"
        const val EXTRA_REMOTE_TASK_NAME = "remote_task_name"
        const val EXTRA_REMOTE_TASK_VERSION = "remote_task_version"
        const val EXTRA_REMOTE_DEVICE_ID = "remote_device_id"
        private const val FOREGROUND_HEARTBEAT_INTERVAL_MILLIS = 60_000L
    }
}

@Composable
private fun FlowPilotScreen(
    viewModel: FlowPilotViewModel,
    remoteIntentId: String?,
    accessibilityEnabled: Boolean,
    remoteMonitoring: Boolean,
    onRunTask: (AvailableTask, String?, String?) -> Unit,
    onDecideRemoteRun: (RemoteRun, Boolean, Boolean) -> Unit,
    onClearRemoteTarget: () -> Unit,
    onSetRemoteMonitoring: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRememberLoginInfoChanged: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onStopTask: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user = state.user
    val device = state.device
    val canLogout = canLogoutDuringRun(state.runStatus?.status)
    var apiBaseUrl by rememberSaveable { mutableStateOf(state.apiBaseUrl) }
    var email by rememberSaveable { mutableStateOf(state.loginEmail) }
    var password by rememberSaveable { mutableStateOf(state.loginPassword) }
    var rememberLoginInfo by rememberSaveable { mutableStateOf(state.rememberLoginInfo) }
    var selectedRemoteRun by remember { mutableStateOf<RemoteRun?>(null) }
    var selectedTask by remember { mutableStateOf<AvailableTask?>(null) }
    var authorizationToRevoke by remember { mutableStateOf<String?>(null) }
    var confirmDeviceUnbind by rememberSaveable { mutableStateOf(false) }
    var scriptReviewAttempt by remember { mutableStateOf<String?>(null) }
    var preauthorize by rememberSaveable { mutableStateOf(true) }
    var confirmScriptRun by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(user?.id, device?.id) {
        if (user != null && device != null) {
            viewModel.refreshRemoteRuns()
            viewModel.refreshRunAuthorizations()
        } else if (user != null) {
            onSetRemoteMonitoring(false)
        }
    }
    LaunchedEffect(remoteIntentId, state.remoteRuns) {
        if (remoteIntentId != null) {
            val request = state.remoteRuns.firstOrNull { it.id == remoteIntentId }
            if (request?.status == "running" && request.runId != null) {
                onRunTask(request.asTask(), request.runId, request.id)
                onClearRemoteTarget()
            } else {
                selectedRemoteRun = request
            }
        }
    }
    LaunchedEffect(selectedTask?.id, selectedTask?.version, selectedRemoteRun?.id, state.loading) {
        if (state.loading) return@LaunchedEffect
        val task = selectedTask ?: selectedRemoteRun?.takeIf { it.status == "pending" }?.asTask()
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
                subtitle = "审阅任务脚本，在设备端确认并运行。",
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
                                    viewModel.refreshRunAuthorizations()
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
                                    viewModel.refreshRunAuthorizations()
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
                            Text("远程运行预授权", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = viewModel::refreshRunAuthorizations,
                                enabled = !state.runAuthorizationsLoading,
                            ) {
                                Text(if (state.runAuthorizationsLoading) "刷新中" else "刷新")
                            }
                        }
                        Text(
                            "仅列出当前账号仍有效的任务和设备授权；“该版本及后续版本”表示从当前审阅版本起生效，撤销后不再自动运行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.runAuthorizationsError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                        if (state.runAuthorizations.isEmpty() && !state.runAuthorizationsLoading &&
                            state.runAuthorizationsError == null
                        ) {
                            Text("当前没有有效预授权。")
                        }
                        state.runAuthorizations.forEach { authorization ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        "${authorization.taskName} · ${
                                            runAuthorizationVersionLabel(authorization.version, authorization.allVersions)
                                        }",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "${authorization.deviceName} · 有效期至 ${
                                            formatEpochSeconds(authorization.expiresAt) ?: "时间未知"
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { authorizationToRevoke = authorization.id },
                                    enabled = !state.loading,
                                ) {
                                    Text("撤销")
                                }
                            }
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
                            "待命期间会显示持续通知。预授权限当前任务及此设备，包含该版本和后续版本 30 天；无需逐次系统授权的任务可自动运行并可随时停止。远程 OCR 会通知你打开 App；确认请求后仍需通过 Android 屏幕捕获系统授权。",
                        )
                        Button(
                            onClick = { onSetRemoteMonitoring(!remoteMonitoring) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (remoteMonitoring) "停止远程待命" else "开启远程待命")
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
                                    "本地及远程屏幕捕获均需 Android 系统授权；远程请求可通过通知打开 App 后审阅并发起授权。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusBadge(label = "已启用", status = "active")
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
                                        Button(onClick = { selectedRemoteRun = request }) {
                                            Text("查看并确认")
                                        }
                                    } else if (request.runId != null) {
                                        Button(
                                            onClick = {
                                                onRunTask(request.asTask(), request.runId, request.id)
                                            },
                                            enabled = (!requiresAccessibility(request.capabilities) || accessibilityEnabled) &&
                                                        state.runStatus?.status !in setOf("running", "awaiting_permission"),
                                        ) {
                                            Text("继续已授权任务")
                                        }
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

    selectedRemoteRun?.let { request ->
        val review = state.scriptReview?.takeIf {
            it.task.id == request.taskId && it.task.version == request.version
        }
        AlertDialog(
            onDismissRequest = {
                selectedRemoteRun = null
                scriptReviewAttempt = null
                viewModel.clearScriptReview()
                onClearRemoteTarget()
            },
            title = {
                Text(if (request.status == "pending") "确认远程运行" else "继续已预授权任务")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        buildString {
                            append("任务：${request.taskName}\n版本：${request.version}\n发起人：${request.requestedBy}")
                            formatEpochSeconds(request.expiresAt)?.let { append("\n请求有效期至：$it") }
                        },
                    )
                    Text("来源：${sourceLabel(request.source)}")
                    if (request.capabilities.isNotEmpty()) {
                        Text("声明能力：${request.capabilities.joinToString("、")}")
                    }
                    if (state.loading && review == null) {
                        CircularProgressIndicator()
                    } else if (review == null) {
                        Text("无法读取并校验脚本内容。请关闭后刷新请求。")
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
                    if (request.status == "pending") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = preauthorize,
                                onCheckedChange = { preauthorize = it },
                            )
                            Text("为此任务、此设备及后续版本预授权 30 天")
                        }
                    }
                    Text(
                        if (requiresScreenCapture(request.capabilities)) {
                            "确认后仍需通过 Android 系统屏幕捕获授权，拒绝系统授权则任务不会运行。此授权由系统控制，不能预先或静默授予。"
                        } else {
                            "授权后，后续不需要逐次系统授权的远程版本会自动在可见前台服务中运行并可停止。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Python 脚本在应用进程内运行，不是安全沙箱。只运行你信任且已检查的代码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedRemoteRun = null
                        scriptReviewAttempt = null
                        viewModel.clearScriptReview()
                        if (request.status == "pending") {
                            onDecideRemoteRun(request, true, preauthorize)
                        } else if (request.runId != null) {
                            onRunTask(request.asTask(), request.runId, request.id)
                        }
                        onClearRemoteTarget()
                    },
                    enabled = request.status != "pending" || review != null,
                ) {
                    Text(if (request.status == "pending") "确认并运行" else "继续")
                }
            },
            dismissButton = {
                if (request.status == "pending") {
                    TextButton(
                        onClick = {
                            selectedRemoteRun = null
                            scriptReviewAttempt = null
                            viewModel.clearScriptReview()
                            onDecideRemoteRun(request, false, false)
                            onClearRemoteTarget()
                        },
                    ) {
                        Text("拒绝")
                    }
                } else {
                    TextButton(
                        onClick = {
                            selectedRemoteRun = null
                            scriptReviewAttempt = null
                            viewModel.clearScriptReview()
                            onClearRemoteTarget()
                        },
                    ) {
                        Text("稍后")
                    }
                }
            },
        )
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

    val authorization = state.runAuthorizations.firstOrNull { it.id == authorizationToRevoke }
    if (authorization != null) {
        AlertDialog(
            onDismissRequest = { authorizationToRevoke = null },
            title = { Text("撤销运行预授权？") },
            text = {
                Text("${authorization.taskName} · ${authorization.version}\n设备：${authorization.deviceName}")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        authorizationToRevoke = null
                        viewModel.revokeRunAuthorization(authorization.id)
                    },
                    enabled = !state.loading,
                ) {
                    Text("确认撤销")
                }
            },
            dismissButton = {
                TextButton(onClick = { authorizationToRevoke = null }) {
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

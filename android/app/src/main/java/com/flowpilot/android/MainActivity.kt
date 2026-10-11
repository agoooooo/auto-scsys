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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            if (pending.runId != null) {
                lifecycleScope.launch {
                    runCatching {
                        flowPilotViewModel.failRun(pending.runId, "用户未授予 Android 屏幕捕获权限")
                    }.onFailure {
                        flowPilotViewModel.showError("屏幕捕获被拒绝；运行结果同步失败：${it.message}")
                    }
                }
            }
            RunStatusStore(this).update(
                pending.runId.orEmpty(),
                pending.task.name,
                "failure",
                "屏幕捕获授权被拒绝，任务未执行",
            )
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
                    packageManager.getLaunchIntentForPackage(DouyinAccessibilityService.DOUYIN_PACKAGE)
                        ?: throw IllegalStateException("未检测到已安装的抖音，请先安装后再运行任务")
                } else {
                    null
                }
                RunStatusStore(this@MainActivity)
                    .update(activeRunId, pending.task.name, "running", "正在启动任务")
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
                    "failure",
                    original,
                )
                flowPilotViewModel.refreshRunStatus()
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRemoteIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
        if (!accessibilityEnabled.value) {
            flowPilotViewModel.showError("请先启用 FlowPilot 自动化操作服务，再运行需要点击界面的任务")
            return
        }
        val currentStatus = RunStatusStore(this).snapshot().status
        if (currentStatus == "running" || currentStatus == "awaiting_permission") {
            flowPilotViewModel.showError("当前已有任务运行，请先停止或等待完成")
            return
        }
        RunStatusStore(this).update(
            runId.orEmpty(),
            task.name,
            "awaiting_permission",
            "等待 Android 屏幕捕获授权",
        )
        flowPilotViewModel.refreshRunStatus()
        pendingExecution = PendingExecution(task, runId, remoteRequestId)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun decideRemoteRun(request: RemoteRun, approve: Boolean, preauthorize: Boolean) {
        RemoteRunMonitorService.markRequestHandled(this, request.id)
        lifecycleScope.launch {
            try {
                val runId = flowPilotViewModel.decideRemoteRun(request, approve, preauthorize)
                if (approve && runId != null) {
                    beginExecution(request.asTask(), runId, request.id)
                }
                flowPilotViewModel.refreshRemoteRuns()
            } catch (exception: Exception) {
                flowPilotViewModel.showError(exception.message ?: "无法处理远程运行请求")
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
    onLogout: () -> Unit,
    onStopTask: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user = state.user
    val device = state.device
    var apiBaseUrl by rememberSaveable { mutableStateOf(state.apiBaseUrl) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var selectedRemoteRun by remember { mutableStateOf<RemoteRun?>(null) }
    var selectedTask by remember { mutableStateOf<AvailableTask?>(null) }
    var scriptReviewAttempt by remember { mutableStateOf<String?>(null) }
    var preauthorize by rememberSaveable { mutableStateOf(true) }
    var confirmScriptRun by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(user?.id, device?.id) {
        if (user != null && device != null) viewModel.refreshRemoteRuns()
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("FlowPilot", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Android 自动化客户端 · 演示服务端",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (user == null) {
            Text("连接服务并登录", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = apiBaseUrl,
                onValueChange = { apiBaseUrl = it },
                label = { Text("API 地址") },
                placeholder = { Text("http://192.168.0.101:8001") },
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
            Button(
                onClick = { viewModel.login(apiBaseUrl, email, password) },
                enabled = !state.loading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("登录")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(user.name, style = MaterialTheme.typography.titleLarge)
                    Text(user.email, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onLogout, enabled = !state.loading) {
                    Text("退出")
                }
            }

            if (device == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("已绑定设备", style = MaterialTheme.typography.titleMedium)
                        Text("${device.name} · ${device.model}")
                        Text("设备 ID：${device.id}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.refreshTasks()
                                    viewModel.refreshRemoteRuns()
                                },
                                enabled = !state.loading,
                            ) {
                                Text("同步")
                            }
                            TextButton(
                                onClick = { viewModel.refreshRemoteRuns() },
                                enabled = !state.loading,
                            ) {
                                Text("刷新远程请求")
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("远程运行待命", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "待命期间会显示持续通知。预授权只限明确任务和此设备；运行仍需屏幕捕获系统授权，并可随时停止。",
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("需要启用无障碍操作", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "FlowPilot 仅在已启动任务时读取抖音界面文字并点击脚本指定的文字。请在系统设置中手动启用；可随时关闭。",
                            )
                            TextButton(onClick = onOpenAccessibilitySettings) {
                                Text("打开系统无障碍设置")
                            }
                        }
                    }
                } else {
                    Text(
                        "无障碍服务已启用；屏幕捕获仍由 Android 在每次任务启动时单独授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(request.taskName, style = MaterialTheme.typography.titleMedium)
                                    Text("版本 ${request.version} · ${if (request.status == "pending") "等待确认" else "已预授权"}")
                                    Text("来源：${sourceLabel(request.source)}")
                                    if (request.status == "pending") {
                                        Button(onClick = { selectedRemoteRun = request }) {
                                            Text("查看并确认")
                                        }
                                    } else if (request.runId != null) {
                                        Button(
                                            onClick = {
                                                onRunTask(request.asTask(), request.runId, request.id)
                                            },
                                            enabled = accessibilityEnabled &&
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

                state.runStatus?.let { runStatus ->
                    if (runStatus.status != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("最近一次运行", style = MaterialTheme.typography.titleMedium)
                                Text("${runStatus.taskName.orEmpty()} · ${runStatus.status}")
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
                    Text("任务：${request.taskName}\n版本：${request.version}\n发起人：${request.requestedBy}")
                    Text("来源：${sourceLabel(request.source)}")
                    if (request.capabilities.isNotEmpty()) {
                        Text("声明能力：${request.capabilities.joinToString("、")}")
                    }
                    if (state.loading && review == null) {
                        CircularProgressIndicator()
                    } else if (review == null) {
                        Text("无法读取并校验脚本内容。请关闭后刷新请求。")
                    } else {
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
                            Text("同时为此任务和设备预授权 30 天")
                        }
                    }
                    Text(
                        "确认后会显示前台运行通知并提供停止入口；Android 屏幕捕获仍需系统授权。",
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
            it.task.id == task.id && it.task.version == task.version
        }
        AlertDialog(
            onDismissRequest = {
                selectedTask = null
                scriptReviewAttempt = null
                viewModel.clearScriptReview()
            },
            title = { Text("运行前检查脚本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${task.name} · 版本 ${task.version}")
                    Text("来源：${sourceLabel(task.source)}")
                    if (task.description.isNotBlank()) Text(task.description)
                    if (task.capabilities.isNotEmpty()) {
                        Text("声明能力：${task.capabilities.joinToString("、")}")
                    }
                    if (!accessibilityEnabled) {
                        Text("查看脚本不需要无障碍权限；运行前请先在 Android 系统设置中手动启用 FlowPilot 无障碍服务。")
                    }
                    if (state.loading && review == null) {
                        CircularProgressIndicator()
                    } else if (review == null) {
                        Text("无法读取并校验脚本内容。请关闭后重试。")
                    } else {
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
                        onRunTask(task, null, null)
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
private fun TaskCard(task: AvailableTask, canInspectScript: Boolean, onRun: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(task.name, style = MaterialTheme.typography.titleMedium)
            Text("当前版本：${task.version.ifBlank { "未发布" }}")
            if (task.description.isNotBlank()) Text(task.description)
            if (task.capabilities.isNotEmpty()) {
                Text("所需能力：${task.capabilities.joinToString("、")}")
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

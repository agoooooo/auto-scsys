package com.flowpilot.controller

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ControllerScreenState(
    val apiUrl: String = defaultControllerApiUrl(),
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val isRegistering: Boolean = false,
    val session: ControllerSession? = null,
    val devices: List<ControllerDevice> = emptyList(),
    val tasks: List<ControllerTask> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedTaskId: String? = null,
    val run: ControllerRunStatus? = null,
    val loading: Boolean = false,
    val dispatchUncertain: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class MainActivity : ComponentActivity() {
    private lateinit var sessionStore: ControllerSessionStore
    private var state by mutableStateOf(ControllerScreenState())
    private var monitorJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = ControllerSessionStore(this)
        sessionStore.read()?.let { session ->
            state = state.copy(apiUrl = session.apiUrl, email = session.email, session = session, loading = true)
            lifecycleScope.launch { loadDashboard(session) }
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ControllerScreen(
                        state = state,
                        onApiUrlChange = { state = state.copy(apiUrl = it) },
                        onNameChange = { state = state.copy(name = it) },
                        onEmailChange = { state = state.copy(email = it) },
                        onPasswordChange = { state = state.copy(password = it) },
                        onToggleRegister = {
                            state = state.copy(isRegistering = !state.isRegistering, error = null, message = null)
                        },
                        onLoginOrRegister = ::loginOrRegister,
                        onRefresh = ::refreshDashboard,
                        onSelectDevice = ::selectDevice,
                        onSelectTask = { state = state.copy(selectedTaskId = it) },
                        onDispatch = ::dispatch,
                        onCancelPending = ::cancelPendingRun,
                        onLogout = ::logout,
                    )
                }
            }
        }
    }

    private fun loginOrRegister() {
        if (state.loading) return
        val snapshot = state
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                val apiUrl = normalizeControllerApiUrl(snapshot.apiUrl)
                val api = ControllerApi(apiUrl)
                if (snapshot.isRegistering) {
                    withContext(Dispatchers.IO) {
                        api.register(snapshot.name.trim(), snapshot.email.trim(), snapshot.password)
                    }
                    state = state.copy(
                        apiUrl = apiUrl,
                        email = snapshot.email.trim(),
                        password = "",
                        isRegistering = false,
                        loading = false,
                        message = "注册成功，请使用新账号登录",
                    )
                } else {
                    val login = withContext(Dispatchers.IO) {
                        api.login(snapshot.email.trim(), snapshot.password)
                    }
                    if (login.user.role != "user") throw IllegalStateException("控制端仅支持普通用户账号")
                    val session = ControllerSession(apiUrl, login.token, login.user.email)
                    sessionStore.save(session)
                    state = state.copy(
                        apiUrl = apiUrl,
                        password = "",
                        session = session,
                        loading = true,
                    )
                    loadDashboard(session)
                }
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "请求失败")
            }
        }
    }

    private suspend fun loadDashboard(session: ControllerSession) {
        try {
            val previousDeviceId = state.selectedDeviceId
            val (devices, tasks, activeRun) = withContext(Dispatchers.IO) {
                val api = ControllerApi(session.apiUrl)
                val devices = api.devices(session.token)
                val tasks = api.tasks(session.token)
                val selectedId = previousDeviceId?.takeIf { id -> devices.any { it.id == id } }
                    ?: devices.firstOrNull()?.id
                Triple(
                    devices,
                    tasks,
                    selectedId?.let { api.activeRemoteRun(session.token, it) },
                )
            }
            val selectedDevice = state.selectedDeviceId?.takeIf { id -> devices.any { it.id == id } }
                ?: devices.firstOrNull()?.id
            val availableTasks = tasks.filter { it.status == "published" && it.version.isNotBlank() }
            val selectedTask = state.selectedTaskId?.takeIf { id -> availableTasks.any { it.id == id } }
                ?: availableTasks.firstOrNull()?.id
            state = state.copy(
                devices = devices,
                tasks = tasks,
                selectedDeviceId = selectedDevice,
                selectedTaskId = selectedTask,
                run = activeRun,
                loading = false,
                dispatchUncertain = false,
                error = null,
                message = null,
            )
            monitorJob?.cancel()
            activeRun?.let { run ->
                monitorJob = lifecycleScope.launch { monitorRun(session, run.id) }
            }
        } catch (error: Exception) {
            if (error is ControllerApiException && error.statusCode == 401) {
                sessionStore.clear()
                state = state.copy(session = null, loading = false, error = "登录已过期，请重新登录")
            } else {
                state = state.copy(loading = false, error = error.message ?: "加载设备和任务失败")
            }
        }
    }

    private fun refreshDashboard() {
        val session = state.session ?: return
        state = state.copy(loading = true)
        lifecycleScope.launch { loadDashboard(session) }
    }

    private fun selectDevice(deviceId: String) {
        val session = state.session ?: return
        monitorJob?.cancel()
        state = state.copy(
            selectedDeviceId = deviceId,
            run = null,
            loading = true,
            dispatchUncertain = false,
            error = null,
            message = null,
        )
        lifecycleScope.launch {
            try {
                val activeRun = withContext(Dispatchers.IO) {
                    ControllerApi(session.apiUrl).activeRemoteRun(session.token, deviceId)
                }
                state = state.copy(run = activeRun, loading = false)
                activeRun?.let { monitorJob = lifecycleScope.launch { monitorRun(session, it.id) } }
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "读取设备任务状态失败")
            }
        }
    }

    private fun dispatch() {
        val session = state.session ?: return
        val device = state.devices.firstOrNull { it.id == state.selectedDeviceId } ?: return
        val task = state.tasks.firstOrNull { it.id == state.selectedTaskId } ?: return
        if (!canDispatchControllerTask(device.status, task.status, task.version) || state.loading) return
        state = state.copy(loading = true, error = null, message = null, run = null)
        lifecycleScope.launch {
            try {
                val requestId = withContext(Dispatchers.IO) {
                    ControllerApi(session.apiUrl).dispatch(session.token, task.id, device.id)
                }
                state = state.copy(
                    loading = false,
                    dispatchUncertain = false,
                    run = ControllerRunStatus(requestId, "pending", null, ""),
                    message = "任务已下发；请保持本页面打开以跟踪状态",
                )
                monitorJob?.cancel()
                monitorJob = lifecycleScope.launch { monitorRun(session, requestId) }
            } catch (error: Exception) {
                state = state.copy(
                    loading = false,
                    dispatchUncertain = true,
                    error = error.message ?: "下发任务失败",
                    message = "派发结果可能尚未确认；请先刷新并检查设备请求，避免重复下发。",
                )
            }
        }
    }

    private suspend fun monitorRun(session: ControllerSession, requestId: String) {
        repeat(MAX_STATUS_POLLS) {
            try {
                val latest = withContext(Dispatchers.IO) {
                    ControllerApi(session.apiUrl).runStatus(session.token, requestId)
                }
                state = state.copy(run = latest)
                if (!isControllerRunActive(latest.status)) return
            } catch (error: Exception) {
                state = state.copy(error = error.message ?: "刷新运行状态失败")
                return
            }
            delay(STATUS_POLL_INTERVAL_MS)
        }
        if (state.run?.id == requestId && isControllerRunActive(state.run?.status.orEmpty())) {
            state = state.copy(message = "仍在等待设备状态；可点击刷新查看最新状态")
        }
    }

    private fun cancelPendingRun() {
        val session = state.session ?: return
        val run = state.run?.takeIf { it.status == "pending" } ?: return
        if (state.loading) return
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ControllerApi(session.apiUrl).rejectRemoteRun(session.token, run.id)
                }
                monitorJob?.cancel()
                state = state.copy(
                    loading = false,
                    run = run.copy(status = "rejected"),
                    message = "等待中的任务已取消",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "取消任务失败")
            }
        }
    }

    private fun logout() {
        val session = state.session ?: return
        monitorJob?.cancel()
        state = state.copy(loading = true, error = null)
        lifecycleScope.launch {
            var logoutError: String? = null
            try {
                withContext(Dispatchers.IO) { ControllerApi(session.apiUrl).logout(session.token) }
            } catch (error: Exception) {
                logoutError = error.message ?: "服务端退出失败"
            } finally {
                sessionStore.clear()
                state = ControllerScreenState(
                    apiUrl = session.apiUrl,
                    email = session.email,
                    error = logoutError,
                    message = if (logoutError == null) "已退出登录" else null,
                )
            }
        }
    }

    private companion object {
        const val STATUS_POLL_INTERVAL_MS = 5_000L
        const val MAX_STATUS_POLLS = 360
    }
}

private fun defaultControllerApiUrl(): String {
    val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("Emulator", ignoreCase = true)
    return if (isEmulator) "http://10.0.2.2:8001" else "http://192.168.0.101:8001"
}

@Composable
private fun ControllerScreen(
    state: ControllerScreenState,
    onApiUrlChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleRegister: () -> Unit,
    onLoginOrRegister: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onSelectTask: (String) -> Unit,
    onDispatch: () -> Unit,
    onCancelPending: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FlowPilot 控制端", style = MaterialTheme.typography.headlineMedium)
        Text("管理你的受控设备，并在授权范围内下发脚本任务。")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.session == null) {
            OutlinedTextField(
                value = state.apiUrl,
                onValueChange = onApiUrlChange,
                label = { Text("服务端 API 地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (state.isRegistering) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("姓名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                label = { Text("邮箱") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            if (state.isRegistering) Text("注册仅创建普通用户；密码至少 12 位。")
            Button(onClick = onLoginOrRegister, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isRegistering) "注册" else "登录")
            }
            TextButton(onClick = onToggleRegister, enabled = !state.loading) {
                Text(if (state.isRegistering) "已有账号？返回登录" else "没有账号？注册")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.session.email, modifier = Modifier.weight(1f))
                TextButton(onClick = onLogout, enabled = !state.loading) { Text("退出") }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
            }
            if (state.loading) CircularProgressIndicator()
            Selector(
                title = "受控设备",
                selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }?.let {
                    "${it.name} · ${it.model} · ${controllerDeviceStatusLabel(it.status)}"
                } ?: "暂无已绑定设备",
                enabled = state.devices.isNotEmpty(),
                options = state.devices.map {
                    it.id to "${it.name} · ${it.model} · ${controllerDeviceStatusLabel(it.status)}"
                },
                onSelect = onSelectDevice,
            )
            Selector(
                title = "已发布任务",
                selected = state.tasks.firstOrNull { it.id == state.selectedTaskId }?.let {
                    "${it.name} · ${it.version}"
                } ?: "暂无可运行的已发布任务",
                enabled = state.tasks.any { it.status == "published" && it.version.isNotBlank() },
                options = state.tasks.filter { it.status == "published" && it.version.isNotBlank() }
                    .map { it.id to "${it.name} · ${it.version}" },
                onSelect = onSelectTask,
            )
            val device = state.devices.firstOrNull { it.id == state.selectedDeviceId }
            val task = state.tasks.firstOrNull { it.id == state.selectedTaskId }
            Button(
                onClick = onDispatch,
                enabled = !state.loading && device != null && task != null &&
                    canDispatchControllerTask(device.status, task.status, task.version) &&
                    !state.dispatchUncertain &&
                    state.run?.let { isControllerRunActive(it.status) } != true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("下发运行任务")
            }
            Text("仅可向在线设备下发；受控端需处于远程待命状态。")
            state.run?.let { run ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("最近一次运行", style = MaterialTheme.typography.titleMedium)
                        Text(controllerRunStatusLabel(run.status))
                        run.runStatus?.takeIf(String::isNotBlank)?.let {
                            Text("执行状态：${controllerRunStatusLabel(it)}")
                        }
                        run.resultSummary.takeIf(String::isNotBlank)?.let { Text("结果：$it") }
                        Text("请求 ID：${run.id}", style = MaterialTheme.typography.bodySmall)
                        if (run.status == "pending") {
                            OutlinedButton(onClick = onCancelPending, enabled = !state.loading) {
                                Text("取消等待中的任务")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("控制端仅负责派发与跟踪，不包含脚本执行运行时。运行中停止功能尚待服务端和受控端停止协议支持。")
        }
    }
}

@Composable
private fun Selector(
    title: String,
    selected: String,
    enabled: Boolean,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

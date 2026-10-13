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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import java.util.UUID

private data class ControllerScreenState(
    val apiUrl: String = defaultControllerApiUrl(),
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val isRegistering: Boolean = false,
    val session: ControllerSession? = null,
    val profileName: String = "",
    val profileEmail: String = "",
    val profileCurrentPassword: String = "",
    val currentPassword: String = "",
    val newPassword: String = "",
    val devices: List<ControllerDevice> = emptyList(),
    val tasks: List<ControllerTask> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedTaskId: String? = null,
    val run: ControllerRunStatus? = null,
    val loading: Boolean = false,
    val dispatchUncertain: Boolean = false,
    val dispatchIdempotencyKey: String? = null,
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
            state = state.copy(
                apiUrl = session.apiUrl,
                email = session.email,
                session = session,
                profileName = session.name,
                profileEmail = session.email,
                loading = true,
            )
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
                        onProfileNameChange = { state = state.copy(profileName = it) },
                        onProfileEmailChange = { state = state.copy(profileEmail = it) },
                        onProfileCurrentPasswordChange = { state = state.copy(profileCurrentPassword = it) },
                        onCurrentPasswordChange = { state = state.copy(currentPassword = it) },
                        onNewPasswordChange = { state = state.copy(newPassword = it) },
                        onToggleRegister = {
                            state = state.copy(isRegistering = !state.isRegistering, error = null, message = null)
                        },
                        onLoginOrRegister = ::loginOrRegister,
                        onRefresh = ::refreshDashboard,
                        onSaveProfile = ::saveProfile,
                        onChangePassword = ::changePassword,
                        onSelectDevice = ::selectDevice,
                        onSelectTask = {
                            state = state.copy(
                                selectedTaskId = it,
                                dispatchIdempotencyKey = null,
                                dispatchUncertain = false,
                            )
                        },
                        onDispatch = ::dispatch,
                        onCancelPending = ::cancelPendingRun,
                        onStopRunning = ::stopRunningRun,
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
                    val session = ControllerSession(apiUrl, login.token, login.user.email, login.user.name)
                    sessionStore.save(session)
                    state = state.copy(
                        apiUrl = apiUrl,
                        password = "",
                        session = session,
                        profileName = session.name,
                        profileEmail = session.email,
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
            val (dashboard, profile) = withContext(Dispatchers.IO) {
                val api = controllerApi(session)
                val devices = api.devices(session.token)
                val tasks = api.tasks(session.token)
                val profile = api.profile(session.token)
                val selectedId = previousDeviceId?.takeIf { id -> devices.any { it.id == id } }
                    ?: devices.firstOrNull()?.id
                Pair(
                    Triple(
                        devices,
                        tasks,
                        selectedId?.let { api.activeRemoteRun(session.token, it) },
                    ),
                    profile,
                )
            }
            val (devices, tasks, activeRun) = dashboard
            val selectedDevice = state.selectedDeviceId?.takeIf { id -> devices.any { it.id == id } }
                ?: devices.firstOrNull()?.id
            val availableTasks = tasks.filter { it.status == "published" && it.version.isNotBlank() }
            val selectedTask = state.selectedTaskId?.takeIf { id -> availableTasks.any { it.id == id } }
                ?: availableTasks.firstOrNull()?.id
            val updatedSession = session.copy(
                token = sessionStore.read()?.token ?: session.token,
                name = profile.name,
                email = profile.email,
            )
            sessionStore.save(updatedSession)
            state = state.copy(
                session = updatedSession,
                profileName = profile.name,
                profileEmail = profile.email,
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

    private fun controllerApi(session: ControllerSession) =
        ControllerApi(
            session.apiUrl,
            initialAccessToken = sessionStore.read()?.token ?: session.token,
            onAccessTokenRefreshed = { refreshedToken ->
                sessionStore.save(session.copy(token = refreshedToken))
            },
            onSessionExpired = {
                runOnUiThread {
                    monitorJob?.cancel()
                    sessionStore.clear()
                    state = state.copy(
                        session = null,
                        loading = false,
                        error = "登录已过期，请重新登录",
                        message = null,
                    )
                }
            },
        )

    private fun saveProfile() {
        val session = state.session ?: return
        if (state.loading) return
        val profileName = state.profileName.trim()
        val profileEmail = state.profileEmail.trim()
        if (profileName.isBlank() || profileEmail.isBlank()) {
            state = state.copy(error = "姓名和邮箱不能为空")
            return
        }
        val currentPassword = state.profileCurrentPassword
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                val user = withContext(Dispatchers.IO) {
                    controllerApi(session).updateProfile(
                        session.token,
                        profileName,
                        profileEmail,
                        currentPassword,
                    )
                }
                val updatedSession = session.copy(
                    token = sessionStore.read()?.token ?: session.token,
                    email = user.email,
                    name = user.name,
                )
                sessionStore.save(updatedSession)
                state = state.copy(
                    session = updatedSession,
                    profileName = user.name,
                    profileEmail = user.email,
                    profileCurrentPassword = "",
                    loading = false,
                    message = "个人资料已更新",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "更新个人资料失败")
            }
        }
    }

    private fun changePassword() {
        val session = state.session ?: return
        if (state.loading) return
        if (state.currentPassword.isBlank() || state.newPassword.length < 12) {
            state = state.copy(error = "请输入当前密码和至少 12 位的新密码")
            return
        }
        val currentPassword = state.currentPassword
        val newPassword = state.newPassword
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controllerApi(session).changePassword(
                        session.token,
                        currentPassword,
                        newPassword,
                    )
                }
                state = state.copy(
                    loading = false,
                    currentPassword = "",
                    newPassword = "",
                    message = "密码已更新，其他登录会话已退出",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "修改密码失败")
            }
        }
    }

    private fun selectDevice(deviceId: String) {
        val session = state.session ?: return
        monitorJob?.cancel()
        state = state.copy(
            selectedDeviceId = deviceId,
            run = null,
            loading = true,
            dispatchUncertain = false,
            dispatchIdempotencyKey = null,
            error = null,
            message = null,
        )
        lifecycleScope.launch {
            try {
                val activeRun = withContext(Dispatchers.IO) {
                    controllerApi(session).activeRemoteRun(session.token, deviceId)
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
        val idempotencyKey = state.dispatchIdempotencyKey ?: UUID.randomUUID().toString()
        state = state.copy(
            loading = true,
            error = null,
            message = null,
            run = null,
            dispatchIdempotencyKey = idempotencyKey,
        )
        lifecycleScope.launch {
            try {
                val requestId = withContext(Dispatchers.IO) {
                    controllerApi(session).dispatch(session.token, task.id, device.id, idempotencyKey)
                }
                state = state.copy(
                    loading = false,
                    dispatchUncertain = false,
                    dispatchIdempotencyKey = null,
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
                    controllerApi(session).runStatus(session.token, requestId)
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
                    controllerApi(session).rejectRemoteRun(session.token, run.id)
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

    private fun stopRunningRun() {
        val session = state.session ?: return
        val run = state.run?.takeIf { it.status == "running" && !it.stopRequested } ?: return
        if (state.loading) return
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controllerApi(session).requestStop(session.token, run.id)
                }
                state = state.copy(
                    loading = false,
                    run = run.copy(stopRequested = true),
                    message = "已发送停止请求，等待受控端响应",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "发送停止请求失败")
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
                withContext(Dispatchers.IO) { controllerApi(session).logout(session.token) }
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
    onProfileNameChange: (String) -> Unit,
    onProfileEmailChange: (String) -> Unit,
    onProfileCurrentPasswordChange: (String) -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onToggleRegister: () -> Unit,
    onLoginOrRegister: () -> Unit,
    onRefresh: () -> Unit,
    onSaveProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onSelectTask: (String) -> Unit,
    onDispatch: () -> Unit,
    onCancelPending: () -> Unit,
    onStopRunning: () -> Unit,
    onLogout: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FlowPilot 控制端", style = MaterialTheme.typography.headlineMedium)
        Text("管理你的受控设备，并在授权范围内下发脚本任务。")
        TextButton(onClick = { showHelp = true }) {
            Text("帮助与关于")
        }
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
                Text("账号：${state.session.name} · ${state.session.email}", modifier = Modifier.weight(1f))
                TextButton(onClick = onLogout, enabled = !state.loading) { Text("退出") }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
            }
            if (state.loading) CircularProgressIndicator()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号资料与安全", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.profileName,
                        onValueChange = onProfileNameChange,
                        label = { Text("姓名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.profileEmail,
                        onValueChange = onProfileEmailChange,
                        label = { Text("邮箱") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.profileCurrentPassword,
                        onValueChange = onProfileCurrentPasswordChange,
                        label = { Text("当前密码（仅改邮箱时需要）") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedButton(onClick = onSaveProfile, enabled = !state.loading) {
                        Text("保存个人资料")
                    }
                    OutlinedTextField(
                        value = state.currentPassword,
                        onValueChange = onCurrentPasswordChange,
                        label = { Text("当前密码") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.newPassword,
                        onValueChange = onNewPasswordChange,
                        label = { Text("新密码（至少 12 位）") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedButton(onClick = onChangePassword, enabled = !state.loading) {
                        Text("修改密码")
                    }
                }
            }
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
                        } else if (run.status == "running") {
                            OutlinedButton(
                                onClick = onStopRunning,
                                enabled = !state.loading && !run.stopRequested,
                            ) {
                                Text(if (run.stopRequested) "已请求停止" else "停止运行")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("控制端仅负责派发与跟踪，不包含脚本执行运行时。停止请求由受控端轮询，并在脚本的下一个停止检查点响应。")
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("控制端帮助与关于") },
            text = {
                Column(
                    modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    controllerHelpSections(BuildConfig.VERSION_NAME).forEach { section ->
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

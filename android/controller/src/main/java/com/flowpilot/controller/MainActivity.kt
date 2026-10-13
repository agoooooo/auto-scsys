package com.flowpilot.controller

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal val ControllerColorScheme = lightColorScheme(
    primary = Color(0xFF07C160),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F5E9),
    onPrimaryContainer = Color(0xFF075B36),
    secondary = Color(0xFF079B4F),
    background = Color(0xFFF5F7F6),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F3F1),
    onSurface = Color(0xFF17201B),
    onSurfaceVariant = Color(0xFF77817B),
    outline = Color(0xFFDCE3DE),
)

@Composable
private fun ControllerCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        content = content,
    )
}

private data class ControllerScreenState(
    val apiUrl: String = defaultControllerApiUrl(),
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val isRegistering: Boolean = false,
    val recoveryEnabled: Boolean = false,
    val isRecoveringPassword: Boolean = false,
    val recoveryCodeSent: Boolean = false,
    val recoveryCode: String = "",
    val recoveryNewPassword: String = "",
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

internal enum class ControllerDestination(val label: String) {
    DEVICES("设备"),
    TASKS("任务"),
    RUNS("运行"),
    ACCOUNT("我的"),
}

class MainActivity : ComponentActivity() {
    private lateinit var sessionStore: ControllerSessionStore
    private var state by mutableStateOf(ControllerScreenState())
    private var monitorJob: Job? = null
    private var recoveryStatusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = ControllerSessionStore(this)
        checkPasswordRecovery(state.apiUrl)
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
            MaterialTheme(colorScheme = ControllerColorScheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ControllerScreen(
                        state = state,
                        onApiUrlChange = {
                            state = state.copy(apiUrl = it, recoveryEnabled = false)
                            checkPasswordRecovery(it)
                        },
                        onNameChange = { state = state.copy(name = it) },
                        onEmailChange = { state = state.copy(email = it) },
                        onPasswordChange = { state = state.copy(password = it) },
                        onRecoveryCodeChange = { state = state.copy(recoveryCode = it) },
                        onRecoveryNewPasswordChange = { state = state.copy(recoveryNewPassword = it) },
                        onProfileNameChange = { state = state.copy(profileName = it) },
                        onProfileEmailChange = { state = state.copy(profileEmail = it) },
                        onProfileCurrentPasswordChange = { state = state.copy(profileCurrentPassword = it) },
                        onCurrentPasswordChange = { state = state.copy(currentPassword = it) },
                        onNewPasswordChange = { state = state.copy(newPassword = it) },
                        onToggleRegister = {
                            state = state.copy(
                                isRegistering = !state.isRegistering,
                                isRecoveringPassword = false,
                                recoveryCodeSent = false,
                                recoveryCode = "",
                                recoveryNewPassword = "",
                                error = null,
                                message = null,
                            )
                        },
                        onLoginOrRegister = ::loginOrRegister,
                        onToggleRecovery = {
                            state = state.copy(
                                isRecoveringPassword = !state.isRecoveringPassword,
                                recoveryCodeSent = false,
                                recoveryCode = "",
                                recoveryNewPassword = "",
                                error = null,
                                message = null,
                            )
                        },
                        onRequestRecoveryCode = ::requestRecoveryCode,
                        onResetPassword = ::resetPassword,
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

    private fun checkPasswordRecovery(apiUrl: String) {
        recoveryStatusJob?.cancel()
        recoveryStatusJob = lifecycleScope.launch {
            delay(400)
            val enabled = runCatching {
                withContext(Dispatchers.IO) {
                    ControllerApi(apiUrl).passwordRecoveryEnabled()
                }
            }.getOrDefault(false)
            if (state.apiUrl == apiUrl) state = state.copy(recoveryEnabled = enabled)
        }
    }

    private fun requestRecoveryCode() {
        if (state.loading) return
        val snapshot = state
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ControllerApi(normalizeControllerApiUrl(snapshot.apiUrl))
                        .requestPasswordResetCode(snapshot.email.trim())
                }
                state = state.copy(
                    loading = false,
                    recoveryCodeSent = true,
                    message = "如果该邮箱对应可用账号，已发送验证码。",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "验证码发送失败")
            }
        }
    }

    private fun resetPassword() {
        if (state.loading) return
        val snapshot = state
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ControllerApi(normalizeControllerApiUrl(snapshot.apiUrl))
                        .resetPassword(snapshot.email.trim(), snapshot.recoveryCode.trim(), snapshot.recoveryNewPassword)
                }
                state = state.copy(
                    loading = false,
                    isRecoveringPassword = false,
                    recoveryCodeSent = false,
                    recoveryCode = "",
                    recoveryNewPassword = "",
                    password = "",
                    message = "密码已重置，请使用新密码登录",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "密码重置失败")
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
    onRecoveryCodeChange: (String) -> Unit,
    onRecoveryNewPasswordChange: (String) -> Unit,
    onProfileNameChange: (String) -> Unit,
    onProfileEmailChange: (String) -> Unit,
    onProfileCurrentPasswordChange: (String) -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onToggleRegister: () -> Unit,
    onLoginOrRegister: () -> Unit,
    onToggleRecovery: () -> Unit,
    onRequestRecoveryCode: () -> Unit,
    onResetPassword: () -> Unit,
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
    var selectedDestinationName by rememberSaveable {
        mutableStateOf(ControllerDestination.DEVICES.name)
    }
    val selectedDestination = ControllerDestination.entries.firstOrNull {
        it.name == selectedDestinationName
    } ?: ControllerDestination.DEVICES
    Column(Modifier.fillMaxSize()) {
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (state.session == null) {
            Text("FlowPilot 控制端", style = MaterialTheme.typography.headlineMedium)
            Text("管理你的受控设备，并在授权范围内下发脚本任务。")
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
                            ControllerDestination.DEVICES -> "我的设备"
                            ControllerDestination.TASKS -> "任务列表"
                            ControllerDestination.RUNS -> "运行状态"
                            ControllerDestination.ACCOUNT -> "我的账号"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        when (selectedDestination) {
                            ControllerDestination.DEVICES -> "查看受控设备状态并选择操作目标"
                            ControllerDestination.TASKS -> "浏览已发布任务并选择设备进行派发"
                            ControllerDestination.RUNS -> "查看设备执行状态与操作结果"
                            ControllerDestination.ACCOUNT -> "管理个人资料、登录凭据和应用信息"
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
            if (state.isRecoveringPassword) {
                if (state.recoveryCodeSent) {
                    OutlinedTextField(
                        value = state.recoveryCode,
                        onValueChange = onRecoveryCodeChange,
                        label = { Text("邮箱验证码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.recoveryNewPassword,
                        onValueChange = onRecoveryNewPasswordChange,
                        label = { Text("新密码（至少 12 位）") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Button(
                        onClick = onResetPassword,
                        enabled = !state.loading && state.recoveryCode.length == 6 &&
                            state.recoveryNewPassword.length >= 12,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重置密码") }
                } else {
                    Text("发送验证码后输入邮件中的 6 位数字；验证码 10 分钟内有效。")
                    Button(
                        onClick = onRequestRecoveryCode,
                        enabled = !state.loading && state.email.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("发送验证码") }
                }
                TextButton(onClick = onToggleRecovery, enabled = !state.loading) {
                    Text("返回登录")
                }
            } else {
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
                if (!state.isRegistering && state.recoveryEnabled) {
                    TextButton(onClick = onToggleRecovery, enabled = !state.loading) {
                        Text("忘记密码？")
                    }
                }
            }
            if (!state.isRecoveringPassword) {
                TextButton(onClick = onToggleRegister, enabled = !state.loading) {
                    Text(if (state.isRegistering) "已有账号？返回登录" else "没有账号？注册")
                }
            }
        } else {
            if (selectedDestination == ControllerDestination.ACCOUNT) {
                ControllerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.session.name, style = MaterialTheme.typography.titleLarge)
                        Text(state.session.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("普通用户", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.loading) CircularProgressIndicator()
            if (selectedDestination == ControllerDestination.ACCOUNT) {
              ControllerCard(Modifier.fillMaxWidth()) {
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
                    OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
                        Text("刷新账号信息")
                    }
                    TextButton(onClick = { showHelp = true }) {
                        Text("帮助与关于")
                    }
                    TextButton(onClick = onLogout, enabled = !state.loading) {
                        Text("退出登录")
                    }
                }
              }
            }
            if (selectedDestination == ControllerDestination.DEVICES) {
                Text("受控设备（${state.devices.size}）", style = MaterialTheme.typography.titleMedium)
                if (state.devices.isEmpty()) {
                    ControllerCard(Modifier.fillMaxWidth()) {
                        Text(
                            "暂无已绑定设备。请先在受控端登录并绑定设备。",
                            modifier = Modifier.padding(18.dp),
                        )
                    }
                } else {
                    ControllerCard(Modifier.fillMaxWidth()) {
                        Column {
                            state.devices.forEachIndexed { index, device ->
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                                        Text(controllerDeviceStatusLabel(device.status), color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(device.model, style = MaterialTheme.typography.bodySmall)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("ID ${device.id.take(8)}", style = MaterialTheme.typography.labelSmall)
                                        TextButton(
                                            onClick = { onSelectDevice(device.id) },
                                            enabled = !state.loading,
                                        ) {
                                            Text(if (state.selectedDeviceId == device.id) "派发目标 ✓" else "设为目标")
                                        }
                                    }
                                }
                                if (index < state.devices.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新设备列表") }
            }
            if (selectedDestination == ControllerDestination.TASKS) {
                Text("已发布任务（${state.tasks.count { it.status == "published" && it.version.isNotBlank() }}）", style = MaterialTheme.typography.titleMedium)
                Selector(
                    title = "目标设备",
                    selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }?.let {
                        "${it.name} · ${controllerDeviceStatusLabel(it.status)}"
                    } ?: "请选择设备",
                    enabled = state.devices.isNotEmpty(),
                    options = state.devices.map { it.id to "${it.name} · ${controllerDeviceStatusLabel(it.status)}" },
                    onSelect = onSelectDevice,
                )
                val availableTasks = state.tasks.filter { it.status == "published" && it.version.isNotBlank() }
                if (availableTasks.isEmpty()) {
                    ControllerCard(Modifier.fillMaxWidth()) {
                        Text("暂无已发布任务。", modifier = Modifier.padding(18.dp))
                    }
                } else {
                    ControllerCard(Modifier.fillMaxWidth()) {
                        Column {
                            availableTasks.forEachIndexed { index, taskItem ->
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(taskItem.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        Text(taskItem.version, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (taskItem.description.isNotBlank()) {
                                        Text(taskItem.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(
                                        onClick = { onSelectTask(taskItem.id) },
                                        enabled = !state.loading,
                                    ) {
                                        Text(if (state.selectedTaskId == taskItem.id) "已选择 ✓" else "选择任务")
                                    }
                                }
                                if (index < availableTasks.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
              val device = state.devices.firstOrNull { it.id == state.selectedDeviceId }
              val task = state.tasks.firstOrNull { it.id == state.selectedTaskId }
              Button(
                onClick = {
                    onDispatch()
                    selectedDestinationName = ControllerDestination.RUNS.name
                },
                enabled = !state.loading && device != null && task != null &&
                    canDispatchControllerTask(device.status, task.status, task.version) &&
                    !state.dispatchUncertain &&
                    state.run?.let { isControllerRunActive(it.status) } != true,
                modifier = Modifier.fillMaxWidth(),
              ) {
                  Text("向所选设备下发任务")
              }
              Text("仅可向在线设备下发；受控端需处于远程待命状态。")
              Spacer(Modifier.height(8.dp))
              Text("任务发布不会自动运行；此页只创建受控端远程运行请求。", style = MaterialTheme.typography.bodySmall)
            }
            if (selectedDestination == ControllerDestination.RUNS) {
              state.run?.let { run ->
                ControllerCard(Modifier.fillMaxWidth()) {
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
              } ?: ControllerCard(Modifier.fillMaxWidth()) {
                  Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      Text("暂无运行记录", style = MaterialTheme.typography.titleMedium)
                      Text("从“派发”选择在线设备和已发布任务后，即可在此跟踪执行状态。")
                  }
              }
              Spacer(Modifier.height(8.dp))
              Text("停止请求由受控端轮询，并在脚本的下一个停止检查点响应。", style = MaterialTheme.typography.bodySmall)
            }
        }
      }
      if (state.session != null) {
          NavigationBar(
              containerColor = Color.White,
              tonalElevation = 4.dp,
          ) {
              ControllerDestination.entries.forEach { destination ->
                  NavigationBarItem(
                      selected = selectedDestination == destination,
                      onClick = { selectedDestinationName = destination.name },
                      icon = { ControllerNavigationIcon(destination, selectedDestination == destination) },
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
private fun ControllerNavigationIcon(destination: ControllerDestination, selected: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val color = if (selected) Color(0xFF07C160) else Color(0xFF80868B)
        val stroke = 1.8.dp.toPx()
        when (destination) {
            ControllerDestination.DEVICES -> {
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
            ControllerDestination.TASKS -> {
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
            ControllerDestination.RUNS -> {
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
            ControllerDestination.ACCOUNT -> {
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

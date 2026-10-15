package com.flowpilot.controller

import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File

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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

@Composable
private fun ControllerDeviceStatusBadge(status: String) {
    val online = status == "online"
    Surface(
        shape = CircleShape,
        color = if (online) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            controllerDeviceStatusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (online) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ControllerLoginBrand() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF17201B),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("F", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row {
                    Text("Flow", style = MaterialTheme.typography.titleLarge)
                    Text("Pilot", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "CONTROL CENTER",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                "控制端",
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ControllerLoginHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF075B36), Color(0xFF07C160))),
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "设备协作 · 任务管理",
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "让设备协作更简单",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "查看设备状态，派发并跟踪自动化任务。",
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
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
    val uiState: ControllerUiState = ControllerUiState(),
    val profileName: String = "",
    val profileEmail: String = "",
    val profileCurrentPassword: String = "",
    val currentPassword: String = "",
    val newPassword: String = "",
    val devices: List<ControllerDevice> = emptyList(),
    val tasks: List<ControllerTask> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedDeviceIds: Set<String> = emptySet(),
    val selectedTaskIds: Set<String> = emptySet(),
    val run: ControllerRunStatus? = null,
    val batchRuns: List<ControllerBatchRun> = emptyList(),
    val runHistory: List<ControllerRunHistory> = emptyList(),
    val runHistoryLoaded: Boolean = false,
    val runHistoryLoading: Boolean = false,
    val runHistoryError: String? = null,
    val loading: Boolean = false,
    val dispatchUncertain: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

private data class ControllerBatchRun(
    val device: ControllerDevice,
    val task: ControllerTask,
    val requestId: String? = null,
    val run: ControllerRunStatus? = null,
    val error: String? = null,
)

internal enum class ControllerDestination(val label: String) {
    DEVICES("设备"),
    TASKS("任务"),
    RUNS("运行"),
    ACCOUNT("我的"),
}

internal enum class ControllerAccountPage(val title: String) {
    HOME("我的"),
    PROFILE("个人资料"),
    SECURITY("账号与安全"),
}

private const val DEVICE_REFRESH_INTERVAL_MS = 5_000L
private const val SESSION_CHECK_INTERVAL_MS = 15_000L
private const val CONTROLLER_UI_STATE_SAVE_DELAY_MS = 250L

class MainActivity : ComponentActivity() {
    private lateinit var sessionStore: ControllerSessionStore
    private lateinit var uiStateStore: ControllerUiStateStore
    private var state by mutableStateOf(ControllerScreenState())
    private var monitorJob: Job? = null
    private var batchMonitorJob: Job? = null
    private var recoveryStatusJob: Job? = null
    private var deviceRefreshInFlight = false
    private var sessionCheckInFlight = false
    private var appUpdateCheckInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = ControllerSessionStore(this)
        uiStateStore = ControllerUiStateStore(this)
        val restoredUiState = uiStateStore.read()
        state = state.copy(
            uiState = restoredUiState,
            selectedDeviceId = restoredUiState.selectedDeviceId,
            selectedDeviceIds = restoredUiState.selectedDeviceIds,
            selectedTaskIds = restoredUiState.selectedTaskIds,
        )
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
                        onRefreshDevices = ::refreshDevices,
                        onCheckSession = ::checkSession,
                        onRefreshRunHistory = ::refreshRunHistory,
                        onSaveUiState = ::saveUiState,
                        uiStateStore = uiStateStore,
                        onSaveProfile = ::saveProfile,
                        onChangePassword = ::changePassword,
                        onToggleDeviceSelection = ::toggleDeviceSelection,
                        onUnbindOfflineDevice = ::unbindOfflineDevice,
                        onToggleTaskSelection = ::toggleTaskSelection,
                        onDispatch = ::dispatch,
                        onCancelPending = ::cancelPendingRun,
                        onStopRunning = ::stopRunningRun,
                        onCancelBatchRun = ::cancelBatchRun,
                        onStopBatchRun = ::stopBatchRun,
                        onLogout = ::logout,
                        onCheckAppUpdate = ::checkForAppUpdate,
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
        if (snapshot.isRegistering && snapshot.password.length < 6) {
            state = state.copy(error = "密码至少需要 6 个字符")
            return
        }
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
                val availableDevices = visibleControllerDevices(devices)
                val selectedId = previousDeviceId?.takeIf { id -> availableDevices.any { it.id == id } }
                    ?: orderControllerDevices(availableDevices).firstOrNull()?.id
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
            val availableDevices = visibleControllerDevices(devices)
            val selectedDevice = state.selectedDeviceId?.takeIf { id -> availableDevices.any { it.id == id } }
                ?: orderControllerDevices(availableDevices).firstOrNull()?.id
            val availableTasks = tasks.filter { it.status == "published" && it.version.isNotBlank() }
            val onlineDeviceIds = availableDevices.filter { it.status == "online" }.mapTo(mutableSetOf()) { it.id }
            val taskIds = availableTasks.mapTo(mutableSetOf()) { it.id }
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
                selectedDeviceIds = state.selectedDeviceIds.intersect(onlineDeviceIds),
                selectedTaskIds = state.selectedTaskIds.intersect(taskIds),
                run = activeRun,
                loading = false,
                dispatchUncertain = false,
                error = null,
                message = null,
            )
            saveUiState(
                state.uiState.copy(
                    selectedDeviceId = selectedDevice,
                    selectedDeviceIds = state.selectedDeviceIds,
                    selectedTaskIds = state.selectedTaskIds,
                ),
            )
            monitorJob?.cancel()
            activeRun?.let { run ->
                monitorJob = lifecycleScope.launch { monitorRun(session, run.id) }
            }
        } catch (error: Exception) {
            if (error is ControllerApiException && error.statusCode == 401) {
                sessionStore.clear()
                clearUiState()
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

    override fun onResume() {
        super.onResume()
        checkForAppUpdate()
    }

    private fun checkForAppUpdate() {
        if (state.session == null || appUpdateCheckInFlight || isFinishing || isDestroyed) return
        appUpdateCheckInFlight = true
        lifecycleScope.launch {
            try {
                val api = ControllerApi(normalizeControllerApiUrl(state.apiUrl))
                val release = withContext(Dispatchers.IO) {
                    api.checkAppUpdate("controller", BuildConfig.VERSION_CODE.toLong())
                } ?: return@launch
                if (release.packageName != BuildConfig.APPLICATION_ID ||
                    !isNewerControllerAppRelease(BuildConfig.VERSION_CODE.toLong(), release.versionCode)
                ) {
                    throw IllegalStateException("服务器发布的控制端 App 信息无效")
                }

                val preferences = getSharedPreferences(CONTROLLER_APP_UPDATE_PREFERENCES, MODE_PRIVATE)
                val settingsPending =
                    preferences.getLong(CONTROLLER_KEY_SETTINGS_VERSION, -1L) == release.versionCode
                if (settingsPending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !packageManager.canRequestPackageInstalls()
                ) return@launch
                if (!settingsPending && isControllerAppUpdatePromptDeferred(
                        preferences.getLong(CONTROLLER_KEY_PROMPT_VERSION, -1L),
                        preferences.getLong(CONTROLLER_KEY_PROMPT_TIME, 0L),
                        release.versionCode,
                        System.currentTimeMillis(),
                    )
                ) return@launch

                val updateDirectory = File(cacheDir, "updates").apply { mkdirs() }
                val apk = File(updateDirectory, "controller-${release.versionCode}.apk")
                val cached = withContext(Dispatchers.IO) {
                    isCachedControllerAppUpdateValid(apk, release.sha256)
                }
                if (!cached) {
                    withContext(Dispatchers.IO) { api.downloadAppUpdate(release, apk) }
                }
                withContext(Dispatchers.IO) {
                    validateControllerAppUpdateApk(
                        apk,
                        BuildConfig.APPLICATION_ID,
                        release.versionCode,
                        release.versionName,
                        packageManager,
                    )
                }
                launchControllerAppUpdateInstall(this@MainActivity, apk, release.versionCode)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Toast.makeText(this@MainActivity, "App 更新检查失败：${exception.message ?: "网络错误"}", Toast.LENGTH_LONG).show()
            } finally {
                appUpdateCheckInFlight = false
            }
        }
    }

    private fun refreshDevices() {
        val session = state.session ?: return
        if (deviceRefreshInFlight || state.loading) return
        deviceRefreshInFlight = true
        lifecycleScope.launch {
            try {
                val devices = withContext(Dispatchers.IO) {
                    controllerApi(session).devices(session.token)
                }
                if (state.session?.email == session.email && state.session?.apiUrl == session.apiUrl) {
                    val visibleDevices = visibleControllerDevices(devices)
                    val onlineDeviceIds = visibleDevices
                        .filter { it.status == "online" }
                        .mapTo(mutableSetOf()) { it.id }
                    val selectedDeviceId = state.selectedDeviceId
                        ?.takeIf { id -> visibleDevices.any { it.id == id } }
                        ?: orderControllerDevices(visibleDevices).firstOrNull()?.id
                    state = state.copy(
                        devices = devices,
                        selectedDeviceId = selectedDeviceId,
                        selectedDeviceIds = state.selectedDeviceIds.intersect(onlineDeviceIds),
                    )
                    saveUiState(
                        state.uiState.copy(
                            selectedDeviceId = selectedDeviceId,
                            selectedDeviceIds = state.selectedDeviceIds,
                        ),
                    )
                }
            } catch (error: Exception) {
                if (state.session?.email == session.email && state.session?.apiUrl == session.apiUrl &&
                    (error !is ControllerApiException || error.statusCode != 401)
                ) {
                    state = state.copy(error = error.message ?: "刷新设备状态失败")
                }
            } finally {
                deviceRefreshInFlight = false
            }
        }
    }

    private fun checkSession() {
        val session = state.session ?: return
        if (sessionCheckInFlight) return
        sessionCheckInFlight = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controllerApi(session).profile(session.token)
                }
            } catch (error: Exception) {
                if (error !is ControllerApiException || error.statusCode != 401) {
                    state = state.copy(error = error.message ?: "验证登录状态失败")
                }
            } finally {
                sessionCheckInFlight = false
            }
        }
    }

    private fun refreshRunHistory() {
        val session = state.session ?: return
        if (state.runHistoryLoading) return
        state = state.copy(runHistoryLoading = true, runHistoryError = null)
        lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    controllerApi(session).runHistory(session.token)
                }
                if (state.session?.email != session.email || state.session?.apiUrl != session.apiUrl) {
                    return@launch
                }
                state = state.copy(
                    runHistory = history,
                    runHistoryLoaded = true,
                    runHistoryLoading = false,
                    runHistoryError = null,
                )
            } catch (error: Exception) {
                if (state.session?.email != session.email || state.session?.apiUrl != session.apiUrl) {
                    return@launch
                }
                state = state.copy(
                    runHistoryLoading = false,
                    runHistoryError = error.message ?: "加载运行历史失败",
                )
            }
        }
    }

    private fun controllerApi(session: ControllerSession) =
        ControllerApi(
            session.apiUrl,
            initialAccessToken = sessionStore.read()?.token ?: session.token,
            onAccessTokenRefreshed = { refreshedToken ->
                val updatedSession = session.copy(token = refreshedToken)
                sessionStore.save(updatedSession)
                runOnUiThread {
                    if (state.session?.email == session.email && state.session?.apiUrl == session.apiUrl) {
                        state = state.copy(session = updatedSession)
                    }
                }
            },
            onSessionExpired = {
                runOnUiThread {
                    monitorJob?.cancel()
                    batchMonitorJob?.cancel()
                    sessionStore.clear()
                    clearUiState()
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
        if (state.currentPassword.isBlank() || state.newPassword.length < 6) {
            state = state.copy(error = "请输入当前密码和至少 6 位的新密码")
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

    private fun toggleDeviceSelection(deviceId: String) {
        val selected = state.selectedDeviceIds.toMutableSet()
        if (deviceId in selected) selected.remove(deviceId) else selected.add(deviceId)
        state = state.copy(
            selectedDeviceIds = selected,
            selectedDeviceId = deviceId,
            dispatchUncertain = false,
            error = null,
        )
        saveUiState(
            state.uiState.copy(
                selectedDeviceId = deviceId,
                selectedDeviceIds = selected,
            ),
        )
    }

    private fun unbindOfflineDevice(device: ControllerDevice) {
        val session = state.session ?: return
        if (state.loading || !canUnbindControllerDevice(device.status)) return
        state = state.copy(loading = true, error = null, message = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controllerApi(session).unbindDevice(session.token, device.id)
                }
                val nextSelectedDevice = if (state.selectedDeviceId == device.id) {
                    state.devices.firstOrNull { it.id != device.id && it.status == "online" }?.id
                } else {
                    state.selectedDeviceId
                }
                state = state.copy(
                    devices = state.devices.map {
                        if (it.id == device.id) it.copy(status = "disabled") else it
                    },
                    selectedDeviceIds = state.selectedDeviceIds - device.id,
                    selectedDeviceId = nextSelectedDevice,
                    run = if (state.selectedDeviceId == device.id) null else state.run,
                    loading = false,
                    error = null,
                    message = "设备已解绑并停用；待处理请求和设备授权已撤销",
                )
                saveUiState(
                    state.uiState.copy(
                        selectedDeviceId = nextSelectedDevice,
                        selectedDeviceIds = state.selectedDeviceIds,
                    ),
                )
            } catch (error: Exception) {
                if (error is ControllerApiException && error.statusCode == 401) {
                    sessionStore.clear()
                    clearUiState()
                    state = state.copy(session = null, loading = false, error = "登录已过期，请重新登录")
                } else {
                    state = state.copy(loading = false, error = error.message ?: "设备解绑失败")
                }
            }
        }
    }

    private fun toggleTaskSelection(taskId: String) {
        val selected = state.selectedTaskIds.toMutableSet()
        if (taskId in selected) selected.remove(taskId) else selected.add(taskId)
        state = state.copy(
            selectedTaskIds = selected,
            dispatchUncertain = false,
            error = null,
        )
        saveUiState(state.uiState.copy(selectedTaskIds = selected))
    }

    private fun saveUiState(uiState: ControllerUiState) {
        val updated = uiState.copy(
            selectedDeviceId = state.selectedDeviceId,
            selectedDeviceIds = state.selectedDeviceIds,
            selectedTaskIds = state.selectedTaskIds,
        )
        uiStateStore.save(updated)
        state = state.copy(uiState = updated)
    }

    private fun clearUiState() {
        uiStateStore.clear()
        state = state.copy(
            uiState = ControllerUiState(),
            selectedDeviceId = null,
            selectedDeviceIds = emptySet(),
            selectedTaskIds = emptySet(),
        )
    }

    private fun dispatch() {
        val session = state.session ?: return
        if (state.loading || state.dispatchUncertain) return
        val targets = buildControllerDispatchTargets(
            state.devices,
            state.tasks,
            state.selectedDeviceIds,
            state.selectedTaskIds,
        )
        if (targets.isEmpty()) return
        val startIndex = state.batchRuns.size
        state = state.copy(
            loading = true,
            error = null,
            message = null,
            batchRuns = state.batchRuns + targets.map { ControllerBatchRun(it.device, it.task) },
        )
        lifecycleScope.launch {
            var failedCount = 0
            var successfulCount = 0
            targets.forEachIndexed { index, target ->
                try {
                    val run = withContext(Dispatchers.IO) {
                        controllerApi(session).dispatch(
                            session.token,
                            target.task.id,
                            target.device.id,
                            UUID.randomUUID().toString(),
                        )
                    }
                    state = state.copy(
                        batchRuns = state.batchRuns.toMutableList().also { items ->
                            items[startIndex + index] = items[startIndex + index].copy(
                                requestId = run.id,
                                run = run,
                            )
                        },
                    )
                    successfulCount++
                } catch (error: Exception) {
                    failedCount++
                    state = state.copy(
                        batchRuns = state.batchRuns.toMutableList().also { items ->
                            items[startIndex + index] = items[startIndex + index].copy(
                                error = error.message ?: "派发请求失败",
                            )
                        },
                    )
                }
            }
            state = state.copy(
                loading = false,
                dispatchUncertain = failedCount > 0,
                message = "已派发 $successfulCount 项，失败 $failedCount 项；同设备任务将按队列顺序执行",
            )
            if (successfulCount > 0) {
                batchMonitorJob?.cancel()
                batchMonitorJob = lifecycleScope.launch { monitorBatchRuns(session) }
            }
        }
    }

    private suspend fun monitorBatchRuns(session: ControllerSession) {
        repeat(MAX_STATUS_POLLS) {
            val active = state.batchRuns.withIndex().filter { (_, item) ->
                item.requestId != null && item.run?.let { isControllerRunActive(it.status) } == true
            }
            if (active.isEmpty()) return
            val updated = state.batchRuns.toMutableList()
            active.forEach { (index, item) ->
                try {
                    updated[index] = item.copy(
                        run = withContext(Dispatchers.IO) {
                            controllerApi(session).runStatus(session.token, requireNotNull(item.requestId))
                        },
                    )
                } catch (error: Exception) {
                    updated[index] = item.copy(error = error.message ?: "刷新运行状态失败")
                }
            }
            state = state.copy(batchRuns = updated)
            if (updated.none { it.run?.let { run -> isControllerRunActive(run.status) } == true }) return
            delay(STATUS_POLL_INTERVAL_MS)
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

    private fun cancelBatchRun(requestId: String) {
        val session = state.session ?: return
        val index = state.batchRuns.indexOfFirst {
            it.requestId == requestId && it.run?.status in setOf("queued", "pending")
        }
        if (index < 0 || state.loading) return
        state = state.copy(loading = true, error = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controllerApi(session).rejectRemoteRun(session.token, requestId)
                }
                state = state.copy(
                    loading = false,
                    batchRuns = state.batchRuns.toMutableList().also { items ->
                        val item = items[index]
                        items[index] = item.copy(run = item.run?.copy(status = "rejected"))
                    },
                    message = "队列任务已取消",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "取消队列任务失败")
            }
        }
    }

    private fun stopBatchRun(requestId: String) {
        val session = state.session ?: return
        val index = state.batchRuns.indexOfFirst {
            it.requestId == requestId && it.run?.status == "running" && it.run.stopRequested.not()
        }
        if (index < 0 || state.loading) return
        state = state.copy(loading = true, error = null)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controllerApi(session).requestStop(session.token, requestId)
                }
                state = state.copy(
                    loading = false,
                    batchRuns = state.batchRuns.toMutableList().also { items ->
                        val item = items[index]
                        items[index] = item.copy(run = item.run?.copy(stopRequested = true))
                    },
                    message = "停止请求已发送",
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = error.message ?: "发送停止请求失败")
            }
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
        batchMonitorJob?.cancel()
        state = state.copy(loading = true, error = null)
        lifecycleScope.launch {
            var logoutError: String? = null
            try {
                withContext(Dispatchers.IO) { controllerApi(session).logout(session.token) }
            } catch (error: Exception) {
                logoutError = error.message ?: "服务端退出失败"
            } finally {
                sessionStore.clear()
                clearUiState()
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
    return if (isEmulator) "http://10.0.2.2:8001" else "http://192.168.0.105:8001"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
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
    onRefreshDevices: () -> Unit,
    onCheckSession: () -> Unit,
    onRefreshRunHistory: () -> Unit,
    onSaveUiState: (ControllerUiState) -> Unit,
    uiStateStore: ControllerUiStateStore,
    onSaveProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onToggleDeviceSelection: (String) -> Unit,
    onUnbindOfflineDevice: (ControllerDevice) -> Unit,
    onToggleTaskSelection: (String) -> Unit,
    onDispatch: () -> Unit,
    onCancelPending: () -> Unit,
    onStopRunning: () -> Unit,
    onCancelBatchRun: (String) -> Unit,
    onStopBatchRun: (String) -> Unit,
    onLogout: () -> Unit,
    onCheckAppUpdate: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    var selectedRunHistory by remember { mutableStateOf<ControllerRunHistory?>(null) }
    var deviceToUnbind by remember { mutableStateOf<ControllerDevice?>(null) }
    var offlineDevicesExpanded by rememberSaveable(state.session?.email) {
        mutableStateOf(state.uiState.offlineDevicesExpanded)
    }
    var selectedDestinationName by rememberSaveable(state.session?.email) {
        mutableStateOf(state.uiState.destination)
    }
    var accountPageName by rememberSaveable(state.session?.email) {
        mutableStateOf(state.uiState.accountPage)
    }
    val selectedDestination = ControllerDestination.entries.firstOrNull {
        it.name == selectedDestinationName
    } ?: ControllerDestination.DEVICES
    LaunchedEffect(state.session?.email, state.session?.apiUrl) {
        if (state.session != null) onCheckAppUpdate()
    }
    LaunchedEffect(selectedDestination, state.session?.apiUrl, state.session?.email) {
        val session = state.session ?: return@LaunchedEffect
        if (selectedDestination == ControllerDestination.RUNS) onRefreshRunHistory()
        while (
            state.session?.apiUrl == session.apiUrl &&
            state.session?.email == session.email
        ) {
            delay(if (selectedDestination == ControllerDestination.DEVICES) DEVICE_REFRESH_INTERVAL_MS else SESSION_CHECK_INTERVAL_MS)
            if (selectedDestination == ControllerDestination.DEVICES) {
                onRefreshDevices()
            } else {
                onCheckSession()
            }
        }
    }
    val accountPage = ControllerAccountPage.entries.firstOrNull {
        it.name == accountPageName
    } ?: ControllerAccountPage.HOME
    val scrollState = key(state.session?.email, selectedDestinationName, accountPageName) {
        rememberScrollState(
            uiStateStore.readScrollPosition(selectedDestinationName, accountPageName),
        )
    }
    fun persistScreenState() {
        onSaveUiState(
            state.uiState.copy(
                destination = selectedDestinationName,
                accountPage = accountPageName,
                offlineDevicesExpanded = offlineDevicesExpanded,
            ),
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.session?.email, selectedDestinationName, accountPageName, scrollState) {
        if (state.session == null) return@LaunchedEffect
        snapshotFlow { scrollState.value }
            .distinctUntilChanged()
            .collectLatest { position ->
                delay(CONTROLLER_UI_STATE_SAVE_DELAY_MS)
                uiStateStore.saveScrollPosition(
                    selectedDestinationName,
                    accountPageName,
                    position,
                )
            }
    }
    DisposableEffect(
        lifecycleOwner,
        state.session?.email,
        selectedDestinationName,
        accountPageName,
        scrollState,
    ) {
        if (state.session == null) return@DisposableEffect onDispose {}
        val destination = selectedDestinationName
        val page = accountPageName
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                uiStateStore.saveScrollPosition(destination, page, scrollState.value)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            uiStateStore.saveScrollPosition(destination, page, scrollState.value)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    BackHandler(
        enabled = state.session != null &&
            selectedDestination == ControllerDestination.ACCOUNT &&
            accountPage != ControllerAccountPage.HOME,
    ) {
        accountPageName = ControllerAccountPage.HOME.name
        persistScreenState()
    }
    Column(Modifier.fillMaxSize()) {
      PullToRefreshBox(
        isRefreshing = state.loading || state.runHistoryLoading,
        onRefresh = onRefresh,
        modifier = Modifier.weight(1f),
      ) {
      Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(
            if (selectedDestination == ControllerDestination.ACCOUNT) 16.dp else 12.dp,
        ),
      ) {
        if (state.session == null) {
            ControllerLoginBrand()
            ControllerLoginHero()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (selectedDestination == ControllerDestination.ACCOUNT &&
                    accountPage != ControllerAccountPage.HOME
                ) {
                    TextButton(
                        onClick = {
                            accountPageName = ControllerAccountPage.HOME.name
                            persistScreenState()
                        },
                    ) {
                        Text("‹ 返回")
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (selectedDestination == ControllerDestination.ACCOUNT) {
                            accountPage.title
                        } else when (selectedDestination) {
                            ControllerDestination.DEVICES -> "我的设备"
                            ControllerDestination.TASKS -> "任务列表"
                            ControllerDestination.RUNS -> "运行状态"
                            ControllerDestination.ACCOUNT -> "我的账号"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        if (selectedDestination == ControllerDestination.ACCOUNT) {
                            when (accountPage) {
                                ControllerAccountPage.HOME -> "个人信息与账号设置"
                                ControllerAccountPage.PROFILE -> "管理姓名和邮箱"
                                ControllerAccountPage.SECURITY -> "修改登录密码"
                            }
                        } else when (selectedDestination) {
                            ControllerDestination.DEVICES -> "查看受控设备状态并选择操作目标"
                            ControllerDestination.TASKS -> "浏览已发布任务并选择设备进行派发"
                            ControllerDestination.RUNS -> "查看设备执行状态与操作结果"
                            ControllerDestination.ACCOUNT -> "个人资料、密码与应用设置"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.session == null) {
            ControllerCard(Modifier.fillMaxWidth()) {
              Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
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
                        label = { Text("新密码（至少 6 位）") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Button(
                        onClick = onResetPassword,
                        enabled = !state.loading && state.recoveryCode.length == 6 &&
                            state.recoveryNewPassword.length >= 6,
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
                if (state.isRegistering) Text("注册仅创建普通用户；密码至少 6 位。")
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
              }
            }
        } else {
            if (state.loading) CircularProgressIndicator()
            if (selectedDestination == ControllerDestination.ACCOUNT) {
                when (accountPage) {
                    ControllerAccountPage.HOME -> {
                        ControllerCard(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    accountPageName = ControllerAccountPage.PROFILE.name
                                    persistScreenState()
                                }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            state.session.name.firstOrNull()?.uppercase() ?: "我",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.headlineSmall,
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(state.session.name, style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        state.session.email,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ControllerCard(Modifier.fillMaxWidth()) {
                            Column {
                                ControllerAccountRow(
                                    title = "个人资料",
                                    detail = "姓名、邮箱",
                                    onClick = {
                                        accountPageName = ControllerAccountPage.PROFILE.name
                                        persistScreenState()
                                    },
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                                ControllerAccountRow(
                                    title = "账号与安全",
                                    detail = "修改登录密码",
                                    onClick = {
                                        accountPageName = ControllerAccountPage.SECURITY.name
                                        persistScreenState()
                                    },
                                )
                            }
                        }
                        ControllerCard(Modifier.fillMaxWidth()) {
                            ControllerAccountRow(
                                title = "帮助与关于",
                                onClick = { showHelp = true },
                            )
                        }
                        Button(
                            onClick = onLogout,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("退出登录")
                        }
                    }
                    ControllerAccountPage.PROFILE -> {
                        ControllerCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    label = { Text("当前密码（修改邮箱时需要）") },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                )
                                Button(
                                    onClick = onSaveProfile,
                                    enabled = !state.loading &&
                                        state.profileName.isNotBlank() && state.profileEmail.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("保存")
                                }
                            }
                        }
                    }
                    ControllerAccountPage.SECURITY -> {
                        ControllerCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    label = { Text("新密码（至少 6 位）") },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                )
                                Button(
                                    onClick = onChangePassword,
                                    enabled = !state.loading && state.currentPassword.isNotBlank() &&
                                        state.newPassword.length >= 6,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("更新密码")
                                }
                            }
                        }
                    }
                }
            }
            if (selectedDestination == ControllerDestination.DEVICES) {
                val visibleDevices = visibleControllerDevices(state.devices)
                val selectedOnlineCount = state.devices.count {
                    it.id in state.selectedDeviceIds && it.status == "online"
                }
                ControllerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("快速开始", style = MaterialTheme.typography.titleSmall)
                        ControllerQuickStartStep("1", "选设备", "点选在线设备，可一次选择多台")
                        ControllerQuickStartStep("2", "选脚本并派发", "到“任务”页选择脚本，点击派发")
                        ControllerQuickStartStep("3", "查看结果", "到“运行”页查看排队和执行状态")
                    }
                }
                
                Text(
                    "已选 ${state.selectedDeviceIds.size} 台 · 在线可派发 $selectedOnlineCount 台",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (visibleDevices.isEmpty()) {
                    ControllerCard(Modifier.fillMaxWidth()) {
                        Text(
                            "暂无可用受控设备。请先在受控端登录并绑定设备；已停用设备不会显示。",
                            modifier = Modifier.padding(18.dp),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groupControllerDevices(state.devices).forEach { group ->
                            val isOfflineGroup = group.status == "offline"
                            val isExpanded = if (isOfflineGroup) {
                                offlineDevicesExpanded
                            } else {
                                isControllerDeviceGroupExpandedByDefault(group.status)
                            }
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clickable(enabled = isOfflineGroup) {
                                        offlineDevicesExpanded = !offlineDevicesExpanded
                                        persistScreenState()
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(group.label, style = MaterialTheme.typography.titleSmall)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        group.devices.size.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (isOfflineGroup) {
                                        Text(
                                            if (isExpanded) "收起" else "展开",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            if (!isExpanded) return@forEach
                            group.devices.forEach { device ->
                                val selected = device.id in state.selectedDeviceIds
                                val online = device.status == "online"
                                Card(
                                    onClick = { onToggleDeviceSelection(device.id) },
                                    enabled = !state.loading && online,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.White
                                        },
                                        disabledContainerColor = Color.White,
                                    ),
                                    elevation = CardDefaults.cardElevation(
                                        defaultElevation = 2.dp,
                                        disabledElevation = 2.dp,
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    ),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    device.name,
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Spacer(Modifier.size(8.dp))
                                                ControllerDeviceStatusBadge(device.status)
                                            }
                                            if (shouldShowControllerDeviceModel(device.name, device.model)) {
                                                Text(
                                                    device.model,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                "设备 ID · ${device.id.take(8)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (canUnbindControllerDevice(device.status)) {
                                            OutlinedButton(
                                                onClick = { deviceToUnbind = device },
                                                enabled = !state.loading,
                                            ) {
                                                Text("解绑")
                                            }
                                        } else {
                                            Checkbox(
                                                checked = selected,
                                                onCheckedChange = { onToggleDeviceSelection(device.id) },
                                                enabled = !state.loading && online,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (selectedOnlineCount > 0) {
                    Button(
                        onClick = {
                            selectedDestinationName = ControllerDestination.TASKS.name
                            persistScreenState()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("为已选设备选择脚本")
                    }
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新设备列表") }
            }
            if (selectedDestination == ControllerDestination.TASKS) {
                Text("已发布任务（${state.tasks.count { it.status == "published" && it.version.isNotBlank() }}）", style = MaterialTheme.typography.titleMedium)
                val selectedOnlineCount = state.devices.count {
                    it.id in state.selectedDeviceIds && it.status == "online"
                }
                Text(
                    "派发目标：$selectedOnlineCount 台在线设备 · ${state.selectedTaskIds.size} 个脚本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val availableTasks = state.tasks.filter { it.status == "published" && it.version.isNotBlank() }
                if (availableTasks.isEmpty()) {
                    ControllerCard(Modifier.fillMaxWidth()) {
                        Text("暂无已发布任务。", modifier = Modifier.padding(18.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableTasks.forEach { taskItem ->
                            val selected = taskItem.id in state.selectedTaskIds
                            Card(
                                onClick = { onToggleTaskSelection(taskItem.id) },
                                enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.White
                                    },
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                ),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(
                                            Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Text(
                                                taskItem.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 2,
                                            )
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                            ) {
                                                Text(
                                                    taskItem.version,
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                        Checkbox(
                                            checked = selected,
                                            onCheckedChange = { onToggleTaskSelection(taskItem.id) },
                                            enabled = !state.loading,
                                        )
                                    }
                                    if (taskItem.description.isNotBlank()) {
                                        Text(
                                            taskItem.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
              val dispatchTargets = buildControllerDispatchTargets(
                  state.devices,
                  state.tasks,
                  state.selectedDeviceIds,
                  state.selectedTaskIds,
              )
              Button(
                onClick = {
                    onDispatch()
                    selectedDestinationName = ControllerDestination.RUNS.name
                    persistScreenState()
                },
                enabled = !state.loading && dispatchTargets.isNotEmpty() && !state.dispatchUncertain,
                modifier = Modifier.fillMaxWidth(),
              ) {
                  Text("派发 ${dispatchTargets.size} 个任务请求")
              }
              
            }
            if (selectedDestination == ControllerDestination.RUNS) {
              if (state.batchRuns.isNotEmpty()) {
                Text("派发队列（${state.batchRuns.size}）", style = MaterialTheme.typography.titleMedium)
                ControllerCard(Modifier.fillMaxWidth()) {
                    Column {
                        state.batchRuns.forEachIndexed { index, item ->
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.task.name, style = MaterialTheme.typography.titleSmall)
                                        Text(item.device.name, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        item.error?.let { "派发失败" }
                                            ?: item.run?.let { controllerRunStatusLabel(it.status) }
                                            ?: "正在派发",
                                        color = if (item.error == null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                item.run?.resultSummary?.takeIf(String::isNotBlank)?.let { Text(it) }
                                item.run?.let { run ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (run.status == "queued" || run.status == "pending") {
                                            TextButton(
                                                onClick = { onCancelBatchRun(run.id) },
                                                enabled = !state.loading,
                                            ) { Text("取消") }
                                        } else if (run.status == "running") {
                                            TextButton(
                                                onClick = { onStopBatchRun(run.id) },
                                                enabled = !state.loading && !run.stopRequested,
                                            ) { Text(if (run.stopRequested) "已请求停止" else "停止") }
                                        }
                                    }
                                }
                            }
                            if (index < state.batchRuns.lastIndex) HorizontalDivider()
                        }
                    }
                }
              }
              state.run?.takeIf { controllerRunHistoryIsActive(it.status) }?.let { run ->
                ControllerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前任务状态", style = MaterialTheme.typography.titleMedium)
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
              if (state.run?.let { controllerRunHistoryIsActive(it.status) } == true ||
                  state.batchRuns.any { item -> item.run?.let { controllerRunHistoryIsActive(it.status) } == true }
              ) {
                  Spacer(Modifier.height(8.dp))
                  Text("停止请求由受控端轮询，并在脚本的下一个停止检查点响应。", style = MaterialTheme.typography.bodySmall)
              }
              Spacer(Modifier.height(8.dp))
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                  Text("执行历史", style = MaterialTheme.typography.titleMedium)
                  TextButton(
                      onClick = onRefreshRunHistory,
                      enabled = !state.runHistoryLoading,
                  ) {
                      Text(if (state.runHistoryLoading) "刷新中" else "刷新记录")
                  }
              }
              Text(
                  when {
                      state.runHistoryLoading && !state.runHistoryLoaded -> "正在加载运行记录…"
                      state.runHistoryError != null -> "刷新失败：${state.runHistoryError}"
                      !state.runHistoryLoaded -> "运行记录尚未加载"
                      state.runHistory.isEmpty() -> "暂无历史运行记录"
                      else -> "显示最近 ${state.runHistory.size.coerceAtMost(MAX_VISIBLE_CONTROLLER_RUN_HISTORY)} 条"
                  },
                  color = if (state.runHistoryError == null) {
                      MaterialTheme.colorScheme.onSurfaceVariant
                  } else {
                      MaterialTheme.colorScheme.error
                  },
                  style = MaterialTheme.typography.bodySmall,
              )
              val visibleHistory = visibleControllerRunHistory(state.runHistory)
              if (visibleHistory.isNotEmpty()) {
                  visibleHistory.forEach { run ->
                      ControllerCard(
                          Modifier.fillMaxWidth().clickable { selectedRunHistory = run },
                      ) {
                          Column(
                              Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                              verticalArrangement = Arrangement.spacedBy(5.dp),
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
                                  )
                                  Text(
                                      controllerRunStatusLabel(run.status),
                                      color = controllerRunHistoryStatusColor(run.status),
                                      style = MaterialTheme.typography.labelMedium,
                                  )
                              }
                              Text(run.deviceName, style = MaterialTheme.typography.bodySmall)
                              Text(
                                  "版本：${run.version} · ${formatControllerRunTime(run.startedAt)}",
                                  style = MaterialTheme.typography.bodySmall,
                              )
                              Text("点击查看详情", style = MaterialTheme.typography.labelSmall)
                          }
                      }
                  }
              }
            }
        }
      }
      }
      if (state.session != null) {
          Surface(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              shape = RoundedCornerShape(24.dp),
              color = Color.White,
              tonalElevation = 2.dp,
              shadowElevation = 8.dp,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          ) {
              Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                  ControllerDestination.entries.forEach { destination ->
                      val selected = selectedDestination == destination
                      Column(
                          modifier = Modifier.weight(1f)
                              .clip(RoundedCornerShape(18.dp))
                              .clickable {
                                  selectedDestinationName = destination.name
                                  accountPageName = ControllerAccountPage.HOME.name
                                  persistScreenState()
                              }
                              .background(
                                  if (selected) MaterialTheme.colorScheme.primaryContainer
                                  else Color.Transparent,
                              )
                              .padding(vertical = 7.dp),
                          horizontalAlignment = Alignment.CenterHorizontally,
                          verticalArrangement = Arrangement.spacedBy(3.dp),
                      ) {
                          ControllerNavigationIcon(destination, selected)
                          Text(
                              destination.label,
                              color = if (selected) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.onSurfaceVariant,
                              style = MaterialTheme.typography.labelMedium,
                          )
                      }
                  }
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
    deviceToUnbind?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToUnbind = null },
            title = { Text("解绑离线设备？") },
            text = {
                Text(
                    "将解绑并停用“${device.name}”。待处理的远程请求会过期，设备授权和分发记录会撤销；" +
                        "设备重新接入前需要重新绑定。若设备其实仍在执行任务，解绑不会保证立即中止本地执行。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = device
                        deviceToUnbind = null
                        onUnbindOfflineDevice(target)
                    },
                    enabled = !state.loading,
                ) { Text("确认解绑") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnbind = null }) { Text("取消") }
            },
        )
    }
    selectedRunHistory?.let { run ->
        AlertDialog(
            onDismissRequest = { selectedRunHistory = null },
            title = { Text("运行详情") },
            text = {
                Column(
                    modifier = Modifier.height(440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("任务：${run.taskName}")
                    Text("状态：${controllerRunStatusLabel(run.status)}")
                    Text("版本：${run.version.ifBlank { "未知" }}")
                    Text("设备：${run.deviceName.ifBlank { "未知设备" }}")
                    Text("设备 ID：${run.deviceId}")
                    Text("开始时间：${formatControllerRunTime(run.startedAt)}")
                    Text(
                        "结束时间：${run.finishedAt.takeIf(String::isNotBlank)?.let(::formatControllerRunTime) ?: "尚未结束"}",
                    )
                    run.resultSummary.takeIf(String::isNotBlank)?.let { Text("运行结果：$it") }
                    Text("运行 ID：${run.id}", style = MaterialTheme.typography.bodySmall)
                    run.remoteRunId?.let { Text("派发请求 ID：$it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedRunHistory = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ControllerQuickStartStep(number: String, title: String, detail: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(26.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ControllerAccountRow(
    title: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControllerNavigationIcon(destination: ControllerDestination, selected: Boolean) {
    Canvas(Modifier.size(26.dp)) {
        val color = if (selected) Color(0xFF07C160) else Color(0xFF80868B)
        val stroke = if (selected) 2.2.dp.toPx() else 1.8.dp.toPx()
        when (destination) {
            ControllerDestination.DEVICES -> {
                drawRoundRect(
                    color = color.copy(alpha = 0.12f),
                    topLeft = Offset(size.width * 0.18f, size.height * 0.08f),
                    size = Size(size.width * 0.64f, size.height * 0.84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.13f),
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.08f),
                    size = Size(size.width * 0.64f, size.height * 0.84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.13f),
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
                    color = color.copy(alpha = 0.1f),
                    topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                    size = Size(size.width * 0.76f, size.height * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.15f),
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                    size = Size(size.width * 0.76f, size.height * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.15f),
                    style = Stroke(stroke),
                )
                listOf(0.37f, 0.56f, 0.75f).forEach { y ->
                    drawCircle(color, size.width * 0.045f, Offset(size.width * 0.29f, size.height * y))
                    drawLine(
                        color,
                        Offset(size.width * 0.42f, size.height * y),
                        Offset(size.width * 0.73f, size.height * y),
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
                listOf(
                    Offset(size.width * 0.22f, size.height * 0.68f),
                    Offset(size.width * 0.43f, size.height * 0.48f),
                    Offset(size.width * 0.60f, size.height * 0.60f),
                    Offset(size.width * 0.86f, size.height * 0.28f),
                ).forEach { point ->
                    drawCircle(color, size.width * 0.07f, point)
                }
            }
            ControllerDestination.ACCOUNT -> {
                drawCircle(
                    color,
                    size.minDimension * 0.15f,
                    Offset(size.width * 0.5f, size.height * 0.29f),
                )
                drawArc(
                    color = color,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.17f, size.height * 0.45f),
                    size = Size(size.width * 0.66f, size.height * 0.45f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

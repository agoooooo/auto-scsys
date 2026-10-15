package com.flowpilot.android

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppUiState(
    val user: SignedInUser? = null,
    val token: String? = null,
    val tokenExpiresAt: Long = 0L,
    val apiBaseUrl: String = defaultApiBaseUrl(isEmulator()),
    val loginEmail: String = "",
    val loginPassword: String = "",
    val rememberLoginInfo: Boolean = true,
    val notice: String? = null,
    val device: BoundDevice? = null,
    val tasks: List<AvailableTask> = emptyList(),
    val remoteRuns: List<RemoteRun> = emptyList(),
    val remoteAssistSessions: List<RemoteAssistSession> = emptyList(),
    val runHistory: List<RunRecord> = emptyList(),
    val runHistoryLoading: Boolean = false,
    val runHistoryError: String? = null,
    val remoteRunsLoading: Boolean = false,
    val remoteRunsError: String? = null,
    val remoteAssistLoading: Boolean = false,
    val remoteAssistError: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val runStatus: RunStatus? = null,
    val remoteMonitoring: Boolean = false,
)

private fun isEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("google_sdk", ignoreCase = true) ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
        (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))

internal class FlowPilotViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionStore = SessionStore(application)
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var taskSyncInFlight = false
    private var sessionCheckInFlight = false
    private var remoteAssistRefreshInFlight = false

    private fun apiClient(baseUrl: String) = ApiClient(baseUrl, ::expireSession)

    init {
        restoreRememberedLogin()
        restoreSession()
    }

    fun login(baseUrl: String, email: String, password: String, rememberLoginInfo: Boolean) {
        if (_state.value.loading) return
        val normalizedUrl = try {
            normalizeApiBaseUrl(baseUrl)
        } catch (exception: IllegalArgumentException) {
            _state.value = _state.value.copy(error = exception.message ?: "请输入有效的 API 地址")
            return
        }
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            notice = null,
            apiBaseUrl = normalizedUrl,
        )
        viewModelScope.launch {
            try {
                val client = apiClient(normalizedUrl)
                val login = withContext(Dispatchers.IO) { client.login(email.trim(), password) }
                if (login.user.role != "user") {
                    throw IllegalArgumentException("Android 客户端仅支持普通用户账号，请使用后台管理员账号管理任务")
                }
                if (rememberLoginInfo) {
                    sessionStore.saveRememberedLogin(
                        RememberedLogin(normalizedUrl, email.trim(), password),
                    )
                } else {
                    sessionStore.clearRememberedLogin()
                }
                val expiresAt = System.currentTimeMillis() / 1000 + login.expiresIn
                sessionStore.save(
                    StoredSession(normalizedUrl, login.token, expiresAt, login.user, null),
                )
                _state.value = AppUiState(
                    user = login.user,
                    token = login.token,
                    tokenExpiresAt = expiresAt,
                    apiBaseUrl = normalizedUrl,
                    loading = true,
                )
                try {
                    loadExistingDevice(client, login.token, login.user)
                    _state.value = _state.value.copy(loading = false)
                    refreshRunHistory()
                } catch (exception: Exception) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "登录成功，但加载设备信息失败：${exception.message ?: "未知错误"}",
                    )
                }
            } catch (exception: Exception) {
                sessionStore.clear()
                _state.value = _state.value.copy(loading = false, error = exception.message ?: "登录失败")
            }
        }
    }

    fun register(baseUrl: String, name: String, email: String, password: String, confirmation: String) {
        if (_state.value.loading) return
        val normalizedUrl = try {
            normalizeApiBaseUrl(baseUrl)
        } catch (exception: IllegalArgumentException) {
            _state.value = _state.value.copy(error = exception.message ?: "请输入有效的 API 地址")
            return
        }
        if (password != confirmation) {
            _state.value = _state.value.copy(error = "两次输入的密码不一致", notice = null)
            return
        }
        if (password.length < 6) {
            _state.value = _state.value.copy(error = "密码至少需要 6 个字符", notice = null)
            return
        }
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            notice = null,
            apiBaseUrl = normalizedUrl,
        )
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    apiClient(normalizedUrl).register(name.trim(), email.trim(), password)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    loginEmail = email.trim(),
                    notice = "注册成功，请使用新账号登录",
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "注册失败",
                )
            }
        }
    }

    fun bindDevice() {
        val current = _state.value
        if (current.token == null) return
        if (current.loading) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val (session, device) = withContext(Dispatchers.IO) {
                    val session = freshSession()
                    val client = apiClient(session.apiBaseUrl)
                    client.registerDevice(
                        token = session.token,
                        name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    ).let { session to it }
                }
                current.user?.let { user ->
                    sessionStore.saveDeviceId(user.id, device.id)
                }
                val taskSync = withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).syncTasks(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    device = device,
                    tasks = taskSync,
                )
                refreshRunHistory()
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "设备绑定或任务同步失败",
                )
            }
        }
    }

    fun refreshTasks(showLoading: Boolean = true) {
        val current = _state.value
        if (current.token == null) return
        val device = current.device ?: return
        if (current.loading || taskSyncInFlight) return
        taskSyncInFlight = true
        _state.value = current.copy(loading = showLoading, error = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val taskSync = withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).syncTasks(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    loading = if (showLoading) false else _state.value.loading,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    tasks = taskSync,
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = if (showLoading) false else _state.value.loading,
                    error = exception.message ?: "任务同步失败",
                )
            } finally {
                taskSyncInFlight = false
            }
        }
    }

    fun refreshRemoteRuns() {
        val current = _state.value
        val userId = current.user?.id ?: return
        if (current.token == null || current.remoteRunsLoading) return
        val device = current.device ?: return
        _state.value = current.copy(remoteRunsLoading = true, remoteRunsError = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val requests = withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).also { client ->
                        client.heartbeat(session.token, device.id)
                    }.pendingRemoteRuns(session.token, device.id)
                }
                if (_state.value.user?.id != userId || _state.value.device?.id != device.id) return@launch
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    device = markDeviceOnlineAfterHeartbeat(_state.value.device, device.id),
                    remoteRuns = requests,
                    remoteRunsLoading = false,
                    remoteRunsError = null,
                )
            } catch (exception: Exception) {
                if (_state.value.user?.id != userId || _state.value.device?.id != device.id) return@launch
                _state.value = _state.value.copy(
                    remoteRunsLoading = false,
                    remoteRunsError = "远程请求同步失败：${exception.message ?: "服务器不可用"}",
                )
            }
        }
    }

    fun clearRemoteRunsError() {
        _state.value = _state.value.copy(remoteRunsError = null)
    }

    fun refreshRemoteAssistSessions() {
        val current = _state.value
        val userId = current.user?.id ?: return
        if (current.token == null) return
        val device = current.device ?: return
        if (remoteAssistRefreshInFlight) return
        remoteAssistRefreshInFlight = true
        _state.value = current.copy(remoteAssistLoading = true, remoteAssistError = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val sessions = withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).pendingRemoteAssistSessions(session.token, device.id)
                }
                if (_state.value.user?.id != userId || _state.value.device?.id != device.id) return@launch
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    remoteAssistSessions = sessions,
                    remoteAssistLoading = false,
                    remoteAssistError = null,
                )
            } catch (exception: Exception) {
                if (_state.value.user?.id != userId || _state.value.device?.id != device.id) return@launch
                _state.value = _state.value.copy(
                    remoteAssistLoading = false,
                    remoteAssistError = exception.message ?: "远程协助请求同步失败",
                )
            } finally {
                remoteAssistRefreshInFlight = false
            }
        }
    }

    suspend fun decideRemoteAssistSession(sessionId: String, accept: Boolean): RemoteAssistAccess? =
        withContext(Dispatchers.IO) {
            val session = freshSession()
            apiClient(session.apiBaseUrl).decideRemoteAssistSession(session.token, sessionId, accept)
        }

    suspend fun endRemoteAssistSession(sessionId: String) = withContext(Dispatchers.IO) {
        val session = freshSession()
        apiClient(session.apiBaseUrl).endRemoteAssistSession(session.token, sessionId)
    }

    fun unbindDevice() {
        val current = _state.value
        val user = current.user ?: return
        val device = current.device ?: return
        if (current.token == null || current.loading ||
            !canLogoutDuringRun(current.runStatus?.status)
        ) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).unbindDevice(session.token, device.id)
                }
                sessionStore.clearDeviceId(user.id)
                RemoteRunMonitorService.stop(getApplication())
                RemoteAssistService.stop(getApplication())
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    device = null,
                    tasks = emptyList(),
                    remoteRuns = emptyList(),
                    remoteAssistSessions = emptyList(),
                    remoteRunsLoading = false,
                    remoteMonitoring = false,
                    loading = false,
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "设备解绑失败",
                )
            }
        }
    }

    fun logout() {
        val current = _state.value
        sessionStore.read()?.let { session ->
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        apiClient(session.apiBaseUrl).logout(session.token)
                    }
                } catch (exception: Exception) {
                    _state.value = _state.value.copy(
                        error = "本机已退出，但服务端会话撤销失败：${exception.message ?: "服务器不可用"}",
                    )
                }
            }
        }
        sessionStore.clear()
        val remembered = sessionStore.readRememberedLogin()
        _state.value = AppUiState(
            apiBaseUrl = remembered?.apiBaseUrl ?: current.apiBaseUrl,
            loginEmail = remembered?.email.orEmpty(),
            loginPassword = remembered?.password.orEmpty(),
            rememberLoginInfo = remembered != null,
        )
    }

    fun setRememberLoginInfo(enabled: Boolean) {
        if (!enabled) sessionStore.clearRememberedLogin()
        _state.value = _state.value.copy(rememberLoginInfo = enabled)
    }

    fun clearAccountNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    fun updateAccountProfile(name: String, email: String, currentPassword: String) {
        val current = _state.value
        if (current.loading) return
        val user = current.user ?: return
        if (name.isBlank() || email.isBlank()) {
            _state.value = current.copy(error = "姓名和邮箱不能为空", notice = null)
            return
        }
        val emailChanged = !email.trim().equals(user.email, ignoreCase = true)
        if (!emailChanged && name.trim() == user.name) {
            _state.value = current.copy(error = "个人资料没有变化", notice = null)
            return
        }
        if (emailChanged && currentPassword.isBlank()) {
            _state.value = current.copy(error = "修改邮箱需要验证当前密码", notice = null)
            return
        }
        _state.value = current.copy(loading = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val updatedUser = withContext(Dispatchers.IO) {
                    val client = apiClient(session.apiBaseUrl)
                    client.updateProfile(session.token, name.trim(), email.trim(), currentPassword)
                    client.getCurrentUser(session.token)
                }
                _state.value = _state.value.copy(
                    user = updatedUser,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    loading = false,
                    notice = "个人资料已更新",
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "个人资料更新失败",
                )
            }
        }
    }

    fun changeAccountPassword(currentPassword: String, newPassword: String) {
        val current = _state.value
        if (current.loading) return
        if (current.user == null) return
        if (currentPassword.isBlank() || newPassword.length < 6) {
            _state.value = current.copy(error = "请输入当前密码，并设置至少 6 位的新密码", notice = null)
            return
        }
        _state.value = current.copy(loading = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).changePassword(session.token, currentPassword, newPassword)
                }
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    loading = false,
                    notice = "密码已修改，当前设备保持登录",
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "密码修改失败",
                )
            }
        }
    }

    fun refreshRunStatus() {
        _state.value = _state.value.copy(runStatus = RunStatusStore(getApplication()).snapshot())
    }

    fun checkSession() {
        val current = _state.value
        val token = current.token ?: return
        if (sessionCheckInFlight) return
        sessionCheckInFlight = true
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val user = withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).getCurrentUser(session.token)
                }
                if (_state.value.token != token) return@launch
                _state.value = _state.value.copy(
                    user = user,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                )
            } catch (exception: Exception) {
                if (exception is ApiException && exception.statusCode == 401) expireSession()
            } finally {
                sessionCheckInFlight = false
            }
        }
    }

    fun refreshRunHistory() {
        val current = _state.value
        val userId = current.user?.id ?: return
        if (current.token == null || current.runHistoryLoading) return
        _state.value = current.copy(runHistoryLoading = true, runHistoryError = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val runs = withContext(Dispatchers.IO) {
                    apiClient(session.apiBaseUrl).listRuns(session.token)
                }
                if (_state.value.user?.id != userId) return@launch
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    runHistory = runs,
                    runHistoryLoading = false,
                    runHistoryError = null,
                )
            } catch (exception: Exception) {
                if (_state.value.user?.id != userId) return@launch
                _state.value = _state.value.copy(
                    runHistoryLoading = false,
                    runHistoryError = "运行记录同步失败：${exception.message ?: "服务器不可用"}",
                )
            }
        }
    }

    fun clearRunHistoryError() {
        _state.value = _state.value.copy(runHistoryError = null)
    }

    suspend fun createLocalRun(task: AvailableTask): String = withContext(Dispatchers.IO) {
        val session = freshSession()
        val device = requireNotNull(_state.value.device) { "当前账号尚未绑定此设备" }
        apiClient(session.apiBaseUrl).startLocalRun(session.token, device.id, task)
    }

    suspend fun approveRemoteRun(requestId: String): String? = withContext(Dispatchers.IO) {
        val session = freshSession()
        apiClient(session.apiBaseUrl).approveRemoteRun(
            session.token,
            requestId,
        )
    }

    suspend fun failRun(runId: String, message: String) = withContext(Dispatchers.IO) {
        val session = freshSession()
        apiClient(session.apiBaseUrl).updateRun(session.token, runId, "failure", message.take(500))
    }

    fun showError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    private suspend fun loadExistingDevice(client: ApiClient, token: String, user: SignedInUser) {
        val savedId = sessionStore.read()?.deviceId ?: return
        val device = withContext(Dispatchers.IO) {
            client.listDevices(token).firstOrNull { it.id == savedId && it.status != "disabled" }
        } ?: return
        _state.value = _state.value.copy(device = device)
        val taskSync = withContext(Dispatchers.IO) { client.syncTasks(token, device.id) }
        _state.value = _state.value.copy(
            tasks = taskSync,
        )
    }

    private fun restoreSession() {
        val stored = try {
            sessionStore.read()
        } catch (exception: Exception) {
            _state.value = _state.value.copy(
                error = "无法安全读取登录状态，请清除应用数据后重新登录：${exception.message ?: "未知错误"}",
            )
            return
        } ?: return
        if (stored.user.role != "user") {
            sessionStore.clear()
            return
        }
        _state.value = AppUiState(
            user = stored.user,
            token = stored.token,
            tokenExpiresAt = stored.tokenExpiresAt,
            apiBaseUrl = stored.apiBaseUrl,
            loading = true,
        )
        viewModelScope.launch {
            try {
                val client = apiClient(stored.apiBaseUrl)
                var token = stored.token
                var expiresAt = stored.tokenExpiresAt
                if (shouldRefreshAccessToken(
                        expiresAt,
                        System.currentTimeMillis() / 1000,
                        TOKEN_REFRESH_LEAD_SECONDS,
                    )
                ) {
                    val refreshed = withContext(Dispatchers.IO) { client.refreshToken(token) }
                    token = refreshed.first
                    expiresAt = System.currentTimeMillis() / 1000 + refreshed.second
                    sessionStore.updateToken(token, expiresAt)
                }
                val user = withContext(Dispatchers.IO) { client.getCurrentUser(token) }
                if (user.role != "user") throw IllegalStateException("此客户端仅支持普通用户账号")
                _state.value = _state.value.copy(user = user, token = token, tokenExpiresAt = expiresAt)
                loadExistingDevice(client, token, user)
                _state.value = _state.value.copy(loading = false)
                refreshRunHistory()
            } catch (exception: Exception) {
                sessionStore.clear()
                val remembered = sessionStore.readRememberedLogin()
                _state.value = AppUiState(
                    apiBaseUrl = remembered?.apiBaseUrl ?: stored.apiBaseUrl,
                    loginEmail = remembered?.email.orEmpty(),
                    loginPassword = remembered?.password.orEmpty(),
                    rememberLoginInfo = remembered != null,
                    error = "登录状态已过期，请重新登录：${exception.message ?: "服务器不可用"}",
                )
            }
        }
    }

    private fun restoreRememberedLogin() {
        try {
            val remembered = sessionStore.readRememberedLogin() ?: return
            normalizeApiBaseUrl(remembered.apiBaseUrl)
            _state.value = _state.value.copy(
                apiBaseUrl = remembered.apiBaseUrl,
                loginEmail = remembered.email,
                loginPassword = remembered.password,
                rememberLoginInfo = true,
            )
        } catch (exception: Exception) {
            sessionStore.clearRememberedLogin()
            _state.value = _state.value.copy(
                error = "无法安全读取已记住的登录信息：${exception.message ?: "未知错误"}",
            )
        }
    }

    private fun freshSession(): StoredSession {
        val store = requireNotNull(sessionStore.read()) { "登录状态已失效，请重新登录" }
        if (!shouldRefreshAccessToken(
                store.tokenExpiresAt,
                System.currentTimeMillis() / 1000,
                TOKEN_REFRESH_LEAD_SECONDS,
            )
        ) {
            return store
        }
        val refreshed = apiClient(store.apiBaseUrl).refreshToken(store.token)
        val expiresAt = System.currentTimeMillis() / 1000 + refreshed.second
        sessionStore.updateToken(refreshed.first, expiresAt)
        return store.copy(token = refreshed.first, tokenExpiresAt = expiresAt)
    }

    private fun expireSession() {
        val current = _state.value
        if (current.token == null) return
        sessionStore.clear()
        RemoteRunMonitorService.stop(getApplication())
        OcrStandbyService.stop(getApplication())
        RemoteAssistService.stop(getApplication())
        val remembered = sessionStore.readRememberedLogin()
        _state.value = AppUiState(
            apiBaseUrl = remembered?.apiBaseUrl ?: current.apiBaseUrl,
            loginEmail = remembered?.email.orEmpty(),
            loginPassword = remembered?.password.orEmpty(),
            rememberLoginInfo = remembered != null,
            error = "登录已过期，请重新登录",
        )
    }

    private companion object {
        const val TOKEN_REFRESH_LEAD_SECONDS = 5 * 60L
        const val SCRIPT_CACHE_DIRECTORY = "verified_scripts"
    }
}

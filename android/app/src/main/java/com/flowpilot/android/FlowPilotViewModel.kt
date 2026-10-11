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
    val device: BoundDevice? = null,
    val tasks: List<AvailableTask> = emptyList(),
    val remoteRuns: List<RemoteRun> = emptyList(),
    val scriptReview: ScriptReview? = null,
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

internal data class ScriptReview(val task: AvailableTask, val script: DownloadedScript)

internal class FlowPilotViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionStore = SessionStore(application)
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        restoreSession()
    }

    fun login(baseUrl: String, email: String, password: String) {
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
            apiBaseUrl = normalizedUrl,
        )
        viewModelScope.launch {
            try {
                val client = ApiClient(normalizedUrl)
                val login = withContext(Dispatchers.IO) { client.login(email.trim(), password) }
                if (login.user.role != "user") {
                    throw IllegalArgumentException("Android 客户端仅支持普通用户账号，请使用后台管理员账号管理任务")
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

    fun bindDevice() {
        val current = _state.value
        if (current.token == null) return
        if (current.loading) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val (session, device) = withContext(Dispatchers.IO) {
                    val session = freshSession()
                    val client = ApiClient(session.apiBaseUrl)
                    client.registerDevice(
                        token = session.token,
                        name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    ).let { session to it }
                }
                current.user?.let { user ->
                    sessionStore.saveDeviceId(user.id, device.id)
                }
                val tasks = withContext(Dispatchers.IO) {
                    ApiClient(session.apiBaseUrl).syncTasks(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    device = device,
                    tasks = tasks,
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "设备绑定或任务同步失败",
                )
            }
        }
    }

    fun refreshTasks() {
        val current = _state.value
        if (current.token == null) return
        val device = current.device ?: return
        if (current.loading) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val tasks = withContext(Dispatchers.IO) {
                    ApiClient(session.apiBaseUrl).syncTasks(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    tasks = tasks,
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "任务同步失败",
                )
            }
        }
    }

    fun refreshRemoteRuns() {
        val current = _state.value
        if (current.token == null) return
        val device = current.device ?: return
        if (current.loading) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val requests = withContext(Dispatchers.IO) {
                    ApiClient(session.apiBaseUrl).pendingRemoteRuns(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    remoteRuns = requests,
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(loading = false, error = exception.message ?: "远程任务查询失败")
            }
        }
    }

    fun inspectScript(task: AvailableTask) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null, scriptReview = null)
        viewModelScope.launch {
            try {
                val (session, device) = withContext(Dispatchers.IO) {
                    freshSession() to requireNotNull(_state.value.device) { "当前账号尚未绑定此设备" }
                }
                val script = withContext(Dispatchers.IO) {
                    ApiClient(session.apiBaseUrl).downloadScript(session.token, device.id, task)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    scriptReview = ScriptReview(task, script),
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    scriptReview = null,
                    error = exception.message ?: "无法下载并校验脚本",
                )
            }
        }
    }

    fun clearScriptReview() {
        _state.value = _state.value.copy(scriptReview = null)
    }

    fun logout() {
        sessionStore.clear()
        _state.value = AppUiState(apiBaseUrl = _state.value.apiBaseUrl)
    }

    fun refreshRunStatus() {
        _state.value = _state.value.copy(runStatus = RunStatusStore(getApplication()).snapshot())
    }

    suspend fun createLocalRun(task: AvailableTask): String = withContext(Dispatchers.IO) {
        val session = freshSession()
        val device = requireNotNull(_state.value.device) { "当前账号尚未绑定此设备" }
        ApiClient(session.apiBaseUrl).startLocalRun(session.token, device.id, task)
    }

    suspend fun decideRemoteRun(
        request: RemoteRun,
        approve: Boolean,
        preauthorize: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val session = freshSession()
        ApiClient(session.apiBaseUrl).decideRemoteRun(
            session.token,
            request.id,
            approve,
            preauthorize,
        )
    }

    suspend fun failRun(runId: String, message: String) = withContext(Dispatchers.IO) {
        val session = freshSession()
        ApiClient(session.apiBaseUrl).updateRun(session.token, runId, "failure", message.take(500))
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
        val tasks = withContext(Dispatchers.IO) { client.syncTasks(token, device.id) }
        _state.value = _state.value.copy(tasks = tasks)
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
                val client = ApiClient(stored.apiBaseUrl)
                var token = stored.token
                var expiresAt = stored.tokenExpiresAt
                if (expiresAt <= System.currentTimeMillis() / 1000 + TOKEN_REFRESH_LEAD_SECONDS) {
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
            } catch (exception: Exception) {
                sessionStore.clear()
                _state.value = AppUiState(
                    apiBaseUrl = stored.apiBaseUrl,
                    error = "登录状态已过期，请重新登录：${exception.message ?: "服务器不可用"}",
                )
            }
        }
    }

    private fun freshSession(): StoredSession {
        val store = requireNotNull(sessionStore.read()) { "登录状态已失效，请重新登录" }
        if (store.tokenExpiresAt > System.currentTimeMillis() / 1000 + TOKEN_REFRESH_LEAD_SECONDS) {
            return store
        }
        val refreshed = ApiClient(store.apiBaseUrl).refreshToken(store.token)
        val expiresAt = System.currentTimeMillis() / 1000 + refreshed.second
        sessionStore.updateToken(refreshed.first, expiresAt)
        return store.copy(token = refreshed.first, tokenExpiresAt = expiresAt)
    }

    private companion object {
        const val TOKEN_REFRESH_LEAD_SECONDS = 5 * 60L
    }
}

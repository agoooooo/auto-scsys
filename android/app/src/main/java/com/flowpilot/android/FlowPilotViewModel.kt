package com.flowpilot.android

import android.app.Application
import android.os.Build
import java.io.IOException
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
    val device: BoundDevice? = null,
    val tasks: List<AvailableTask> = emptyList(),
    val pendingPublications: List<PendingPublication> = emptyList(),
    val remoteRuns: List<RemoteRun> = emptyList(),
    val runHistory: List<RunRecord> = emptyList(),
    val runHistoryLoading: Boolean = false,
    val runHistoryError: String? = null,
    val remoteRunsLoading: Boolean = false,
    val remoteRunsError: String? = null,
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

internal data class ScriptReview(
    val task: AvailableTask,
    val script: DownloadedScript,
    val cacheNotice: String? = null,
)

internal class FlowPilotViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionStore = SessionStore(application)
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

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
            apiBaseUrl = normalizedUrl,
        )
        viewModelScope.launch {
            try {
                val client = ApiClient(normalizedUrl)
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
                val taskSync = withContext(Dispatchers.IO) {
                    ApiClient(session.apiBaseUrl).syncTasks(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    device = device,
                    tasks = taskSync.tasks,
                    pendingPublications = taskSync.pendingPublications,
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

    fun refreshTasks() {
        val current = _state.value
        if (current.token == null) return
        val device = current.device ?: return
        if (current.loading) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val taskSync = withContext(Dispatchers.IO) {
                    ApiClient(session.apiBaseUrl).syncTasks(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    tasks = taskSync.tasks,
                    pendingPublications = taskSync.pendingPublications,
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "任务同步失败",
                )
            }
        }
    }

    fun acceptPublication(publication: PendingPublication) {
        val current = _state.value
        val device = current.device ?: return
        if (current.token == null || current.loading) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { freshSession() }
                val client = ApiClient(session.apiBaseUrl)
                withContext(Dispatchers.IO) {
                    client.acceptPublication(session.token, device.id, publication.id)
                }
                val taskSync = withContext(Dispatchers.IO) {
                    client.syncTasks(session.token, device.id)
                }
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    tasks = taskSync.tasks,
                    pendingPublications = taskSync.pendingPublications,
                    loading = false,
                )
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = exception.message ?: "确认接收脚本失败",
                )
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
                    ApiClient(session.apiBaseUrl).also { client ->
                        client.heartbeat(session.token, device.id)
                    }.pendingRemoteRuns(session.token, device.id)
                }
                if (_state.value.user?.id != userId || _state.value.device?.id != device.id) return@launch
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
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
                    ApiClient(session.apiBaseUrl).unbindDevice(session.token, device.id)
                }
                sessionStore.clearDeviceId(user.id)
                RemoteRunMonitorService.stop(getApplication())
                _state.value = _state.value.copy(
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    device = null,
                    tasks = emptyList(),
                    pendingPublications = emptyList(),
                    remoteRuns = emptyList(),
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

    fun inspectScript(task: AvailableTask, allowOlderCachedVersion: Boolean = true) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null, scriptReview = null)
        viewModelScope.launch {
            try {
                val (session, user, device) = withContext(Dispatchers.IO) {
                    Triple(
                        freshSession(),
                        requireNotNull(_state.value.user) { "登录状态已失效，请重新登录" },
                        requireNotNull(_state.value.device) { "当前账号尚未绑定此设备" },
                    )
                }
                val review = withContext(Dispatchers.IO) {
                    val client = ApiClient(session.apiBaseUrl)
                    val cache = ScriptCache(getApplication<Application>().filesDir.resolve(SCRIPT_CACHE_DIRECTORY))
                    val scope = ScriptCacheScope(session.apiBaseUrl, user.id, device.id)
                    try {
                        val script = client.downloadScript(session.token, device.id, task)
                        val cacheNotice = try {
                            cache.save(scope, task, script)
                            null
                        } catch (exception: IOException) {
                            "脚本已校验，但无法保存本地缓存：${exception.message ?: "存储不可用"}"
                        }
                        ScriptReview(task, script, cacheNotice)
                    } catch (exception: Exception) {
                        if (exception is ApiException && exception.statusCode in 400..499) throw exception
                        val cached = cache.load(scope, task)?.let {
                            ScriptReview(task, it, "无法连接服务器，使用本机保存的已校验版本。运行仍需服务端授权。")
                        }
                            ?: if (allowOlderCachedVersion) {
                                cache.latest(scope, task)?.let {
                                    ScriptReview(
                                        it.task,
                                        it.script,
                                        "无法下载请求版本 ${task.version}，改用本机最近一次成功校验的版本 ${it.task.version}。运行仍需服务端授权。",
                                    )
                                }
                            } else {
                                null
                            }
                        cached ?: throw exception
                    }
                }
                _state.value = _state.value.copy(
                    loading = false,
                    token = session.token,
                    tokenExpiresAt = session.tokenExpiresAt,
                    scriptReview = review,
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
        val current = _state.value
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

    fun refreshRunStatus() {
        _state.value = _state.value.copy(runStatus = RunStatusStore(getApplication()).snapshot())
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
                    ApiClient(session.apiBaseUrl).listRuns(session.token)
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
        ApiClient(session.apiBaseUrl).startLocalRun(session.token, device.id, task)
    }

    suspend fun approveRemoteRun(requestId: String): String? = withContext(Dispatchers.IO) {
        val session = freshSession()
        ApiClient(session.apiBaseUrl).approveRemoteRun(
            session.token,
            requestId,
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
        val taskSync = withContext(Dispatchers.IO) { client.syncTasks(token, device.id) }
        _state.value = _state.value.copy(
            tasks = taskSync.tasks,
            pendingPublications = taskSync.pendingPublications,
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
                val client = ApiClient(stored.apiBaseUrl)
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
        val refreshed = ApiClient(store.apiBaseUrl).refreshToken(store.token)
        val expiresAt = System.currentTimeMillis() / 1000 + refreshed.second
        sessionStore.updateToken(refreshed.first, expiresAt)
        return store.copy(token = refreshed.first, tokenExpiresAt = expiresAt)
    }

    private companion object {
        const val TOKEN_REFRESH_LEAD_SECONDS = 5 * 60L
        const val SCRIPT_CACHE_DIRECTORY = "verified_scripts"
    }
}

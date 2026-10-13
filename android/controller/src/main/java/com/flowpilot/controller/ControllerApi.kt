package com.flowpilot.controller

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class ControllerUser(val id: String, val name: String, val email: String, val role: String)
internal data class ControllerLogin(val token: String, val expiresIn: Long, val user: ControllerUser)
internal data class ControllerDevice(val id: String, val name: String, val model: String, val status: String)
internal data class ControllerTask(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val version: String,
)
internal data class ControllerRunStatus(
    val id: String,
    val status: String,
    val runStatus: String?,
    val resultSummary: String,
    val stopRequested: Boolean = false,
)

internal fun normalizeControllerApiUrl(value: String, allowCleartext: Boolean = BuildConfig.DEBUG): String {
    val uri = try {
        URI(value.trim())
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("请输入有效的 API 地址，例如 http://192.168.0.101:8001", error)
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme !in setOf("http", "https") ||
        (scheme == "http" && !allowCleartext) ||
        uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        uri.rawPath !in listOf(null, "", "/") ||
        uri.port == 0 ||
        uri.port > 65535
    ) {
        throw IllegalArgumentException("API 地址必须是 HTTP(S) 服务根地址，正式版必须使用 HTTPS")
    }
    return URI(scheme, null, uri.host.lowercase(Locale.ROOT), uri.port, null, null, null).toASCIIString()
}

internal class ControllerApi(
    baseUrl: String,
    initialAccessToken: String? = null,
    private val onAccessTokenRefreshed: (String) -> Unit = {},
    private val onSessionExpired: () -> Unit = {},
) {
    private val baseUrl = normalizeControllerApiUrl(baseUrl)
    @Volatile private var latestAccessToken: String? = initialAccessToken

    fun register(name: String, email: String, password: String) {
        request(
            "/auth/register",
            "POST",
            payload = JSONObject().put("name", name).put("email", email).put("password", password),
        )
    }

    fun passwordRecoveryEnabled(): Boolean =
        JSONObject(request("/auth/recovery-status")).optBoolean("enabled")

    fun requestPasswordResetCode(email: String) {
        request(
            "/auth/forgot-password",
            "POST",
            payload = JSONObject().put("email", email),
        )
    }

    fun resetPassword(email: String, code: String, newPassword: String) {
        request(
            "/auth/reset-password",
            "POST",
            payload = JSONObject()
                .put("email", email)
                .put("code", code)
                .put("new_password", newPassword),
        )
    }

    fun login(email: String, password: String): ControllerLogin {
        val response = JSONObject(
            request("/auth/login", "POST", payload = JSONObject().put("email", email).put("password", password)),
        )
        val user = response.optJSONObject("user") ?: throw IOException("登录响应缺少用户信息")
        val token = response.optString("access_token")
        if (token.isBlank()) throw IOException("登录响应缺少访问令牌")
        return ControllerLogin(
            token,
            response.optLong("expires_in"),
            ControllerUser(user.optString("id"), user.optString("name"), user.optString("email"), user.optString("role")),
        )
    }

    fun logout(token: String) {
        request("/auth/logout", "POST", token)
    }

    fun profile(token: String): ControllerUser {
        val response = JSONObject(request("/me", token = token))
        return ControllerUser(
            response.optString("id"),
            response.optString("name"),
            response.optString("email"),
            response.optString("role"),
        )
    }

    fun updateProfile(token: String, name: String, email: String, currentPassword: String?): ControllerUser {
        val payload = JSONObject().put("name", name).put("email", email)
        currentPassword?.takeIf(String::isNotBlank)?.let { payload.put("current_password", it) }
        val response = JSONObject(request("/me", "PATCH", token, payload))
        return ControllerUser(
            response.optString("id"),
            response.optString("name"),
            response.optString("email"),
            response.optString("role"),
        )
    }

    fun changePassword(token: String, currentPassword: String, newPassword: String) {
        request(
            "/auth/change-password",
            "POST",
            token,
            JSONObject()
                .put("current_password", currentPassword)
                .put("new_password", newPassword),
        )
    }

    fun devices(token: String): List<ControllerDevice> =
        arrayRequest("/devices", token).map {
            ControllerDevice(it.optString("id"), it.optString("name"), it.optString("model"), it.optString("status"))
        }

    fun tasks(token: String): List<ControllerTask> =
        arrayRequest("/tasks", token).map {
            ControllerTask(
                it.optString("id"),
                it.optString("name"),
                it.optString("description"),
                it.optString("status"),
                it.optString("active_version").takeUnless { version -> version == "null" }.orEmpty(),
            )
        }

    fun activeRemoteRun(token: String, deviceId: String): ControllerRunStatus? =
        arrayRequest("/devices/${encode(deviceId)}/remote-runs", token)
            .firstOrNull { isControllerRunActive(it.optString("status")) }
            ?.let {
                ControllerRunStatus(
                    id = it.optString("id"),
                    status = it.optString("status"),
                    runStatus = null,
                    resultSummary = "",
                    stopRequested = it.optBoolean("stop_requested"),
                )
            }

    fun dispatch(
        token: String,
        taskId: String,
        deviceId: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): ControllerRunStatus {
        val response = JSONObject(
            request(
                "/remote-runs",
                "POST",
                token,
                JSONObject().put("task_id", taskId).put("device_id", deviceId),
                idempotencyKey,
            ),
        )
        val id = response.optString("id").takeIf(String::isNotBlank)
            ?: throw IOException("服务器没有返回运行请求 ID")
        return ControllerRunStatus(
            id = id,
            status = response.optString("status").takeIf(String::isNotBlank) ?: "pending",
            runStatus = null,
            resultSummary = "",
        )
    }

    fun rejectRemoteRun(token: String, requestId: String) {
        request(
            "/remote-runs/${encode(requestId)}",
            "PATCH",
            token,
            JSONObject().put("decision", "reject"),
        )
    }

    fun requestStop(token: String, requestId: String) {
        request(
            "/remote-runs/${encode(requestId)}/stop",
            "POST",
            token,
        )
    }

    fun runStatus(token: String, requestId: String): ControllerRunStatus {
        val response = JSONObject(request("/remote-runs/${encode(requestId)}", token = token))
        return ControllerRunStatus(
            id = response.optString("id"),
            status = response.optString("status"),
            runStatus = response.optString("run_status").takeIf(String::isNotBlank),
            resultSummary = response.optString("result_summary"),
            stopRequested = response.optBoolean("stop_requested"),
        )
    }

    private fun arrayRequest(path: String, token: String): List<JSONObject> {
        val array = JSONArray(request(path, token = token))
        return (0 until array.length()).map { index ->
            array.optJSONObject(index) ?: throw IOException("服务器返回了无效的数据项")
        }
    }

    private fun request(
        path: String,
        method: String = "GET",
        token: String? = null,
        payload: JSONObject? = null,
        idempotencyKey: String? = null,
        allowRefresh: Boolean = true,
    ): String {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            val effectiveToken = latestAccessToken ?: token
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            effectiveToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            }
            val status = connection.responseCode
            if (status == 401 && effectiveToken != null && path != "/auth/refresh" && allowRefresh) {
                connection.disconnect()
                val refreshed = try {
                    JSONObject(
                        request("/auth/refresh", "POST", effectiveToken, allowRefresh = false),
                    ).optString("access_token").takeIf(String::isNotBlank)
                        ?: throw IOException("登录已过期，请重新登录")
                } catch (error: ControllerApiException) {
                    if (error.statusCode == 401) onSessionExpired()
                    throw error
                }
                latestAccessToken = refreshed
                onAccessTokenRefreshed(refreshed)
                return request(path, method, refreshed, payload, idempotencyKey, allowRefresh = false)
            }
            if (status == 204) return ""
            val body = readLimited(if (status in 200..299) connection.inputStream else connection.errorStream)
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body.toString(Charsets.UTF_8)).optString("detail") }.getOrNull()
                throw ControllerApiException(detail?.takeIf(String::isNotBlank) ?: "服务器请求失败（HTTP $status）", status)
            }
            if (body.isEmpty()) throw IOException("服务器返回了空响应")
            return body.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: java.io.InputStream?): ByteArray {
        if (stream == null) return byteArrayOf()
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("服务器响应超过允许大小")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}

internal class ControllerApiException(message: String, val statusCode: Int) : IOException(message)

internal data class ControllerDispatchTarget(
    val device: ControllerDevice,
    val task: ControllerTask,
)

internal fun orderControllerDevices(devices: List<ControllerDevice>): List<ControllerDevice> =
    devices.sortedWith(
        compareByDescending<ControllerDevice> { it.status == "online" }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.id },
    )

internal fun buildControllerDispatchTargets(
    devices: List<ControllerDevice>,
    tasks: List<ControllerTask>,
    selectedDeviceIds: Set<String>,
    selectedTaskIds: Set<String>,
): List<ControllerDispatchTarget> =
    orderControllerDevices(devices)
        .filter { it.id in selectedDeviceIds && it.status == "online" }
        .flatMap { device ->
            tasks.filter {
                it.id in selectedTaskIds && it.status == "published" && it.version.isNotBlank()
            }.map { task -> ControllerDispatchTarget(device, task) }
        }

internal fun canDispatchControllerTask(deviceStatus: String, taskStatus: String, version: String): Boolean =
    deviceStatus == "online" && taskStatus == "published" && version.isNotBlank()

internal fun isControllerRunActive(status: String): Boolean =
    status == "queued" || status == "pending" || status == "running"

internal fun controllerRunStatusLabel(status: String): String = when (status) {
    "queued" -> "队列等待中"
    "pending" -> "等待受控端接收"
    "running" -> "受控端运行中"
    "success" -> "运行成功"
    "failure" -> "运行失败"
    "stopped" -> "已停止"
    "expired" -> "请求已过期"
    "rejected" -> "请求已拒绝"
    else -> "状态：$status"
}

internal fun controllerDeviceStatusLabel(status: String): String = when (status) {
    "online" -> "在线"
    "disabled" -> "已停用"
    else -> "离线"
}

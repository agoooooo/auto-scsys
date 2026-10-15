package com.flowpilot.controller

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
internal data class ControllerAppUpdate(
    val appId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val sha256: String,
    val downloadUrl: String,
)
internal data class ControllerDevice(val id: String, val name: String, val model: String, val status: String)
internal data class ControllerRemoteAssistSession(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val requestedByName: String,
    val status: String,
    val expiresAt: Long,
)
internal data class ControllerRemoteAssistInput(
    val type: String,
    val x: Float? = null,
    val y: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val durationMs: Long = 300,
)

internal fun controllerRemoteAssistGesture(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    width: Float,
    height: Float,
    contentWidth: Float = width,
    contentHeight: Float = height,
    contentOffsetX: Float = 0f,
    contentOffsetY: Float = 0f,
): ControllerRemoteAssistInput {
    require(width > 0f && height > 0f && contentWidth > 0f && contentHeight > 0f) {
        "远程画面尺寸必须大于零"
    }
    val x = ((startX - contentOffsetX) / contentWidth).coerceIn(0f, 1f)
    val y = ((startY - contentOffsetY) / contentHeight).coerceIn(0f, 1f)
    val normalizedEndX = ((endX - contentOffsetX) / contentWidth).coerceIn(0f, 1f)
    val normalizedEndY = ((endY - contentOffsetY) / contentHeight).coerceIn(0f, 1f)
    return if (kotlin.math.abs(endX - startX) < 20f && kotlin.math.abs(endY - startY) < 20f) {
        ControllerRemoteAssistInput("tap", x, y)
    } else {
        ControllerRemoteAssistInput("swipe", x, y, normalizedEndX, normalizedEndY)
    }
}
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
internal data class ControllerRunHistory(
    val id: String,
    val taskId: String,
    val taskName: String,
    val version: String,
    val deviceId: String,
    val deviceName: String,
    val remoteRunId: String?,
    val status: String,
    val startedAt: String,
    val finishedAt: String,
    val resultSummary: String,
)

internal fun normalizeControllerApiUrl(value: String, allowCleartext: Boolean = BuildConfig.DEBUG): String {
    val uri = try {
        URI(value.trim())
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("请输入有效的 API 地址，例如 http://192.168.0.105:8001", error)
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

    fun checkAppUpdate(appId: String, currentVersionCode: Long): ControllerAppUpdate? {
        val response = JSONObject(request("/app-updates/${encode(appId)}?current_version_code=$currentVersionCode"))
        if (!response.optBoolean("available")) return null
        val release = ControllerAppUpdate(
            appId = response.optString("app_id"),
            packageName = response.optString("package_name"),
            versionCode = response.optLong("version_code"),
            versionName = response.optString("version_name"),
            notes = response.optString("notes"),
            sha256 = response.optString("sha256"),
            downloadUrl = response.optString("download_url"),
        )
        if (release.appId != appId || release.versionCode <= currentVersionCode ||
            release.versionName.isBlank() || release.sha256.isBlank() ||
            !release.downloadUrl.startsWith("/app-updates/$appId/")
        ) {
            throw IOException("服务器返回的 App 更新信息无效")
        }
        return release
    }

    fun downloadAppUpdate(release: ControllerAppUpdate, target: File) {
        val connection = URL("$baseUrl${release.downloadUrl}").openConnection() as HttpURLConnection
        val partialFile = File(target.parentFile, "${target.name}.part")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = readLimited(connection.errorStream, 64 * 1024)
                val detail = runCatching { JSONObject(error.toString(Charsets.UTF_8)).optString("detail") }.getOrNull()
                throw ControllerApiException(detail?.takeIf(String::isNotBlank) ?: "App 下载失败（HTTP $status）", status)
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        if (downloadedBytes > MAX_APP_APK_BYTES) {
                            throw IOException("App 安装包超过 100 MiB 限制")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            val responseHash = connection.getHeaderField("X-Content-SHA256")
            if (downloadedBytes == 0L ||
                (responseHash != null && !responseHash.equals(release.sha256, ignoreCase = true)) ||
                !java.security.MessageDigest.isEqual(
                    actualHash.toByteArray(Charsets.US_ASCII),
                    release.sha256.lowercase().toByteArray(Charsets.US_ASCII),
                )
            ) {
                throw IOException("App 安装包完整性校验失败，已拒绝安装")
            }
            if (target.exists() && !target.delete()) throw IOException("无法替换本地 App 安装包")
            if (!partialFile.renameTo(target)) throw IOException("无法保存本地 App 安装包")
        } catch (exception: Exception) {
            partialFile.delete()
            throw exception
        } finally {
            connection.disconnect()
        }
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

    fun unbindDevice(token: String, deviceId: String) {
        request("/devices/${encode(deviceId)}", "DELETE", token)
    }

    fun createRemoteAssistSession(
        token: String,
        deviceId: String,
        durationSeconds: Int = 300,
    ): ControllerRemoteAssistSession =
        JSONObject(
            request(
                "/remote-sessions",
                "POST",
                token,
                JSONObject().put("device_id", deviceId).put("duration_seconds", durationSeconds),
            ),
        ).toRemoteAssistSession()

    fun remoteAssistSession(token: String, sessionId: String): ControllerRemoteAssistSession =
        JSONObject(request("/remote-sessions/${encode(sessionId)}", token = token)).toRemoteAssistSession()

    fun remoteAssistTicket(token: String, sessionId: String): String =
        JSONObject(
            request(
                "/remote-sessions/${encode(sessionId)}/controller-ticket",
                "POST",
                token,
            ),
        ).optString("ticket").takeIf(String::isNotBlank)
            ?: throw IOException("服务器没有返回远程协助凭据")

    fun remoteAssistFrame(sessionId: String, ticket: String, afterSequence: Long): Pair<ByteArray, Long>? {
        val connection = URL(
            "$baseUrl/remote-sessions/${encode(sessionId)}/frame?after_seq=$afterSequence",
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer $ticket")
            val status = connection.responseCode
            if (status == 204) return null
            val body = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_REMOTE_FRAME_BYTES,
            )
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body.toString(Charsets.UTF_8)).optString("detail") }.getOrNull()
                throw ControllerApiException(detail?.takeIf(String::isNotBlank) ?: "远程画面读取失败（HTTP $status）", status)
            }
            if (connection.contentType?.substringBefore(';') != "image/jpeg") {
                throw IOException("服务器返回的远程画面格式无效")
            }
            val sequence = connection.getHeaderField("X-Frame-Sequence")?.toLongOrNull()
                ?: throw IOException("服务器返回的远程画面缺少序号")
            if (sequence <= afterSequence || body.isEmpty()) throw IOException("服务器返回了无效的远程画面")
            return body to sequence
        } finally {
            connection.disconnect()
        }
    }

    fun sendRemoteAssistInput(
        sessionId: String,
        ticket: String,
        input: ControllerRemoteAssistInput,
    ) {
        val payload = JSONObject().put("type", input.type)
        input.x?.let { payload.put("x", it) }
        input.y?.let { payload.put("y", it) }
        input.endX?.let { payload.put("end_x", it) }
        input.endY?.let { payload.put("end_y", it) }
        if (input.type == "swipe") payload.put("duration_ms", input.durationMs.coerceIn(100, 2_000))
        request(
            "/remote-sessions/${encode(sessionId)}/input",
            "POST",
            ticket,
            payload,
            allowRefresh = false,
        )
    }

    fun endRemoteAssistSession(token: String, sessionId: String) {
        request("/remote-sessions/${encode(sessionId)}/end", "POST", token)
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

    fun runHistory(token: String): List<ControllerRunHistory> =
        arrayRequest("/runs", token).map {
            ControllerRunHistory(
                id = it.optString("id"),
                taskId = it.optString("task_id"),
                taskName = it.optString("task_name"),
                version = it.optString("version"),
                deviceId = it.optString("device_id"),
                deviceName = it.optString("device_name"),
                remoteRunId = it.optString("remote_run_id").takeUnless { value -> value.isBlank() || value == "null" },
                status = it.optString("status"),
                startedAt = it.optString("started_at"),
                finishedAt = it.optString("finished_at").takeUnless { value -> value.isBlank() || value == "null" }.orEmpty(),
                resultSummary = it.optString("result_summary"),
            )
        }

    private fun arrayRequest(path: String, token: String): List<JSONObject> {
        val array = JSONArray(request(path, token = token))
        return (0 until array.length()).map { index ->
            array.optJSONObject(index) ?: throw IOException("服务器返回了无效的数据项")
        }
    }

    private fun JSONObject.toRemoteAssistSession() = ControllerRemoteAssistSession(
        id = optString("id"),
        deviceId = optString("device_id"),
        deviceName = optString("device_name"),
        deviceModel = optString("device_model"),
        requestedByName = optString("requested_by_name"),
        status = optString("status"),
        expiresAt = optLong("expires_at"),
    )

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
                val refreshed = JSONObject(
                    request("/auth/refresh", "POST", effectiveToken, allowRefresh = false),
                ).optString("access_token").takeIf(String::isNotBlank)
                    ?: throw IOException("登录已过期，请重新登录")
                latestAccessToken = refreshed
                onAccessTokenRefreshed(refreshed)
                return request(path, method, refreshed, payload, idempotencyKey, allowRefresh = false)
            }
            if (status == 401 && effectiveToken != null) onSessionExpired()
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

    private fun readLimited(stream: java.io.InputStream?, maxBytes: Int = MAX_RESPONSE_BYTES): ByteArray {
        if (stream == null) return byteArrayOf()
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw IOException("服务器响应超过允许大小")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_APP_APK_BYTES = 100 * 1024 * 1024
        const val MAX_REMOTE_FRAME_BYTES = 1024 * 1024
    }
}

internal class ControllerApiException(message: String, val statusCode: Int) : IOException(message)

internal data class ControllerDispatchTarget(
    val device: ControllerDevice,
    val task: ControllerTask,
)

internal data class ControllerDeviceGroup(
    val status: String,
    val label: String,
    val devices: List<ControllerDevice>,
)

internal fun orderControllerDevices(devices: List<ControllerDevice>): List<ControllerDevice> =
    devices.sortedWith(
        compareByDescending<ControllerDevice> { it.status == "online" }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.id },
    )

internal fun visibleControllerDevices(devices: List<ControllerDevice>): List<ControllerDevice> =
    devices.filter { it.status != "disabled" }

internal fun groupControllerDevices(devices: List<ControllerDevice>): List<ControllerDeviceGroup> {
    val groups = listOf(
        "online" to "在线设备",
        "offline" to "离线设备",
    ).mapNotNull { (status, label) ->
        orderControllerDevices(devices.filter { it.status == status })
            .takeIf { it.isNotEmpty() }
            ?.let { ControllerDeviceGroup(status, label, it) }
    }
    val otherDevices = orderControllerDevices(devices.filter {
        it.status != "online" && it.status != "offline" && it.status != "disabled"
    })
    return groups + if (otherDevices.isEmpty()) {
        emptyList()
    } else {
        listOf(ControllerDeviceGroup("unknown", "其他状态", otherDevices))
    }
}

internal fun isControllerDeviceGroupExpandedByDefault(status: String): Boolean =
    status != "offline"

internal fun canUnbindControllerDevice(status: String): Boolean =
    status == "offline"

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

internal fun shouldShowControllerDeviceModel(name: String, model: String): Boolean =
    model.isNotBlank() && !model.equals(name, ignoreCase = true)

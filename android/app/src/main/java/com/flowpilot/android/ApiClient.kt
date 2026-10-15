package com.flowpilot.android

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class SignedInUser(val id: String, val name: String, val email: String, val role: String)

internal data class LoginResult(val token: String, val expiresIn: Long, val user: SignedInUser)

internal data class BoundDevice(val id: String, val name: String, val model: String, val status: String)

internal data class RemoteRun(
    val id: String,
    val taskId: String,
    val taskName: String,
    val source: String,
    val version: String,
    val status: String,
    val runId: String?,
    val requestedBy: String,
    val expiresAt: Long,
    val capabilities: List<String>,
)

internal data class RunRecord(
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

internal data class RemoteRunStatus(
    val status: String,
    val stopRequested: Boolean,
)

internal data class RemoteAssistSession(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val requestedBy: String,
    val requestedByName: String,
    val status: String,
    val expiresAt: Long,
)

internal data class RemoteAssistAccess(
    val session: RemoteAssistSession,
    val deviceTicket: String,
)

internal data class RemoteAssistInput(
    val seq: Long,
    val type: String,
    val x: Float? = null,
    val y: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val durationMs: Long = 300,
)

internal data class DownloadedScript(
    val filename: String,
    val source: String,
    val sha256: String,
    val isCached: Boolean = false,
)

internal data class AppUpdateRelease(
    val appId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val sha256: String,
    val downloadUrl: String,
)

internal data class AvailableTask(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val capabilities: List<String>,
    val source: String = "user",
)

internal fun shouldRefreshAccessToken(
    expiresAtSeconds: Long,
    nowSeconds: Long,
    refreshLeadSeconds: Long,
): Boolean = expiresAtSeconds <= nowSeconds + refreshLeadSeconds

internal class ApiClient(
    baseUrl: String,
    private val onSessionExpired: () -> Unit = {},
) {
    private val baseUrl = normalizeApiBaseUrl(baseUrl)

    fun register(name: String, email: String, password: String) {
        request(
            "/auth/register",
            method = "POST",
            payload = JSONObject()
                .put("name", name)
                .put("email", email)
                .put("password", password),
        )
    }

    fun passwordRecoveryEnabled(): Boolean =
        JSONObject(request("/auth/recovery-status")).optBoolean("enabled")

    fun requestPasswordResetCode(email: String) {
        request(
            "/auth/forgot-password",
            method = "POST",
            payload = JSONObject().put("email", email),
        )
    }

    fun resetPassword(email: String, code: String, newPassword: String) {
        request(
            "/auth/reset-password",
            method = "POST",
            payload = JSONObject()
                .put("email", email)
                .put("code", code)
                .put("new_password", newPassword),
        )
    }

    fun login(email: String, password: String): LoginResult {
        val response = JSONObject(request(
            "/auth/login",
            payload = JSONObject().put("email", email).put("password", password),
        ))
        val token = response.optString("access_token")
        val user = response.optJSONObject("user")
            ?: throw IOException("登录响应缺少用户信息")
        if (token.isBlank()) throw IOException("登录响应缺少访问令牌")
        return LoginResult(
            token = token,
            expiresIn = response.optLong("expires_in"),
            user = SignedInUser(
                id = user.optString("id"),
                name = user.optString("name"),
                email = user.optString("email"),
                role = user.optString("role", "user"),
            ),
        )
    }

    fun refreshToken(token: String): Pair<String, Long> {
        val response = JSONObject(request(
            "/auth/refresh",
            method = "POST",
            token = token,
        ))
        val refreshed = response.optString("access_token")
        if (refreshed.isBlank()) throw IOException("续期响应缺少访问令牌")
        return refreshed to response.optLong("expires_in")
    }

    fun checkAppUpdate(appId: String, currentVersionCode: Long): AppUpdateRelease? {
        val response = JSONObject(request("/app-updates/${encodePathSegment(appId)}?current_version_code=$currentVersionCode"))
        if (!response.optBoolean("available")) return null
        val release = AppUpdateRelease(
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

    fun downloadAppUpdate(release: AppUpdateRelease, target: File) {
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
                throw ApiException(detail?.takeIf(String::isNotBlank) ?: "App 下载失败（HTTP $status）", status)
            }
            val digest = MessageDigest.getInstance("SHA-256")
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
                !MessageDigest.isEqual(
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

    fun logout(token: String) {
        request("/auth/logout", method = "POST", token = token)
    }

    fun getCurrentUser(token: String): SignedInUser {
        val user = JSONObject(request("/me", token = token))
        return SignedInUser(
            id = user.optString("id"),
            name = user.optString("name"),
            email = user.optString("email"),
            role = user.optString("role", "user"),
        )
    }

    fun updateProfile(token: String, name: String, email: String, currentPassword: String?) {
        val payload = JSONObject().put("name", name).put("email", email)
        if (!currentPassword.isNullOrBlank()) payload.put("current_password", currentPassword)
        request("/me", method = "PATCH", token = token, payload = payload)
    }

    fun changePassword(token: String, currentPassword: String, newPassword: String) {
        request(
            "/auth/change-password",
            method = "POST",
            token = token,
            payload = JSONObject()
                .put("current_password", currentPassword)
                .put("new_password", newPassword),
        )
    }
    fun listDevices(token: String): List<BoundDevice> =
        requestArray("/devices", token).map { item ->
            BoundDevice(
                id = item.optString("id"),
                name = item.optString("name"),
                model = item.optString("model"),
                status = item.optString("status"),
            )
        }

    fun registerDevice(token: String, name: String, model: String): BoundDevice {
        val response = JSONObject(request(
            "/devices",
            method = "POST",
            token = token,
            payload = JSONObject()
                .put("name", name)
                .put("model", model)
                .put("app_version", BuildConfig.VERSION_NAME),
        ))
        return BoundDevice(
            id = response.optString("id"),
            name = response.optString("name"),
            model = response.optString("model"),
            status = response.optString("status"),
        )
    }

    fun unbindDevice(token: String, deviceId: String) {
        request("/devices/${encodePathSegment(deviceId)}", method = "DELETE", token = token)
    }

    fun syncTasks(token: String, deviceId: String): List<AvailableTask> {
        val response = JSONObject(request("/devices/$deviceId/sync", method = "POST", token = token))
        val tasks = response.optJSONArray("tasks").orEmpty().objects().map { task ->
            AvailableTask(
                id = task.optString("id"),
                name = task.optString("name"),
                description = task.optString("description"),
                version = task.optString("active_version"),
                capabilities = task.optJSONArray("capabilities").orEmpty().strings(),
                source = task.optString("source", "user"),
            )
        }
        return tasks
    }

    fun heartbeat(token: String, deviceId: String) {
        request(
            "/devices/${encodePathSegment(deviceId)}/heartbeat",
            method = "POST",
            token = token,
            payload = JSONObject().put("app_version", BuildConfig.VERSION_NAME),
        )
    }

    fun downloadScript(token: String, deviceId: String, task: AvailableTask): DownloadedScript {
        if (task.version.isBlank()) throw IOException("任务没有可运行版本")
        val contentPath =
            "/devices/$deviceId/tasks/${encodePathSegment(task.id)}/versions/${encodePathSegment(task.version)}/content"
        val ticket = JSONObject(request("$contentPath-url", token = token))
        val expectedHash = ticket.optString("sha256")
            .takeIf(String::isNotBlank) ?: throw IOException("脚本响应缺少完整性校验信息")
        val signedUrl = ticket.opt("url")
            .takeIf { it != null && it != JSONObject.NULL }
            ?.toString()
            .orEmpty()
        val response = if (signedUrl.isNotBlank()) {
            exchange(signedUrl, absoluteUrl = true)
        } else {
            exchange(contentPath, token = token)
        }
        if (response.status !in 200..299) throw response.error()
        val responseHash = response.headers["x-content-sha256"]
            ?: response.headers["x-amz-meta-sha256"]
        val source = response.body.toString(Charsets.UTF_8)
        verifyDownloadedScriptSha256(source, expectedHash, responseHash)
        return DownloadedScript(
            filename = ticket.optString("filename", task.name).ifBlank { task.name },
            source = source,
            sha256 = expectedHash.lowercase(),
        )
    }

    fun startLocalRun(token: String, deviceId: String, task: AvailableTask): String {
        val response = JSONObject(request(
            "/devices/$deviceId/runs",
            method = "POST",
            token = token,
            payload = JSONObject().put("task_id", task.id).put("version", task.version),
        ))
        return response.optString("id").takeIf(String::isNotBlank)
            ?: throw IOException("服务器没有返回运行记录 ID")
    }

    fun pendingRemoteRuns(token: String, deviceId: String): List<RemoteRun> =
        requestArray("/devices/$deviceId/remote-runs", token).map { item ->
            RemoteRun(
                id = item.optString("id"),
                taskId = item.optString("task_id"),
                taskName = item.optString("task"),
                source = item.optString("source"),
                version = item.optString("version"),
                status = item.optString("status"),
                runId = item.optString("run_id").takeIf(String::isNotBlank),
                requestedBy = item.optString("requested_by_name"),
                expiresAt = item.optLong("expires_at"),
                capabilities = item.optJSONArray("capabilities").orEmpty().strings(),
            )
        }

    fun pendingRemoteAssistSessions(token: String, deviceId: String): List<RemoteAssistSession> =
        requestArray("/devices/${encodePathSegment(deviceId)}/remote-sessions", token).map(::remoteAssistSession)

    fun decideRemoteAssistSession(token: String, sessionId: String, accept: Boolean): RemoteAssistAccess? {
        val response = JSONObject(request(
            "/remote-sessions/${encodePathSegment(sessionId)}/decision",
            method = "POST",
            token = token,
            payload = JSONObject().put("decision", if (accept) "accept" else "reject"),
        ))
        if (!accept) return null
        val ticket = response.optString("device_ticket")
        if (ticket.isBlank()) throw IOException("远程协助响应缺少设备通道凭据")
        return RemoteAssistAccess(remoteAssistSession(response), ticket)
    }

    fun publishRemoteAssistFrame(sessionId: String, ticket: String, jpeg: ByteArray) {
        val connection = URL("$baseUrl/remote-sessions/${encodePathSegment(sessionId)}/frame")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $ticket")
            connection.setRequestProperty("Content-Type", "image/jpeg")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(jpeg.size)
            connection.outputStream.use { it.write(jpeg) }
            val status = connection.responseCode
            if (status != 204) {
                val body = readLimited(
                    if (status in 200..299) connection.inputStream else connection.errorStream,
                    MAX_RESPONSE_BYTES,
                )
                val detail = runCatching {
                    JSONObject(body.toString(Charsets.UTF_8)).optString("detail")
                }.getOrNull()
                throw ApiException(detail?.takeIf(String::isNotBlank) ?: "远程画面发送失败（HTTP $status）", status)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun remoteAssistInputs(sessionId: String, ticket: String, afterSequence: Long): List<RemoteAssistInput> {
        val connection = URL(
            "$baseUrl/remote-sessions/${encodePathSegment(sessionId)}/input?after_seq=$afterSequence",
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer $ticket")
            val status = connection.responseCode
            val body = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_BYTES,
            )
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(body.toString(Charsets.UTF_8)).optString("detail")
                }.getOrNull()
                throw ApiException(detail?.takeIf(String::isNotBlank) ?: "远程操作读取失败（HTTP $status）", status)
            }
            val events = JSONObject(body.toString(Charsets.UTF_8)).optJSONArray("events")
                ?: throw IOException("服务器返回的远程操作列表无效")
            return (0 until events.length()).map { index ->
                val event = events.optJSONObject(index) ?: throw IOException("远程操作格式无效")
                RemoteAssistInput(
                    seq = event.optLong("seq"),
                    type = event.optString("type"),
                    x = event.optDouble("x").takeIf { event.has("x") && !event.isNull("x") }?.toFloat(),
                    y = event.optDouble("y").takeIf { event.has("y") && !event.isNull("y") }?.toFloat(),
                    endX = event.optDouble("end_x").takeIf { event.has("end_x") && !event.isNull("end_x") }?.toFloat(),
                    endY = event.optDouble("end_y").takeIf { event.has("end_y") && !event.isNull("end_y") }?.toFloat(),
                    durationMs = event.optLong("duration_ms", 300),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    fun endRemoteAssistSessionWithTicket(sessionId: String, ticket: String) {
        request(
            "/remote-sessions/${encodePathSegment(sessionId)}/device-end",
            method = "POST",
            token = ticket,
        )
    }

    fun endRemoteAssistSession(token: String, sessionId: String) {
        request(
            "/remote-sessions/${encodePathSegment(sessionId)}/end",
            method = "POST",
            token = token,
        )
    }

    fun approveRemoteRun(
        token: String,
        requestId: String,
    ): String? {
        val response = JSONObject(request(
            "/remote-runs/${encodePathSegment(requestId)}",
            method = "PATCH",
            token = token,
            payload = JSONObject()
                .put("decision", "approve"),
        ))
        return response.optString("run_id").takeIf(String::isNotBlank)
    }

    fun remoteRunStatus(token: String, requestId: String): RemoteRunStatus {
        val response = JSONObject(request(
            "/remote-runs/${encodePathSegment(requestId)}",
            token = token,
        ))
        return RemoteRunStatus(
            status = response.optString("status"),
            stopRequested = response.optBoolean("stop_requested"),
        )
    }

    fun listRuns(token: String): List<RunRecord> =
        requestArray("/runs", token).map { item ->
            RunRecord(
                id = item.optString("id"),
                taskId = item.optString("task_id"),
                taskName = item.optString("task_name"),
                version = item.optString("version"),
                deviceId = item.optString("device_id"),
                deviceName = item.optString("device_name"),
                remoteRunId = item.optString("remote_run_id").takeUnless { it.isBlank() || it == "null" },
                status = item.optString("status"),
                startedAt = item.optString("started_at"),
                finishedAt = item.optString("finished_at").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                resultSummary = item.optString("result_summary"),
            )
        }

    fun updateRun(token: String, runId: String, status: String, resultSummary: String) {
        request(
            "/runs/${encodePathSegment(runId)}",
            method = "POST",
            token = token,
            payload = JSONObject().put("status", status).put("result_summary", resultSummary.take(500)),
        )
    }

    private fun request(
        path: String,
        method: String = "GET",
        token: String? = null,
        payload: JSONObject? = null,
    ): String = exchange(path, method, token, payload).let { response ->
        if (response.status == 401 && token != null) onSessionExpired()
        if (response.status !in 200..299) throw response.error()
        if (response.body.isEmpty()) throw IOException("服务器返回了空响应")
        response.body.toString(Charsets.UTF_8)
    }

    private fun exchange(
        path: String,
        method: String = "GET",
        token: String? = null,
        payload: JSONObject? = null,
        absoluteUrl: Boolean = false,
    ): ApiResponse {
        val connection = URL(if (absoluteUrl) path else "$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            }

            val status = connection.responseCode
            val body = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_BYTES,
            )
            return ApiResponse(
                status = status,
                body = body,
                headers = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key!!.lowercase(java.util.Locale.ROOT) to connection.getHeaderField(it.key) },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun requestArray(path: String, token: String): List<JSONObject> =
        JSONArray(request(path, token = token)).objects()

    private fun remoteAssistSession(item: JSONObject) = RemoteAssistSession(
        id = item.optString("id"),
        deviceId = item.optString("device_id"),
        deviceName = item.optString("device_name"),
        deviceModel = item.optString("device_model"),
        requestedBy = item.optString("requested_by"),
        requestedByName = item.optString("requested_by_name"),
        status = item.optString("status"),
        expiresAt = item.optLong("expires_at"),
    )

    private data class ApiResponse(
        val status: Int,
        val body: ByteArray,
        val headers: Map<String, String?>,
    ) {
        fun error(): ApiException {
            val detail = runCatching { JSONObject(body.toString(Charsets.UTF_8)).optString("detail") }.getOrNull()
            return ApiException(detail?.takeIf(String::isNotBlank) ?: "服务器请求失败（HTTP $status）", status)
        }
    }

    private fun readLimited(stream: java.io.InputStream?, maxBytes: Int): ByteArray {
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

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_APP_APK_BYTES = 100 * 1024 * 1024
    }
}

internal class ApiException(message: String, val statusCode: Int) : IOException(message)

internal fun markDeviceOnlineAfterHeartbeat(device: BoundDevice?, heartbeatDeviceId: String): BoundDevice? =
    device?.takeIf { it.id == heartbeatDeviceId }?.copy(status = "online") ?: device

internal fun shouldShowDeviceModel(name: String, model: String): Boolean =
    model.isNotBlank() && !model.equals(name, ignoreCase = true)

internal fun verifyScriptSha256(source: String, expectedHash: String) {
    val actualHash = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    if (!MessageDigest.isEqual(
            actualHash.toByteArray(Charsets.US_ASCII),
            expectedHash.trim().lowercase().toByteArray(Charsets.US_ASCII),
        )
    ) {
        throw IOException("脚本完整性校验失败，已拒绝运行")
    }
}

internal fun verifyDownloadedScriptSha256(source: String, expectedHash: String, responseHash: String?) {
    if (responseHash != null && !responseHash.equals(expectedHash, ignoreCase = true)) {
        throw IOException("脚本完整性校验失败，已拒绝运行")
    }
    verifyScriptSha256(source, expectedHash)
}

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { index ->
        optJSONObject(index) ?: throw IOException("服务器返回了无效的数据项")
    }

private fun JSONArray.strings(): List<String> =
    (0 until length()).map { index -> optString(index) }

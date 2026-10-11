package com.flowpilot.android

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class SignedInUser(val id: String, val name: String, val email: String, val role: String)

internal data class LoginResult(val token: String, val expiresIn: Long, val user: SignedInUser)

internal data class BoundDevice(val id: String, val name: String, val model: String, val status: String)

internal data class RunAuthorization(
    val id: String,
    val taskId: String,
    val taskName: String,
    val version: String,
    val deviceId: String,
    val deviceName: String,
    val expiresAt: Long,
)

internal fun runAuthorizationVersionLabel(version: String): String =
    if (version == "*") "该版本及后续版本" else version

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
    val status: String,
    val startedAt: String,
    val resultSummary: String,
)

internal data class DownloadedScript(
    val filename: String,
    val source: String,
    val sha256: String,
    val isCached: Boolean = false,
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

internal class ApiClient(baseUrl: String) {
    private val baseUrl = normalizeApiBaseUrl(baseUrl)

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

    fun getCurrentUser(token: String): SignedInUser {
        val user = JSONObject(request("/me", token = token))
        return SignedInUser(
            id = user.optString("id"),
            name = user.optString("name"),
            email = user.optString("email"),
            role = user.optString("role", "user"),
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
                .put("app_version", "0.1.0"),
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
        return response.optJSONArray("tasks").orEmpty().objects().map { task ->
            AvailableTask(
                id = task.optString("id"),
                name = task.optString("name"),
                description = task.optString("description"),
                version = task.optString("active_version"),
                capabilities = task.optJSONArray("capabilities").orEmpty().strings(),
                source = task.optString("source", "user"),
            )
        }
    }

    fun heartbeat(token: String, deviceId: String) {
        request("/devices/${encodePathSegment(deviceId)}/heartbeat", method = "POST", token = token)
    }

    fun downloadScript(token: String, deviceId: String, task: AvailableTask): DownloadedScript {
        if (task.version.isBlank()) throw IOException("任务没有可运行版本")
        val response = exchange(
            "/devices/$deviceId/tasks/${encodePathSegment(task.id)}/versions/${encodePathSegment(task.version)}/content",
            token = token,
        )
        if (response.status !in 200..299) throw response.error()
        val expectedHash = response.headers["x-content-sha256"]
            ?: throw IOException("脚本响应缺少完整性校验信息")
        val source = response.body.toString(Charsets.UTF_8)
        verifyScriptSha256(source, expectedHash)
        return DownloadedScript(
            filename = response.headers["content-disposition"]?.substringAfter("filename=")?.trim('"')
                ?.takeIf(String::isNotBlank) ?: task.name,
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

    fun decideRemoteRun(
        token: String,
        requestId: String,
        approve: Boolean,
        preauthorize: Boolean,
    ): String? {
        val response = JSONObject(request(
            "/remote-runs/${encodePathSegment(requestId)}",
            method = "PATCH",
            token = token,
            payload = JSONObject()
                .put("decision", if (approve) "approve" else "reject")
                .put("preauthorize", approve && preauthorize),
        ))
        return response.optString("run_id").takeIf(String::isNotBlank)
    }

    fun listRunAuthorizations(token: String): List<RunAuthorization> =
        requestArray("/run-authorizations", token).map { item ->
            RunAuthorization(
                id = item.optString("id"),
                taskId = item.optString("task_id"),
                taskName = item.optString("task_name"),
                version = item.optString("version"),
                deviceId = item.optString("device_id"),
                deviceName = item.optString("device_name"),
                expiresAt = item.optLong("expires_at"),
            )
        }

    fun revokeRunAuthorization(token: String, authorizationId: String) {
        request(
            "/run-authorizations/${encodePathSegment(authorizationId)}",
            method = "DELETE",
            token = token,
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
                status = item.optString("status"),
                startedAt = item.optString("started_at"),
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
        if (response.status !in 200..299) throw response.error()
        if (response.body.isEmpty()) throw IOException("服务器返回了空响应")
        response.body.toString(Charsets.UTF_8)
    }

    private fun exchange(
        path: String,
        method: String = "GET",
        token: String? = null,
        payload: JSONObject? = null,
    ): ApiResponse {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
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
    }
}

internal class ApiException(message: String, val statusCode: Int) : IOException(message)

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

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { index ->
        optJSONObject(index) ?: throw IOException("服务器返回了无效的数据项")
    }

private fun JSONArray.strings(): List<String> =
    (0 until length()).map { index -> optString(index) }

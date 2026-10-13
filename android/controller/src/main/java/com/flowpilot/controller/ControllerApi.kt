package com.flowpilot.controller

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale
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

internal class ControllerApi(baseUrl: String) {
    private val baseUrl = normalizeControllerApiUrl(baseUrl)

    fun register(name: String, email: String, password: String) {
        request(
            "/auth/register",
            "POST",
            payload = JSONObject().put("name", name).put("email", email).put("password", password),
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
                )
            }

    fun dispatch(token: String, taskId: String, deviceId: String): String {
        val response = JSONObject(
            request(
                "/remote-runs",
                "POST",
                token,
                JSONObject().put("task_id", taskId).put("device_id", deviceId),
            ),
        )
        return response.optString("id").takeIf(String::isNotBlank)
            ?: throw IOException("服务器没有返回运行请求 ID")
    }

    fun rejectRemoteRun(token: String, requestId: String) {
        request(
            "/remote-runs/${encode(requestId)}",
            "PATCH",
            token,
            JSONObject().put("decision", "reject"),
        )
    }

    fun runStatus(token: String, requestId: String): ControllerRunStatus {
        val response = JSONObject(request("/remote-runs/${encode(requestId)}", token = token))
        return ControllerRunStatus(
            id = response.optString("id"),
            status = response.optString("status"),
            runStatus = response.optString("run_status").takeIf(String::isNotBlank),
            resultSummary = response.optString("result_summary"),
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
    ): String {
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

internal fun canDispatchControllerTask(deviceStatus: String, taskStatus: String, version: String): Boolean =
    deviceStatus == "online" && taskStatus == "published" && version.isNotBlank()

internal fun isControllerRunActive(status: String): Boolean = status == "pending" || status == "running"

internal fun controllerRunStatusLabel(status: String): String = when (status) {
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

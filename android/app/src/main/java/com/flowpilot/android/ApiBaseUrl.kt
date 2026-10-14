package com.flowpilot.android

import java.net.URI
import java.util.Locale

internal fun defaultApiBaseUrl(isEmulator: Boolean): String =
    if (isEmulator) "http://10.0.2.2:8001" else "http://192.168.0.105:8001"

internal fun normalizeApiBaseUrl(value: String, allowCleartext: Boolean = BuildConfig.DEBUG): String {
    val uri = try {
        URI(value.trim())
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("请输入有效的 API 地址，例如 http://192.168.0.105:8001", exception)
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme == "http" && !allowCleartext) {
        throw IllegalArgumentException("正式版必须使用 HTTPS API 地址")
    }
    val port = uri.port
    if (scheme !in setOf("http", "https") ||
        uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        uri.rawPath !in listOf(null, "", "/") ||
        port == 0 ||
        port > 65535
    ) {
        throw IllegalArgumentException("API 地址必须是 HTTP(S) 服务根地址，不能包含路径、账号或查询参数")
    }
    return URI(scheme, null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString()
}

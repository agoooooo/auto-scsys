package com.flowpilot.controller

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class ControllerSession(val apiUrl: String, val token: String, val email: String, val name: String)

@Suppress("DEPRECATION")
internal class ControllerSessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "flowpilot_controller_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): ControllerSession? {
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        return ControllerSession(
            apiUrl = preferences.getString(KEY_API_URL, null)
                ?: throw IllegalStateException("已保存会话缺少 API 地址"),
            token = token,
            email = preferences.getString(KEY_EMAIL, null).orEmpty(),
            name = preferences.getString(KEY_NAME, null).orEmpty(),
        )
    }

    fun save(session: ControllerSession) {
        preferences.edit()
            .putString(KEY_API_URL, session.apiUrl)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_NAME, session.name)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_API_URL = "api_url"
        const val KEY_TOKEN = "access_token"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
    }
}

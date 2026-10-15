package com.flowpilot.controller

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class ControllerSession(val apiUrl: String, val token: String, val email: String, val name: String)

internal data class ControllerRememberedLogin(val apiUrl: String, val email: String, val password: String)

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

    fun readRememberedLogin(): ControllerRememberedLogin? {
        if (!isRememberPasswordEnabled()) return null
        val apiUrl = preferences.getString(KEY_REMEMBERED_API_URL, null) ?: return null
        val email = preferences.getString(KEY_REMEMBERED_EMAIL, null) ?: return null
        val password = preferences.getString(KEY_REMEMBERED_PASSWORD, null) ?: return null
        return ControllerRememberedLogin(apiUrl, email, password)
    }

    fun isRememberPasswordEnabled(): Boolean =
        preferences.getBoolean(KEY_REMEMBER_PASSWORD, true)

    fun setRememberPasswordEnabled(enabled: Boolean) {
        val editor = preferences.edit().putBoolean(KEY_REMEMBER_PASSWORD, enabled)
        if (!enabled) {
            editor
                .remove(KEY_REMEMBERED_API_URL)
                .remove(KEY_REMEMBERED_EMAIL)
                .remove(KEY_REMEMBERED_PASSWORD)
        }
        editor.apply()
    }

    fun saveRememberedLogin(apiUrl: String, email: String, password: String) {
        preferences.edit()
            .putBoolean(KEY_REMEMBER_PASSWORD, true)
            .putString(KEY_REMEMBERED_API_URL, apiUrl)
            .putString(KEY_REMEMBERED_EMAIL, email)
            .putString(KEY_REMEMBERED_PASSWORD, password)
            .apply()
    }

    fun clearRememberedLogin() {
        preferences.edit()
            .putBoolean(KEY_REMEMBER_PASSWORD, false)
            .remove(KEY_REMEMBERED_API_URL)
            .remove(KEY_REMEMBERED_EMAIL)
            .remove(KEY_REMEMBERED_PASSWORD)
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_API_URL)
            .remove(KEY_TOKEN)
            .remove(KEY_EMAIL)
            .remove(KEY_NAME)
            .apply()
    }

    private companion object {
        const val KEY_API_URL = "api_url"
        const val KEY_TOKEN = "access_token"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
        const val KEY_REMEMBER_PASSWORD = "remember_password"
        const val KEY_REMEMBERED_API_URL = "remembered_api_url"
        const val KEY_REMEMBERED_EMAIL = "remembered_email"
        const val KEY_REMEMBERED_PASSWORD = "remembered_password"
    }
}

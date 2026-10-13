package com.flowpilot.android

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class StoredSession(
    val apiBaseUrl: String,
    val token: String,
    val tokenExpiresAt: Long,
    val user: SignedInUser,
    val deviceId: String?,
)

internal data class RememberedLogin(
    val apiBaseUrl: String,
    val email: String,
    val password: String,
)

@Suppress("DEPRECATION")
internal class SessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "flowpilot_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): StoredSession? {
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        val userId = preferences.getString(KEY_USER_ID, null) ?: return null
        return StoredSession(
            apiBaseUrl = preferences.getString(KEY_API_URL, null)
                ?: throw IllegalStateException("已保存会话缺少 API 地址"),
            token = token,
            tokenExpiresAt = preferences.getLong(KEY_TOKEN_EXPIRES_AT, 0L),
            user = SignedInUser(
                id = userId,
                name = preferences.getString(KEY_USER_NAME, null).orEmpty(),
                email = preferences.getString(KEY_USER_EMAIL, null).orEmpty(),
                role = preferences.getString(KEY_USER_ROLE, "user").orEmpty(),
            ),
            deviceId = preferences.getString(deviceKey(userId), null),
        )
    }

    fun save(session: StoredSession) {
        preferences.edit()
            .putString(KEY_API_URL, session.apiBaseUrl)
            .putString(KEY_TOKEN, session.token)
            .putLong(KEY_TOKEN_EXPIRES_AT, session.tokenExpiresAt)
            .putString(KEY_USER_ID, session.user.id)
            .putString(KEY_USER_NAME, session.user.name)
            .putString(KEY_USER_EMAIL, session.user.email)
            .putString(KEY_USER_ROLE, session.user.role)
            .apply()
        session.deviceId?.let { saveDeviceId(session.user.id, it) }
    }

    fun saveDeviceId(userId: String, deviceId: String) {
        preferences.edit().putString(deviceKey(userId), deviceId).apply()
    }

    fun clearDeviceId(userId: String) {
        preferences.edit().remove(deviceKey(userId)).apply()
    }

    fun updateToken(token: String, expiresAt: Long) {
        preferences.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TOKEN_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun readRememberedLogin(): RememberedLogin? {
        val email = preferences.getString(KEY_LOGIN_EMAIL, null) ?: return null
        val password = preferences.getString(KEY_LOGIN_PASSWORD, null) ?: return null
        val apiBaseUrl = preferences.getString(KEY_LOGIN_API_URL, null) ?: return null
        return RememberedLogin(apiBaseUrl, email, password)
    }

    fun saveRememberedLogin(login: RememberedLogin) {
        preferences.edit()
            .putString(KEY_LOGIN_API_URL, login.apiBaseUrl)
            .putString(KEY_LOGIN_EMAIL, login.email)
            .putString(KEY_LOGIN_PASSWORD, login.password)
            .apply()
    }

    fun clearRememberedLogin() {
        preferences.edit()
            .remove(KEY_LOGIN_API_URL)
            .remove(KEY_LOGIN_EMAIL)
            .remove(KEY_LOGIN_PASSWORD)
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_ROLE)
            .apply()
    }

    private fun deviceKey(userId: String) = "device_id_$userId"
    private companion object {
        const val KEY_API_URL = "api_url"
        const val KEY_TOKEN = "access_token"
        const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_ROLE = "user_role"
        const val KEY_LOGIN_API_URL = "login_api_url"
        const val KEY_LOGIN_EMAIL = "login_email"
        const val KEY_LOGIN_PASSWORD = "login_password"
    }
}

package com.flowpilot.controller

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal fun isNewerControllerAppRelease(currentVersionCode: Long, latestVersionCode: Long): Boolean =
    latestVersionCode > currentVersionCode

internal fun isControllerAppUpdatePromptDeferred(
    lastPromptVersionCode: Long,
    lastPromptAtMillis: Long,
    latestVersionCode: Long,
    nowMillis: Long,
): Boolean =
    lastPromptVersionCode == latestVersionCode &&
        nowMillis - lastPromptAtMillis < CONTROLLER_APP_UPDATE_PROMPT_COOLDOWN_MILLIS

internal fun validateControllerAppUpdateApk(
    apk: File,
    expectedPackageName: String,
    expectedVersionCode: Long,
    expectedVersionName: String,
    packageManager: android.content.pm.PackageManager,
) {
    val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        ?: throw IOException("下载的文件不是有效 APK")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        archive.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        archive.versionCode.toLong()
    }
    if (archive.packageName != expectedPackageName ||
        versionCode != expectedVersionCode ||
        archive.versionName != expectedVersionName
    ) {
        throw IOException("APK 包名或版本与发布信息不符，已拒绝安装")
    }
}

internal fun launchControllerAppUpdateInstall(
    activity: Activity,
    apk: File,
    versionCode: Long,
) {
    val preferences = activity.getSharedPreferences(CONTROLLER_APP_UPDATE_PREFERENCES, Activity.MODE_PRIVATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
        if (preferences.getLong(CONTROLLER_KEY_SETTINGS_VERSION, -1L) == versionCode) return
        preferences.edit().putLong(CONTROLLER_KEY_SETTINGS_VERSION, versionCode).apply()
        Toast.makeText(activity, "请允许 FlowPilot 控制端安装此来源的应用更新", Toast.LENGTH_LONG).show()
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
        )
        return
    }

    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
    activity.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, CONTROLLER_APK_CONTENT_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    preferences.edit()
        .remove(CONTROLLER_KEY_SETTINGS_VERSION)
        .putLong(CONTROLLER_KEY_PROMPT_VERSION, versionCode)
        .putLong(CONTROLLER_KEY_PROMPT_TIME, System.currentTimeMillis())
        .apply()
}

internal fun isCachedControllerAppUpdateValid(apk: File, expectedSha256: String): Boolean {
    if (!apk.isFile || apk.length() == 0L) return false
    val digest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    return MessageDigest.isEqual(
        actual.toByteArray(Charsets.US_ASCII),
        expectedSha256.lowercase().toByteArray(Charsets.US_ASCII),
    )
}

internal const val CONTROLLER_APP_UPDATE_PROMPT_COOLDOWN_MILLIS = 6 * 60 * 60 * 1000L
internal const val CONTROLLER_APP_UPDATE_PREFERENCES = "flowpilot_app_updates"
internal const val CONTROLLER_APK_CONTENT_TYPE = "application/vnd.android.package-archive"
internal const val CONTROLLER_KEY_PROMPT_VERSION = "controller_prompt_version_code"
internal const val CONTROLLER_KEY_PROMPT_TIME = "controller_prompt_time_millis"
internal const val CONTROLLER_KEY_SETTINGS_VERSION = "controller_settings_version_code"

package com.flowpilot.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

internal data class ScriptCacheScope(
    val apiBaseUrl: String,
    val userId: String,
    val deviceId: String,
)

internal data class CachedTaskScript(
    val task: AvailableTask,
    val script: DownloadedScript,
)

internal class ScriptCache(private val directory: File) {
    fun load(
        scope: ScriptCacheScope,
        task: AvailableTask,
    ): DownloadedScript? {
        val file = scriptFile(scope, task.id, task.version)
        if (!file.exists()) return null
        val entry = readEntry(file)
        requireMatches(entry, scope, task.id, task.version)
        return entry.script.copy(isCached = true)
    }

    fun latest(scope: ScriptCacheScope, task: AvailableTask): CachedTaskScript? {
        val prefix = "${scopeKey(scope, task.id)}-"
        val candidates = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.extension == CACHE_EXTENSION }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        for (file in candidates) {
            val entry = readEntry(file)
            if (entry.scope == scope && entry.task.id == task.id) {
                return CachedTaskScript(
                    task = entry.task,
                    script = entry.script.copy(isCached = true),
                )
            }
        }
        return null
    }

    fun save(scope: ScriptCacheScope, task: AvailableTask, script: DownloadedScript) {
        val sourceBytes = script.source.toByteArray(Charsets.UTF_8)
        if (sourceBytes.size > MAX_SCRIPT_BYTES) throw IOException("脚本超过本地缓存大小限制")
        verifyScriptSha256(script.source, script.sha256)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建脚本缓存目录")
        }

        val target = scriptFile(scope, task.id, task.version)
        val temporary = File(directory, "${target.name}.$TEMP_EXTENSION")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(fileOutput).use { output ->
                    output.writeInt(FORMAT_VERSION)
                    output.writeUTF(scope.apiBaseUrl)
                    output.writeUTF(scope.userId)
                    output.writeUTF(scope.deviceId)
                    output.writeUTF(task.id)
                    output.writeUTF(task.name)
                    output.writeUTF(task.description)
                    output.writeUTF(task.version)
                    output.writeUTF(task.source)
                    output.writeInt(task.capabilities.size)
                    task.capabilities.forEach(output::writeUTF)
                    output.writeUTF(script.filename)
                    output.writeUTF(script.sha256.lowercase())
                    output.writeInt(sourceBytes.size)
                    output.write(sourceBytes)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        retainRecentVersions(scope, task.id)
    }

    private fun readEntry(file: File): CacheEntry {
        try {
            DataInputStream(FileInputStream(file)).use { input ->
                if (input.readInt() != FORMAT_VERSION) throw IOException("不支持的脚本缓存格式")
                val scope = ScriptCacheScope(input.readUTF(), input.readUTF(), input.readUTF())
                val taskId = input.readUTF()
                val name = input.readUTF()
                val description = input.readUTF()
                val version = input.readUTF()
                val taskSource = input.readUTF()
                val capabilityCount = input.readInt()
                if (capabilityCount !in 0..MAX_CAPABILITIES) throw IOException("脚本缓存能力列表无效")
                val capabilities = List(capabilityCount) { input.readUTF() }
                val filename = input.readUTF()
                val sha256 = input.readUTF()
                val byteCount = input.readInt()
                if (byteCount !in 1..MAX_SCRIPT_BYTES) throw IOException("脚本缓存长度无效")
                val sourceBytes = ByteArray(byteCount)
                input.readFully(sourceBytes)
                if (input.read() != -1) throw IOException("脚本缓存包含无效附加数据")
                val source = sourceBytes.toString(Charsets.UTF_8)
                if (!sourceBytes.contentEquals(source.toByteArray(Charsets.UTF_8))) {
                    throw IOException("脚本缓存文本编码无效")
                }
                verifyScriptSha256(source, sha256)
                return CacheEntry(
                    scope,
                    AvailableTask(taskId, name, description, version, capabilities, taskSource),
                    DownloadedScript(filename, source, sha256, isCached = true),
                )
            }
        } catch (exception: EOFException) {
            throw IOException("脚本缓存数据不完整", exception)
        }
    }

    private fun requireMatches(
        entry: CacheEntry,
        scope: ScriptCacheScope,
        taskId: String,
        version: String,
    ) {
        if (entry.scope != scope || entry.task.id != taskId || entry.task.version != version) {
            throw IOException("脚本缓存与请求任务不匹配")
        }
    }

    private fun retainRecentVersions(scope: ScriptCacheScope, taskId: String) {
        val prefix = "${scopeKey(scope, taskId)}-"
        val versions = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.extension == CACHE_EXTENSION }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        versions.drop(MAX_VERSIONS_PER_TASK).forEach { file ->
            if (!file.delete()) throw IOException("无法清理过期脚本缓存")
        }
    }

    private fun scriptFile(scope: ScriptCacheScope, taskId: String, version: String): File =
        File(directory, "${scopeKey(scope, taskId)}-${digest(version)}.$CACHE_EXTENSION")

    private fun scopeKey(scope: ScriptCacheScope, taskId: String): String =
        digest("${scope.apiBaseUrl}\u0000${scope.userId}\u0000${scope.deviceId}\u0000$taskId")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class CacheEntry(
        val scope: ScriptCacheScope,
        val task: AvailableTask,
        val script: DownloadedScript,
    )

    private companion object {
        const val FORMAT_VERSION = 2
        const val CACHE_EXTENSION = "cache"
        const val TEMP_EXTENSION = "tmp"
        const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
        const val MAX_VERSIONS_PER_TASK = 2
        const val MAX_CAPABILITIES = 128
    }
}

package com.flowpilot.android

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
internal class DouyinAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private val mainHandler by lazy { Handler(mainLooper) }

    fun openDouyin() {
        val alreadyInDouyin = onServiceThread {
            val root = rootInActiveWindow ?: return@onServiceThread false
            try {
                DouyinPackagePolicy.isSupportedPackage(root.packageName?.toString())
            } finally {
                root.recycle()
            }
        }
        if (alreadyInDouyin) {
            return
        }
        val packageName = DouyinPackagePolicy.preferredInstalledPackage {
            packageManager.getLaunchIntentForPackage(it) != null
        } ?: throw IllegalStateException("未检测到已安装的抖音或抖音极速版，请先安装后再运行任务")
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("无法启动已安装的抖音应用")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    fun waitForDouyin(timeoutSeconds: Int): Boolean =
        awaitDouyinForeground(
            isForeground = ::isDouyinForeground,
            timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong()),
            pollIntervalMillis = FOREGROUND_POLL_INTERVAL_MILLIS,
        )

    private fun isDouyinForeground(): Boolean = onServiceThread {
        val root = rootInActiveWindow ?: return@onServiceThread false
        try {
            DouyinPackagePolicy.isSupportedPackage(root.packageName?.toString())
        } finally {
            root.recycle()
        }
    }

    fun currentScreenText(): String = onServiceThread {
        val root = checkedRoot()
        val found = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                visited += 1
                try {
                    listOfNotNull(node.text, node.contentDescription)
                        .map(CharSequence::toString)
                        .filter(String::isNotBlank)
                        .forEach(found::add)
                    if (depth < MAX_DEPTH) {
                        for (index in 0 until node.childCount) {
                            node.getChild(index)?.let { queue.add(it to depth + 1) }
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().first.recycle()
        }
        found.distinct().joinToString("\n")
    }

    fun clickText(target: String): Boolean = onServiceThread {
        val query = target.trim()
        require(query.isNotEmpty()) { "点击目标不能为空" }
        val root = checkedRoot()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited += 1
                try {
                    val textMatches = sequenceOf(node.text, node.contentDescription)
                        .filterNotNull()
                        .any { it.toString().contains(query, ignoreCase = true) }
                    if (textMatches && clickNodeOrClickableAncestor(node)) {
                        return@onServiceThread true
                    }
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let(queue::add)
                    }
                } finally {
                    node.recycle()
                }
            }
            false
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().recycle()
        }
    }

    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        var ownsCandidate = false
        try {
            repeat(MAX_CLICKABLE_ANCESTORS) {
                val current = candidate ?: return false
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val parent = current.parent
                if (ownsCandidate) current.recycle()
                candidate = parent
                ownsCandidate = parent != null
            }
            return false
        } finally {
            if (ownsCandidate) candidate?.recycle()
        }
    }

    private fun checkedRoot(): AccessibilityNodeInfo {
        val root = rootInActiveWindow ?: throw IllegalStateException("无法读取当前界面，请确认抖音已打开")
        if (!DouyinPackagePolicy.isSupportedPackage(root.packageName?.toString())) {
            root.recycle()
            throw IllegalStateException("出于隐私保护，自动化仅能读取抖音或抖音极速版界面")
        }
        return root
    }

    private fun <T> onServiceThread(action: () -> T): T {
        if (Thread.currentThread() === mainLooper.thread) return action()
        val task = FutureTask(Callable(action))
        mainHandler.post(task)
        return awaitAccessibilityCall(task, SERVICE_CALL_TIMEOUT_SECONDS) {
            mainHandler.removeCallbacks(task)
        }
    }

    companion object {
        private const val MAX_NODES = 800
        private const val MAX_DEPTH = 24
        private const val MAX_CLICKABLE_ANCESTORS = 8
        private const val SERVICE_CALL_TIMEOUT_SECONDS = 8L
        private const val FOREGROUND_POLL_INTERVAL_MILLIS = 250L
        @Volatile
        private var instance: DouyinAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, DouyinAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            return splitter.any { it.equals(expected, ignoreCase = true) }
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun requireInstance(): DouyinAccessibilityService =
            instance ?: throw IllegalStateException("请先在系统设置中启用 FlowPilot 自动化操作服务")
    }
}

package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RunResultSummaryTest {
    @Test
    fun reportsSafeKnownFailureCauses() {
        assertEquals(
            "设备未安装抖音或抖音极速版，或无法启动目标应用",
            RunResultSummary.forServer(
                "failure",
                "未检测到已安装的抖音或抖音极速版，请先安装后再运行任务",
            ),
        )
        assertEquals(
            "缺少有效的屏幕捕获授权，请在设备上重新授权",
            RunResultSummary.forServer("failure", "此远程任务未获屏幕捕获授权，不能调用 OCR"),
        )
        assertEquals(
            "FlowPilot 无障碍服务未启用，请在设备设置中开启",
            RunResultSummary.forServer("failure", "此任务需要先启用 FlowPilot 无障碍服务"),
        )
    }

    @Test
    fun neverUploadsArbitraryScriptFailureText() {
        assertEquals(
            "任务执行失败，请查看设备端运行记录",
            RunResultSummary.forServer("failure", "OCR 内容或任意 Python 异常"),
        )
    }

    @Test
    fun reportsNonFailureStatusesWithoutLocalDetails() {
        assertEquals("任务完成", RunResultSummary.forServer("success", "屏幕中的敏感文本"))
        assertEquals("任务已停止", RunResultSummary.forServer("stopped", "内部异常细节"))
    }
}

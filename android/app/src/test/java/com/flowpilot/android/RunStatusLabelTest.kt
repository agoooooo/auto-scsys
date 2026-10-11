package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RunStatusLabelTest {
    @Test
    fun mapsRunStatesToChineseLabels() {
        assertEquals("等待系统授权", runStatusLabel("awaiting_permission"))
        assertEquals("执行中", runStatusLabel("running"))
        assertEquals("已完成", runStatusLabel("success"))
        assertEquals("已停止", runStatusLabel("stopped"))
        assertEquals("失败", runStatusLabel("failure"))
    }

    @Test
    fun usesNeutralLabelForUnknownStatus() {
        assertEquals("未知状态", runStatusLabel("unexpected"))
    }
}

package com.flowpilot.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAssistGestureTest {
    @Test
    fun tapCoordinatesAreNormalizedAndClamped() {
        assertEquals(
            ControllerRemoteAssistInput("tap", 0.5f, 1f),
            controllerRemoteAssistGesture(50f, 120f, 50f, 120f, 100f, 100f),
        )
    }

    @Test
    fun dragCoordinatesAreNormalized() {
        assertEquals(
            ControllerRemoteAssistInput("swipe", 0.1f, 0.2f, 0.8f, 0.9f),
            controllerRemoteAssistGesture(10f, 20f, 80f, 90f, 100f, 100f),
        )
    }

    @Test
    fun letterboxedContentCoordinatesIgnoreMargins() {
        assertEquals(
            ControllerRemoteAssistInput("tap", 0.5f, 0.5f),
            controllerRemoteAssistGesture(
                50f,
                100f,
                50f,
                100f,
                width = 100f,
                height = 200f,
                contentWidth = 100f,
                contentHeight = 100f,
                contentOffsetY = 50f,
            ),
        )
    }
}

package com.flowpilot.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ScreenCaptureSession(
    context: Context,
    resultCode: Int,
    resultData: Intent,
    private val onProjectionStopped: () -> Unit = {},
) : Closeable {
    private val thread = HandlerThread("flowpilot-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val request = AtomicReference<CaptureRequest?>()
    private val projectionStopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val projection: MediaProjection
    private val imageReader: ImageReader
    private val virtualDisplay: VirtualDisplay

    init {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Android 屏幕捕获授权无效")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener(::onImageAvailable, handler)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projectionStopped.set(true)
                request.getAndSet(null)?.let { stopped ->
                    stopped.error.set(IllegalStateException("Android 屏幕捕获授权已停止"))
                    stopped.latch.countDown()
                }
                onProjectionStopped()
            }
        }, handler)
        virtualDisplay = projection.createVirtualDisplay(
            "FlowPilot task screen capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )
    }

    fun recognizeText(): String {
        val image = captureFrame()
        try {
            return Tasks.await(
                recognizer.process(InputImage.fromBitmap(image, 0)),
                OCR_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            ).text
        } finally {
            image.recycle()
        }
    }

    fun captureJpeg(maxDimension: Int = 800, quality: Int = 55): ByteArray {
        require(maxDimension >= 320)
        require(quality in 20..90)
        val original = captureFrame()
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(original.width, original.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { original.recycle() }
        } else {
            original
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IllegalStateException("无法编码远程屏幕画面")
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureFrame(): Bitmap {
        check(!projectionStopped.get()) { "Android 屏幕捕获授权已停止" }
        val capture = CaptureRequest()
        check(request.compareAndSet(null, capture)) { "屏幕捕获正在进行中" }
        try {
            if (!capture.latch.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IllegalStateException("读取屏幕超时，请确认系统屏幕共享仍在运行")
            }
            capture.error.get()?.let { throw it }
            return capture.bitmap.get()
                ?: throw IllegalStateException("无法取得屏幕图像")
        } finally {
            request.compareAndSet(capture, null)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val capture = request.get()
        val image = reader.acquireLatestImage() ?: return
        if (capture == null) {
            image.close()
            return
        }
        try {
            capture.bitmap.set(image.toBitmap())
        } catch (exception: Exception) {
            capture.error.set(exception)
        } finally {
            image.close()
            capture.latch.countDown()
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull() ?: throw IllegalStateException("屏幕图像没有可读像素")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        check(paddedWidth >= width) { "屏幕图像行宽异常" }
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val buffer: ByteBuffer = plane.buffer
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        request.getAndSet(null)?.let { pending ->
            pending.error.set(IllegalStateException("屏幕捕获会话已结束"))
            pending.latch.countDown()
        }
        virtualDisplay.release()
        imageReader.close()
        projection.stop()
        recognizer.close()
        thread.quitSafely()
    }

    private class CaptureRequest {
        val latch = CountDownLatch(1)
        val bitmap = AtomicReference<Bitmap?>()
        val error = AtomicReference<Exception?>()
    }

    private companion object {
        const val FRAME_TIMEOUT_SECONDS = 5L
        const val OCR_TIMEOUT_SECONDS = 15L
    }
}

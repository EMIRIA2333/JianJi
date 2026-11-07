package com.jianji.app.util

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

/**
 * 截图 + 本地 OCR（中文，模型随 APK 打包，完全离线）。
 *
 * 为什么需要：
 * 微信的「账单详情 / 零钱明细 / 转账详情」都是 H5 页面（`MMWebViewUI`），
 * 而且**对无障碍完全屏蔽** —— adb `uiautomator dump` 只能拿到 1 个空节点，
 * 我这边的无障碍扫描同样 0 个字。无障碍看不到，就只能看**屏幕**。
 * （支付宝是原生页面，无障碍能取到 300~1200 字，走原路径即可，不用 OCR。）
 *
 * 省电设计：
 * - 只由 AutoCaptureService 在「前台是支付/账单类应用 + a11y 文本 < 4 字」时调用；
 * - 全局 15 秒限流 + busy 互斥，同一时刻最多一次识别；
 * - OCR 在单线程池里跑（低优先级），不占用主线程。
 */
object OcrCapture {

    private const val MIN_INTERVAL_MS = 1_500L

    /** OCR 输入的最大宽度：分辨率越高越慢，900 宽对中文账单已经够用 */
    private const val MAX_WIDTH = 900

    @Volatile
    private var lastRunAt = 0L

    @Volatile
    private var busy = false

    /** OCR 引擎只创建一次：每次 new 都要重新加载模型，是"识别慢"的主要原因 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jianji-ocr").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /**
     * 预热：服务启动时用一张 8x8 的小图跑一次识别，把模型加载进内存。
     * 不预热的话「第一次识别」要 2~5 秒（模型冷启动），用户感觉就是"很慢"。
     */
    fun warmUp() {
        executor.execute {
            runCatching {
                val tiny = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                recognizer.process(InputImage.fromBitmap(tiny, 0))
                    .addOnCompleteListener { AppLog.i("扫描", "OCR 模型预热完成") }
            }.onFailure { AppLog.e("扫描", it) }
        }
    }

    /** 截图 → OCR → 回调纯文本（回调在 OCR 线程，调用方自行切线程） */
    @SuppressLint("NewApi")
    fun recognize(service: AccessibilityService, onText: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AppLog.i("扫描", "截图 OCR 需要 Android 11+，当前 ${Build.VERSION.SDK_INT} 跳过")
            return
        }
        val now = System.currentTimeMillis()
        if (busy || now - lastRunAt < MIN_INTERVAL_MS) return
        busy = true
        lastRunAt = now
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        executor.execute {
                            runCatching { doOcr(result, onText) }
                                .onFailure { AppLog.e("扫描", it) }
                            busy = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        busy = false
                        AppLog.w("扫描", "截图失败 code=$errorCode")
                    }
                }
            )
        }.onFailure {
            busy = false
            AppLog.e("扫描", it)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("NewApi")
    private fun doOcr(
        result: AccessibilityService.ScreenshotResult,
        onText: (String) -> Unit
    ) {
        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        if (hardware == null) {
            AppLog.w("扫描", "截图硬件位图拿不到")
            return
        }
        // ML Kit 需要软件位图（hardware buffer 不能直接识别）
        val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
        runCatching { hardware.recycle() }
        if (bitmap == null) {
            AppLog.w("扫描", "截图位图转换失败")
            return
        }
        val scaled = if (bitmap.width > MAX_WIDTH) {
            val ratio = MAX_WIDTH.toFloat() / bitmap.width
            Bitmap.createScaledBitmap(
                bitmap, MAX_WIDTH, (bitmap.height * ratio).toInt().coerceAtLeast(1), true
            )
        } else bitmap
        recognizer
            .process(InputImage.fromBitmap(scaled, 0))
            .addOnSuccessListener { text ->
                AppLog.i("扫描", "OCR 完成：${text.text.length} 字")
                onText(text.text)
            }
            .addOnFailureListener { e ->
                AppLog.e("扫描", e)
            }
    }
}

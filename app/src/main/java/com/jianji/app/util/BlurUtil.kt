package com.jianji.app.util

import android.graphics.Bitmap

/**
 * 纯软件模糊（不碰 GPU 渲染）。
 *
 * 关键设计：**先缩到很小再模糊**，然后交给 BitmapDrawable 平滑放大 ——
 * - 内存占用从 ~20MB 降到 ~1MB（之前大图多轮滤波在部分机型上会 OOM，
 *   被 runCatching 吞掉后就表现为"毛玻璃没效果"）；
 * - 速度快 10 倍以上，主线程调用也无感；
 * - 放大用双线性过滤，视觉上就是柔和的磨砂底。
 */
object BlurUtil {

    /** 模糊工作分辨率上限（越小越省内存，放大后依然是柔和的模糊） */
    const val WORK_WIDTH = 220

    /**
     * @param src 原图
     * @return **小尺寸**的模糊位图（调用方负责平滑放大显示）
     */
    fun blurSmall(src: Bitmap, radius: Int = 8): Bitmap {
        if (src.width <= 0 || src.height <= 0) return src
        return runCatching {
            val w = WORK_WIDTH.coerceAtMost(src.width)
            val h = (src.height.toFloat() * w / src.width).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            boxBlur(small, radius)
        }.getOrDefault(src)
    }

    /** 分离式盒式模糊（横竖各一遍），O(n) 与半径无关 */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        blurPass(pixels, tmp, w, h, radius, horizontal = true)
        blurPass(tmp, pixels, w, h, radius, horizontal = false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (i in -radius..radius) {
                val p = src[index(i.coerceIn(0, len - 1), line, w, horizontal)]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (i in 0 until len) {
                dst[index(i, line, w, horizontal)] = pack(
                    (sumA / window).toInt(), (sumR / window).toInt(),
                    (sumG / window).toInt(), (sumB / window).toInt()
                )
                val outIdx = index((i - radius).coerceIn(0, len - 1), line, w, horizontal)
                val inIdx = index((i + radius + 1).coerceIn(0, len - 1), line, w, horizontal)
                val po = src[outIdx]
                val pi = src[inIdx]
                sumA += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                sumR += ((pi ushr 16) and 0xFF) - ((po ushr 16) and 0xFF)
                sumG += ((pi ushr 8) and 0xFF) - ((po ushr 8) and 0xFF)
                sumB += (pi and 0xFF) - (po and 0xFF)
            }
        }
    }

    private fun index(i: Int, line: Int, w: Int, horizontal: Boolean): Int =
        if (horizontal) line * w + i else i * w + line

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
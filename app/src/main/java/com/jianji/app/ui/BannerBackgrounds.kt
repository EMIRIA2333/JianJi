package com.jianji.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import com.jianji.app.R
import com.jianji.app.util.AppLog
import com.jianji.app.util.BlurUtil
import com.jianji.app.util.Prefs
import java.io.File

/**
 * 首页背景图：5 张内置插画 + 用户自选相册图片（压缩后存到应用私有目录）。
 */
object BannerBackgrounds {

    data class Entry(val id: String, val label: String, val resId: Int)

    val ENTRIES = listOf(
        Entry("bg_balloon", "热气球日落", R.drawable.bg_balloon),
        Entry("bg_sunrise", "雪山日出", R.drawable.bg_sunrise),
        Entry("bg_coast", "海岸椰林", R.drawable.bg_coast),
        Entry("bg_city", "城市夜景", R.drawable.bg_city),
        Entry("bg_aurora", "极光星空", R.drawable.bg_aurora)
    )

    const val CUSTOM_ID = "custom"
    private const val CUSTOM_FILE = "banner_custom.jpg"
    private const val MAX_WIDTH = 1440

    /** 模糊后的位图缓存（键含背景 id 与日/夜，避免日间底图被用在夜间） */
    private val blurredCache = HashMap<String, Bitmap>()

    /** 毛玻璃开关变化时调用：清缓存并通知正在显示的界面重新应用 */
    var onChanged: (() -> Unit)? = null

    fun invalidate() {
        blurredCache.clear()
        runCatching { onChanged?.invoke() }
    }

    fun customFile(ctx: Context): File = File(ctx.filesDir, CUSTOM_FILE)

    fun labelOf(ctx: Context): String {
        val id = Prefs.backgroundId(ctx)
        if (id == CUSTOM_ID) return "自定义图片"
        return ENTRIES.firstOrNull { it.id == id }?.label ?: ENTRIES.first().label
    }

    fun apply(ctx: Context, imageView: ImageView) {
        val id = Prefs.backgroundId(ctx)
        // 注意：**背景图保持清晰**，毛玻璃是全局主题层的事（见 glassWindowDrawable）
        if (id == CUSTOM_ID) {
            val f = customFile(ctx)
            if (f.exists()) {
                val bmp = decodeSampled(f, MAX_WIDTH)
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    return
                }
            }
        }
        val entry = ENTRIES.firstOrNull { it.id == id } ?: ENTRIES.first()
        imageView.setImageResource(entry.resId)
    }

    /**
     * 全局毛玻璃用：当前背景图的**小尺寸模糊版**，作为内容区的底（不是窗口底！）。
     *
     * - 只算一次并按背景 id 缓存；
     * - 小图（约 220px 宽）+ 双线性放大 = 柔和磨砂，内存 ~1MB，**不会 OOM**；
     * - 不用 GPU 实时模糊（MIUI 上会黑屏）。
     */
    fun glassBackdrop(ctx: Context): android.graphics.drawable.Drawable? {
        val id = Prefs.backgroundId(ctx)
        val night = isNight(ctx)
        val cacheKey = "$id|${if (night) "n" else "d"}"
        val bmp = blurredCache[cacheKey] ?: run {
            val source = loadBitmap(ctx, id)
            if (source == null) {
                AppLog.w("毛玻璃", "背景图解码失败，无法生成模糊底")
                return null
            }
            var small = BlurUtil.blurSmall(source, radius = 8)
            // 深色/纯黑主题：把底图压暗，否则亮底 + 白字看不清（"黑玻璃"也看不出来）
            if (night) small = darken(small, 0.45f)
            blurredCache[cacheKey] = small
            AppLog.i(
                "毛玻璃",
                "模糊底图已生成 ${small.width}x${small.height}（原图 ${source.width}x${source.height}）夜间=$night"
            )
            small
        }
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp).apply {
            setGravity(android.view.Gravity.FILL)
            // 关键：放大用双线性过滤，小图也能得到柔和模糊
            paint.isFilterBitmap = true
        }
    }

    /** 压暗位图（深色主题用），纯 CPU、只在生成底图时做一次 */
    private fun darken(src: Bitmap, factor: Float): Bitmap = runCatching {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = ((1f - factor) * 255).toInt().coerceIn(0, 255)
        }
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        out
    }.getOrDefault(src)

    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    /** 读出当前背景对应的位图（内置图也解码成位图，便于模糊） */
    private fun loadBitmap(ctx: Context, id: String): Bitmap? = runCatching {
        if (id == CUSTOM_ID) {
            val f = customFile(ctx)
            if (f.exists()) return decodeSampled(f, MAX_WIDTH)
            return null
        }
        val entry = ENTRIES.firstOrNull { it.id == id } ?: ENTRIES.first()
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeResource(ctx.resources, entry.resId, opts)
    }.getOrNull()

    /** 保存相册图片为背景（压缩到 1440px 宽，JPEG 88）；失败返回 false */
    fun saveCustom(ctx: Context, uri: Uri): Boolean {
        return runCatching {
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            val decoded = input.use { BitmapFactory.decodeStream(it) } ?: return false
            val scaled = if (decoded.width > MAX_WIDTH) {
                val h = (decoded.height.toFloat() * MAX_WIDTH / decoded.width).toInt()
                Bitmap.createScaledBitmap(decoded, MAX_WIDTH, h, true)
            } else decoded
            customFile(ctx).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            Prefs.setBackgroundId(ctx, CUSTOM_ID)
            true
        }.getOrDefault(false)
    }

    private fun decodeSampled(file: File, reqWidth: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqWidth * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()
}

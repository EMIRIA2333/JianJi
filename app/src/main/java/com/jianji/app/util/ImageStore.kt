package com.jianji.app.util

import android.content.Context
import java.io.File

/**
 * 账单图片存储（「账单图片」开关开启时，自动把账单页面截图存到 `filesDir/shots/`）。
 *
 * 设计要点：
 * - 只存本机、不联网；压缩到最长边 1080、JPEG 75，单张约 60~120KB；
 * - 彻底删除账单时一并删除对应图片，避免越用越占空间；
 * - 提供孤儿文件清理（数据库里没有引用的图片）。
 */
object ImageStore {

    private const val DIR = "shots"

    fun absolute(ctx: Context, relPath: String): File? {
        if (relPath.isBlank()) return null
        val f = File(ctx.filesDir, relPath)
        return if (f.exists()) f else null
    }

    fun exists(ctx: Context, relPath: String): Boolean = absolute(ctx, relPath) != null

    /** 删除若干张图片，返回实际删除数量 */
    fun delete(ctx: Context, relPaths: List<String>): Int {
        var n = 0
        relPaths.forEach { rel ->
            runCatching {
                if (rel.isNotBlank() && File(ctx.filesDir, rel).delete()) n++
            }
        }
        return n
    }

    /** 清理没有被任何账单引用的图片（用户手动改过数据库/异常中断时兜底） */
    fun cleanupOrphans(ctx: Context, usedPaths: List<String>): Int {
        val used = usedPaths.toHashSet()
        var n = 0
        runCatching {
            File(ctx.filesDir, DIR).listFiles()?.forEach { f ->
                val rel = "$DIR/${f.name}"
                if (!used.contains(rel) && f.delete()) n++
            }
        }
        return n
    }

    /** 图片占用空间（字节），用于设置页显示 */
    fun totalBytes(ctx: Context): Long = runCatching {
        File(ctx.filesDir, DIR).listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes > 0 -> "${bytes / 1024} KB"
        else -> "0 KB"
    }
}

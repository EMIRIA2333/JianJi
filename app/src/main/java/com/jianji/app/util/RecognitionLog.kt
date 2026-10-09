package com.jianji.app.util

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 识别记录（内存环形缓冲，只保留最近几十条）。
 *
 * 用途：自动记账是"静默"工作的，用户看不到它有没有扫到、为什么没记。
 * 这里把关键判断点记下来（识别到什么 / 为什么跳过），在
 * 「自动记账设置 → 识别记录」里可以查看与复制，排查问题非常直接。
 *
 * 特点：纯内存、不写磁盘、不上传；重启即清空。
 */
object RecognitionLog {

    private const val MAX = 40
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        entries.addFirst("${fmt.format(Date())}  $message")
        while (entries.size > MAX) entries.removeLast()
    }

    @Synchronized
    fun dump(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()
}

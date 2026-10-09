package com.jianji.app.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root 命令执行（仅在本机已 root 时可用）。
 *
 * 应用只做三件事，都需要 root：
 * 1. 一键初始化：把权限、无障碍、通知读取、电池白名单一次性设好；
 * 2. 读取通知栏内容（`dumpsys notification --noredact`）——**不依赖无障碍/通知读取**也能拿到消费信息；
 * 3. 无障碍被系统关闭时直接写回系统设置（比 WRITE_SECURE_SETTINGS 更可靠）。
 *
 * 所有命令都在工作线程执行，带超时，失败一律返回失败而不抛异常。
 * 不联网、不上传任何数据。
 */
object RootShell {

    data class Result(val ok: Boolean, val output: String)

    @Volatile
    private var rootChecked: Boolean? = null

    /** Root 是否可用（结果缓存；root 状态基本不会中途变化） */
    fun isAvailable(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) rootChecked?.let { return it }
        val r = run("id")
        val ok = r.ok && r.output.contains("uid=0")
        rootChecked = ok
        return ok
    }

    /** 只读缓存结果（不执行 su，可在 UI 线程调用）：null = 还没检测过 */
    fun cachedAvailability(): Boolean? = rootChecked

    /** 执行 `su -c <cmd>`；失败或超时返回 ok = false */
    fun run(command: String, timeoutMs: Long = 8000L): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            process.inputStream.bufferedReader().use { reader: BufferedReader ->
                val buf = CharArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        sb.append(buf, 0, n)
                    } else if (!process.isAlive) {
                        // 进程结束，把剩余内容读完
                        val rest = reader.readText()
                        sb.append(rest)
                        break
                    } else {
                        Thread.sleep(20)
                    }
                }
            }
            val finished = process.waitFor(500, TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            Result(finished && process.exitValue() == 0, sb.toString())
        } catch (_: Exception) {
            runCatching { process?.destroy() }
            Result(false, "")
        }
    }
}

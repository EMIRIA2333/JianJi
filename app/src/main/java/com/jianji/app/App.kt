/*
 * 简记 JianJi — 离线优先的 Android 自动记账应用
 * Copyright (C) 2026  EMIRIA2333
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.jianji.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.jianji.app.core.Deduper
import com.jianji.app.db.CategoryMemoryDao
import com.jianji.app.util.AppLog
import com.jianji.app.db.RecordDao
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局单例：DAO + 去重器 + 单条低优先级后台线程。
 *
 * 后台占用策略：
 * - 不启动前台服务、不做任何轮询、不持有唤醒锁、不联网；
 * - 只有一条 MIN_PRIORITY 工作线程，空闲时阻塞（不占 CPU），无常驻定时器；
 * - 数据库/解析全部在 worker 线程串行执行，主线程零阻塞；
 * - 队列带背压：积压超过 [MAX_PENDING] 时由调用方（页面扫描）主动放弃本次任务，
 *   避免设备繁忙时任务雪崩式堆积，既省电又省内存。
 */
class App : Application() {

    lateinit var dao: RecordDao
        private set
    /** 分类记忆（学习你改过的分类） */
    lateinit var categoryMemory: CategoryMemoryDao
        private set
    lateinit var deduper: Deduper
        private set

    /** 安全模式：上次启动崩过 → 本次不启用毛玻璃等可选功能 */
    var safeMode: Boolean = false
        private set

    private var executor: ExecutorService? = null
    private val pending = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 顺序很重要：先建好 dao / deduper，日志的环境快照才读得到数据
        // （曾经把日志初始化放在 dao 之前，读快照时会碰到 lateinit 未初始化）
        dao = RecordDao(this)
        categoryMemory = CategoryMemoryDao(this)
        deduper = Deduper()
        runCatching { applyTheme() }
        runCatching { AppLog.init(this) }
        safeMode = runCatching { AppLog.enterSafeModeIfLastCrash(this) }.getOrDefault(false)
        executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "jianji-worker").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY + 1
            }
        }
    }

    /** 主题：跟随系统 / 纯白 / 纯黑（在 Application 里统一生效，切换后所有页面一致） */
    fun applyTheme() {
        val mode = when (com.jianji.app.util.Prefs.themeMode(this)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        runCatching { AppCompatDelegate.setDefaultNightMode(mode) }
    }

    /** 将任务调度到单一后台线程执行 */
    fun post(block: () -> Unit) {
        val ex = executor ?: return
        pending.incrementAndGet()
        runCatching {
            ex.execute {
                try {
                    block()
                } finally {
                    pending.decrementAndGet()
                }
            }
        }.onFailure { pending.decrementAndGet() }
    }

    /** 后台线程是否已积压过多任务（页面扫描据此主动降频） */
    fun isWorkerBusy(): Boolean = pending.get() >= MAX_PENDING

    companion object {
        lateinit var instance: App
            private set

        /** 允许积压的最大任务数 */
        private const val MAX_PENDING = 8
    }
}

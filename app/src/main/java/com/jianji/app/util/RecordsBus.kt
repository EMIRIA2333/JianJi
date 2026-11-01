package com.jianji.app.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 账本变更的进程内通知（记账 / 删除 / 回收站操作之后调用）。
 *
 * 为什么需要：明细页是一次性查询后就不再更新 —— 用户停在明细页时新记的账不会出现，
 * 表现就是"通知说已记 3 笔，但账单列表里没有"（真实反馈）。
 *
 * 用进程内回调而不是广播：没有 IPC 开销，也不会被系统限制后台广播。
 */
object RecordsBus {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var lastNotifyAt = 0L

    fun register(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregister(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 通知所有正在显示的页面刷新（可从任意线程调用） */
    fun notifyChanged() {
        lastNotifyAt = System.currentTimeMillis()
        listeners.forEach { runCatching { it() } }
    }

    /** 最近一次变更时间（页面可据此判断"我是不是过期了"） */
    fun lastChangedAt(): Long = lastNotifyAt
}

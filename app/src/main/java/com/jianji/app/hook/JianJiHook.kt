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
package com.jianji.app.hook

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed / Xposed 模块入口（**双路 hook**）。
 *
 * ## 路线一：系统框架（system_server）—— 推荐，最彻底
 * hook `NotificationManagerService.enqueueNotificationInternal(...)`：
 * **所有应用的通知都必须经过这里**，因此不需要把模块注入微信/支付宝，
 * 只要作用域勾了「系统框架」就能拿到全部通知内容。
 *
 * ## 路线二：应用进程（微信 / 支付宝）
 * hook `android.app.NotificationManager.notify(...)`：若 LSPosed 能注入目标应用，这条路更快更省。
 *
 * 两条路读到的同一条通知会由主程序按文本去重，**不会重复记账**。
 *
 * 安全约束（非常重要）：只**读**参数，绝不修改任何字段或返回值；所有逻辑都包在
 * try/catch 里；hook 安装失败就跳过。因此不会影响系统或宿主应用。
 */
class JianJiHook : IXposedHookLoadPackage {

    init {
        // 只要框架实例化了入口类，就说明模块被成功加载 —— 这是排查的第一证据
        runCatching { XposedBridge.log("$TAG 入口类已实例化") }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName ?: return
            val cl = lpparam.classLoader ?: ClassLoader.getSystemClassLoader()
            when {
                pkg == "android" -> hookSystemServer(cl)
                WATCHED.contains(pkg) -> hookAppProcess(pkg, cl)
                else -> return
            }
        } catch (t: Throwable) {
            runCatching { XposedBridge.log("$TAG 接入失败：" + t.message) }
        }
    }

    // ---------------- 路线一：系统框架 ----------------

    private fun hookSystemServer(cl: ClassLoader) {
        diagSystem("hookSystemServer: 开始安装")
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                try {
                    val args = param?.args ?: return
                    val n = args.firstOrNull { it is Notification } as? Notification ?: return
                    val pkg = args.firstOrNull { it is String } as? String ?: return
                    if (!WATCHED.contains(pkg)) return
                    val (title, body) = extract(n) ?: return
                    XposedBridge.log("$TAG 系统框架拦截到通知($pkg)：$title / ${body.take(30)}")
                    // mContext 是 NotificationManagerService 自己的字段（系统上下文）
                    val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        ?: systemContext() ?: return
                    sendFrom(ctx, pkg, title, body, "system", runCatching { n.`when` }.getOrDefault(0L))
                } catch (_: Throwable) {
                    // system_server 里的异常绝不能抛出去
                }
            }
        }

        // 不按参数签名猜，直接扫描该类里所有同名方法（各 Android 版本参数形态不同）
        val clazz = runCatching {
            cl.loadClass("com.android.server.notification.NotificationManagerService")
        }.getOrNull()
        var hooked = 0
        val names = mutableListOf<String>()
        runCatching {
            clazz?.declaredMethods?.filter { it.name == "enqueueNotificationInternal" }?.forEach { m ->
                runCatching {
                    XposedBridge.hookMethod(m, callback)
                    hooked++
                    names.add(m.parameterTypes.joinToString(",") { it.simpleName })
                }
            }
        }
        // 兜底：老写法（按固定签名尝试）
        if (hooked == 0) {
            NMS_SIGNATURES.forEach { types ->
                val ok = runCatching {
                    XposedHelpers.findAndHookMethod(NMS_CLASS, cl, "enqueueNotificationInternal", *types, callback)
                    true
                }.getOrDefault(false)
                if (ok) hooked++
            }
        }
        XposedBridge.log(
            "$TAG 系统框架 hook：" + if (hooked > 0) "已安装 $hooked 个（${names.joinToString(" | ")}）"
            else "未找到 enqueueNotificationInternal"
        )
        diagSystem("hookSystemServer: 已安装 $hooked 个")
        // 只要能拿到系统上下文就先发一次心跳 —— 主程序立刻显示「已生效 ✅（android · system）」
        if (hooked > 0) {
            systemContext()?.let { ctx ->
                sendHello(ctx, "android", "system")
                startHeartbeat(ctx)
            }
        }
    }

    /**
     * **周期性心跳**（每 5 分钟一次）。
     *
     * 为什么必须要有：hello 只在「模块加载 / 界面出现」时发一次，而这两个时机经常
     * 拿不到可用的 Context（模块加载早于应用上下文创建）→ 主程序收不到心跳，
     * 于是明明在正常工作却一直显示"模块未生效"（你的日志正是这种情况）。
     * system_server 一直都在，用它定时发最可靠。
     */
    private fun startHeartbeat(ctx: Context) {
        if (heartbeatStarted) return
        heartbeatStarted = true
        runCatching {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val task = object : Runnable {
                override fun run() {
                    runCatching { sendHello(ctx, "android", "heartbeat") }
                    runCatching { handler.postDelayed(this, HEARTBEAT_MS) }
                }
            }
            handler.postDelayed(task, HEARTBEAT_MS)
            diagSystem("周期性心跳已启动（每 ${HEARTBEAT_MS / 1000}s）")
        }.onFailure { diagSystem("心跳启动失败: ${it.message}") }
    }

    /** 系统框架进程里没有应用数据目录，写到 /data/system（system uid 可写，主程序 root 可读） */
    private fun diagSystem(line: String) {
        try {
            val f = java.io.File("/data/system/jianji_hook.txt")
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {
        }
    }

    /** system_server 里没有 Application，用系统的系统上下文 */
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getMethod("currentActivityThread").invoke(null) ?: return null
        at.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull()

    // ---------------- 路线二：应用进程（微信 / 支付宝） ----------------

    private fun hookAppProcess(pkg: String, cl: ClassLoader) {
        currentPkg = pkg
        diag("handleLoadPackage: $pkg")
        sendHelloFromApp(pkg, "load")
        hookNotify(cl)
        hookActivityCreate(cl)
        runCatching { XposedBridge.log("$TAG 应用进程 hook 已接入：$pkg") }
    }

    private fun hookNotify(cl: ClassLoader) {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                try {
                    val n = param?.args?.firstOrNull { it is Notification } as? Notification ?: return
                    val (title, body) = extract(n) ?: return
                    XposedBridge.log("$TAG 拦截到通知(${currentPkg})：$title / ${body.take(30)}")
                    sendFromApp(title, body, runCatching { n.`when` }.getOrDefault(0L))
                } catch (_: Throwable) {
                }
            }
        }
        // 不按签名猜：扫描所有名为 notify 的方法（各版本重载不同）
        var hooked = 0
        runCatching {
            val nm = cl.loadClass("android.app.NotificationManager")
            nm.declaredMethods.filter { it.name == "notify" }.forEach { m ->
                val ok = runCatching { XposedBridge.hookMethod(m, callback); true }.getOrDefault(false)
                if (ok) hooked++
            }
        }
        if (hooked == 0) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "android.app.NotificationManager", cl, "notify",
                    Int::class.javaPrimitiveType, Notification::class.java, callback
                )
            }
        }
        XposedBridge.log("$TAG 应用进程 notify hook：$hooked 个方法")
    }

    /** 兜底心跳：微信/支付宝一显示界面就再报一次 */
    private fun hookActivityCreate(cl: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", cl, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        try {
                            val pkg = currentPkg ?: return
                            if (helloFromActivity) return
                            helloFromActivity = true
                            sendHelloFromApp(pkg, "activity")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        }
    }

    private fun sendHelloFromApp(pkg: String, stage: String) {
        val ctx = broadcastContext()
        if (ctx == null) {
            diag("hello($stage) 失败：拿不到任何 Context")
            return
        }
        sendHello(ctx, pkg, stage)
    }

    // ---------------- 通知内容提取 ----------------

    /** 取出标题与正文；都没内容就返回 null */
    private fun extract(n: Notification): Pair<String, String>? {
        val extras = runCatching { n.extras }.getOrNull() ?: return null
        val title = runCatching { extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() }
            .getOrDefault("")
        val body = listOf(
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT
        ).mapNotNull { key ->
            runCatching { extras.getCharSequence(key)?.toString().orEmpty() }
                .getOrDefault("").takeIf { it.isNotEmpty() }
        }.joinToString("\n")
        if (title.isBlank() && body.isBlank()) return null
        return title to body
    }

    // ---------------- 与主程序通信 ----------------

    private fun sendHello(ctx: Context, pkg: String, stage: String) {
        runCatching {
            ctx.sendBroadcast(
                baseIntent(pkg)
                    .putExtra(KEY_HELLO, true)
                    .putExtra(KEY_STAGE, stage)
                    .putExtra(KEY_DIAG, diagSnapshot())
            )
            diag("hello($stage) 已发送")
        }.onFailure { diag("hello($stage) 广播失败: " + it.message) }
    }

    private fun sendFromApp(title: String, text: String, notifyAt: Long) {
        val pkg = currentPkg ?: return
        val ctx = broadcastContext() ?: return
        sendFrom(ctx, pkg, title, text, "app", notifyAt)
    }

    private fun sendFrom(
        ctx: Context,
        pkg: String,
        title: String,
        text: String,
        stage: String,
        notifyAt: Long = 0L
    ) {
        runCatching {
            ctx.sendBroadcast(
                baseIntent(pkg)
                    // 每条通知都顺带一次"模块活着"标记：主程序状态不会因为收不到 hello 而误判
                    .putExtra(KEY_HELLO, true)
                    .putExtra(KEY_TITLE, title)
                    .putExtra(KEY_TEXT, text)
                    .putExtra(KEY_STAGE, stage)
                    .putExtra(KEY_WHEN, notifyAt)
            )
            XposedBridge.log("$TAG 已转发给简记（$stage / $pkg）：${title.take(20)}")
            if (stage == "app") diag("通知已转发: $title / ${text.take(40)}")
        }.onFailure {
            XposedBridge.log("$TAG 转发失败：${it.message}")
            diag("通知转发失败: " + it.message)
        }
    }

    private fun baseIntent(pkg: String): Intent = Intent(ACTION)
        .setComponent(ComponentName(HOST_PKG, HOST_RECEIVER))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra(KEY_TOKEN, TOKEN)
        .putExtra(KEY_PKG, pkg)

    /** 当前进程的 Application（反射取，避免依赖额外的 Xposed API） */
    private fun application(): Application? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    /**
     * 拿一个能发广播的 Context。
     *
     * **关键**：注入发生在 Application 创建之前，此时 `currentApplication()` 是 null，
     * 所以必须退回「系统上下文」——否则加载阶段的心跳永远发不出去，
     * 表现就是"LSPosed 里启用了、主程序却从未收到心跳"。
     */
    private fun broadcastContext(): Context? {
        application()?.let { return it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getMethod("currentActivityThread").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(current) as? Context
            ctx ?: systemContext()
        }.getOrNull()
    }

    // ---------------- 现场诊断 ----------------

    /**
     * 现场诊断（**不依赖 Application**）。
     *
     * 注入发生在 Application 创建之前，用 `app.filesDir` 会直接失败；
     * 这里直接按包名拼路径（`/data/data/<pkg>/files/`）写文件，
     * 因此"模块到底有没有被注入"这个问题一定有文件层面的答案。
     */
    private fun diag(line: String) {
        val stamp = System.currentTimeMillis()
        try {
            synchronized(diagLines) {
                diagLines.add("$stamp $line")
                while (diagLines.size > 12) diagLines.removeAt(0)
            }
        } catch (_: Throwable) {
        }
        try {
            val dir = application()?.filesDir?.absolutePath
                ?: currentPkg?.let { "/data/data/$it/files" }
                ?: return
            val f = java.io.File(dir, "jianji_hook.txt")
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText("$stamp $line\n")
        } catch (_: Throwable) {
            // 诊断失败不能影响主流程
        }
    }

    private fun diagSnapshot(): String = synchronized(diagLines) { diagLines.joinToString("\n") }

    private companion object {
        const val TAG = "[简记]"

        /** 周期心跳间隔：5 分钟（system_server 一直活着，用它发最可靠） */
        const val HEARTBEAT_MS = 5 * 60 * 1000L

        // ---- 与主程序约定的协议（刻意硬编码：hook 不引用任何应用类，
        //      避免某个类初始化失败导致整个 hook 被静默跳过）----
        const val ACTION = "com.jianji.app.HOOK_BRIDGE"
        const val HOST_PKG = "com.jianji.app"
        const val HOST_RECEIVER = "com.jianji.app.service.HookBridgeReceiver"
        const val TOKEN = "jianji-hook-v1"
        const val KEY_TOKEN = "token"
        const val KEY_PKG = "pkg"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
        const val KEY_HELLO = "hello"
        const val KEY_STAGE = "stage"
        const val KEY_DIAG = "diag"
        const val KEY_WHEN = "when"

        const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

        /** 需要接管的支付/购物类应用 */
        /**
         * 接管名单：**统一来自 [HookPackages.ALL]**（唯一来源）。
         * 以前这里自己写了一份，和应用侧不同步 → 购物/外卖/银行的账单被静默丢掉。
         */
        val WATCHED: Set<String> = HookPackages.ALL

        /** `enqueueNotificationInternal` 的各版本参数形态（Android 11~15） */
        val NMS_SIGNATURES: List<Array<Class<*>>> = listOf(
            arrayOf(
                String::class.java, String::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                String::class.java, Int::class.javaPrimitiveType!!,
                Notification::class.java, Int::class.javaPrimitiveType!!
            ),
            arrayOf(
                String::class.java, String::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                String::class.java, Int::class.javaPrimitiveType!!,
                Notification::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            )
        )

        @Volatile
        var currentPkg: String? = null

        @Volatile
        var helloFromActivity = false
        /** 周期心跳只启动一次 */
        var heartbeatStarted = false

        val diagLines = ArrayList<String>(16)
    }
}

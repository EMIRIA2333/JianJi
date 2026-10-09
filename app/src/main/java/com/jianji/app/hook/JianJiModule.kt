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

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.HookParam
import io.github.libxposed.api.Hooker
import io.github.libxposed.api.ModuleLoadedParam
import io.github.libxposed.api.PackageReadyParam
import io.github.libxposed.api.SystemServerStartingParam
import io.github.libxposed.api.XposedModule

/**
 * **新版（libxposed API）模块入口** —— 面向 LSPosed 2.x。
 *
 * 背景：LSPosed 2.x 不再加载旧版 `assets/xposed_init` 格式的模块
 * （现象：管理器里能看到模块，但配置库不登记、模块日志一行都没有）。
 * 因此这里按新格式实现：`META-INF/xposed/java_init.list` + `io.github.libxposed.api.XposedModule`。
 * 旧入口 [JianJiHook] 保留，供仍在用 LSPosed 1.x / EdXposed 的用户。
 *
 * 两条 hook 路线（与旧入口一致）：
 * 1. 系统框架 `NotificationManagerService#enqueueNotificationInternal` —— 所有通知的必经之路；
 * 2. 应用进程 `NotificationManager#notify`。
 *
 * 安全约束：只读参数、不改返回值；所有异常吞掉；hook 失败即跳过。
 *
 * 为了在 API 细节与文档有出入时依然可用，参数读取一律走**反射**（见 [argsOf]）。
 */
class JianJiModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam?) {
        // 只要这句出现在 LSPosed 日志里，就说明新格式模块被成功加载了
        runCatching { log("$TAG 新格式模块已加载（libxposed API）") }
        // 自证：系统框架进程写 /data/system（system uid 可写，主程序 root 可读）
        val isSystem = runCatching { param?.isSystemServer == true }.getOrDefault(false)
        val proc = runCatching { param?.processName }.getOrNull().orEmpty()
        diagSystem("onModuleLoaded: isSystemServer=$isSystem process=$proc")
    }

    override fun onPackageReady(param: PackageReadyParam?) {
        try {
            val pkg = stringOf(param, "getPackageName") ?: return
            if (!WATCHED.contains(pkg)) return
            currentPkg = pkg
            diag(pkg, "onPackageReady: $pkg")
            sendHello(pkg, "load")
            log("$TAG 应用进程 hook 已接入：$pkg")
            hookNotify(classLoaderOf(param))
        } catch (t: Throwable) {
            runCatching { log("$TAG onPackageReady 失败：${t.message}") }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam?) {
        try {
            diagSystem("onSystemServerStarting: 开始安装")
            val cl = classLoaderOf(param) ?: ClassLoader.getSystemClassLoader()
            hookSystemServer(cl)
            log("$TAG 系统框架 hook 已安装")
            // 系统框架就绪即发心跳：主程序立刻显示「已生效（android · system）」
            systemContext()?.let { sendHelloFrom(it, "android", "system") }
        } catch (t: Throwable) {
            runCatching { log("$TAG onSystemServerStarting 失败：${t.message}") }
        }
    }

    /** 系统框架进程里的自证文件 */
    private fun diagSystem(line: String) {
        try {
            val f = java.io.File("/data/system/jianji_hook.txt")
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {
        }
    }

    // ---------------- 路线一：系统框架 ----------------

    private fun hookSystemServer(cl: ClassLoader) {
        val clazz = runCatching {
            cl.loadClass("com.android.server.notification.NotificationManagerService")
        }.getOrNull() ?: return
        val hooker = object : Hooker {
            override fun before(param: HookParam) {
                try {
                    val args = argsOf(param) ?: return
                    val n = args.firstOrNull { it is Notification } as? Notification ?: return
                    val pkg = args.firstOrNull { it is String } as? String ?: return
                    if (!WATCHED.contains(pkg)) return
                    val (title, body) = extract(n) ?: return
                    log("$TAG 系统框架拦截到通知($pkg)：$title / ${body.take(30)}")
                    val ctx = systemContext() ?: return
                    sendFrom(ctx, pkg, title, body, "system", runCatching { n.`when` }.getOrDefault(0L))
                } catch (_: Throwable) {
                }
            }
        }
        // 不按签名猜：扫描所有同名方法（各 Android 版本参数形态不同）
        var hooked = 0
        runCatching {
            clazz.declaredMethods.filter { it.name == "enqueueNotificationInternal" }.forEach { m ->
                val ok = runCatching { hook(m, hooker); true }.getOrDefault(false)
                if (ok) hooked++
            }
        }
        log("$TAG 系统框架 hook：$hooked 个方法")
        diagSystem("hookSystemServer: $hooked 个方法")
    }

    // ---------------- 路线二：应用进程 ----------------

    private fun hookNotify(cl: ClassLoader?) {
        val cl = cl ?: return
        val nm = runCatching { cl.loadClass("android.app.NotificationManager") }.getOrNull() ?: return
        val hooker = object : Hooker {
            override fun before(param: HookParam) {
                try {
                    val args = argsOf(param) ?: return
                    val n = args.firstOrNull { it is Notification } as? Notification ?: return
                    val (title, body) = extract(n) ?: return
                    val pkg = currentPkg ?: return
                    log("$TAG 拦截到通知($pkg)：$title / ${body.take(30)}")
                    val ctx = broadcastContext() ?: return
                    sendFrom(ctx, pkg, title, body, "app", runCatching { n.`when` }.getOrDefault(0L))
                } catch (_: Throwable) {
                }
            }
        }
        var hooked = 0
        runCatching {
            nm.declaredMethods.filter { it.name == "notify" }.forEach { m ->
                val ok = runCatching { hook(m, hooker); true }.getOrDefault(false)
                if (ok) hooked++
            }
        }
        log("$TAG 应用进程 notify hook：$hooked 个方法")
    }

    // ---------------- 反射取参（对 API 命名差异免疫） ----------------

    private fun argsOf(p: Any?): Array<Any?>? {
        if (p == null) return null
        listOf("getArgs", "args").forEach { name ->
            runCatching {
                val m = p.javaClass.getMethod(name)
                @Suppress("UNCHECKED_CAST")
                (m.invoke(p) as? Array<Any?>)?.let { return it }
            }
        }
        runCatching {
            val f = p.javaClass.getField("args")
            @Suppress("UNCHECKED_CAST")
            return f.get(p) as? Array<Any?>
        }
        return null
    }

    private fun stringOf(p: Any?, method: String): String? = runCatching {
        p?.javaClass?.getMethod(method)?.invoke(p) as? String
    }.getOrNull()

    private fun classLoaderOf(p: Any?): ClassLoader? = runCatching {
        p?.javaClass?.getMethod("getClassLoader")?.invoke(p) as? ClassLoader
    }.getOrNull()

    // ---------------- 通知内容提取 ----------------

    private fun extract(n: Notification): Pair<String, String>? {
        val extras = runCatching { n.extras }.getOrNull() ?: return null
        val title = runCatching { extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() }
            .getOrDefault("")
        val body = listOf(
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT
        ).mapNotNull { key ->
            runCatching { extras.getCharSequence(key)?.toString().orEmpty() }
                .getOrDefault("").takeIf { it.isNotEmpty() }
        }.joinToString("\n")
        if (title.isBlank() && body.isBlank()) return null
        return title to body
    }

    // ---------------- 与主程序通信 ----------------

    private fun sendHello(pkg: String, stage: String) {
        val ctx = broadcastContext() ?: run {
            diag(pkg, "hello($stage) 失败：拿不到 Context")
            return
        }
        sendHelloFrom(ctx, pkg, stage)
    }

    private fun sendHelloFrom(ctx: Context, pkg: String, stage: String) {
        runCatching {
            ctx.sendBroadcast(
                baseIntent(pkg).putExtra(KEY_HELLO, true).putExtra(KEY_STAGE, stage).putExtra(KEY_DIAG, diagSnapshot())
            )
            diag(pkg, "hello($stage) 已发送")
        }
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
                    .putExtra(KEY_TITLE, title)
                    .putExtra(KEY_TEXT, text)
                    .putExtra(KEY_STAGE, stage)
                    .putExtra(KEY_WHEN, notifyAt)
            )
            runCatching { log("$TAG 已转发给简记（$stage / $pkg）") }
            if (stage == "app") diag(pkg, "通知已转发: $title")
        }.onFailure { runCatching { log("$TAG 转发失败：${it.message}") } }
    }

    private fun baseIntent(pkg: String): Intent = Intent(ACTION)
        .setComponent(ComponentName(HOST_PKG, HOST_RECEIVER))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra(KEY_TOKEN, TOKEN)
        .putExtra(KEY_PKG, pkg)

    // ---------------- Context 与诊断 ----------------

    private fun broadcastContext(): Context? {
        application()?.let { return it }
        return systemContext()
    }

    private fun application(): android.app.Application? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? android.app.Application
    }.getOrNull()

    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull()

    /** 诊断落盘（不依赖 Application：直接按包名拼路径） */
    private fun diag(pkg: String, line: String) {
        val stamp = System.currentTimeMillis()
        try {
            synchronized(diagLines) {
                diagLines.add("$stamp $line")
                while (diagLines.size > 12) diagLines.removeAt(0)
            }
        } catch (_: Throwable) {
        }
        try {
            val dir = application()?.filesDir?.absolutePath ?: "/data/data/$pkg/files"
            val f = java.io.File(dir, "jianji_hook.txt")
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText("$stamp $line\n")
        } catch (_: Throwable) {
        }
    }

    private fun diagSnapshot(): String = synchronized(diagLines) { diagLines.joinToString("\n") }

    private companion object {
        const val TAG = "[简记]"
        const val ACTION = "com.jianji.app.HOOK_BRIDGE"
        const val HOST_PKG = "com.jianji.app"
        const val HOST_RECEIVER = "com.jianji.app.service.HookBridgeReceiver"
        const val TOKEN = "jianji-hook-v1"
        const val KEY_TOKEN = "token"
        const val KEY_PKG = "pkg"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
        const val HEARTBEAT_MS = 5 * 60 * 1000L
        const val KEY_HELLO = "hello"
        const val KEY_STAGE = "stage"
        const val KEY_DIAG = "diag"
        const val KEY_WHEN = "when"

        /**
         * 接管名单：**统一来自 [HookPackages.ALL]**（唯一来源）。
         * 以前这里自己写了一份，和应用侧不同步 → 购物/外卖/银行的账单被静默丢掉。
         */
        val WATCHED: Set<String> = HookPackages.ALL

        /** `enqueueNotificationInternal` 各版本参数形态 */
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

        val diagLines = ArrayList<String>(16)
    }
}

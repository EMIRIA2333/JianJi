package com.jianji.app.core

/**
 * Hook 模块与被 hook 进程之间的协议（纯 Kotlin，可单测）。
 *
 * 模块自身运行在**微信 / 支付宝的进程里**，通过一条显式广播把通知内容发给简记主程序；
 * 主程序侧的 [com.jianji.app.service.HookBridgeReceiver] 校验 token 后，交给与无障碍 /
 * 通知读取**同一个记账管道**（内容相同只会记一次）。
 */
object HookProtocol {

    /** 广播 action（显式指定组件，Android 8+ 后台也能送达） */
    const val ACTION = "com.jianji.app.HOOK_BRIDGE"

    const val HOST_PKG = "com.jianji.app"
    const val HOST_RECEIVER = "com.jianji.app.service.HookBridgeReceiver"

    /** 简易校验串：防止任意应用往记账管道里塞假数据 */
    const val TOKEN = "jianji-hook-v1"

    const val KEY_TOKEN = "token"
    const val KEY_PKG = "pkg"
    const val KEY_TITLE = "title"
    const val KEY_TEXT = "text"
    const val KEY_HELLO = "hello"

    /** 心跳来源阶段（load / activity），仅用于诊断显示 */
    const val KEY_STAGE = "stage"

    /** 模块侧现场诊断快照（随心跳一起送过来，比读文件更可靠） */
    const val KEY_DIAG = "diag"

    /** 自测专用阶段标记：**不计入"模块已生效"**，避免自测把状态点亮（曾经因此误判） */
    const val STAGE_SELFTEST = "selftest"

    /** 通知自身的时间戳（Notification.when）：判断"同一条通知只记一次"的关键 */
    const val KEY_WHEN = "when"

    /** 心跳有效时长：超过这个时间没收到就认为模块没在跑 */
    const val ACTIVE_TTL_MS = 6 * 60 * 60 * 1000L

    /** 需要接管的支付/购物类应用 */
    private val WATCHED = setOf(
        PaymentParser.PKG_WECHAT,
        PaymentParser.PKG_ALIPAY,
        "com.unionpay"
    )

    fun isWatched(pkg: String): Boolean =
        WATCHED.contains(pkg) || PaymentParser.APP_NAMES.containsKey(pkg)

    /**
     * 模块侧专用：**只查硬编码集合，不触碰任何解析器类**。
     *
     * hook 跑在微信/支付宝进程里，类初始化越少越安全 —— 万一某个解析器类初始化失败，
     * 整个 hook 会被静默跳过（这正是"启用了却不生效"最难查的情况）。
     */
    /**
     * 模块侧接管名单：**与 Hook 模块共用同一份** [com.jianji.app.hook.HookPackages.ALL]。
     * 这样"应用认为该接管"和"模块实际接管"永远一致（曾经两边不同步，
     * 导致购物/外卖/银行的账单被模块静默丢弃）。
     */
    val FAST_WATCHED: Set<String> = com.jianji.app.hook.HookPackages.ALL

    fun isWatchedFast(pkg: String): Boolean = FAST_WATCHED.contains(pkg)

    fun validToken(token: String?): Boolean = token == TOKEN

    /** 是否认为 Hook 模块正在生效（收到过心跳且未过期） */
    fun isActive(lastHelloAt: Long, now: Long): Boolean =
        lastHelloAt > 0 && now - lastHelloAt < ACTIVE_TTL_MS
}

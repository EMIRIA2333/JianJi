package com.jianji.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jianji.app.core.HookProtocol
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog

/**
 * 接收来自 Hook 模块（LSPosed）的通知内容。
 *
 * 模块运行在微信 / 支付宝进程里，发一条显式广播到这里；本接收器校验 token 后
 * 交给与无障碍、通知读取**同一个记账管道**（内容相同只记一次）。
 *
 * 安全说明：接收器需要 exported 才能被其它进程调用，因此用固定 token 做校验，
 * 并对包名与文本做白名单/空值过滤 —— 最坏情况只是本地多出一条可撤销的账单。
 */
class HookBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val i = intent ?: return
        if (i.action != HookProtocol.ACTION) return
        if (!HookProtocol.validToken(i.getStringExtra(HookProtocol.KEY_TOKEN))) return

        // 记录"确实收到过广播" —— 用于区分「广播没送达」和「送达了没记上」
        val now = System.currentTimeMillis()
        Prefs.setHookLastDeliveryAt(context, now)
        // ★ 关键修复：**任何**来自模块的广播都证明模块活着 —— 能拦到通知就是最好的证据。
        //   以前只认 hello 心跳，而 hello 只在「加载 / 界面出现」时发一次，常常发不出来，
        //   于是明明在正常工作却一直显示"模块未生效"（你的日志就是这种情况）。
        Prefs.setHookActiveAt(context, now)

        val pkg = i.getStringExtra(HookProtocol.KEY_PKG).orEmpty()
        val stage = i.getStringExtra(HookProtocol.KEY_STAGE).orEmpty()
        val title = i.getStringExtra(HookProtocol.KEY_TITLE).orEmpty()
        val text = i.getStringExtra(HookProtocol.KEY_TEXT).orEmpty()

        // 心跳：只更新"模块是否生效"的状态，正文仍要照常处理
        // （曾经在这里直接 return，导致带心跳的自测内容永远不记账）
        if (i.getBooleanExtra(HookProtocol.KEY_HELLO, false)) {
            if (stage != HookProtocol.STAGE_SELFTEST) {
                // hookActiveAt 已在上面无条件更新；这里只补充来源与心跳日志，
                // 通知转发（stage=app/system）不刷心跳日志，避免刷屏
                if (stage != "app") {
                    Prefs.setHookLastSource(context, "$pkg · $stage")
                    RecognitionLog.add("Hook 模块心跳（$pkg · $stage）：通知直读已生效")
                }
                i.getStringExtra(HookProtocol.KEY_DIAG)?.takeIf { it.isNotBlank() }?.let {
                    Prefs.setHookDiag(context, it)
                }
            }
            // 心跳包里没有正文就不用往下走了
            if (title.isBlank() && text.isBlank()) return
        }

        if (!HookProtocol.isWatched(pkg)) return
        if (title.isBlank() && text.isBlank()) return
        NotifyPipe.handle(
            context, pkg, title, text, "Hook",
            i.getLongExtra(HookProtocol.KEY_WHEN, 0L)
        )
    }
}

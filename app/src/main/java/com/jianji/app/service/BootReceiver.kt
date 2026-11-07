package com.jianji.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jianji.app.util.BackgroundWork
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog
import com.jianji.app.util.RootManager

/**
 * 开机自启：把「Hook 守护」与「root 读取通知」两个前台服务拉起来。
 *
 * 这一步很关键 —— MIUI/HyperOS 等 ROM 会在后台清理时把无障碍服务与应用进程一并杀掉，
 * 表现就是「只有打开应用才记账」。开机就把守护服务起起来，能大幅减少这种情况。
 *
 * 说明：需要系统「自启动」权限（应用内「自启动设置」可一键跳转）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        RecognitionLog.add("开机自启：按门控恢复后台服务")
        // 统一门控：没开的功能一律不启动
        BackgroundWork.sync(context)
        // 开机这一瞬间：Hook 还没上报（hookActive=false）、root 缓存也可能为空，
        // 按门控会算出"没活干"→ 守护服务不启动 → 表现就是**自启动失效**（真实反馈）。
        // 所以这里先无条件把守护拉起来（用户开了总开关 + Hook 常驻），
        // 由服务内部再按门控决定是否继续常驻。
        if (Prefs.isAutoEnabled(context) && Prefs.isHookGuard(context)) {
            runCatching {
                context.startForegroundService(
                    Intent(context, HookGuardService::class.java)
                )
                com.jianji.app.util.AppLog.i("后台", "开机：先拉起 Hook 守护（等 Hook 上报后按门控决定去留）")
            }
        }
        // 有 root 且自动记账开着时，顺手确认无障碍是否被关
        if (Prefs.isAutoEnabled(context) && RootManager.rootedCached() == true) {
            RootManager.autoRestoreWithRoot(context)
        }
    }
}

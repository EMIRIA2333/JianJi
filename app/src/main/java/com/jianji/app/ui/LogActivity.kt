package com.jianji.app.ui

import android.os.Bundle
import android.widget.Toast
import com.jianji.app.databinding.ActivityLogBinding
import com.jianji.app.util.AppLog
import com.jianji.app.util.Prefs
import com.jianji.app.util.RootManager

/**
 * 运行日志页：分享 / 发送文本 / 复制 / 保存到下载 / 清空，以及 **Hook 诊断** 的保存与分享。
 *
 * 导出设计（多次踩坑后的结论）：
 * - **分享文件**：先写进系统「下载/简记」，再分享**系统媒体 URI**。
 *   以前用 FileProvider（由简记进程提供文件），分享面板一起简记被 MIUI 杀掉，
 *   对方读文件时就报"文件不存在"；换系统 URI 后简记死了也不影响。
 * - **发送文本**：完全不涉及文件，任何应用（QQ/微信/邮件）都收得下 —— 最稳的一条。
 * - **保存到下载**：文件落在 下载/简记/，可从文件管理器直接发送。
 * - **复制全部**：进剪贴板，直接粘贴。
 * - **Hook 诊断**：模块加载情况、LSPosed 日志、注入框架列表 —— 排查"Hook 不生效"必备。
 */
class LogActivity : BaseActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnShare.setOnClickListener {
            val intent = AppLog.shareFileIntent(this)
            if (intent == null) {
                toast("生成分享失败，请改用「发送文本」或「保存到下载」")
                return@setOnClickListener
            }
            runCatching { startActivity(intent) }
                .onFailure {
                    AppLog.e("日志", "调起分享失败：${it.message}")
                    toast("无法调起分享，请用「保存到下载」")
                }
        }

        binding.btnSendText.setOnClickListener {
            runCatching { startActivity(AppLog.shareTextIntent(this)) }
                .onFailure { toast("无法调起分享，请用「复制全部」") }
        }

        binding.btnCopy.setOnClickListener {
            val ok = AppLog.copyToClipboard(this, AppLog.visibleText(this))
            toast(if (ok) "已复制，直接粘贴发送即可" else "复制失败")
        }

        binding.btnSave.setOnClickListener {
            val saved = AppLog.saveToDownloads(this, AppLog.visibleText(this))
            toast(
                if (saved != null) "已保存到：${saved.second}（可从文件管理器发送）"
                else "保存失败（详见日志）"
            )
            if (saved != null) AppLog.i("日志", "用户保存成功：${saved.second}")
        }

        // ---------------- Hook 诊断 ----------------
        binding.btnHookSave.setOnClickListener {
            val text = hookDiagText()
            val saved = AppLog.saveToDownloadsNamed(this, text, "jianji-hook")
            toast(
                if (saved != null) "Hook 诊断已保存：${saved.second}"
                else "保存失败（详见日志）"
            )
        }

        binding.btnHookShare.setOnClickListener {
            // 与运行日志一样用**文件**分享（先写进 下载/简记，再分享系统媒体 URI）
            val intent = AppLog.shareFileIntentOf(this, hookDiagText(), "jianji-hook", "简记 Hook 诊断")
            if (intent == null) {
                toast("生成分享失败，请用「保存 Hook 诊断」")
                return@setOnClickListener
            }
            runCatching { startActivity(intent) }
                .onFailure { toast("无法调起分享，请用「保存 Hook 诊断」") }
        }

        // ---------------- 日志只保留当天 ----------------
        binding.swKeepToday.isChecked = Prefs.isLogKeepTodayOnly(this)
        binding.swKeepToday.setOnClickListener {
            val on = binding.swKeepToday.isChecked
            Prefs.setLogKeepTodayOnly(this, on)
            AppLog.i("设置", "日志只保留当天 → ${if (on) "开" else "关"}")
            if (on) AppLog.pruneOldDays(this)
            render()
        }

        binding.btnClear.setOnClickListener {
            AppLog.clear(this)
            render()
            toast("已清空")
        }

        render()
    }

    /** Hook 诊断正文：与「Hook 模块」页看到的诊断一致，便于直接发给开发者 */
    private fun hookDiagText(): String = runCatching {
        RootManager.diagnoseHookEnv(this)
    }.getOrElse {
        AppLog.e("日志", "生成 Hook 诊断失败：${it.message}")
        "生成 Hook 诊断失败：${it.message}"
    }

    private fun render() {
        binding.tvLog.text = AppLog.visibleText(this)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

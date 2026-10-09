package com.jianji.app.ui

import android.os.Bundle
import android.widget.Toast
import com.jianji.app.databinding.ActivityLogBinding
import com.jianji.app.util.AppLog

/**
 * 运行日志页：分享 / 发送文本 / 复制 / 保存到下载 / 清空。
 *
 * 导出设计（多次踩坑后的结论）：
 * - **分享文件**：先写进系统「下载/简记」，再分享**系统媒体 URI**。
 *   以前用 FileProvider（由简记进程提供文件），分享面板一起简记被 MIUI 杀掉，
 *   对方读文件时就报"文件不存在"；换系统 URI 后简记死了也不影响。
 * - **发送文本**：完全不涉及文件，任何应用（QQ/微信/邮件）都收得下 —— 最稳的一条。
 * - **保存到下载**：文件落在 下载/简记/，可从文件管理器直接发送。
 * - **复制全部**：进剪贴板，直接粘贴。
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
            val ok = AppLog.copyToClipboard(this, AppLog.fullText(this))
            toast(if (ok) "已复制全部日志，直接粘贴发送即可" else "复制失败")
        }

        binding.btnSave.setOnClickListener {
            val saved = AppLog.saveToDownloads(this, AppLog.fullText(this))
            toast(
                if (saved != null) "已保存到：${saved.second}（可从文件管理器发送）"
                else "保存失败（详见日志）"
            )
            if (saved != null) AppLog.i("日志", "用户保存成功：${saved.second}")
        }

        binding.btnClear.setOnClickListener {
            AppLog.clear(this)
            render()
            toast("已清空")
        }

        render()
    }

    private fun render() {
        binding.tvLog.text = AppLog.fullText(this)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
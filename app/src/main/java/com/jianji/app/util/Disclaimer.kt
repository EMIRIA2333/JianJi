package com.jianji.app.util

import android.app.Activity
import android.content.DialogInterface
import android.os.Process
import android.view.LayoutInflater
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jianji.app.R

/**
 * 首次启动的使用前警告。
 *
 * 规则（用户明确要求）：
 * 1. **必须下拉看完**才能点「同意」→ 按钮在滚到底部之前是灰色不可点；
 * 2. 「拒绝」→ 应用**自动退出**（不记账、不常驻）；
 * 3. 「同意」→ 正常使用，之后可在「我的 → 使用前警告」里重新查看。
 *
 * 文案同时在 README/PRIVACY 里公开，避免"点了同意却不知道同意了什么"。
 */
object Disclaimer {

    /** 完整警告正文（与「我的」页里重新查看的内容一致） */
    val BODY: String = """
        简记（JianJi）是一个个人学习作品，仅供作者学习与技术研究使用，请自行评估是否用于日常记账。

        一、关于自动记账
        本应用通过系统公开接口（无障碍、通知、短信、LSPosed Hook、root dumpsys）读取支付相关信息，
        不包含任何第三方应用的代码或资源，也不修改第三方应用。使用 Hook / root 等功能可能与部分
        应用的用户协议冲突，由此产生的一切后果由使用者自行承担。

        二、关于联网（仅版本检查）
        本应用提供基于 GitHub Releases 的版本检查。该功能仅查询公开版本号，
        不收集、不上传任何数据（不发送账号、设备标识或账本内容）。
        更新需你自行从 GitHub 下载安装，作者不保证新版本与你的设备或第三方应用的兼容性，
        也不对更新过程中的数据丢失负责。

        三、关于数据
        账本数据仅保存在本机。卸载应用或清除数据会永久删除账本，
        请定期在「我的 → 备份与导出」里导出备份。使用前请备份账本数据。

        四、免责
        软件按 GPL-3.0「按原样」提供，不附带任何担保；作者不对记账错误、漏记、
        数据丢失或任何间接损失负责。请勿将本应用用于绕过支付、破解或伪造数据等用途。

        点击「同意」即表示你已阅读并理解上述内容，并自愿承担使用 Hook/root 等功能
        及自行更新所带来的相关风险；点击「拒绝」将退出应用。
    """.trimIndent()

    /** 是否需要在启动时弹窗 */
    fun needed(activity: Activity): Boolean = !Prefs.isDisclaimerAccepted(activity)

    /**
     * 展示警告（不可取消、不能点外部关闭）。未读到底部时两个按钮都不可点。
     */
    fun show(activity: Activity, onAgree: () -> Unit = {}) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_disclaimer, null, false)
        val sv = view.findViewById<ScrollView>(R.id.svBody)
        val tv = view.findViewById<TextView>(R.id.tvBody)
        val hint = view.findViewById<TextView>(R.id.tvScrollHint)
        tv.text = BODY

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("使用前请阅读")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("同意") { _, _ ->
                Prefs.setDisclaimerAccepted(activity, true)
                AppLog.i("设置", "用户已同意使用前警告")
                onAgree()
            }
            .setNegativeButton("拒绝") { _, _ ->
                AppLog.i("设置", "用户拒绝使用前警告 → 退出应用")
                quit(activity)
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        // 未读到底部前禁用两个按钮
        val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        var unlocked = false
        fun unlockIfAtBottom() {
            val atBottom = !sv.canScrollVertically(1)
            if (atBottom && !unlocked) {
                unlocked = true
                positive?.isEnabled = true
                negative?.isEnabled = true
                hint.text = "已阅读到底部：请选择「同意」或「拒绝」"
            } else if (!atBottom && unlocked) {
                unlocked = false
                positive?.isEnabled = false
                negative?.isEnabled = false
                hint.text = "↓ 请下拉阅读到底部，才能选择「同意」或「拒绝」"
            }
        }
        positive?.isEnabled = false
        negative?.isEnabled = false
        sv.setOnScrollChangeListener { _, _, _, _, _ -> unlockIfAtBottom() }
        sv.post {
            // 内容不足一屏时（大屏/小字号）直接视为已读完
            if (!sv.canScrollVertically(1)) {
                unlocked = true
                positive?.isEnabled = true
                negative?.isEnabled = true
                hint.text = "请选择「同意」或「拒绝」"
            }
        }
    }

    /** 拒绝后彻底退出（不留在后台） */
    private fun quit(activity: Activity) {
        runCatching { activity.finishAffinity() }
        activity.postDelayedKill()
    }

    private fun Activity.postDelayedKill() {
        runCatching {
            android.os.Handler(mainLooper).postDelayed({
                runCatching { Process.killProcess(Process.myPid()) }
            }, 250L)
        }
    }
}

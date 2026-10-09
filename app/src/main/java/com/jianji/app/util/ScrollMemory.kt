package com.jianji.app.util

import android.content.Context
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 记住并恢复页面滚动位置。
 *
 * 用途：**切换主题会重建界面**（系统切换深色模式的固有行为），
 * 不特意记住位置，用户就会看到"设置完回到最顶上"。
 *
 * 两个易错点，这里都处理了：
 * 1. **别用 0 覆盖有效值**：界面刚重建、还没恢复时若触发 onPause，
 *    会把"0"写进去，下次就真的从顶部开始了 → 只有视图已布局且有内容时才保存；
 * 2. **布局未完成时滚动无效** → 先 post，再 postDelayed 兜一次。
 */
object ScrollMemory {

    fun save(ctx: Context, key: String, view: View?) {
        runCatching {
            when (view) {
                is NestedScrollView -> {
                    // 还没布局（高度 0）说明界面刚起来，不覆盖已存的位置
                    if (view.height <= 0) return
                    Prefs.setScrollY(ctx, key, view.scrollY)
                }
                is RecyclerView -> {
                    // 列表还是空的（数据未到）时不覆盖
                    if (view.childCount == 0) return
                    val pos = (view.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
                    Prefs.setScrollY(ctx, key, pos)
                }
                else -> Unit
            }
        }
    }

    fun restore(ctx: Context, key: String, view: View?) {
        val saved = Prefs.scrollY(ctx, key)
        if (saved <= 0 || view == null) return
        val doScroll = {
            runCatching {
                when (view) {
                    is NestedScrollView -> view.scrollTo(0, saved)
                    is RecyclerView ->
                        (view.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(saved, 0)
                    else -> Unit
                }
            }
        }
        view.post { doScroll() }
        // 内容高度可能稍后才稳定（例如统计数字异步刷新），再兜一次
        view.postDelayed({ doScroll() }, 250L)
    }
}
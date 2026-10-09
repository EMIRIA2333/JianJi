package com.jianji.app.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Record
import com.jianji.app.databinding.ActivityTrashBinding
import com.jianji.app.util.ImageStore
import com.jianji.app.util.TrashPolicy

/**
 * 回收站：删除的账单先放这里，可**还原**或**彻底删除**，支持批量操作。
 * 超过保留期（默认 30 天）的记录在打开回收站时自动清理。
 */
class TrashActivity : BaseActivity() {

    private lateinit var binding: ActivityTrashBinding
    private val selected = LinkedHashSet<Long>()
    private var items: List<Record> = emptyList()

    private val adapter by lazy {
        TrashAdapter(
            onToggle = { r -> toggle(r) },
            onLongPress = { r -> showSingleActions(r) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.rvTrash.layoutManager = LinearLayoutManager(this)
        binding.rvTrash.adapter = adapter

        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnRestore.setOnClickListener { confirmRestore() }
        binding.btnPurge.setOnClickListener { confirmPurge() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_PURGE_ALL, 0, "清空回收站")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_PURGE_ALL) {
            confirmPurgeAll()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------- 数据 ----------------

    private fun load() {
        val app = application as App
        app.post {
            // 自动清理超期记录
            val purged = app.dao.purgeTrashBefore(TrashPolicy.expiryCutoff(System.currentTimeMillis()))
            val list = app.dao.trashRecords()
            runOnUiThread {
                items = list
                selected.retainAll(list.map { it.id }.toSet())
                adapter.submit(list, selected)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.tvTrashSummary.text = if (list.isEmpty()) {
                    "回收站为空"
                } else {
                    "共 ${list.size} 条 · 保留 ${TrashPolicy.RETENTION_DAYS} 天后自动清理"
                }
                if (purged > 0) toast("已自动清理 $purged 条超期记录")
                refreshActionBar()
            }
        }
    }

    private fun toggle(r: Record) {
        if (!selected.remove(r.id)) selected.add(r.id)
        adapter.submit(items, selected)
        refreshActionBar()
    }

    private fun toggleSelectAll() {
        if (selected.size == items.size) {
            selected.clear()
            binding.btnSelectAll.text = "全选"
        } else {
            selected.clear()
            selected.addAll(items.map { it.id })
            binding.btnSelectAll.text = "取消全选"
        }
        adapter.submit(items, selected)
        refreshActionBar()
    }

    private fun refreshActionBar() {
        binding.tvSelected.text = "已选 ${selected.size} 项"
        binding.btnRestore.isEnabled = selected.isNotEmpty()
        binding.btnPurge.isEnabled = selected.isNotEmpty()
        binding.btnSelectAll.text = if (items.isNotEmpty() && selected.size == items.size) "取消全选" else "全选"
    }

    // ---------------- 操作 ----------------

    private fun confirmRestore() {
        val ids = selected.toList()
        if (ids.isEmpty()) {
            toast("请先选择要还原的账单")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("还原账单")
            .setMessage("将把选中的 ${ids.size} 条账单还原到账单列表。")
            .setPositiveButton("还原") { _, _ ->
                val app = application as App
                app.post {
                    val n = app.dao.restoreMany(ids)
                    runOnUiThread {
                        selected.clear()
                        toast("已还原 $n 条")
                        load()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmPurge() {
        val ids = selected.toList()
        if (ids.isEmpty()) {
            toast("请先选择要彻底删除的账单")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("彻底删除")
            .setMessage("将永久删除选中的 ${ids.size} 条账单，无法恢复。确定继续吗？")
            .setPositiveButton("彻底删除") { _, _ ->
                val app = application as App
                app.post {
                    // 同时清理这些账单的截图文件，避免越用越占空间
                    ImageStore.delete(this, app.dao.imagePathsByIds(ids))
                    val n = app.dao.purgeMany(ids)
                    runOnUiThread {
                        selected.clear()
                        toast("已彻底删除 $n 条")
                        load()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmPurgeAll() {
        val app = application as App
        app.post {
            val count = app.dao.trashCount()
            runOnUiThread {
                if (count == 0) {
                    toast("回收站已经是空的")
                    return@runOnUiThread
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("清空回收站")
                    .setMessage("将永久删除回收站里的 $count 条账单，无法恢复。")
                    .setPositiveButton("清空") { _, _ ->
                        app.post {
                            // 清空回收站：连同截图文件一起删掉，并清理孤儿图片
                            ImageStore.delete(this, app.dao.trashRecords(limit = 2000).map { it.imagePath })
                            val n = app.dao.purgeAllTrash()
                            ImageStore.cleanupOrphans(this, app.dao.allImagePaths())
                            runOnUiThread {
                                selected.clear()
                                toast("已彻底删除 $n 条")
                                load()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun showSingleActions(r: Record) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${r.category} " + com.jianji.app.util.Money.plain(r.amount))
            .setItems(arrayOf("还原这条", "彻底删除这条")) { _, which ->
                val app = application as App
                if (which == 0) {
                    app.post {
                        app.dao.restoreMany(listOf(r.id))
                        runOnUiThread {
                            toast("已还原")
                            load()
                        }
                    }
                } else {
                    app.post {
                        ImageStore.delete(this, app.dao.imagePathsByIds(listOf(r.id)))
                        app.dao.purgeMany(listOf(r.id))
                        runOnUiThread {
                            toast("已彻底删除")
                            load()
                        }
                    }
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MENU_PURGE_ALL = 1001
    }
}

package com.jianji.app.util

import android.content.Context
import android.net.Uri
import com.jianji.app.App

/**
 * 数据备份：导出 / 导入（CSV、Excel）。所有方法需在后台线程调用。
 */
object BackupHelper {

    enum class Format(val mime: String, val ext: String, val label: String) {
        CSV("text/csv", "csv", "CSV（通用，Excel 可打开）"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", "Excel 工作簿（.xlsx）")
    }

    data class ImportSummary(
        val added: Int,
        val duplicated: Int,
        val invalid: Int,
        val badFormat: Boolean
    )

    fun defaultFileName(format: Format): String =
        "jianji_${System.currentTimeMillis()}.${format.ext}"

    /** 导出全部账单，返回导出条数；失败返回 -1 */
    fun export(context: Context, uri: Uri, format: Format): Int {
        val app = context.applicationContext as App
        val records = app.dao.all()
        val bytes: ByteArray = when (format) {
            Format.CSV -> CsvFormat.buildCsv(records).toByteArray(Charsets.UTF_8)
            Format.XLSX -> XlsxCodec.buildXlsx(records)
        }
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.write(bytes)
                os.flush()
                true
            } ?: false
        }.getOrDefault(false)
        return if (ok) records.size else -1
    }

    /** 导入账单：自动识别 xlsx / csv（xlsx 为 zip 魔数 PK），并跳过与现有记录重复的条目 */
    fun importFrom(context: Context, uri: Uri): ImportSummary {
        val app = context.applicationContext as App
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return ImportSummary(0, 0, 0, true)

        val result: SheetParser.ImportResult = if (XlsxCodec.isXlsx(bytes)) {
            val rows = XlsxCodec.readRows(bytes) ?: return ImportSummary(0, 0, 0, true)
            SheetParser.recordsFromRows(rows)
        } else {
            CsvFormat.parseCsv(bytes.toString(Charsets.UTF_8))
        }
        if (result.badHeader) return ImportSummary(0, 0, result.skippedLines, true)

        var added = 0
        var dup = 0
        result.records.forEach { r ->
            if (app.dao.existsSame(r.time, r.amount, r.type)) dup++ else {
                app.dao.insert(r)
                added++
            }
        }
        return ImportSummary(added, dup, result.skippedLines, false)
    }
}

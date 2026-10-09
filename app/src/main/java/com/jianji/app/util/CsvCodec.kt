package com.jianji.app.util

/**
 * CSV 编解码（纯 Kotlin，可单测）。
 * 简化版 RFC 4180：支持引号包裹、字段内逗号与双引号转义；不含引号内换行（导出时已把换行替换为空格）。
 */
object CsvCodec {

    fun encodeField(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    fun buildLine(fields: List<String>): String = fields.joinToString(",") { encodeField(it) }

    /** 解析一行 CSV 为字段列表；引号不闭合等异常按字面处理，尽量不丢数据 */
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        var fieldStarted = false
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                        else inQuotes = false
                    } else sb.append(c)
                }
                c == '"' -> { inQuotes = true; fieldStarted = true }
                c == ',' -> { out.add(sb.toString()); sb.setLength(0); fieldStarted = false }
                else -> { sb.append(c); fieldStarted = true }
            }
            i++
        }
        if (fieldStarted || sb.isNotEmpty() || out.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

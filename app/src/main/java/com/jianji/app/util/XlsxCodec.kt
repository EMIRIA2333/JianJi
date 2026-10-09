package com.jianji.app.util

import com.jianji.app.core.Record
import com.jianji.app.core.RecordType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 最小可用的 XLSX（Excel）读写，纯 Kotlin + java.util.zip + 字符串解析，无第三方依赖。
 *
 * 写出：inlineStr 字符串 + 数字单元格，表头冻结，列宽预设 —— Excel / WPS / 手机端表格均可打开。
 * 读入：解包 zip → 解析 sharedStrings 与首个 worksheet → 得到二维单元格文本。
 * 只覆盖账单导入导出所需子集（值、共享字符串、内联字符串、列引用），不做公式与样式。
 */
object XlsxCodec {

    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val HEADER = "时间,类型,分类,金额,备注,标签,支付方式,来源"

    fun isXlsx(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte())

    // ---------------- 写出 ----------------

    fun buildXlsx(records: List<Record>, sheetName: String = "账单"): ByteArray {
        val rows = ArrayList<List<Pair<String, Boolean>>>() // (文本, 是否数字)
        rows.add(HEADER.split(",").map { it to false })
        records.forEach { r ->
            rows.add(
                listOf(
                    TimeUtil.formatFull(r.time) to false,
                    (if (r.type == RecordType.INCOME) "收入" else "支出") to false,
                    r.category to false,
                    SheetParser.amountCell(r.amount) to true,
                    r.note to false,
                    r.tag to false,
                    r.payMethod to false,
                    CsvFormat.sourceName(r.source) to false
                )
            )
        }

        val sheet = StringBuilder()
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sheet.append("<worksheet xmlns=\"$NS_MAIN\">")
        sheet.append("<cols>")
            .append("<col min=\"1\" max=\"1\" width=\"21\" customWidth=\"1\"/>")
            .append("<col min=\"2\" max=\"3\" width=\"10\" customWidth=\"1\"/>")
            .append("<col min=\"4\" max=\"4\" width=\"12\" customWidth=\"1\"/>")
            .append("<col min=\"5\" max=\"5\" width=\"28\" customWidth=\"1\"/>")
            .append("<col min=\"6\" max=\"6\" width=\"14\" customWidth=\"1\"/>")
            .append("<col min=\"7\" max=\"7\" width=\"14\" customWidth=\"1\"/>")
            .append("<col min=\"8\" max=\"8\" width=\"14\" customWidth=\"1\"/>")
            .append("</cols>")
        sheet.append("<sheetViews><sheetView workbookViewId=\"0\">")
            .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            .append("</sheetView></sheetViews>")
        sheet.append("<sheetData>")
        rows.forEachIndexed { rIdx, cells ->
            val rowNum = rIdx + 1
            sheet.append("<row r=\"$rowNum\">")
            cells.forEachIndexed { cIdx, (text, isNumber) ->
                val ref = colName(cIdx) + rowNum
                if (isNumber && text.toDoubleOrNull() != null) {
                    sheet.append("<c r=\"$ref\"><v>$text</v></c>")
                } else {
                    sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escapeXml(text))
                        .append("</t></is></c>")
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="$NS_PKG_REL">
<Relationship Id="rId1" Type="$NS_REL/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="$NS_MAIN" xmlns:r="$NS_REL">
<sheets><sheet name="${escapeXml(sheetName)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

        val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="$NS_PKG_REL">
<Relationship Id="rId1" Type="$NS_REL/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rootRels)
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", workbookRels)
            put("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return out.toByteArray()
    }

    // ---------------- 读入 ----------------

    /** 读取首个工作表为二维文本；非 xlsx / 结构异常返回 null */
    fun readRows(bytes: ByteArray): List<List<String>>? {
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val buf = ByteArrayOutputStream()
                        val tmp = ByteArray(8192)
                        while (true) {
                            val n = zip.read(tmp)
                            if (n <= 0) break
                            buf.write(tmp, 0, n)
                        }
                        entries[e.name] = buf.toByteArray()
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (entries.isEmpty()) return null

        val shared = entries.entries.firstOrNull { it.key.equals("xl/sharedStrings.xml", true) }
            ?.value?.toString(Charsets.UTF_8)?.let { parseSharedStrings(it) } ?: emptyList()

        val sheetXml = entries.entries
            .firstOrNull { it.key.equals("xl/worksheets/sheet1.xml", true) }?.value
            ?: entries.entries.firstOrNull { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }?.value
            ?: return null
        return parseSheet(sheetXml.toString(Charsets.UTF_8), shared)
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { si ->
            val texts = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(si.groupValues[1]).map { unescapeXml(it.groupValues[1]) }
            out.add(texts.joinToString(""))
        }
        return out
    }

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { rowMatch ->
            val cells = ArrayList<String>()
            var expectCol = 0
            Regex("<c\\b([^>]*?)(/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
                .findAll(rowMatch.groupValues[1]).forEach { cellMatch ->
                    val attrs = cellMatch.groupValues[1]
                    val body = cellMatch.groupValues[3]
                    val colRef = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                    val col = colRef?.let { colIndex(it) } ?: expectCol
                    while (cells.size < col) cells.add("")
                    cells.add(cellText(attrs, body, shared))
                    expectCol = col + 1
                }
            rows.add(cells)
        }
        return rows
    }

    private fun cellText(attrs: String, body: String?, shared: List<String>): String {
        if (body.isNullOrBlank()) return ""
        val type = Regex("t=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1).orEmpty()
        return when (type) {
            "s" -> {
                val idx = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)
                    ?.groupValues?.get(1)?.trim()?.toIntOrNull()
                if (idx != null && idx in shared.indices) shared[idx] else ""
            }
            "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).map { unescapeXml(it.groupValues[1]) }.joinToString("")
            "str" -> unescapeXml(
                Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()
            )
            else -> Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.trim().orEmpty()
        }
    }

    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    private fun colIndex(name: String): Int {
        var v = 0
        name.forEach { ch -> v = v * 26 + (ch - 'A' + 1) }
        return v - 1
    }

    fun escapeXml(s: String): String = buildString(s.length) {
        s.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

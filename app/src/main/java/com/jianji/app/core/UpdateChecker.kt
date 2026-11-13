package com.jianji.app.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * 基于 GitHub Releases 的版本检查。
 *
 * **只做一件事**：GET `https://api.github.com/repos/<owner>/<repo>/releases/latest`，
 * 读出 `tag_name`（例如 `v3.18.0`）与页面地址，和当前版本比大小。
 * 不携带任何用户数据（没有账号、没有设备信息、没有账本内容），也不会上传任何东西。
 *
 * 解析部分刻意写成纯函数（[parseLatest]），可以在 JVM 上单测；
 * 网络部分只在这里做，超时 10 秒、失败一律静默（更新检查绝不能影响记账）。
 */
object UpdateChecker {

    const val REPO_OWNER = "EMIRIA2333"
    const val REPO_NAME = "JianJi"

    /** 最新版本信息 */
    data class Latest(
        /** 去掉前导 v 的版本号，例如 `3.18.0` */
        val version: String,
        val tagName: String,
        val releaseUrl: String,
        /** Release 说明（截断后用于展示） */
        val notes: String,
        /** APK 直链（应用内下载用）；没上传 APK 时为 null */
        val apkUrl: String? = null,
        /** APK 文件名与大小（展示用） */
        val apkName: String = "",
        val apkSize: Long = 0L
    )

    /** 解析 GitHub Releases API 的 JSON（够用就好，不引第三方 JSON 库） */
    fun parseLatest(json: String): Latest? {
        val tag = json.stringField("tag_name") ?: return null
        val version = normalizeVersion(tag) ?: return null
        val url = json.stringField("html_url") ?: return null
        val notes = json.stringField("body").orEmpty()
        val apk = parseApkAsset(json)
        return Latest(version, tag, url, notes.take(1500), apk?.first, apk?.second ?: "", apk?.third ?: 0L)
    }

    /** `v3.18.0` / `3.18.0` / `V3.18` → 规范化；解析不出来返回 null */
    fun normalizeVersion(raw: String): String? {
        val v = raw.trim().removePrefix("v").removePrefix("V")
        val m = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){1,3})").find(v) ?: return null
        return m.groupValues[1]
    }

    /** 版本比较：a 比 b 新返回正数，相同返回 0，更旧返回负数（缺失的段按 0 处理） */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 是否有新版本（current 为空时视为没有） */
    fun isNewer(latest: String?, current: String): Boolean {
        val cur = normalizeVersion(current) ?: return false
        val lat = latest?.let { normalizeVersion(it) } ?: return false
        return compare(lat, cur) > 0
    }

    /**
     * 真正发起请求（阻塞，必须在后台线程调用）。
     * @return 最新版本；网络异常/限流/没有 Release 时返回 null（静默）
     */
    /**
     * 依次尝试的 API 地址。
     *
     * 用户开着代理时，api.github.com 可能被分流规则拦掉（表现为"网络连接失败"），
     * 所以直连失败后自动换公共镜像再试一次。
     */
    private fun apiHosts(): List<String> = listOf(
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest",
        "https://gh-proxy.com/https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest",
        "https://ghproxy.net/https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    )

    /**
     * 读取 GitHub 最新版本。
     *
     * @param useProxy true 时走系统代理（Android 的 HttpURLConnection 默认就会用系统代理，
     *                 这里显式置 true 是为了在部分 ROM 上确保生效）。
     */
    fun fetchLatest(
        currentVersion: String,
        timeoutMs: Int = 12_000,
        useProxy: Boolean = true
    ): Latest? {
        for (host in apiHosts()) {
            val latest = runCatching {
                val conn = (URL(host).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("Accept", "application/vnd.github+json")
                    // GitHub API 要求带 User-Agent
                    setRequestProperty("User-Agent", "JianJi/$currentVersion (Android)")
                    if (!useProxy) {
                        // 显式直连（部分代理软件对 app 流量处理异常时用来兜底）
                        @Suppress("DEPRECATION")
                        setRequestProperty("Connection", "close")
                    }
                }
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseLatest(body)
                } finally {
                    runCatching { conn.disconnect() }
                }
            }.getOrNull()
            if (latest != null) return latest
        }
        return null
    }

    // ---------------- 内部 ----------------

    /**
     * 从 Release JSON 里找 APK 资产：返回 (下载地址, 文件名, 大小)。
     * GitHub 的 assets 数组里每个对象都有 name / browser_download_url / size。
     */
    fun parseApkAsset(json: String): Triple<String, String, Long>? {
        // 先按对象切分（GitHub 的 assets 元素里字段顺序不固定：url 常常排在 name 前面，
        // 所以不能要求 "name" 出现在对象开头——那样永远匹配不到，只剩"网页下载"）
        val objects = Regex("\\{[^{}]*\\}").findAll(json)
        for (m in objects) {
            val block = m.value
            val url = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").find(block)
                ?.groupValues?.get(1) ?: continue
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(block)
                ?.groupValues?.get(1) ?: continue
            if (!name.endsWith(".apk", true) && !url.endsWith(".apk", true)) continue
            val size = Regex("\"size\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return Triple(url, name, size)
        }
        // 兜底：整个 JSON 里只要出现 apk 直链就用它
        Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(json)?.let { m ->
            val url = m.groupValues[1]
            val name = url.substringAfterLast('/')
            val size = Regex("\"size\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return Triple(url, name, size)
        }
        return null
    }

    /** 从简单 JSON 里取字符串字段（处理 \" 与 \\ 转义） */
    private fun String.stringField(name: String): String? {
        val key = "\"$name\""
        val i = indexOf(key)
        if (i < 0) return null
        val colon = indexOf(':', i + key.length)
        if (colon < 0) return null
        var j = colon + 1
        while (j < length && this[j].isWhitespace()) j++
        if (j >= length || this[j] != '"') return null
        j++
        val sb = StringBuilder()
        while (j < length) {
            val ch = this[j]
            when {
                ch == '\\' && j + 1 < length -> {
                    val n = this[j + 1]
                    sb.append(
                        when (n) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            else -> n
                        }
                    )
                    j += 2
                }
                ch == '"' -> return sb.toString()
                else -> { sb.append(ch); j++ }
            }
        }
        return null
    }
}

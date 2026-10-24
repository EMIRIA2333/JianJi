package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本检查的纯逻辑测试（网络部分不在单测范围内）。
 *
 * 要点：**只读取公开版本号**；解析失败/网络失败一律静默，绝不能影响记账。
 */
class UpdateCheckerTest {

    private val sample = """
        {
          "url": "https://api.github.com/repos/EMIRIA2333/JianJi/releases/1",
          "html_url": "https://github.com/EMIRIA2333/JianJi/releases/tag/v3.18.0",
          "id": 1,
          "tag_name": "v3.18.0",
          "name": "简记 v3.18.0",
          "body": "修复若干问题\n- 退款识别\n- 聊天转账去重",
          "draft": false,
          "prerelease": false
        }
    """.trimIndent()

    @Test
    fun parsesLatestRelease() {
        val latest = UpdateChecker.parseLatest(sample)
        assertNotNull(latest)
        assertEquals("3.18.0", latest!!.version)
        assertEquals("v3.18.0", latest.tagName)
        assertEquals("https://github.com/EMIRIA2333/JianJi/releases/tag/v3.18.0", latest.releaseUrl)
        assertTrue(latest.notes.contains("退款识别"))
    }

    @Test
    fun handlesEscapedCharactersAndMissingBody() {
        val json = """{"tag_name":"v3.19.1","html_url":"https://x/y","body":"a \"quoted\" line\nand slash \\ end"}"""
        val latest = UpdateChecker.parseLatest(json)!!
        assertEquals("3.19.1", latest.version)
        assertTrue(latest.notes.contains("\"quoted\""))
        assertTrue(latest.notes.contains("\\"))

        val noBody = """{"tag_name":"v3.19.2","html_url":"https://x/y"}"""
        assertEquals("", UpdateChecker.parseLatest(noBody)!!.notes)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(UpdateChecker.parseLatest("{}"))
        assertNull(UpdateChecker.parseLatest("not json"))
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"nightly","html_url":"https://x"}"""))
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"v3.18.0"}""")) // 没有 html_url
    }

    @Test
    fun normalizesVersions() {
        assertEquals("3.18.0", UpdateChecker.normalizeVersion("v3.18.0"))
        assertEquals("3.18.0", UpdateChecker.normalizeVersion("V3.18.0"))
        assertEquals("3.18", UpdateChecker.normalizeVersion("v3.18-beta"))
        assertNull(UpdateChecker.normalizeVersion("beta"))
    }

    @Test
    fun comparesVersions() {
        assertTrue(UpdateChecker.compare("3.18.1", "3.18.0") > 0)
        assertTrue(UpdateChecker.compare("3.18.0", "3.17.9") > 0)
        assertTrue(UpdateChecker.compare("4.0", "3.99.99") > 0)
        assertEquals(0, UpdateChecker.compare("3.18.0", "3.18.0"))
        assertEquals(0, UpdateChecker.compare("3.18", "3.18.0")) // 缺失段按 0
        assertTrue(UpdateChecker.compare("3.18", "3.18.1") < 0)
    }

    @Test
    fun newerDetection() {
        assertTrue(UpdateChecker.isNewer("3.18.0", "3.17.0"))
        assertFalse(UpdateChecker.isNewer("3.17.0", "3.17.0"))
        assertFalse(UpdateChecker.isNewer("3.16.9", "3.17.0"))
        assertFalse(UpdateChecker.isNewer(null, "3.17.0"))
        assertFalse(UpdateChecker.isNewer("3.18.0", "?"))
    }
}

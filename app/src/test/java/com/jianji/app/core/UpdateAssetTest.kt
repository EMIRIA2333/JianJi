package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Release 里 APK 资产的解析（应用内下载依赖它） */
class UpdateAssetTest {

    private val json = """
        {
          "tag_name": "v3.19.0",
          "html_url": "https://github.com/EMIRIA2333/JianJi/releases/tag/v3.19.0",
          "body": "notes",
          "assets": [
            {
              "name": "jianji-3.19.0.apk",
              "size": 6691160,
              "browser_download_url": "https://github.com/EMIRIA2333/JianJi/releases/download/v3.19.0/jianji-3.19.0.apk",
              "content_type": "application/vnd.android.package-archive"
            },
            {
              "name": "checksums.txt",
              "size": 64,
              "browser_download_url": "https://example.com/checksums.txt"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun findsApkAsset() {
        val apk = UpdateChecker.parseApkAsset(json)
        assertNotNull(apk)
        assertEquals("jianji-3.19.0.apk", apk!!.second)
        assertEquals(6691160L, apk.third)
        assertEquals(
            "https://github.com/EMIRIA2333/JianJi/releases/download/v3.19.0/jianji-3.19.0.apk",
            apk.first
        )
    }

    @Test
    fun latestCarriesApkUrl() {
        val latest = UpdateChecker.parseLatest(json)!!
        assertEquals("3.19.0", latest.version)
        assertEquals("jianji-3.19.0.apk", latest.apkName)
        assertEquals(6691160L, latest.apkSize)
        assertNotNull(latest.apkUrl)
    }

    @Test
    fun noApkAssetMeansNoDownload() {
        val noAsset = """{"tag_name":"v3.20.0","html_url":"https://x/y","assets":[]}"""
        assertNull(UpdateChecker.parseApkAsset(noAsset))
        assertNull(UpdateChecker.parseLatest(noAsset)!!.apkUrl)
    }
}

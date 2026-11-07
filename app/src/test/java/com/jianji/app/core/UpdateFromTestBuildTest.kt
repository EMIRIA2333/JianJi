package com.jianji.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 验证「测试版的版本号」也能被正确比较（3.21.0-test9 < 3.21.1） */
class UpdateFromTestBuildTest {

    @Test
    fun testBuildSeesNewerRelease() {
        val sb = StringBuilder()
        val installed = "3.21.0-test9"
        val latest = "3.21.1"
        sb.appendLine("normalize($installed) = " + UpdateChecker.normalizeVersion(installed))
        sb.appendLine("normalize($latest) = " + UpdateChecker.normalizeVersion(latest))
        sb.appendLine("compare = " + UpdateChecker.compare(latest, installed))
        sb.appendLine("isNewer = " + UpdateChecker.isNewer(latest, installed))
        File("build-alt/probe-update.txt").apply { parentFile?.mkdirs() }.writeText(sb.toString())
        println(sb)
        assertTrue("从测试版应能发现更高版本的正式版", UpdateChecker.isNewer(latest, installed))
    }
}

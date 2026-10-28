/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 正式代码里挂起读文案一律经 [getStringOffMain]: 直接调资源库的 `getString` 的协程若跑在主线程上排队等资源库的锁,
 * 会与 `stringResource` 首读的 `runBlocking` 互等 (见 [getStringOffMain]). 测试代码不算.
 */
class StringsOffMainGuardTest {
    @Test
    fun `main code reads strings through getStringOffMain`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val forbidden = Regex("""^import org\.jetbrains\.compose\.resources\.(getString|getPluralString|getStringArray)$""", RegexOption.MULTILINE)
        val sourceSet = Regex("""[/\\]src[/\\][A-Za-z]*Main[/\\]""")
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.extension == "kt" && sourceSet.containsMatchIn(it.path) && it.name != "StringsOffMain.kt" }
            .filter { forbidden.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue(File(root, "app/shared/ui-foundation").isDirectory, "没找到仓库根目录: $root")
        assertEquals(emptyList(), offenders)
    }
}

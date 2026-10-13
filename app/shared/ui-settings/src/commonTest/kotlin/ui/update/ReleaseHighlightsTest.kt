/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseHighlightsTest {
    private val body = """
        下方有 QQ 群二维码。

        ## 下载

        | 安装包 | 下载 |
        |---|---|
        | universal | [GitHub][github-android] |

        [//]: # (发版后发现问题时在这里加「## 通知」)

        ## 本次更新

        - 新增「加密直连」：连不上 Bangumi 官方时先用它
        - 按住方向键连续翻海报墙更流畅
        - **Web 控制台**播放卡新增「全屏（同步播放）」：手机直接播电视正在放的这一集

        ----

        - 网盘视频缓存改为分段下载
        - 修复冷启动后「继续观看」经常要等一会儿才出来

        ### 已知问题

        * 画质增强在电视上不可用

        ## 交流群

        - 不该算进来
    """.trimIndent()

    @Test
    fun `features above the rule, minor below, stops at the next heading`() {
        val highlights = parseReleaseHighlights("1.0.6", body)!!
        assertEquals(
            listOf(
                ReleaseNoteItem("新增「加密直连」", "连不上 Bangumi 官方时先用它"),
                ReleaseNoteItem("按住方向键连续翻海报墙更流畅", null),
                ReleaseNoteItem("Web 控制台播放卡新增「全屏（同步播放）」", "手机直接播电视正在放的这一集"),
            ),
            highlights.features,
        )
        assertEquals(listOf("网盘视频缓存改为分段下载", "修复冷启动后「继续观看」经常要等一会儿才出来"), highlights.minor.map { it.title })
        assertTrue(highlights.notice.isEmpty())
    }

    @Test
    fun `without a rule everything is a feature`() {
        val highlights = parseReleaseHighlights("1.0.0", "## 本次更新\n\n- 甲\n- 乙\n")!!
        assertEquals(listOf("甲", "乙"), highlights.features.map { it.title })
        assertTrue(highlights.minor.isEmpty())
    }

    @Test
    fun `no changes section and no notice gives null`() {
        assertNull(parseReleaseHighlights("1.0.0", "- 修复一些问题\n"))
    }

    @Test
    fun `notice section accepts list items and plain lines, skips comments`() {
        val withNotice = body.replace(
            "## 本次更新",
            "## 通知\n\n[//]: # (写给发版人的)\n- 部分索尼电视播 4K 黑屏：先在设置里关掉硬解\n修复版明天发\n\n## 本次更新",
        )
        val highlights = parseReleaseHighlights("1.0.6", withNotice)!!
        assertEquals(
            listOf(
                ReleaseNoteItem("部分索尼电视播 4K 黑屏", "先在设置里关掉硬解"),
                ReleaseNoteItem("修复版明天发", null),
            ),
            highlights.notice,
        )
        assertEquals(3, highlights.features.size)
    }

    @Test
    fun `notice alone is enough`() {
        val highlights = parseReleaseHighlights("1.0.6", "## 通知\n\n- 先别更新\n")!!
        assertEquals(listOf("先别更新"), highlights.notice.map { it.title })
        assertTrue(highlights.features.isEmpty())
    }

    @Test
    fun `colon far into a sentence or inside a link does not split`() {
        assertEquals(
            ReleaseNoteItem("修复从有缓存的一集换到下一集时偶尔选到不相关的视频而且这句话已经很长了：后半句", null),
            releaseNoteItem("修复从有缓存的一集换到下一集时偶尔选到不相关的视频而且这句话已经很长了：后半句"),
        )
        assertEquals(ReleaseNoteItem("说明见 README", null), releaseNoteItem("说明见 [README](https://example.com/a:b)"))
        assertEquals(ReleaseNoteItem("Web 控制台", "新增设置页"), releaseNoteItem("Web 控制台: 新增设置页"))
    }

    @Test
    fun `version order`() {
        assertTrue(isNewerVersion("1.0.6", "1.0.6-alpha06"))
        assertTrue(isNewerVersion("1.0.6-alpha10", "1.0.6-alpha09"))
        assertTrue(isNewerVersion("1.0.7-alpha01", "1.0.6"))
        assertFalse(isNewerVersion("1.0.6-alpha06", "1.0.6"))
        assertFalse(isNewerVersion("1.0.6", "1.0.6"))
    }

    @Test
    fun `stable release shows only itself`() {
        val available = listOf("1.0.6", "1.0.6-alpha06", "1.0.6-alpha01", "1.0.5", "1.0.5-alpha02")
        assertEquals(listOf("1.0.6"), releasesToShow(available, "1.0.6"))
    }

    @Test
    fun `pre-release shows every pre-release of its version up to itself, newest first`() {
        val available = listOf("1.0.6-alpha06", "1.0.6-alpha05", "1.0.6-alpha02", "1.0.6-alpha01", "1.0.5", "1.0.5-alpha02", "1.0.7-alpha01")
        assertEquals(
            listOf("1.0.6-alpha05", "1.0.6-alpha02", "1.0.6-alpha01"),
            releasesToShow(available, "1.0.6-alpha05"),
        )
    }

    @Test
    fun `nothing to show when no release of that version exists`() {
        assertEquals(emptyList(), releasesToShow(listOf("1.0.5"), "1.0.6"))
        assertEquals(emptyList(), releasesToShow(listOf("1.0.5"), "1.0.6-local3"))
    }

    @Test
    fun `notes kept with a version`() {
        assertTrue(isShownWith("1.0.6-alpha01", "1.0.6-alpha06"))
        assertFalse(isShownWith("1.0.6-alpha07", "1.0.6-alpha06"))
        assertFalse(isShownWith("1.0.5", "1.0.6-alpha06"))
        assertFalse(isShownWith("1.0.6-alpha06", "1.0.6"))
        assertTrue(isShownWith("1.0.6", "1.0.6"))
    }
}

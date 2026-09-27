/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Web 控制台各处等待时的进度文字 (应用更新、自动缓存、夸克扫码、导入、时间表、订阅更新、登录倒计时):
 * 网页脚本里写的键要真在页面里、也要在译文表里 (键对不上时英文 / 繁体界面露出简体), 译文不能带进不了 HTML 的字符.
 */
class RemoteProgressTextsTest {
    private val page = renderRemoteControlPage(
        initialTab = "player",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    private val table = REMOTE_I18N_TABLE.associateBy { it.zh }

    /** 网页脚本里 `T('…')` 用到的 */
    private val webKeys = listOf(
        "正在上传：{0}",
        "已查完 {0}/{1} 个数据源 · 已等 {2} / 最长 {3}",
        "正在查找资源：已查完 {0}/{1} 个数据源，已找到 {2} 条",
        "{0} 后过期",
        "已确认，正在登录…",
        "导入中 {0}/{1} 批…",
        "导入中…",
        "已读完 {0}/{1} 天",
        "正在读取… 已读完 {0}/{1} 天",
        "更新中 {0}/{1}…",
        "剩 {0}",
    )

    /** 服务端 `tr("…")` 回给网页的 */
    private val serverKeys = listOf(
        "正在挑选下载线路 {0}/{1}",
        "上一条线路失败，换第 {0} 条线路",
        "正在下载 {0}：{1}",
        "正在校验安装包…",
        "正在写入安装包：{0}",
        "正在同步到存储…",
        "GitHub 连不上，正在查镜像 {0}/{1}",
    )

    @Test
    fun `web keys are used by the page and translated`() {
        for (key in webKeys) {
            assertContains(page, "T('$key'")
            assertTrue(key in table, "译文表里没有「$key」")
        }
    }

    @Test
    fun `server keys are translated`() {
        for (key in serverKeys) assertTrue(key in table, "译文表里没有「$key」")
    }

    @Test
    fun `translations keep the placeholders and stay html safe`() {
        val placeholder = Regex("""\{\d}""")
        for (row in REMOTE_I18N_TABLE) {
            val expected = placeholder.findAll(row.zh).map { it.value }.toSet()
            for (translated in listOf(row.en, row.hk, row.tw)) {
                assertEquals(expected, placeholder.findAll(translated).map { it.value }.toSet(), "「${row.zh}」→「$translated」")
                assertFalse(translated.any { it == '"' || it == '<' || it == '&' }, "「$translated」带了进不了 HTML 属性的字符")
            }
        }
    }

    @Test
    fun `countdowns and transfer text helpers are shared by the page scripts`() {
        assertContains(page, "window.countdown = function (ms)")
        assertContains(page, "window.fmtTransfer = function (done, total, bps)")
    }
}

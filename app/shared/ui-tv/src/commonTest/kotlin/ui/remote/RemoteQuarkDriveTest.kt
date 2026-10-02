/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 「从夸克网盘挑」面板: 页面用到的文案都在, 都有译文.
 */
class RemoteQuarkDriveTest {
    private val page = renderRemoteControlPage(
        initialTab = "player",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    private val table = REMOTE_I18N_TABLE.associateBy { it.zh }

    private val webKeys = listOf(
        "从夸克网盘挑…",
        "文件夹",
        "当作这一集播放",
        "忘掉这个位置",
        "搜索网盘",
        "番名或文件夹名",
        "自动匹配对不上时在这里找：点视频当作当前这一集播放；或者进到放这部番的文件夹，按「就是这个文件夹」，之后每一集都从这里找。记下的位置跟着这个夸克账号。",
        "搜索",
        "浏览网盘根目录",
        "这部番的搜索名，点一下直接搜",
        "还没登录夸克",
        "先在「数据源」页登录夸克网盘",
        "这部番记下的网盘位置",
        "还没有记下",
        "网盘根目录",
        "就是这个文件夹",
        "搜索结果",
        "加载中…",
        "还有 {0} 项没列出",
        "先输入要搜的名字",
        "忘掉这个位置？「夸克网盘」就不再从这里给这部番找资源",
    )

    private val serverKeys = listOf(
        "夸克没登录或登录已失效，请在「数据源」页登录夸克网盘",
        "当作第 {0} 集",
        "先输入要搜的名字",
        "没有找到文件夹或视频",
        "第 {0} 季第 {1} 集",
        "第 {0} 集",
        "找不到这个文件，请重新搜索",
        "找不到这个文件夹，请重新搜索",
        "这个文件夹里没认出这部番的剧集（文件名认不出集号）。可以点进去挑一个文件当作这一集",
        "已记下：这部番在「{0}」里，认出第 {1} 集，没有第 {2} 集",
        "已记下：这部番在「{0}」里，认出第 {1} 集，正在电视上播放第 {2} 集",
        "。正在重新搜索一次，让「夸克网盘」加入，电视上的视频会重新加载",
        "已记下这个文件是第 {0} 集，正在电视上播放",
        "正在电视上播放",
        "已删除",
    )

    @Test
    fun `web keys are used by the page and translated`() {
        for (key in webKeys) {
            assertContains(page, "T('$key'")
            assertTrue(key in table, "译文表里没有「$key」")
        }
        // 面板标题是静态 HTML, 由 translateStatic 整段替换
        assertContains(page, ">从夸克网盘挑<")
        assertTrue("从夸克网盘挑" in table)
    }

    @Test
    fun `server keys are translated`() {
        for (key in serverKeys) assertTrue(key in table, "译文表里没有「$key」")
    }
}

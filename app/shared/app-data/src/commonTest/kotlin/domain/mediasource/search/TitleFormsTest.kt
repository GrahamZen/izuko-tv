/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlin.test.Test
import kotlin.test.assertEquals

/** 名字在站点上的几种写法 ([TitleForms]). */
class TitleFormsTest {
    @Test
    fun `compact form ignores punctuation and spaces`() {
        assertEquals(
            TitleForms.compact("Smoking Behind the Supermarket with You"),
            TitleForms.compact("Smoking.Behind.the.Supermarket.With.You"),
        )
    }

    @Test
    fun `main title drops the season and the subtitle`() {
        assertEquals("无职转生", TitleForms.mainTitle("无职转生 第三季 ～到了异世界就拿出真本事～"))
        assertEquals("葬送的芙莉莲", TitleForms.mainTitle("葬送的芙莉莲 第二季"))
        assertEquals("Smoking Behind the Supermarket with You", TitleForms.mainTitle("Smoking Behind the Supermarket with You"))
        assertEquals("Re:ゼロから始める異世界生活", TitleForms.mainTitle("Re:ゼロから始める異世界生活 3rd season"))
        assertEquals("无职转生", TitleForms.mainTitle("无职转生Ⅱ ～到了异世界就拿出真本事～"))
    }

    @Test
    fun `decorations in names become spaces without dropping words`() {
        assertEquals("浪客剑心 明治剑客浪漫谭 京都动乱", TitleForms.withoutDecorations("浪客剑心 －明治剑客浪漫谭－ 京都动乱"))
        assertEquals("死神 千年血战篇 祸进谭", TitleForms.withoutDecorations("死神 千年血战篇-祸进谭"))
        assertEquals("86 不存在的战区 第2部分", TitleForms.withoutDecorations("86 -不存在的战区- 第2部分"))
        assertEquals("IDOLiSH7 偶像星愿 Second BEAT", TitleForms.withoutDecorations("IDOLiSH7-偶像星愿- Second BEAT"))
        assertEquals("我推的孩子", TitleForms.withoutDecorations("【我推的孩子】"))
        // 英文里的标点照旧
        for (name in listOf("Re:Zero kara Hajimeru Isekai Seikatsu", "Fate/strange Fake", "Kaguya-sama wa Kokurasetai", "葬送的芙莉莲")) {
            assertEquals(name, TitleForms.withoutDecorations(name))
        }
    }

    @Test
    fun `spaces are removed only between chinese or japanese characters`() {
        assertEquals("继怪怪守护神", TitleForms.withoutSpacesBetweenCjk("继 怪怪守护神"))
        assertEquals("BLEACH 千年血戦篇", TitleForms.withoutSpacesBetweenCjk("BLEACH 千年血戦篇"))
        assertEquals("つぐもも第2期", TitleForms.withoutSpacesBetweenCjk("つぐもも 第2期"))
        assertEquals("Re:Zero kara", TitleForms.withoutSpacesBetweenCjk("Re:Zero kara"))
    }
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import kotlin.test.Test
import kotlin.test.assertEquals

class TvSummaryFirstSentenceTest {
    @Test
    fun `取第一段的第一句`() {
        assertEquals(
            "打倒魔王的勇者一行人之一、精灵魔法使芙莉莲。",
            tvSummaryFirstSentence("打倒魔王的勇者一行人之一、精灵魔法使芙莉莲。她将与人类的短暂相处铭记于心。\n第二段"),
        )
    }

    @Test
    fun `段首全角空格与空行跳过`() {
        assertEquals("这是一部讲述少年成长的长篇故事。", tvSummaryFirstSentence("\r\n　　这是一部讲述少年成长的长篇故事。后面还有。"))
    }

    @Test
    fun `太短的一句接着带下一句`() {
        assertEquals("时间是2099年。人类已经移居到了火星。", tvSummaryFirstSentence("时间是2099年。人类已经移居到了火星。第三句。"))
    }

    @Test
    fun `句末的右引号收进这一句`() {
        assertEquals("「我一定会成为海贼王的男人！」", tvSummaryFirstSentence("「我一定会成为海贼王的男人！」少年这样说道。"))
    }

    @Test
    fun `没有句末标点就整段 - 空简介为空`() {
        assertEquals("没有标点的一整段简介", tvSummaryFirstSentence("没有标点的一整段简介\n第二段。"))
        assertEquals("", tvSummaryFirstSentence("  \n "))
    }
}

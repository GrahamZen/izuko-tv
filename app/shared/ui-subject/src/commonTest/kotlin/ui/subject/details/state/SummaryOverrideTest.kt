/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Bangumi 简介整段无中文才用 TMDB 中文简介替换"这条判据. 它两边都会出错且都很难看:
 * 判松了会把 Bangumi 自带的中文翻译顶掉, 判紧了则中文用户看到整段日文.
 */
class SummaryOverrideTest {
    @Test
    fun `全日文简介 - 该替换`() {
        assertTrue(
            summaryHasNoChinese(
                """
                大好きな家族がいて、親友がいて、時には笑い、時には泣く、そんなどこにでもある日常。
                見滝原中学校に通う、普通の中学二年生・鹿目まどかも、そんな日常の中で暮らす一人。
                ある日、彼女に不思議な出会いが訪れる。
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `日文简介里混进一行全汉字短句 - 仍该替换`() {
        // さわらないで小手指くん (bgm 541547): 首行「小手指向陽・高校1年生。」没有任何假名 (那个 ・ 是
        // 片假名中点), 与中文句子字符上完全一样 —— 逐行判会把它当中文行, 一行否决掉整段替换.
        // 中文字数 10 / 原文字数 141 = 0.07, 按字数比就认得出这只是零头.
        assertTrue(
            summaryHasNoChinese(
                """
                小手指向陽・高校1年生。
                特技は、気持ちよくさせすぎちゃう超絶マッサージ！！
                学費を稼ぐため、スポーツ強豪校・星和大付属高校の寮の管理人となった向陽。
                そこで出会ったのは、曲者揃いの美少女アスリート達だった！
                医学部特待生をＧＥＴするため、向陽は彼女達の「心身のケア」に勤しむことに！
                過激すぎるのに、尊すぎる……！？
                新時代のマッサージラブコメ、ここに悶絶！！
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `中日混排 - 中文压成一整段也不替换`() {
        // ONE PIECE (bgm 975): 中文翻译是一行 217 字的长段, 日文原文拆成十几行 —— 行数比只有 0.15,
        // 按行数判会误替换; 字数比 0.68, 说明中文翻译是完整的, 不该顶掉.
        assertFalse(
            summaryHasNoChinese(
                """
                有个男人，他拥有世界上一切财富、名望和权势，他就是「海盗王」高路德·罗杰。他在临死前留下一句话，让全世界的人都涌向大海：想要我的财宝吗？想要的话可以全部给你，去找吧，我把所有的财宝都放在那里。
                [简介原文]
                世は大海賊時代ー
                海賊王を夢見る少年ルフィは、
                ゴムゴムの実を食べてゴム人間になってしまった。
                仲間を集めて、伝説の秘宝を探しに大海原へ。
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `中日混排 - 逐句对译不替换`() {
        assertFalse(
            summaryHasNoChinese(
                """
                [中文简介]
                出生良好家庭，亲朋好友团聚，时哭时笑，这是谁都拥有的日常生活。
                市立见泷原中学的平凡初二女生鹿目圆，就是其中一位。
                一天，一个不可思议的人出现在她眼前。
                [简介原文]
                大好きな家族がいて、親友がいて、時には笑い、時には泣く、そんなどこにでもある日常。
                見滝原中学校に通う、普通の中学二年生・鹿目まどかも、そんな日常の中で暮らす一人。
                ある日、彼女に不思議な出会いが訪れる。
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `整段中文 - 不替换`() {
        assertFalse(
            summaryHasNoChinese(
                """
                迷宫饭，不是吃就是被吃…
                莱欧斯是一名冒险者，在一次探险中，迷宫深处的赤龙吃掉了他的妹妹。
                冒险者啊，以袭来的魔物为食，通关迷宫吧！
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `空简介与纯英文 - 该替换`() {
        // 空: Ani 服务器有些条目 summary 就是空的, 正是最需要 TMDB 中文简介顶上的情形
        assertTrue(summaryHasNoChinese(""))
        assertTrue(summaryHasNoChinese("   \n\n  "))
        assertTrue(summaryHasNoChinese("Kouyou Kotesashi, a sports doctor with massage skills, enrolls at high school."))
    }
}

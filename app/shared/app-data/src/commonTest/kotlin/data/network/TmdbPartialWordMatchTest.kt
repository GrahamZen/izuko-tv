/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用例都取自对应表的自动命中: 前一组是错配, 后一组是写法不同但对的. */
class TmdbPartialWordMatchTest {
    private fun partial(
        query: String,
        title: String,
        ownName: String = query,
        ownDate: String? = null,
        resultDate: String? = null,
    ) = isTmdbPartialWordHit(query, listOf(title), TmdbOwnNames(listOf(ownName), ownDate), resultDate)

    @Test
    fun `query is the head of a longer katakana word`() {
        assertTrue(partial("ミニドラ", "ミニドラマ　ざんねんないきもの事典"))
        assertTrue(partial("ガイ", "強殖装甲ガイバー"))
        assertTrue(partial("キャプテン", "キャプテンフューチャー"))
    }

    @Test
    fun `query is the tail of a longer hiragana word`() {
        assertTrue(partial("いろは", "花咲くいろは", ownName = "いろは 散りぬるを・・・"))
        assertTrue(partial("るすばん", "はじめてのおるすばん"))
    }

    @Test
    fun `query is the head of a longer latin word`() {
        assertTrue(partial("Leg", "The Legend of Korra"))
        assertTrue(partial("A5", "PERSONA5 the Animation"))
        assertTrue(partial("Vent", "The Venture Bros."))
    }

    @Test
    fun `truncated query hitting another work is partial`() {
        assertTrue(partial("なりヒロ", "巨大ヒロイン（R）　巨大ふたなりヒロイン　女体化性下僕 2", ownName = "なりヒロwww"))
        assertTrue(partial("ゴール", "黄金戦士ゴールドライタン", ownName = "ゴールFH"))
    }

    @Test
    fun `same title with a dash or symbol is whole`() {
        assertFalse(partial("ガイ", "ガイ-GUY-"))
        assertFalse(partial("OVAL×OVER", "OVALxOVER"))
        assertFalse(partial("十兵衛ちゃん -ラブリー眼帯の秘密-", "十兵衛ちゃん ーラブリー眼帯の秘密"))
    }

    @Test
    fun `title written without the space is whole`() {
        assertFalse(partial("エレメンタル ジェレイド", "エレメンタルジェレイド"))
        assertFalse(partial("テンリトル", "テンリトルガルフォース", ownName = "テンリトル ガルフォース"))
    }

    @Test
    fun `parent title contained in own name is whole`() {
        assertFalse(partial("爆転シュート ベイブレード 2002", "爆転シュートベイブレード"))
        assertFalse(partial("ASTRO BOY", "ASTROBOY 鉄腕アトム", ownName = "ASTRO BOY 鉄腕アトム〜月面基地・消えたロボットの謎〜"))
    }

    @Test
    fun `one standalone token is enough`() {
        assertFalse(partial("NINKU -忍空-", "Ninkuu-忍空-"))
    }

    @Test
    fun `kanji neighbours do not glue`() {
        assertFalse(partial("物語", "化物語"))
    }

    @Test
    fun `same date keeps a partial title`() {
        assertFalse(partial("ぐだぐだオーダー", "年忘れぐだぐだオーダー2016", ownDate = "2016-12-31", resultDate = "2016-12-31"))
        assertTrue(partial("ぐだぐだオーダー", "年忘れぐだぐだオーダー2016", ownDate = "2016-12-31", resultDate = "2017-03-01"))
    }

    @Test
    fun `token only found across a separator is not judged`() {
        assertFalse(partial("Candy boy", "キャンディ ボーイ"))
    }
}

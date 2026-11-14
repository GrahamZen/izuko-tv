/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.domain.together.TogetherInvite
import me.him188.ani.app.domain.together.TogetherRoomId
import me.him188.ani.app.ui.together.CONSOLE_TOGETHER_ANCHOR
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTogetherTest {
    @Test
    fun `the card script talks to the together routes`() {
        assertContains(TOGETHER_SCRIPT, "window.getJson('api/together')")
        assertContains(TOGETHER_SCRIPT, "act('api/together/join', { number: number, password: password }")
        // 粘贴进来的邀请整段交给服务端认
        assertContains(TOGETHER_SCRIPT, "act('api/together/join', { invite: text }")
        assertContains(TOGETHER_SCRIPT, "act('api/together/create', {}")
        assertContains(TOGETHER_SCRIPT, "act('api/together/password', {}")
        // 复制邀请是卡片上直接的一颗按钮, 卡片里没有二维码
        assertContains(TOGETHER_SCRIPT, "button('copy', T('复制邀请'), true)")
        assertFalse("invite.svg" in TOGETHER_SCRIPT)
        assertContains(TOGETHER_SCRIPT, "act('api/together/follow'")
        assertContains(TOGETHER_SCRIPT, "act('api/together/leave'")
        // 房主在别人那一行转让房主, 先问一句; 被请来临时当房主的电视没有「更换密码」
        assertContains(TOGETHER_SCRIPT, "act('api/together/transfer', { member: id }")
        assertContains(TOGETHER_SCRIPT, "if (!window.confirm(T('将房主转让给 {0}？转让后此电视将成为成员。', name))) return;")
        assertContains(TOGETHER_SCRIPT, "s.owner ? button('password'")
        // 房主离开会把所有人请出去, 先问一句
        assertContains(TOGETHER_SCRIPT, "if (wasHost && !window.confirm(")
        // 成员卡片标出这台电视自己 (state 里的 self)
        assertContains(TOGETHER_SCRIPT, "m.id === s.self")
        // 成员在做什么、这台电视的处境都由电视算好给 (与电视弹窗同一套), 页面只管照着写字
        assertContains(TOGETHER_SCRIPT, "switch (m.activity)")
        assertContains(TOGETHER_SCRIPT, "switch (s.situation)")
    }

    @Test
    fun `card texts are translated`() {
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        val texts = Regex("""(?<![\w.])T\('([^']+)'""").findAll(TOGETHER_SCRIPT).map { it.groupValues[1] }.toList() +
            // 卡片标题写在页面里, 靠整页翻译; 其余是服务端回的
            listOf(
                "一起看", "无法连接服务，请检查网络后重试。", "已在房间中，请先离开当前房间。", "正在加入房间，请稍候。", "连接超时，请重试",
                "密码不正确，请重新输入。", "未能连接到房主。请检查房间号，并确认房主已开启房间。", "无法完成操作，请重试。",
            )
        assertTrue(texts.size > 20)
        for (t in texts) assertTrue(t in keys, "没有译文：$t")
        // 本功能的原文不与表里已有的重复 (重复时后登记的那份译文会盖掉先前的)
        val counts = REMOTE_I18N_TABLE.groupingBy { it.zh }.eachCount()
        for (t in togetherTexts()) assertEquals(1, counts[t.zh], "重复的原文：${t.zh}")
    }

    @Test
    fun `the qr code in the tv dialog opens this card`() {
        assertContains(TOGETHER_SCRIPT, "location.hash === '$CONSOLE_TOGETHER_ANCHOR'")
        assertContains(TOGETHER_SCRIPT, "window.showTab('player')")
    }

    @Test
    fun `the invite the console hands out joins the same room`() {
        val room = TogetherRoomId("28689131", "482193")
        val invite = RemoteTogether.inviteText(room)
        assertContains(invite, "2868 9131")
        assertContains(invite, "482 193")
        assertEquals(room, TogetherInvite.parse(invite))
    }
}

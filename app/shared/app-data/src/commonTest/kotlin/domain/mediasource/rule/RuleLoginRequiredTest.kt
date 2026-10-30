/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuleLoginRequiredTest {
    @Test
    fun `a login hint in the json reply is found`() {
        // 实测的取流接口: 外层说操作成功, 里面说不能播、请先登录
        val reply = """{"code":200,"message":"操作成功","data":{"episodeId":6858,"canPlay":false,"message":"请先登录","playUrl":null},"success":true}"""
        assertEquals("请先登录", RuleLoginRequired.messageOf(reply))
        assertEquals("Login required", RuleLoginRequired.messageOf("""{"error":"Login required"}"""))
        assertEquals("未登陆", RuleLoginRequired.messageOf("""{"list":[{"err_msg":"未登陆"}]}"""))
    }

    @Test
    fun `other replies are not a login hint`() {
        // 别的原因播不了
        assertNull(RuleLoginRequired.messageOf("""{"data":{"canPlay":false,"message":"请求来源不受信任"}}"""))
        // 不是提示字段
        assertNull(RuleLoginRequired.messageOf("""{"title":"登录之后","url":""}"""))
        // 网页上的登录入口
        assertNull(RuleLoginRequired.messageOf("""<html><a href="/login">登录</a></html>"""))
        // 不完整的 JSON
        assertNull(RuleLoginRequired.messageOf("""{"message":"请先登录""""))
    }
}

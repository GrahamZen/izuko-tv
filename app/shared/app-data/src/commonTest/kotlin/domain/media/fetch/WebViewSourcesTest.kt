/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.domain.media.fetch.WebViewSources.Use
import me.him188.ani.app.domain.mediasource.rule.RuleConfig
import me.him188.ani.app.domain.mediasource.rule.RuleMediaSourceArguments
import me.him188.ani.app.domain.mediasource.rule.RuleStep
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSourceArguments
import me.him188.ani.app.domain.mediasource.web.SelectorSearchConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class WebViewSourcesTest {
    private fun rule(vararg play: RuleStep) = RuleMediaSourceArguments(name = "rule", rule = RuleConfig(play = play.toList()))

    @Test
    fun `rule sources by their play stage`() {
        // 空的播放段直接播剧集地址
        assertEquals(null, WebViewSources.useOf(rule()))
        assertEquals(null, WebViewSources.useOf(rule(RuleStep.Fetch(), RuleStep.MacCmsPlayer())))
        assertEquals(Use.ALWAYS, WebViewSources.useOf(rule(RuleStep.Sniff(goal = RuleStep.Sniff.GOAL_VIDEO))))
        // 取页面的嗅探是普通请求
        assertEquals(null, WebViewSources.useOf(rule(RuleStep.Sniff(goal = RuleStep.Sniff.GOAL_HTML))))
        assertEquals(
            Use.FALLBACK,
            WebViewSources.useOf(
                rule(RuleStep.First(listOf(listOf(RuleStep.Fetch(), RuleStep.MacCmsPlayer()), listOf(RuleStep.Sniff())))),
            ),
        )
    }

    @Test
    fun `selector sources need the web view unless direct resolving is configured`() {
        fun selector(requestUrl: String) = SelectorMediaSourceArguments(
            name = "selector", description = "", iconUrl = "",
            searchConfig = SelectorSearchConfig.Empty.copy(resolveVideo = SelectorSearchConfig.ResolveVideoConfig(requestUrl = requestUrl)),
        )
        assertEquals(Use.ALWAYS, WebViewSources.useOf(selector("")))
        assertEquals(null, WebViewSources.useOf(selector("https://example.com/api?url={pageUrl}")))
    }

    @Test
    fun `fallback sources count only when recent plays mostly sniffed`() {
        val uses = mapOf("always" to Use.ALWAYS, "fallback-new" to Use.FALLBACK, "fallback-direct" to Use.FALLBACK, "fallback-web" to Use.FALLBACK)
        val observed = mapOf("always" to false, "fallback-direct" to false, "fallback-web" to true, "direct-but-sniffed" to true)
        // 没播过的先直连源照普通源排, 真要嗅探时再改选 (见 WebViewDeferral)
        assertEquals(
            setOf("always", "fallback-web", "direct-but-sniffed"),
            WebViewSources.of(uses, observed),
        )
    }
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.rule.RuleMediaSourceArguments
import me.him188.ani.app.domain.mediasource.rule.RuleStep
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSourceArguments

/**
 * 解析资源时会不会用到 WebView 嗅探. 嗅探在电视上要几秒到几十秒, 还常常嗅不到; 自动选源只拿可能用到它的数据源
 * ([MediaSelectorSourceTiers.webViewSources]) 兜底: 别的源都查完、没有能直接取到地址的资源时才选 (见 `MediaAutoSelector`).
 */
internal object WebViewSources {
    enum class Use {
        /** 一定要嗅探: 网页抓取源没配直连取流, 规则源播放段直接嗅探. */
        ALWAYS,

        /** 先走直连, 取不到才嗅探 (规则源播放段分支里的嗅探): 看画像里最近几次实际用没用到. */
        FALLBACK,
    }

    /** 这个数据源 (配置 [arguments]) 解析资源时会不会用到 WebView; 用不到为 `null`. */
    fun useOf(arguments: MediaSourceArguments): Use? = when (arguments) {
        is SelectorMediaSourceArguments -> if (arguments.searchConfig.resolveVideo.enabled) null else Use.ALWAYS
        is RuleMediaSourceArguments -> useOf(arguments.rule.play)
        else -> null
    }

    private fun useOf(steps: List<RuleStep>): Use? = when {
        steps.any { it is RuleStep.Sniff && it.goal == RuleStep.Sniff.GOAL_VIDEO } -> Use.ALWAYS
        steps.any { step -> step is RuleStep.First && step.branches.any { useOf(it) != null } } -> Use.FALLBACK
        else -> null
    }

    /**
     * 自动选源当作要 WebView 的数据源: 一定要嗅探的, 以及画像说最近大多要嗅探的.
     * 先直连的 ([Use.FALLBACK]) 画像没记过的 (没播过) 不算: 选源时照普通源排, 解析时真要转去嗅探再看手上有没有直连的资源可换
     * (见 `WebViewDeferral`), 那次也记进画像.
     *
     * @param uses 各数据源的 [useOf]
     * @param observed 画像记下的最近几次是不是大多靠 WebView 嗅探 (见 `SourceProfiles.webViewUsage`)
     */
    fun of(uses: Map<String, Use>, observed: Map<String, Boolean>): Set<String> = buildSet {
        for ((id, use) in uses) if (use == Use.ALWAYS) add(id)
        for ((id, webView) in observed) if (webView) add(id)
    }
}

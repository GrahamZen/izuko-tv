/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.mediasource.web.PageEvaluator
import me.him188.ani.app.domain.mediasource.web.captcha.UnsupportedCaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceCookieJar
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceIdentityRegistry
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.ktor.asScopedHttpClient

/**
 * 测试用的规则源: 搜 `/search/{keyword}/`, 结果里每个 `.result a` 是一个条目, 条目页里 `.list` 的每个链接是一集.
 * 验证码会话的后台任务放在 [sessionScope] (不放进测试调度器), 测完要取消.
 */
internal fun createTestRuleMediaSource(
    engine: MockEngine,
    repository: SelectorMediaSourceEpisodeCacheRepository,
    sessionScope: CoroutineScope,
    mediaSourceId: String = "test-source",
): RuleMediaSource {
    val client = HttpClient(engine).asScopedHttpClient()
    return RuleMediaSource(
        mediaSourceId = mediaSourceId,
        config = MediaSourceConfig(
            serializedArguments = MediaSourceConfig.serializeArguments(
                RuleMediaSourceArguments.serializer(),
                RuleMediaSourceArguments(
                    name = "test",
                    rule = RuleConfig(
                        baseUrl = "https://example.com",
                        search = listOf(RuleStep.Fetch(url = "/search/{keyword}/"), RuleStep.Subjects(list = listOf(".result a"))),
                        detail = listOf(RuleStep.Fetch(), RuleStep.Episodes(lists = listOf(".list"))),
                    ),
                ),
            ),
        ),
        client = client,
        repository = repository,
        sessionManager = WebSessionManager(
            browserFactory = UnsupportedCaptchaBrowserFactory,
            evaluator = PageEvaluator(),
            cookieJar = WebSourceCookieJar(),
            identityRegistry = WebSourceIdentityRegistry(),
            client = client,
            backgroundScope = sessionScope,
        ),
    )
}

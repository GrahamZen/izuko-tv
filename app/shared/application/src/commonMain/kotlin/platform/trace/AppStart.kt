/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform.trace

import io.ktor.client.request.get
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.ServerListFeature
import me.him188.ani.app.domain.foundation.ServerListFeatureConfig
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.withValue
import me.him188.ani.app.domain.settings.ServiceConnectionTester
import me.him188.ani.app.domain.settings.ServiceConnectionTester.Service
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.StartupTimeMonitor
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.bangumi.BangumiClientImpl
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.AppStart
import me.him188.ani.utils.analytics.IAnalytics
import me.him188.ani.utils.analytics.recordEvent

// 统计连接各个服务器的速度
suspend fun IAnalytics.recordAppStart(startupTimeMonitor: StartupTimeMonitor) {
    // 测官方直连, 不装镜像特性 (只列 UA 与服务器清单, 其余特性不装): 装上的话启动高峰里这两个探测一超时,
    // 就会换到镜像并被当成「官方连不上」, 驱动「官方连不上时用镜像」自动切换 —— 统计不该左右线路.
    val client = GlobalKoin.get<HttpClientProvider>().get(
        setOf(
            UserAgentFeature.withValue(ScopedHttpClientUserAgent.ANI),
            ServerListFeature.withValue(ServerListFeatureConfig.Default),
        ),
    )

    val bangumiClient = BangumiClientImpl(client)
    val tester = ServiceConnectionTester(
        listOf(
            Service("bangumi") {
                bangumiClient.testConnectionMaster() == ConnectionStatus.SUCCESS
            },
            Service("bangumi_next") {
                bangumiClient.testConnectionNext() == ConnectionStatus.SUCCESS
            },
        ),
        Dispatchers.Default,
    )
    tester.testAll()
    val results = tester.results.first()

    recordEvent(AppStart) {
        putAll(startupTimeMonitor.getMarks())
        put("total_time", startupTimeMonitor.getTotalDuration().inWholeMilliseconds)

        results.idToStateMap.forEach { (key, value) ->
            put(
                "server_connectivity_$key",
                when (value) {
                    is ServiceConnectionTester.TestState.Error,
                    ServiceConnectionTester.TestState.Failed -> false

                    is ServiceConnectionTester.TestState.Success -> true

                    ServiceConnectionTester.TestState.Idle -> null
                    ServiceConnectionTester.TestState.Testing -> null
                },
            )
        }
    }
}

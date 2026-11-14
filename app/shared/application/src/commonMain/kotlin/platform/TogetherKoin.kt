/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.foundation.withValue
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.app.domain.together.ChannelTogetherRoomGateway
import me.him188.ani.app.domain.together.PlaybackAutomationGate
import me.him188.ani.app.domain.together.TogetherBrokerList
import me.him188.ani.app.domain.together.TogetherChannelFactory
import me.him188.ani.app.domain.together.TogetherManager
import me.him188.ani.app.domain.together.TogetherPlaybackBridge
import me.him188.ani.app.domain.together.TogetherProfile
import me.him188.ani.app.domain.together.TogetherProfileProvider
import me.him188.ani.app.domain.together.TogetherRoomGateway
import me.him188.ani.app.domain.together.TogetherWebSocketsFeature
import org.koin.core.module.Module

/**
 * 一起看 (docs/contributing/code/together.md)。
 *
 * - 连公共代理的客户端用 [ScopedHttpClientUserAgent.NONE] 那一档: 握手里不带应用的 User-Agent, 代理认不出是哪个应用。
 * - 房间里显示的名字取 Bangumi 的昵称; 没登录、或一时取不到时留空, 由界面显示成「观众」。别的资料不往房间里发。
 * - 「播放页是不是栈顶那一页、应用在不在前台」取 [PlaybackActivity] 的。
 * - [TogetherManager] 随应用启动: 回到上次没离开的房间。
 * - 连哪些代理取仓库里的清单 [TogetherBrokerList] (包里不带代理地址); 拉清单用普通的客户端, 与拉别的清单一样。
 */
internal fun Module.together(coroutineScope: CoroutineScope) {
    single<TogetherBrokerList> {
        TogetherBrokerList(get<SettingsRepository>().togetherBrokerCache, { get<HttpClientProvider>().get() }, coroutineScope)
    }
    single<TogetherChannelFactory> {
        val provider = get<HttpClientProvider>()
        val client by lazy {
            provider.get(
                setOf(
                    UserAgentFeature.withValue(ScopedHttpClientUserAgent.NONE),
                    TogetherWebSocketsFeature.withValue(true),
                ),
            )
        }
        val brokers = get<TogetherBrokerList>()
        TogetherChannelFactory(client = { client }, brokerUrls = { brokers.brokersToConnect() })
    }
    single<TogetherRoomGateway> { ChannelTogetherRoomGateway(opener = get<TogetherChannelFactory>(), scope = coroutineScope) }
    single<TogetherPlaybackBridge> {
        val playbackActivity = get<PlaybackActivity>()
        TogetherPlaybackBridge(
            playerOnScreen = { playbackActivity.onScreen.value },
            appForeground = { playbackActivity.appForeground.value },
            playerOnScreenChanges = playbackActivity.onScreen,
        )
    }
    single<PlaybackAutomationGate> { PlaybackAutomationGate() }
    single<TogetherManager>(createdAtStart = true) {
        val users = get<UserRepository>()
        TogetherManager(
            scope = coroutineScope,
            gateway = get(),
            settings = get<SettingsRepository>().togetherSettings,
            profile = object : TogetherProfileProvider {
                override suspend fun current(): TogetherProfile {
                    val self = withTimeoutOrNull(PROFILE_TIMEOUT_MILLIS) { users.selfInfoFlow.first() }
                    return TogetherProfile(nickname = self?.nickname.orEmpty())
                }

                override fun changes(): Flow<TogetherProfile> = users.selfInfoFlow.map { TogetherProfile(nickname = it?.nickname.orEmpty()) }
            },
            playbackBridge = get(),
            automationGate = get(),
        ).also { it.start() }
    }
}

private const val PROFILE_TIMEOUT_MILLIS = 2_000L

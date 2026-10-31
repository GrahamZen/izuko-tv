/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.fetch.MediaFetchSessionRefresh
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.SelectOrigin
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.platform.NETWORK_SETTLE
import me.him188.ani.app.platform.NetworkStatus
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * 这一集还没选中资源时断了网: 在线源全部搜索失败, 画面上说的却是「都没有找到这一集」, 网回来也不会再搜;
 * 手上有旧结果的源还可能被自动选中, 记住的源却因为搜索失败排不上, 网回来后只会在这条上重试.
 *
 * 没网时把加载提示改成在等网, 数据源照常查 (本地缓存不要网, 选中了照常放). 恢复后播放还没起来, 也不是用户自己选的, 就重新搜索一次,
 * 自动选源跟着新的查询会话从记住的源重来. 用户选的资源由 [PlaybackFailureRecovery] 等网后接着放.
 * 播放器里还放着东西 (选源会话重建, 选源器暂时什么都没选) 时不管.
 *
 * @param searchAgain 用当前的数据源重建查询会话
 */
class SearchAfterNetworkOutageExtension(
    private val context: PlayerExtensionContext,
    private val network: NetworkStatus,
    private val searchAgain: () -> Unit,
) : PlayerExtension("SearchAfterNetworkOutage") {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("SearchAfterNetworkOutage") {
            context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.filterNotNull().collectLatest { bundle ->
                watch(bundle.mediaSelector)
            }
        }
    }

    private suspend fun watch(selector: MediaSelector): Unit = coroutineScope {
        var chosenByUser = false
        val selections = launch(start = CoroutineStart.UNDISPATCHED) {
            selector.events.onSelect.collect { chosenByUser = it.origin != SelectOrigin.AUTOMATIC }
        }
        while (true) {
            // 上一个查询会话等网等到一半被换掉时, 提示还留着
            if (network.isAvailable.value && selector.selected.value == null) context.showWaitingForNetwork(false)

            combine(network.isAvailable, selector.selected, context.videoLoadingStateFlow) { available, selected, loading ->
                !available && selected == null && loading.hasNothingLoaded()
            }.first { it }
            logger.info { "No network and nothing selected, waiting to search again" }
            context.showWaitingForNetwork(true)

            network.isAvailable.first { it }
            delay(NETWORK_SETTLE)
            if (!network.isAvailable.value) continue
            // 本地缓存放起来了, 或者用户自己选了: 不打断
            if (chosenByUser || context.videoLoadingStateFlow.first() is VideoLoadingState.Succeed) continue

            logger.info { "Network is back and playback has not started, searching again" }
            context.showWaitingForNetwork(false)
            searchAgain()
            selections.cancel()
            return@coroutineScope
        }
    }

    private fun VideoLoadingState.hasNothingLoaded(): Boolean =
        this == VideoLoadingState.Initial || this == VideoLoadingState.Cancelled || this == VideoLoadingState.WaitingForNetwork

    companion object : EpisodePlayerExtensionFactory<SearchAfterNetworkOutageExtension> {
        private val logger = logger<SearchAfterNetworkOutageExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): SearchAfterNetworkOutageExtension =
            SearchAfterNetworkOutageExtension(context, koin.getOrNull<NetworkStatus>() ?: NetworkStatus.AlwaysAvailable) {
                koin.get<MediaFetchSessionRefresh>().request()
            }
    }
}

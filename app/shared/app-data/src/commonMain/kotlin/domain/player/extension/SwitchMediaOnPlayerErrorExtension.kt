/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import androidx.annotation.VisibleForTesting
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle

import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.selector.MediaAutoSelector
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.GetMediaSelectorSourceTiersUseCase
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.settings.GetMediaSelectorSettingsFlowUseCase
import me.him188.ani.app.domain.settings.GetVideoScaffoldConfigUseCase
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.Koin
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlayerState

/**
 * 当播放失败时, 自动切换到下一个可选择的 media.
 */
class SwitchMediaOnPlayerErrorExtension(
    private val context: PlayerExtensionContext,
    koin: Koin
) : PlayerExtension("SwitchMediaOnPlayerErrorExtension") {
    private val getVideoScaffoldConfigUseCase: GetVideoScaffoldConfigUseCase by koin.inject()
    private val getMediaSelectorSettingsFlowUseCase: GetMediaSelectorSettingsFlowUseCase by koin.inject()
    private val getSourceTiersUseCase: GetMediaSelectorSourceTiersUseCase by koin.inject()
    private val mediaCacheManager: MediaCacheManager by koin.inject()


    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope
    ) {
        backgroundTaskScope.launch("PlayerErrorListener") {
            context.sessionFlow.collectLatest { session ->
                invoke(
                    session.episodeId,
                    session.fetchSelectFlow,
                    context.videoLoadingStateFlow,
                    context.player.state,
                )
            }
        }
    }

    /**
     * 启动播放失败处理逻辑。
     *
     * 此函数监听当前播放会话流、视频加载状态和播放器状态，并在发生错误时触发自动切换。
     * 同时也监听媒体选择事件，当用户手动切换媒体时，将先前选中的媒体加入黑名单，避免自动选择时回退。
     */
    private suspend fun invoke(
        episodeId: Int,
        mediaFetchSessionFlow: Flow<MediaFetchSelectBundle?>,
        videoLoadingStateFlow: Flow<VideoLoadingState>,
        playerStateFlow: Flow<PlayerState>
    ) {
        val handler = PlayerLoadErrorHandler(
            getPreferKind = { getMediaSelectorSettingsFlowUseCase().first().preferKind },
            getSourceTiers = { getSourceTiersUseCase().first() },
        )

        coroutineScope {
            // **缓存被删这条不受"自动换源"开关管**: 文件已经没了, 继续拿它播没有任何正确性可言.
            // 开关只决定"要不要自动挑下一个源", 挡不住"必须停止使用一个不存在的文件".
            launch {
                handler.observeCacheDeletedAndHandle(episodeId, mediaFetchSessionFlow)
            }

            // 播放失败时自动切换下一个 media.
            // 即使是 BT 出错, 我们也会尝试切换到下一个 WEB 类型的数据源, 而不是继续尝试 BT.
            launch {
                getVideoScaffoldConfigUseCase().map { it.autoSwitchMediaOnPlayerError }
                    .collectLatest { autoSwitchMediaOnPlayerError ->
                        if (!autoSwitchMediaOnPlayerError) {
                            // 设置关闭, 不要自动切换
                            return@collectLatest
                        }

                        coroutineScope {
                            launch {
                                handler.observeMediaSelectorBlacklist(
                                    mediaFetchSessionFlow.mapNotNull { it?.mediaSelector },
                                )
                            }

                            launch {
                                handler.observeLoadErrorAndHandle(
                                    mediaFetchSessionFlow,
                                    videoLoadingStateFlow,
                                    playerStateFlow,
                                )
                            }
                        }
                    }
            }
        }
    }

    /**
     * 正在播的那条缓存被用户删掉了 —— 与播放失败同等对待, 自动换下一个源.
     *
     * **不能等播放器自己报错**: 文件被 unlink 之后播放器手上那个 fd 依然有效, 已经打开的流会
     * 若无其事地继续读下去 (Linux 语义), 看上去一切正常; 真正出事的是**下一次 seek** ——
     * mediamp 的 `SeekableInputDataSource.open()` 按**路径**取文件长度, 文件没了就取到 0, 于是
     * "目标位置 >= 文件长度"成立, 那次 seek 被**静默跳过**, 播放器以为跳成功了, 读回来的却是
     * 原位置的字节, 解复用当场失败, 界面上是一句没头没尾的"未知错误". 缓存被删这件事只有我们
     * 自己知道, 所以只能从这一侧主动发现.
     *
     * 判据要带 [episodeId]: 合集资源 (一个种子覆盖多集) 的各集缓存共用同一个 `origin.mediaId`,
     * 只比 mediaId 的话删掉本集、别集还在, 会被当成"还没删".
     */
    private suspend fun PlayerLoadErrorHandler.observeCacheDeletedAndHandle(
        episodeId: Int,
        mediaFetchSessionFlow: Flow<MediaFetchSelectBundle?>,
    ) {
        mediaFetchSessionFlow.collectLatest { bundle ->
            if (bundle == null) return@collectLatest
            coroutineScope {
                bundle.mediaSelector.selected.collectLatest select@{ media ->
                    val cached = media as? CachedMedia ?: return@select
                    // **认这条缓存必须认到"具体是哪一份"**: 同一个磁力资源在开了 PikPak 时两个
                    // 引擎都 supports, 可以各存一份, 两份的 origin.mediaId 与 episodeId 完全相同,
                    // 连 CachedMedia.mediaId 都一样 (它不含引擎). 只比那些的话, 删掉播放器正在用
                    // 的那份、另一份还在, 判据恒为真, 删除永远发现不了.
                    // 也不能拿文件路径代替: 没下完的 BT 缓存给的是磁力链, 压根没有路径.
                    // 见 MediaCacheProperties.cacheId.
                    val trackedCacheId = cached.cacheProperties?.cacheId
                    // 认不出身份时**不做检测**: 宁可这条路整个不生效, 也不能拿一个可能错的判据
                    // 去停用户的播放 (只有 DummyMediaCacheEngine 之类不带 cacheId).
                        ?: return@select

                    mediaCacheManager.listCacheForSubject(context.subjectId)
                        .map { caches -> caches.any { it.cacheId == trackedCacheId } }
                        // 缓存列表在启动恢复/服务重连时会整体重建, 中途可能短暂查不到 —— 稳定消失才算删除
                        .debounce(CACHE_DELETION_SETTLE_DELAY)
                        .distinctUntilChanged()
                        .dropWhile { !it } // 先等它确实在列表里出现过, 否则任何时序抖动都会被当成删除
                        .first { !it }

                    // 换源的第一件事就是改 selected, 而那正是本 collectLatest 的取消条件 ——
                    // 就地 await 的话是自己把自己掐断在换源半路. 放到外层作用域里跑.
                    // 不会重复触发: selected 没变的话 collectLatest 不会再进来一次.
                    launch { handleCacheDeleted(bundle, cached) }
                }
            }
        }
    }

    /**
     * 正在播的缓存已确认被删之后要做的事. 分两步, 第二步是**兜底且必须发生**的.
     */
    private suspend fun PlayerLoadErrorHandler.handleCacheDeleted(
        bundle: MediaFetchSelectBundle,
        cached: CachedMedia,
    ) {
        // **这一道要挡**: 上面的等待可以很久 (防抖两秒 + 缓存列表的更新时机), 期间用户完全
        // 有时间自己换源. 那这条死缓存已经不是当前选择, 一步都不该走 —— 再往下会把用户刚挑的
        // 那个拉黑并顶掉.
        if (bundle.mediaSelector.selected.value !== cached) {
            logger.info { "Selection already changed before handling cache deletion, nothing to do" }
            return
        }

        // 先拉黑, 免得随后的自动选择又把它挑回来 (开关关着时 handleError 不会跑, 这一步也就
        // 只能自己做)
        blacklist(cached.mediaId)

        // **这一道不挡, 只记录**: handleError 里还有一秒延迟, 之后 fastSelectWebSources 自己
        // 还可能再等最多一秒容忍窗; 用户恰好在这一两秒里走回播放页、打开选源面板、挑一个源的话,
        // 他的选择会被 overrideUserSelection 顶掉. 这个窗口窄到刻意都难复现 (遥控器根本来不及),
        // 而堵住它要么给 handleError 加参数、要么把"预期是哪一条"一路传进 selectImpl —— 都是动
        // 主播放路径上的共享代码, 代价与收益不成比例.
        // 真有人报"我自己选的源被顶掉了", 底下那行 outcome 日志加上 handleError 自己的
        // "automatically switched to next media" 就能把经过还原出来.
        try {
            if (getVideoScaffoldConfigUseCase().map { it.autoSwitchMediaOnPlayerError }.first()) {
                handleError(
                    bundle.mediaFetchSession,
                    bundle.mediaSelector,
                    PlayerLoadError("playing cache deleted (mediaId=${cached.mediaId})", null),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 读设置与自动换源**都**要圈进来: 任何一个抛出都不能连累下面那一半 (它才是"必须发生"
            // 的), 而且这里的异常会顺着 launch 冒到整个扩展的作用域, 把本会话的错误监听一起带走.
            logger.warn(e) { "Failed to switch media after cache deletion, falling back to stopping playback" }
        }

        // 到这儿还等于那条死缓存, 说明没换成: 开关关着, 或者 preferKind 不是 WEB (旧用户默认
        // 就是 null, 那条路 handleError 直接 return), 或者压根没有可用的在线源. 无论哪种, 都不能
        // 让播放器继续读一个已经不存在的文件 —— 下一次 seek 会静默落到错误偏移, 变成一句没头没尾
        // 的"未知错误"(正是这整段代码要消灭的东西). 停下来并清空选择, 与删除确认框答应用户的
        // "删除后需要重新选择数据源"正好对上.
        val current = bundle.mediaSelector.selected.value
        if (current === cached) {
            logger.info { "Cache deleted, no replacement selected, stopping playback (mediaId=${cached.mediaId})" }
            bundle.mediaSelector.unselect()
            withContext(Dispatchers.Main.immediate) { context.player.stopPlayback() }
        } else {
            // 见上面那段说明: 正常情况下这就是换源成功; 但如果是用户自己刚挑的那个被顶掉了,
            // 事后只能从这行认出来 (前后还有 handleError 打的换源日志).
            logger.info { "Cache deleted, now playing ${current?.mediaId} (was ${cached.mediaId})" }
        }
    }

    private suspend fun PlayerLoadErrorHandler.observeLoadErrorAndHandle(
        mediaFetchSessionFlow: Flow<MediaFetchSelectBundle?>,
        videoLoadingStateFlow: Flow<VideoLoadingState>,
        playerStateFlow: Flow<PlayerState>
    ) {
        mediaFetchSessionFlow.collectLatest { bundle ->
            if (bundle == null) return@collectLatest

            combine(
                videoLoadingStateFlow, // 解析链接出错 (未匹配到链接)
                playerStateFlow, // 解析成功, 但播放器出错 (无法链接到链接, 例如链接错误)
            ) { videoLoadingState, playerState ->
                // 带上原因: 只打一句 "Player errored" 的话, 用户导出的日志里分不出是没解析到文件
                // 还是播放器解码失败, 也拿不到 ExoPlayer 的错误码 (issue #12).
                val mediaStatus = playerState.mediaStatus
                when {
                    videoLoadingState is VideoLoadingState.Failed -> PlayerLoadError(
                        videoLoadingState.toString(),
                        (videoLoadingState as? VideoLoadingState.UnknownError)?.cause,
                    )

                    mediaStatus is MediaStatus.Error -> PlayerLoadError(
                        "code=${mediaStatus.error.code}",
                        mediaStatus.error,
                    )

                    else -> null
                }
            }.distinctUntilChangedBy { it != null }
                .collectLatest { error ->
                    if (error != null) {
                        handleError(bundle.mediaFetchSession, bundle.mediaSelector, error)
                    } // else: cancel selection
                }
        }
    }

    companion object : EpisodePlayerExtensionFactory<SwitchMediaOnPlayerErrorExtension> {
        /** 缓存"从列表里消失"要稳定这么久才算被删除, 见 [observeCacheDeletedAndHandle]. */
        private val CACHE_DELETION_SETTLE_DELAY = 2.seconds

        private val logger = logger<SwitchMediaOnPlayerErrorExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): SwitchMediaOnPlayerErrorExtension {
            return SwitchMediaOnPlayerErrorExtension(context, koin)
        }
    }
}

/**
 * 播放失败的原因, 只用于日志.
 */
internal class PlayerLoadError(
    val description: String,
    val cause: Throwable?,
)

internal class PlayerLoadErrorHandler(
    private val getPreferKind: suspend () -> MediaSourceKind?,
    private val getSourceTiers: suspend () -> MediaSelectorSourceTiers,
) {
    /**
     * 不可变集合本身可以安全共享, 但 `x = x.add(...)` 是读-改-写三步, 而拉黑来自三条并发的路
     * (缓存被删 / 播放器报错 / 用户手动改选的事件). 两次 add 恰好撞在同一个读-改-写窗口里
     * (微秒级) 会丢掉一条.
     *
     * **不为它加原子保护**: 后果是自愈的 —— 丢掉的那条会被重新选中, 播放照旧失败, 于是又被拉黑
     * 一次. 代价只是多试一个源, 而不是错误的结果.
     */
    private var blacklistedMediaIds = persistentHashSetOf<String>()

    /** 把一个已经确定不可用的 media 拉黑, 供 [handleError] 之外的路径 (如缓存被删) 使用. */
    fun blacklist(mediaId: String) {
        blacklistedMediaIds = blacklistedMediaIds.add(mediaId)
    }

    suspend fun observeMediaSelectorBlacklist(
        mediaSelectorFlow: Flow<MediaSelector>
    ) {
        mediaSelectorFlow.collectLatest { selector ->
            selector.events.onSelect.collect { event ->
                event.previousMedia?.let { blacklist(it.mediaId) }
            }
        }
    }

    suspend fun handleError(
        session: MediaFetchSession,
        mediaSelector: MediaSelector,
        error: PlayerLoadError? = null,
    ) {
        // 播放出错了
        val reason = error?.description ?: "unknown"
        if (error?.cause != null) {
            logger.warn(error.cause) { "Player errored ($reason), automatically switching to next media" }
        } else {
            logger.info { "Player errored ($reason), automatically switching to next media" }
        }

        // 将当前播放的 mediaId 加入黑名单
        val failedMedia = mediaSelector.selected.value
        failedMedia?.let { blacklist(it.mediaId) }

        delay(1.seconds) // 稍等让用户看到播放出错
        if (mediaSelector.selected.value != failedMedia) return

        // Load data in parallel
        val (preferKind, sourceTiers) = combine(
            getPreferKind.asFlow(),
            getSourceTiers.asFlow(),
        ) { kind, tiers -> kind to tiers }.first()

        if (preferKind != MediaSourceKind.WEB) {
            logger.info { "Player errored, but preferKind is not WEB ($preferKind), skip automatic switch" }
            return
        }

        val result = MediaAutoSelector(mediaSelector).select(
            session,
            MediaAutoSelector.Config(
                selectCache = false,
                blacklist = blacklistedMediaIds,
                web = MediaAutoSelector.Web(
                    sourceTiers = sourceTiers,
                    // 错误切换不需要等太长时间。
                    exactMatchAfter = 1.seconds,
                    fuzzyMatchAfter = 1.seconds,
                    waitForPendingSources = false,
                ),
            ),
            expectedSelection = failedMedia,
        )
        logger.info { "Player errored, automatically switched to next media: $result" }
    }

    companion object {
        private val logger = logger<PlayerLoadErrorHandler>()
    }

    @VisibleForTesting
    val blacklist: Set<String> get() = blacklistedMediaIds
}

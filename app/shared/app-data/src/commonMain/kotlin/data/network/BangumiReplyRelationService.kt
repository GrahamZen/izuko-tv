/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.episode.EpisodeComment
import me.him188.ani.app.data.models.episode.EpisodeCommentSource
import me.him188.ani.datasources.bangumi.next.apis.EpisodeBangumiNextApi
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 直接问 Bangumi "这条楼内回复回复的是谁" (`relatedID`), 补上服务端合并接口不给的那层关系.
 *
 * 服务端的 `listEpisodeComments` 只给评论与回复本身, 没有回复之间的指向 (见
 * [EpisodeComment.replyToCommentId]), 而电视上的完整评论弹窗要用它显示"回复 @某人".
 * 缺了这层关系时还有正文引用的推断兜着 (见 `quotedAuthorNicknameOrNull`), 但用户把引用删掉再发
 * 的那种就认不出来了 —— 所以能连上 Bangumi 就用它给的真值.
 *
 * 对号入座靠的是 [EpisodeComment.sourceCommentId] 与 Bangumi 那边 `id` 是同一个值 (服务端合并时
 * 就是照原样带过来的). 哪天服务端换了这个字段的格式, 这里会静悄悄地一条都对不上 —— 表现是"回复
 * @某人"退回到只由正文引用推断, 不会出错, 但排查时记得先看这一点.
 *
 * **连不上就当没有这回事**: 不额外做连通性探测 (那是又一个会超时的请求), 直接试, 失败就记下
 * [UNREACHABLE_BACKOFF] 这么久不再试 —— 否则每翻一页都要为一个注定失败的请求等上一次超时.
 * 单次也压了 [FETCH_TIMEOUT], 评论列表不能为了这层装饰卡在那里.
 *
 * **调用方最多被挡 [BLOCKING_BUDGET]**: 请求跑在本类自己的作用域里, 调用方只是"顺便等一下".
 * 等不到就先把评论发出去 (少一行"回复 @某人", 正文引用推断的那份还在), 请求继续在后台跑完并
 * 进缓存, 下一页与下次打开这一集直接命中. 从前是整页评论一路等到 [FETCH_TIMEOUT] 为止 ——
 * 而这层信息只有电视端渲染, 手机/桌面等完了根本不显示.
 */
class BangumiReplyRelationService internal constructor(
    private val nowMillis: () -> Long,
    /** 请求的宿主作用域: 必须**独立于调用方**, 否则调用方一超时/取消就把后台补齐也一起杀了. */
    private val scope: CoroutineScope,
    /** 取一集的关系表; `null` = 这次没取到. 抽成参数是为了让缓存与退避那部分可测. */
    private val fetchRelations: suspend (episodeId: Long) -> Map<String, String>?,
) {
    constructor(
        client: ScopedHttpClient,
        ioDispatcher: CoroutineContext = Dispatchers.IO_,
    ) : this(
        nowMillis = { currentTimeMillis() },
        scope = CoroutineScope(SupervisorJob() + ioDispatcher),
        fetchRelations = bangumiFetcher(client, ioDispatcher),
    )

    private val mutex = Mutex()

    /**
     * 正在跑的请求: episodeId -> 结果. 同一集并发翻页只发一次, 且**请求本身不在 [mutex] 里跑**
     * (从前整个网络请求包在锁里, 并发翻页会串成一列, 每列各等一次超时).
     * 只在 [mutex] 里读写.
     */
    private val inFlight = mutableMapOf<Long, Deferred<Map<String, String>>>()

    /**
     * 最近几集的关系表: episodeId -> (回复的 id → 被回复的那条回复的 id).
     *
     * 留 [MAX_CACHED_EPISODES] 集而不是一集: 单格缓存在"这集评论 ↔ 下一集评论"来回切时每次都被
     * 顶掉, 每切一次就重发一次 next.bgm.tv 全量评论请求 (上限 3 秒), 而用户比较两集评论正是
     * 最常见的来回路径. 淘汰按**最近使用**: 命中时移到队尾, 超量踢队首.
     * 只在 [mutex] 里读写.
     */
    private val cachedRelations = LinkedHashMap<Long, Map<String, String>>()

    /** 在这个时刻之前不再尝试请求, 见类文档. */
    private var unreachableUntilMillis: Long = 0

    /**
     * 给一页评论里的楼内回复补上 [EpisodeComment.replyToCommentId]; 补不了就原样返回.
     */
    suspend fun fillInReplyTargets(episodeId: Long, comments: List<EpisodeComment>): List<EpisodeComment> {
        if (comments.none { it.mayHaveUnknownReplyTarget() }) return comments
        val relations = relationsOf(episodeId)
        if (relations.isEmpty()) return comments
        return comments.map { comment ->
            if (comment.mayHaveUnknownReplyTarget()) {
                comment.copy(replies = comment.replies.map { it.withReplyTarget(relations) })
            } else {
                comment
            }
        }
    }

    /** 已经从正文引用认出来的不动: 那是同一件事的另一个来源, 换成这里的没有意义. */
    private fun EpisodeComment.withReplyTarget(relations: Map<String, String>): EpisodeComment {
        if (replyToCommentId != null) return this
        val target = relations[sourceCommentId] ?: return this
        return copy(replyToCommentId = target)
    }

    /**
     * 只有 Bangumi 来源、且楼内有多条回复时才谈得上"回复了谁"; 全都已经认出来了也不必再问.
     */
    private fun EpisodeComment.mayHaveUnknownReplyTarget(): Boolean =
        source == EpisodeCommentSource.BANGUMI &&
                replies.size >= 2 &&
                replies.any { it.replyToCommentId == null }

    private suspend fun relationsOf(episodeId: Long): Map<String, String> {
        val pending = mutex.withLock {
            cachedRelations.remove(episodeId)?.let { hit ->
                cachedRelations[episodeId] = hit // 命中移到队尾 = 最近使用
                return hit // 已在手上, 一点都不用等
            }
            if (nowMillis() < unreachableUntilMillis) return emptyMap()
            inFlight.getOrPut(episodeId) { scope.async { fetchAndCache(episodeId) } }
        }
        // 只顺便等一小会儿. 等不到就先把评论发出去, 上面那个请求继续在后台跑完并进缓存
        return withTimeoutOrNull(BLOCKING_BUDGET) { pending.await() } ?: emptyMap()
    }

    /** 跑在 [scope] 里: 请求在锁外, 只有落缓存那几步进锁. */
    private suspend fun fetchAndCache(episodeId: Long): Map<String, String> {
        val relations = runCatching { fetchRelations(episodeId) }.getOrNull()
        return mutex.withLock {
            inFlight.remove(episodeId)
            if (relations == null) {
                // 失败按"连不上"处理并退避. 注意别把失败写进 cachedRelations —— 那会把这一集钉成
                // "没有关系", 连退避到期后的重试都不会再发生
                unreachableUntilMillis = nowMillis() + UNREACHABLE_BACKOFF.inWholeMilliseconds
                emptyMap()
            } else {
                unreachableUntilMillis = 0
                if (cachedRelations.size >= MAX_CACHED_EPISODES) {
                    cachedRelations.keys.firstOrNull()?.let { cachedRelations.remove(it) }
                }
                cachedRelations[episodeId] = relations
                relations
            }
        }
    }

    private companion object {
        /** 失败之后这么久内不再试 */
        private val UNREACHABLE_BACKOFF: Duration = 10.minutes

        /** 缓存的集数上限: 覆盖"两三集之间来回比评论"就够, 单条表很小但没必要留一季. */
        private const val MAX_CACHED_EPISODES = 4

        /**
         * 调用方最多被挡这么久. 比 [FETCH_TIMEOUT] 短得多: 那是请求自己的死线, 这是"评论列表
         * 愿意为一行装饰文字等多久". 网络正常时一次请求进得来, 慢/不通时评论先出来.
         */
        private val BLOCKING_BUDGET: Duration = 800.milliseconds
    }
}

/**
 * 真正去 next.bgm.tv 取关系的那份实现.
 *
 * @return `null` 表示这次没取到 (连不上 / 超时 / 这集在 Bangumi 上不存在), 交给调用方退避.
 */
private fun bangumiFetcher(
    client: ScopedHttpClient,
    ioDispatcher: CoroutineContext,
): suspend (Long) -> Map<String, String>? {
    val api = ApiInvoker(client) { EpisodeBangumiNextApi(BANGUMI_NEXT_API_HOST, it) }
    return { episodeId ->
        withContext(ioDispatcher) {
            runCatching {
                withTimeoutOrNull(FETCH_TIMEOUT) {
                    api {
                        getEpisodeComments(episodeId.toInt()).body().flatMap { comment ->
                            comment.replies.mapNotNull { reply ->
                                // relatedID 指向主楼 (或自身/缺失) 时只是普通的楼内回复, 不算指向某条回复
                                reply.relatedID
                                    .takeIf { it != 0 && it != reply.mainID && it != comment.id && it != reply.id }
                                    ?.let { reply.id.toString() to it.toString() }
                            }
                        }.toMap()
                    }
                }
            }.onFailure {
                logger.debug(it) { "Failed to fetch Bangumi reply relations for episode $episodeId" }
            }.getOrNull()
        }
    }
}

private const val BANGUMI_NEXT_API_HOST = "https://next.bgm.tv"

/** 单次请求最多等这么久: 补不上只是少一行字, 不值得让评论列表干等 */
private val FETCH_TIMEOUT: Duration = 3.seconds

private val logger = logger<BangumiReplyRelationService>()

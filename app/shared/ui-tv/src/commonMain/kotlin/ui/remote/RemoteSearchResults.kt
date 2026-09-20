/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.nameCnOrName
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.exploration.search.SubjectPreviewItemInfo
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/** 电视搜索页结果面板此刻的样子: 查询、已加载的条目与分页状态. */
internal class RemoteSearchResultsSnapshot(
    val query: SubjectSearchQuery,
    val items: List<SubjectPreviewItemInfo>,
    /** 首页加载 / 刷新中. */
    val refreshing: Boolean,
    /** 正在加载下一页. */
    val appending: Boolean,
    val endReached: Boolean,
    val error: String?,
)

/** 电视搜索页结果面板在组合里时登记 (见 [TvRemoteControl.registerSearchResults]). */
internal interface RemoteSearchResultsSource {
    /** HTTP 线程上调用: 只读快照状态. */
    fun snapshot(): RemoteSearchResultsSnapshot

    /** 让电视的列表加载下一页 (实现方自己投到主线程). */
    fun loadMore()
}

/**
 * Web 控制台搜索标签里的「结果」页: 把电视搜索页**已经加载的结果**原样列到手机上 (封面当卡片底图); 点条目电视打开详情页,
 * 点 ▶ 电视直接进播放页.
 *
 * 不单独再搜一份: 手机上看到的就是电视上那份列表 (同一次请求、同一套 NSFW / 忽略看过的处理), 「加载更多」
 * 也是让电视的列表往下翻一页. 结果面板离场 (进了播放页 / 回输入态) 后保留最后一份, 手机上仍能点, 只是不能再翻页.
 *
 * 点条目播哪一集与追番页 / 详情页的播放按钮一致: 全部看完从第一集重温, 其余接着播 `nextEpisodeIdToPlay`.
 * [play] 也给挑番面板与「缓存」标签番名那块的封面用 (都按条目 id).
 */
internal object RemoteSearchResults {
    private val logger = logger<RemoteSearchResults>()

    private val collectionRepository: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()

    /**
     * 结果标签的状态 JSON. [live] = 结果面板此刻在组合里 (能加载更多); [pending] = 手机刚提交的搜索电视还没换上,
     * 先显示「正在搜索」, 免得旧结果闪一下.
     */
    fun stateJson(snapshot: RemoteSearchResultsSnapshot?, live: Boolean, pending: Boolean): JsonObject =
        buildJsonObject {
            put("pending", pending)
            if (pending || snapshot == null) {
                put("available", false)
                return@buildJsonObject
            }
            put("available", true)
            put("live", live)
            put("keywords", snapshot.query.keywords)
            put("filters", filterText(snapshot.query))
            put("refreshing", snapshot.refreshing)
            put("appending", snapshot.appending)
            put("end", snapshot.endReached)
            snapshot.error?.let { put("error", it) }
            putJsonArray("items") {
                for (item in snapshot.items) {
                    // 与电视网格一致: 被标记隐藏的不出, 「隐藏 NSFW」模式下的也不出 (电视上同样不画)
                    if (item.hide || item.nsfwMode == NsfwMode.HIDE) continue
                    addJsonObject {
                        put("id", item.subjectId)
                        put("title", item.title)
                        // 没有类型标签时这行以「 · 」结尾 (compute 先拼分隔符再拼标签), 手机上去掉
                        put("info", item.tags.removeSuffix(" · "))
                        put("rating", ratingText(item.rating))
                        put("nsfw", item.nsfw)
                        // 列表项的封面底图, 候选: 镜像站原图直连 → 电视转发 → Bangumi 600 宽 (见 remoteCoverCandidates).
                        // NSFW 跟电视网格走同一个设置: 「显示」照常给; 「模糊」电视上打码 (obscureImage), 网页也糊着画 (blur);
                        // 「隐藏」的条目上面已经整条跳过
                        putJsonArray("cover") { remoteCoverCandidates(item.subjectId, item.imageUrl).forEach { add(it) } }
                        if (item.nsfwMode == NsfwMode.BLUR) put("blur", true)
                    }
                }
            }
        }

    /** 标题下的一行筛选说明 (网页上有的那几项; 排序不写, 结果顺序本身就说明了). */
    private fun filterText(query: SubjectSearchQuery): String = buildList {
        query.rating?.min?.let { add(tr("{0} 分以上", it)) }
        query.tags?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" / ")) }
    }.joinToString(" · ")

    /** 评分一行: `★ 8.1 · #123 · 1234 人评分`, 没人评分时为空. */
    private fun ratingText(rating: RatingInfo): String = buildList {
        if (rating.total > 0 && rating.scoreFloat > 0f) add("★ " + rating.score)
        if (rating.rank > 0) add("#" + rating.rank)
        if (rating.total > 0) add(tr("{0} 人评分", rating.total))
    }.joinToString(" · ")

    /** 手机刚提交的搜索是否已经是电视上这份结果的查询 (网页上有的字段一致即算). */
    fun matches(submission: RemoteSearchSubmission, query: SubjectSearchQuery): Boolean =
        submission.keywords == query.keywords &&
                submission.sort == query.sort &&
                submission.minRating == query.rating?.min &&
                submission.tags.toSet() == query.tags.orEmpty().toSet()

    /**
     * 点了一条结果的主体: 电视打开这部的详情页 (与电视搜索页点卡片一样). 标题与封面从电视那份结果里取, 当占位先画出来,
     * 不必等详情加载. [snapshot] 取不到这一条 (列表已换) 时仍照 id 打开, 只是没有占位.
     */
    fun open(
        request: LanHttpRequest,
        navigator: AniNavigator?,
        scope: CoroutineScope,
        snapshot: RemoteSearchResultsSnapshot?,
    ): JsonObject {
        val subjectId = request.formFields()["id"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        val item = snapshot?.items?.firstOrNull { it.subjectId == subjectId }
        val placeholder = item?.let { SubjectDetailPlaceholder(id = subjectId, name = it.title, coverUrl = it.imageUrl) }
        TvRemoteControl.notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            runCatching { nav.navigateSubjectDetails(subjectId, placeholder) }
                .onFailure { logger.warn(it) { "Failed to open subject details for remote control" } }
        }
        return result(true, if (item != null) tr("已在电视上打开「{0}」", item.title) else tr("已在电视上打开详情页"))
    }

    /**
     * 点了一条结果的 ▶: 电视直接进播放页. 条目没缓存时 [SubjectCollectionRepository.subjectCollectionFlow] 会先拉一次
     * (含分集), 所以要等一会儿; 没有分集信息的 (比如还没公布) 退而打开详情页.
     */
    fun play(request: LanHttpRequest, navigator: AniNavigator?, scope: CoroutineScope): JsonObject {
        val subjectId = request.formFields()["id"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        val info = runCatching {
            runBlocking { withTimeoutOrNull(PLAY_TIMEOUT) { collectionRepository.subjectCollectionFlow(subjectId).first() } }
        }.getOrElse {
            logger.warn(it) { "Failed to load subject $subjectId for remote play" }
            return result(false, tr("获取剧集信息失败：{0}", it.message ?: it::class.simpleName))
        } ?: return result(false, tr("获取剧集信息超时，请重试"))

        val title = info.subjectInfo.nameCnOrName
        val progress = info.progressInfo
        val episodes = info.episodes
        if (progress.continueWatchingStatus is ContinueWatchingStatus.NotOnAir && progress.nextEpisodeIdToPlay == null) {
            return result(false, tr("「{0}」还没开播", title))
        }
        val episodeId = when (progress.continueWatchingStatus) {
            is ContinueWatchingStatus.Done -> episodes.firstOrNull()?.episodeId
            else -> progress.nextEpisodeIdToPlay ?: episodes.firstOrNull()?.episodeId
        }
        if (episodeId == null) {
            TvRemoteControl.notifyRemoteNavigation()
            scope.launch(Dispatchers.Main) {
                runCatching { nav.navigateSubjectDetails(subjectId, placeholder = null) }
                    .onFailure { logger.warn(it) { "Failed to open subject details for remote play" } }
            }
            return result(true, tr("「{0}」没有剧集信息，已在电视上打开详情页", title))
        }
        val sort = episodes.firstOrNull { it.episodeId == episodeId }?.episodeInfo?.sort?.toString().orEmpty()
        logger.info { "Remote play: subject $subjectId episode $episodeId" }
        // 电视正在播这部番时就地换集, 不再叠一个新的播放页 (见 TvRemoteControl.playEpisode)
        val how = TvRemoteControl.playEpisode(nav, scope, subjectId, episodeId) {
            logger.warn(it) { "Failed to start playback for remote play" }
        }
        val ep = if (sort.isNotEmpty()) tr(" 第 {0} 话", sort) else ""
        val msg = when (how) {
            TvRemoteControl.RemotePlayResult.AlreadyPlaying -> tr("电视正在播「{0}」{1}", title, ep)
            TvRemoteControl.RemotePlayResult.Switched -> tr("已在电视上换到「{0}」{1}", title, ep)
            TvRemoteControl.RemotePlayResult.Opened -> tr("已在电视上播放「{0}」{1}", title, ep)
        }
        return result(true, msg, player = true)
    }

    /** @param player 电视进了播放页: 网页随后切到「播放器」标签, 数据源结果陆续回来可以直接挑 */
    private fun result(ok: Boolean, message: String, player: Boolean = false): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
        if (player) put("player", true)
    }

    private val PLAY_TIMEOUT = 15.seconds
}

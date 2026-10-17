/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tv

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.repository.subject.SubjectNsfw
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.toTmdbMatchHints
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TrendsRepository
import me.him188.ani.app.data.network.newestAiredDateStringOrNull
import me.him188.ani.app.data.network.tmdbStillHeroSizeUrl
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.EpisodeCompletionContext.isKnownOnAir
import me.him188.ani.app.domain.episode.EpisodeCompletionContext.mapAirDate
import me.him188.ani.app.ui.foundation.tv.getTvEpisodeMedia
import me.him188.ani.app.ui.main.TvContinueEpisode
import me.him188.ani.app.ui.main.tvContinueEpisode
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 向安卓系统主屏 (Android TV / Google TV launcher) 写入预览频道内容:
 *
 * - 系统 "继续观看" (Watch Next) 行: 当前在看的番剧, 按最近看过排序. 还没看的 (一集没标看过, 正片也没有播放记录)
 *   是竖海报, 点了进条目详情页; 有进度的按接着看的那一集画横图 (TMDB 单集剧照, 没有就用整部背景图), 点了直接进播放器
 *   (追平连载、下一集还没播时进详情页). 接着看哪一集见 tvContinueEpisode. 用户在这一行里移除一张卡 = 把这部标成搁置,
 *   在频道卡片上「添加到接下来播放」= 标成在看 (见 TvPlayNextSync)
 * - 自建 "热门动画" 频道: 最近热门番剧 (探索页同款数据), 点了进条目详情页
 *
 * 跳转走 `ani://subjects/...` / `ani://episodes/...` 深链接 (见 ResolveEpisodeLinkUseCase).
 * 数据由系统 TvProvider 数据库保存并由 launcher 展示, 不需要 app 常驻.
 *
 * 卡片多宽由 launcher 按 `poster_art_aspect_ratio` 定: 卡高一样, 竖海报 2:3 是窄卡, 横图 16:9 是宽卡.
 */
object TvHomeChannels {
    private val logger = logger<TvHomeChannels>()

    private const val CHANNEL_INTERNAL_ID = "animeko_trending"
    private const val CHANNEL_DISPLAY_NAME = "热门动画"
    private const val TRENDING_LIMIT = 15
    private const val WATCH_NEXT_LIMIT = 10
    private const val DESCRIPTION_MAX_LENGTH = 200

    /** 「接下来播放」这张表变了之后等多久再对一遍, 把我们自己重写时连着来的变化合成一次. */
    private val PLAY_NEXT_SETTLE = 500.milliseconds

    /** 下一集播出后多久内算「新集」. */
    private val NEW_EPISODE_WINDOW = 7.days

    /** 「继续观看」行按时间重算的间隔, 见 [collectWatchNext]. */
    private val TIME_RECHECK_INTERVAL = 15.minutes

    private val trendingUpdatedThisProcess = AtomicBoolean(false)

    /** 两个 activity 交替时可能有两个收集协程重叠, 别让它们同时改同一批行. */
    private val watchNextMutex = Mutex()

    /**
     * 在 MainActivity 启动后调用, 非 TV 设备直接跳过. **不会返回**: 系统"继续观看"行要一直跟着
     * 本地收藏库重写.
     *
     * - 热门动画频道: 每进程对一次热门榜, 没变就不动 (内容一天也变不了几次, 见 [updateTrendingChannel]);
     * - 系统"继续观看"行: 只在进程启动时写一次的话, 用户在详情页把番标成"看过"之后, 主屏那一行
     *   还会挂着它直到下次冷启动.
     */
    suspend fun keepUpdated(context: Context, koin: Koin) {
        if (!isTvDevice(context)) return
        // 系统主屏频道 (PreviewPrograms / WatchNextPrograms) 是 Android TV Oreo (API 26) 才有的,
        // 更低版本上 TvProvider 没有这些 URI, 直接跳过而不是让下面的 runCatching 吞一堆异常.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        if (trendingUpdatedThisProcess.compareAndSet(false, true)) {
            runCatching { updateTrendingChannel(context, koin) }
                .onFailure { logger.error(it) { "Failed to update trending channel" } }
        }

        // 「今日更新」频道在这里建好 (第一次要弹添加频道确认框), 内容由它自己按天重写
        val todayChannelId = runCatching { TvHomeTodayChannel.ensureChannel(context) }
            .onFailure { logger.error(it) { "Failed to create today channel" } }
            .getOrNull()

        // 长期收集期间不拿着 activity: 只有上面弹添加频道确认框那一步需要它
        val appContext = context.applicationContext
        val tmdb = koin.get<TmdbImageService>()
        val settingsRepository = koin.get<SettingsRepository>()
        val playNext = TvPlayNextSync(appContext, koin)
        coroutineScope {
            todayChannelId?.let { launch { TvHomeTodayChannel.keepUpdated(appContext, koin, it) } }
            launch { watchPlayNext(appContext, playNext) }
            collectWatchNext(appContext, koin, tmdb, settingsRepository, playNext)
        }
    }

    /**
     * 进程在时盯着「接下来播放」这张表, 用户加卡 / 移除卡当场对到收藏 (见 [TvPlayNextSync]).
     * 我们自己重写一次会连着来二十来个变化, 合成一次, 过一会儿在锁里对一遍.
     */
    private suspend fun watchPlayNext(appContext: Context, playNext: TvPlayNextSync) {
        callbackFlow {
            val unregister = playNext.watch(appContext) { trySend(Unit) }
            awaitClose { unregister() }
        }.conflate().collect {
            delay(PLAY_NEXT_SETTLE)
            runCatching {
                watchNextMutex.withLock {
                    withContext(Dispatchers.IO_) { playNext.apply(playNext.detect(ownWatchNextRows(appContext))) }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                logger.error(it) { "Failed to check Play Next changes" }
            }
        }
    }

    private suspend fun collectWatchNext(
        appContext: Context,
        koin: Koin,
        tmdb: TmdbImageService,
        settingsRepository: SettingsRepository,
        playNext: TvPlayNextSync,
    ) {
        combine(
            koin.get<SubjectCollectionRepository>()
                .mostRecentlyUpdatedSubjectCollectionsFlow(WATCH_NEXT_LIMIT, listOf(UnifiedCollectionType.DOING)),
            // NSFW 设置一变也重算 (见 toWatchNextEntry)
            SubjectNsfw.mode,
            // 含看完一集留下的墓碑 (见 tvContinueEpisode); 播放中每分钟记一次进度, 卡片上的进度条跟着走
            koin.get<EpisodePlayHistoryRepository>().allHistoriesFlow,
            // 下一集播出、「新集」过期都只随时间变, 数据不动也要定时重算
            flow {
                while (true) {
                    emit(Clock.System.now())
                    delay(TIME_RECHECK_INTERVAL)
                }
            },
        ) { list, _, histories, now ->
            val historyByEpisode = histories.associateBy { it.episodeId }
            list.mapNotNull { info -> info.toWatchNextEntry(historyByEpisode, now)?.let { it to info } }
        }
            // 只比卡片真正用到的字段: 看一集、刷新一次都会让上游重新发射, 而那些变化与这一行无关
            .distinctUntilChangedBy { pairs -> pairs.map { it.first } }
            .collectLatest { pairs ->
                runCatching {
                    // 调用方在主线程的 lifecycleScope 里
                    val cards = withContext(Dispatchers.IO_) {
                        pairs.map { (entry, info) ->
                            async { WatchNextCard(entry, entry.episode?.let { resolveLandscapeImage(tmdb, settingsRepository, info, it.episodeId) }) }
                        }.awaitAll()
                    }
                    watchNextMutex.withLock {
                        withContext(Dispatchers.IO_) { writeWatchNext(appContext, cards, playNext) }
                    }
                }
                    .onFailure {
                        if (it is CancellationException) throw it
                        logger.error(it) { "Failed to update watch-next row" }
                    }
            }
    }

    private fun isTvDevice(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** 一张"继续观看"卡片除横图外的全部内容: 用它判断要不要重写这一行, 见 [keepUpdated]. */
    private data class WatchNextEntry(
        val subjectId: Int,
        val title: String,
        val description: String,
        val posterUri: String,
        val lastEngagementTimeMillis: Long,
        /** 有进度时要播的那一集; null = 还没看, 竖海报进详情页. */
        val episode: WatchNextEpisode?,
    )

    /** 有进度的条目接着看的那一集: 卡片按它画横图, 点了直接进播放器 (追平连载、下一集还没播时进详情页). */
    private data class WatchNextEpisode(
        val episodeId: Int,
        val sort: EpisodeSort,
        val title: String,
        val link: String,
        /** WatchNextPrograms.WATCH_NEXT_TYPE_*. */
        val watchNextType: Int,
        /** 这一集看到一半时的位置与时长, 主屏画进度条; 没看过这一集为 null. */
        val positionMillis: Long?,
        val durationMillis: Long?,
    )

    /** 写进主屏的一张卡: [landscapeImageUri] 为 null 时 (还没看, 或 TMDB 没有横图) 画竖海报. */
    private data class WatchNextCard(
        val entry: WatchNextEntry,
        val landscapeImageUri: String?,
    )

    /**
     * 没有海报的条目不写进主屏 (卡片会是一块空白). NSFW 作品在设置不是「显示」时也不写: 主屏是系统画的, 打不了码 (见 SubjectNsfw).
     * 接着看哪一集见 [tvContinueEpisode]. 要播的下一集是最近 [NEW_EPISODE_WINDOW] 内播出的, 标成「新集」(WATCH_NEXT_TYPE_NEW),
     * 排序时间取播出时刻, 在追的番一更新就排到这一行前面.
     *
     * @param historyByEpisode 播放记录 (含墓碑), 按分集 id.
     */
    private fun SubjectCollectionInfo.toWatchNextEntry(historyByEpisode: Map<Int, EpisodeHistory>, now: Instant): WatchNextEntry? {
        val subject = subjectInfo
        if (subject.imageLarge.isBlank()) return null
        if (SubjectNsfw.modeOf(subject.subjectId) != NsfwMode.DISPLAY) return null
        var engagementMillis = lastUpdated
        val episode = when (val target = tvContinueEpisode(episodes, historyByEpisode) { it.isKnownOnAir(recurrence, now) }) {
            TvContinueEpisode.NotStarted -> null
            is TvContinueEpisode.Play -> {
                val airedAt = recurrence.mapAirDate(target.episode.episodeInfo.airDate)
                val isNew = target.resume == null && airedAt != null && airedAt <= now && now - airedAt <= NEW_EPISODE_WINDOW
                if (isNew) engagementMillis = maxOf(engagementMillis, airedAt.toEpochMilliseconds())
                target.episode.toWatchNextEpisode(
                    link = episodeDeepLink(subject.subjectId, target.episode, episodes),
                    watchNextType = when {
                        target.resume != null -> TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        isNew -> TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW
                        else -> TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                    },
                    resume = target.resume,
                )
            }
            // 还没播的集进播放器也没得放, 去详情页看更新时间
            is TvContinueEpisode.Upcoming -> target.episode.toWatchNextEpisode(
                link = subjectDeepLink(subject.subjectId).toString(),
                watchNextType = TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
                resume = null,
            )
        }
        return WatchNextEntry(
            subjectId = subject.subjectId,
            title = subject.displayName,
            description = formatDescription(subject.summary),
            posterUri = subject.imageLarge,
            lastEngagementTimeMillis = engagementMillis,
            episode = episode,
        )
    }

    private fun EpisodeCollectionInfo.toWatchNextEpisode(link: String, watchNextType: Int, resume: EpisodeHistory?) =
        WatchNextEpisode(
            episodeId = episodeId,
            sort = episodeInfo.sort,
            title = episodeInfo.nameCn.ifBlank { episodeInfo.name },
            link = link,
            watchNextType = watchNextType,
            positionMillis = resume?.positionMillis,
            durationMillis = resume?.durationMillis?.takeIf { it > 0 },
        )

    /** 本应用名下的「接下来播放」行 (TvProvider 只返回本应用写的, 含系统替用户复制进来的). */
    private fun ownWatchNextRows(context: Context): List<WatchNextProgram> =
        context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(WatchNextProgram.fromCursor(cursor)) }
        }.orEmpty()

    /**
     * 有进度的卡片用的横图: 要播那一集的 TMDB 剧照 (w1280 档), 没有就用整部背景图; 都没有 (或这次没取到) 返回 null, 卡片退回竖海报.
     * 两者都有永久缓存, 只有第一次见到的条目会走网络.
     */
    private suspend fun resolveLandscapeImage(
        tmdb: TmdbImageService,
        settingsRepository: SettingsRepository,
        info: SubjectCollectionInfo,
        episodeId: Int,
    ): String? {
        val still = try {
            tmdb.getTvEpisodeMedia(info.subjectId, info.subjectInfo, info.episodes, settingsRepository)?.get(episodeId)?.stillUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (still != null) return tmdbStillHeroSizeUrl(still)
        return try {
            tmdb.getBackdropUrl(
                info.subjectId,
                info.subjectInfo.name,
                activeAsOfDate = info.episodes.newestAiredDateStringOrNull(),
                hints = info.subjectInfo.toTmdbMatchHints(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 系统"继续观看"行: 全量重写为当前在看列表 (先删本 app 写过的行再插入, 免去 diff).
     * TvProvider 查询只会返回本 app 写入的行, 不会误删其他 app 的数据.
     *
     * 先对一遍用户的操作 ([TvPlayNextSync]): 刚被移除的这一轮就不写了 (收藏随后改成搁置); 用户在频道卡片上「添加到接下来播放」
     * 时系统复制进来的卡 (类型 WATCHLIST, 我们自己不写这种) 留着, 除非这部已经在要写的卡里 (标成在看后由我们按集画的那张替掉).
     */
    private fun writeWatchNext(context: Context, cards: List<WatchNextCard>, playNext: TvPlayNextSync) {
        val resolver = context.contentResolver
        val existing = ownWatchNextRows(context)
        val changes = playNext.detect(existing)
        playNext.apply(changes)
        val toWrite = cards.filterNot { it.entry.subjectId in changes.removed }
        val cardSubjects = toWrite.mapTo(HashSet()) { it.entry.subjectId }
        playNext.beginRewrite()
        for (row in existing) {
            if (row.watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST &&
                subjectIdOf(row.intentUri, row.internalProviderId) !in cardSubjects
            ) {
                continue
            }
            resolver.delete(TvContractCompat.buildWatchNextProgramUri(row.id), null, null)
        }

        val written = HashMap<Long, Int>()
        for ((entry, landscapeImageUri) in toWrite) {
            val episode = entry.episode
            val builder = WatchNextProgram.Builder()
                .setType(TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES)
                .setWatchNextType(episode?.watchNextType ?: TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(entry.lastEngagementTimeMillis)
                .setTitle(entry.title)
                .setDescription(entry.description)
                .setIntentUri(episode?.link?.let(Uri::parse) ?: subjectDeepLink(entry.subjectId))
                .setInternalProviderId(entry.subjectId.toString())
            if (landscapeImageUri != null) {
                builder.setPosterArtUri(Uri.parse(landscapeImageUri))
                    .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9)
            } else {
                builder.setPosterArtUri(Uri.parse(entry.posterUri))
                    .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3)
            }
            if (episode != null) {
                builder.setEpisodeTitle(episode.title)
                val sort = episode.sort
                if (sort is EpisodeSort.Normal) builder.setEpisodeNumber(sort.plainNumber(), sort.number.toInt())
                if (episode.positionMillis != null && episode.durationMillis != null) {
                    builder.setLastPlaybackPositionMillis(episode.positionMillis.toInt())
                        .setDurationMillis(episode.durationMillis.toInt())
                }
            }
            resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, builder.build().toContentValues())
                ?.let { written[ContentUris.parseId(it)] = entry.subjectId }
        }
        playNext.endRewrite(written)
        logger.info {
            "Watch-next row updated (${toWrite.size}): " + toWrite.joinToString { (entry, landscapeImageUri) ->
                val isNew = entry.episode?.watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW
                "${entry.subjectId} ${if (landscapeImageUri != null) "16:9" else "2:3"}${if (isNew) " new" else ""} ${entry.episode?.link ?: "details"}"
            }
        }
    }

    /**
     * 直接进播放器的链接. 优先 `ani://subjects/<条目>?ep=<集号>`: 按本地分集表解析, 不发网络请求;
     * 集号对不上唯一一集正片时 (没有集号 / 重号 / 不是正片) 用 `ani://episodes/<分集>`, 那条要先问 Bangumi 这一集属于哪个条目.
     */
    private fun episodeDeepLink(subjectId: Int, episode: EpisodeCollectionInfo, episodes: List<EpisodeCollectionInfo>): String {
        val ep = episode.episodeInfo.ep
        if (ep is EpisodeSort.Normal && ep.number > 0 &&
            episodes.singleOrNull { it.episodeInfo.type == EpisodeType.MainStory && it.episodeInfo.ep == ep }?.episodeId == episode.episodeId
        ) {
            return "ani://subjects/$subjectId?ep=${ep.plainNumber()}"
        }
        return "ani://episodes/${episode.episodeId}"
    }

    /**
     * 自建"热门动画"预览频道: 全量重写 (先删本频道旧节目再插入, 免去 diff).
     *
     * 热门列表只有海报和名字, 简介要逐个取条目. 频道里上次写的节目 (TvProvider 存着, 含简介) 就是现成的:
     * 热门榜没变 (同样的番、同样的顺序、标题与海报) 就整个跳过 —— 简介是空的也不重取: Bangumi 上本来就没写简介的
     * (刚公布的续作常这样) 取了还是空; 偶尔上次没取到的, 等热门榜变了跟新上榜的一起补. 变了的话已有的番照用存着的简介,
     * 只给新上榜的 (或简介是空的) 去取.
     */
    private suspend fun updateTrendingChannel(context: Context, koin: Koin) {
        // NSFW 作品在设置不是「显示」时不放 (主屏打不了码, 见 SubjectNsfw); 没有海报的不放 (卡片会是一块空白)
        val trending = koin.get<TrendsRepository>().getTrendsInfo().subjects
            .filter { SubjectNsfw.modeOf(it.bangumiId) == NsfwMode.DISPLAY }
            .take(TRENDING_LIMIT)
            .filter { it.imageLarge.isNotBlank() }
        if (trending.isEmpty()) return

        val resolver = context.contentResolver
        val channelId = ensureChannel(context, CHANNEL_INTERNAL_ID, CHANNEL_DISPLAY_NAME)
        val existing = trendingPrograms(context, channelId)
        val descriptions = existing.associate { it.subjectId to it.description }
        val wanted = trending.map { TrendingProgram(it.bangumiId, it.nameCn, it.imageLarge, descriptions[it.bangumiId].orEmpty()) }
        if (wanted.map { it.withoutDescription() } == existing.map { it.withoutDescription() }) {
            logger.info { "Trending channel unchanged (${wanted.size} programs), skipped" }
            return
        }

        // 简介: 频道里有的照用, 没有的从服务器补 (失败留空, 不影响卡片展示, 下次再补)
        val subjectService = koin.get<SubjectService>()
        val missing = wanted.filter { it.description.isEmpty() }
        val fetched = coroutineScope {
            missing.map { program ->
                async {
                    program.subjectId to runCatching { subjectService.getSubjectCollection(program.subjectId)?.summary }
                        .getOrNull().orEmpty().let(::formatDescription)
                }
            }.map { it.await() }
        }.toMap()

        resolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = PreviewProgram.fromCursor(cursor)
                if (program.channelId == channelId) {
                    resolver.delete(TvContractCompat.buildPreviewProgramUri(program.id), null, null)
                }
            }
        }

        var inserted = 0
        wanted.forEachIndexed { index, subject ->
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                .setTitle(subject.title)
                .setDescription(fetched[subject.subjectId] ?: subject.description)
                .setPosterArtUri(Uri.parse(subject.posterUri))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setIntentUri(subjectDeepLink(subject.subjectId))
                .setInternalProviderId(subject.subjectId.toString())
                // launcher 按 weight 降序排, 保持热门榜原始顺序
                .setWeight(wanted.size - index)
                .build()
            resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues())
            inserted++
        }
        logger.info { "Trending channel updated: $inserted programs (fetched ${missing.size} summaries)" }
    }

    /** "热门动画"频道里的一个节目; [description] 已按 [formatDescription] 截好, 没有为空. */
    private data class TrendingProgram(
        val subjectId: Int,
        val title: String,
        val posterUri: String,
        val description: String,
    ) {
        /** 比较热门榜有没有变时不看简介, 见 [updateTrendingChannel]. */
        fun withoutDescription() = copy(description = "")
    }

    /** 频道里现有的节目, 按 launcher 的顺序 (weight 降序). */
    private fun trendingPrograms(context: Context, channelId: Long): List<TrendingProgram> {
        val programs = ArrayList<Pair<Int, TrendingProgram>>()
        context.contentResolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = PreviewProgram.fromCursor(cursor)
                if (program.channelId != channelId) continue
                val subjectId = program.internalProviderId?.toIntOrNull() ?: continue
                programs += program.weight to TrendingProgram(
                    subjectId,
                    program.title.orEmpty(),
                    program.posterArtUri?.toString().orEmpty(),
                    program.description.orEmpty(),
                )
            }
        }
        return programs.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * 查找或创建本 app 的一个预览频道 (按 [internalId] 认), 返回频道 id. 新建时请求 launcher 显示它 (系统弹确认框, 要 activity 的 context).
     */
    internal fun ensureChannel(context: Context, internalId: String, displayName: String): Long {
        val resolver = context.contentResolver
        resolver.query(TvContractCompat.Channels.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val channel = Channel.fromCursor(cursor)
                if (channel.internalProviderId == internalId) return channel.id
            }
        }

        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(displayName)
            .setInternalProviderId(internalId)
            .setAppLinkIntentUri(Uri.parse("ani://subjects"))
            .build()
        val uri = requireNotNull(
            resolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()),
        ) { "Failed to insert TV channel" }
        val channelId = ContentUris.parseId(uri)

        runCatching {
            ChannelLogoUtils.storeChannelLogo(
                context, channelId,
                context.packageManager.getApplicationIcon(context.packageName).toBitmap(),
            )
        }.onFailure { logger.error(it) { "Failed to store channel logo" } }

        // 请求 launcher 显示该频道 (系统会弹确认对话框). 失败不致命:
        // 用户仍可在主屏"自定义频道"里手动打开
        runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
            .onFailure { logger.error(it) { "Failed to request channel browsable" } }
        return channelId
    }

    internal fun subjectDeepLink(subjectId: Int): Uri = Uri.parse("ani://subjects/$subjectId")

    internal fun formatDescription(summary: String): String {
        return summary.replace('\n', ' ').trim().take(DESCRIPTION_MAX_LENGTH)
    }
}

/** 集号写成 `5` / `5.5` (`toString` 会补零成 `05`). */
internal fun EpisodeSort.Normal.plainNumber(): String =
    if (number % 1f == 0f) number.toInt().toString() else number.toString()

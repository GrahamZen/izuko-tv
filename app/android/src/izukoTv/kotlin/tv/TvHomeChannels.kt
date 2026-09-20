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
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.network.TrendsRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 向安卓系统主屏 (Android TV / Google TV launcher) 写入预览频道内容:
 *
 * - 系统 "继续观看" (Watch Next) 行: 当前在看的番剧, 按最近看过排序
 * - 自建 "热门动画" 频道: 最近热门番剧 (探索页同款数据)
 *
 * 卡片点击通过现有 deep link `ani://subjects/{id}` 跳转到条目详情页.
 * 数据由系统 TvProvider 数据库保存并由 launcher 展示, 不需要 app 常驻.
 */
object TvHomeChannels {
    private val logger = logger<TvHomeChannels>()

    private const val CHANNEL_INTERNAL_ID = "animeko_trending"
    private const val CHANNEL_DISPLAY_NAME = "热门动画"
    private const val TRENDING_LIMIT = 15
    private const val WATCH_NEXT_LIMIT = 10
    private const val DESCRIPTION_MAX_LENGTH = 200

    private val trendingUpdatedThisProcess = AtomicBoolean(false)

    /** 两个 activity 交替时可能有两个收集协程重叠, 别让它们同时改同一批行. */
    private val watchNextMutex = Mutex()

    /**
     * 在 MainActivity 启动后调用, 非 TV 设备直接跳过. **不会返回**: 系统"继续观看"行要一直跟着
     * 本地收藏库重写.
     *
     * - 热门动画频道: 每进程只写一次 (要发网络请求, 内容一天也变不了几次);
     * - 系统"继续观看"行: 只在进程启动时写一次的话, 用户在详情页把番标成"看过"之后, 主屏那一行
     *   还会挂着它直到下次冷启动.
     */
    suspend fun keepUpdated(context: Context, koin: Koin) {
        if (!isTvDevice(context)) return

        if (trendingUpdatedThisProcess.compareAndSet(false, true)) {
            runCatching { updateTrendingChannel(context, koin) }
                .onFailure { logger.error(it) { "Failed to update trending channel" } }
        }

        // 长期收集期间不拿着 activity: 只有上面弹添加频道确认框那一步需要它
        val appContext = context.applicationContext
        koin.get<SubjectCollectionRepository>()
            .mostRecentlyUpdatedSubjectCollectionsFlow(WATCH_NEXT_LIMIT, listOf(UnifiedCollectionType.DOING))
            // 只取卡片真正用到的字段: 看一集、刷新一次都会让上游重新发射, 而那些变化与这一行无关
            .map { list -> list.mapNotNull { it.toWatchNextEntry() } }
            .distinctUntilChanged()
            .collectLatest { entries ->
                runCatching {
                    watchNextMutex.withLock {
                        // 调用方在主线程的 lifecycleScope 里
                        withContext(Dispatchers.IO_) { writeWatchNext(appContext, entries) }
                    }
                }
                    .onFailure { logger.error(it) { "Failed to update watch-next row" } }
            }
    }

    private fun isTvDevice(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** 一张"继续观看"卡片的全部内容: 用它做 [distinctUntilChanged] 的判据, 见 [keepUpdated]. */
    private data class WatchNextEntry(
        val subjectId: Int,
        val title: String,
        val description: String,
        val posterUri: String,
        val lastEngagementTimeMillis: Long,
    )

    /** 没有海报的条目不写进主屏 (卡片会是一块空白). */
    private fun SubjectCollectionInfo.toWatchNextEntry(): WatchNextEntry? {
        val subject = subjectInfo
        if (subject.imageLarge.isBlank()) return null
        return WatchNextEntry(
            subjectId = subject.subjectId,
            title = subject.displayName,
            description = formatDescription(subject.summary),
            posterUri = subject.imageLarge,
            lastEngagementTimeMillis = lastUpdated,
        )
    }

    /**
     * 系统"继续观看"行: 全量重写为当前在看列表 (先删本 app 写过的行再插入, 免去 diff).
     * TvProvider 查询只会返回本 app 写入的行, 不会误删其他 app 的数据.
     */
    private fun writeWatchNext(context: Context, entries: List<WatchNextEntry>) {
        val resolver = context.contentResolver
        resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val existing = WatchNextProgram.fromCursor(cursor)
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(existing.id), null, null)
            }
        }

        for (entry in entries) {
            val program = WatchNextProgram.Builder()
                .setType(TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(entry.lastEngagementTimeMillis)
                .setTitle(entry.title)
                .setDescription(entry.description)
                .setPosterArtUri(Uri.parse(entry.posterUri))
                .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3)
                .setIntentUri(subjectDeepLink(entry.subjectId))
                .setInternalProviderId(entry.subjectId.toString())
                .build()
            resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
        }
        logger.info { "Watch-next row updated: ${entries.size} programs" }
    }

    /**
     * 自建"热门动画"预览频道: 全量重写 (先删本频道旧节目再插入, 免去 diff).
     */
    private suspend fun updateTrendingChannel(context: Context, koin: Koin) {
        val trending = koin.get<TrendsRepository>().getTrendsInfo().subjects.take(TRENDING_LIMIT)
        if (trending.isEmpty()) return

        // 热门列表只有海报和名字, 简介逐个从服务器补 (失败留空, 不影响卡片展示)
        val subjectService = koin.get<SubjectService>()
        val summaries = coroutineScope {
            trending.map { subject ->
                async {
                    runCatching { subjectService.getSubjectCollection(subject.bangumiId)?.summary }
                        .getOrNull().orEmpty()
                }
            }.map { it.await() }
        }

        val resolver = context.contentResolver
        val channelId = ensureChannel(context)

        resolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val existing = PreviewProgram.fromCursor(cursor)
                if (existing.channelId == channelId) {
                    resolver.delete(TvContractCompat.buildPreviewProgramUri(existing.id), null, null)
                }
            }
        }

        var inserted = 0
        trending.forEachIndexed { index, subject ->
            if (subject.imageLarge.isBlank()) return@forEachIndexed
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                .setTitle(subject.nameCn)
                .setDescription(formatDescription(summaries[index]))
                .setPosterArtUri(Uri.parse(subject.imageLarge))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setIntentUri(subjectDeepLink(subject.bangumiId))
                .setInternalProviderId(subject.bangumiId.toString())
                // launcher 按 weight 降序排, 保持热门榜原始顺序
                .setWeight(trending.size - index)
                .build()
            resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues())
            inserted++
        }
        logger.info { "Trending channel updated: $inserted programs" }
    }

    /**
     * 查找或创建"热门动画"频道, 返回频道 id.
     */
    private fun ensureChannel(context: Context): Long {
        val resolver = context.contentResolver
        resolver.query(TvContractCompat.Channels.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val channel = Channel.fromCursor(cursor)
                if (channel.internalProviderId == CHANNEL_INTERNAL_ID) return channel.id
            }
        }

        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(CHANNEL_DISPLAY_NAME)
            .setInternalProviderId(CHANNEL_INTERNAL_ID)
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

    private fun subjectDeepLink(subjectId: Int): Uri = Uri.parse("ani://subjects/$subjectId")

    private fun formatDescription(summary: String): String {
        return summary.replace('\n', ' ').trim().take(DESCRIPTION_MAX_LENGTH)
    }
}

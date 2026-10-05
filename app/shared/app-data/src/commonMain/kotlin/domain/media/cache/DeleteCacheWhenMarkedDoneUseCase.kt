/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 「标记看过后删除缓存」(设置 - 存储, [MediaCacheSettings.deleteWhenMarkedDone]): 手动把一部番标成「看过」时, 删掉它在本机的全部缓存
 * (下载中的也删).
 *
 * 包在 [SetSubjectCollectionTypeOrDeleteUseCase] 外面 (见 UseCaseModules): 电视与手机界面上各处的收藏菜单、Web 控制台改收藏都走这个用例;
 * 自动改收藏的 (导入 Bangumi、恢复档案) 直接改仓库, 不经过它. 删除在自己的作用域里跑, 不拖住改收藏的调用方.
 * 眼前播放页正开着的那一集 ([PlaybackActivity]) 在读它的缓存, 等它不在放这一集了 (或退出播放页, TV 保留着的会话也算退出) 再删.
 * 删了几集经 [deletions] 交给电视弹提示.
 */
class DeleteCacheWhenMarkedDoneUseCase(
    private val delegate: SetSubjectCollectionTypeOrDeleteUseCase,
    private val settings: Settings<MediaCacheSettings>,
    private val downloadManager: MediaDownloadManager,
    private val playbackActivity: PlaybackActivity,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SetSubjectCollectionTypeOrDeleteUseCase {
    override suspend fun invoke(subjectId: Int, collectionType: UnifiedCollectionType?) {
        delegate(subjectId, collectionType)
        if (collectionType != UnifiedCollectionType.DONE) return
        // 开关也在自己的作用域里读: 调用方改完收藏随时可能被取消 (控制台的请求有限时), 不能连删除一起带走
        scope.launch {
            if (settings.flow.first().deleteWhenMarkedDone) deleteCaches(subjectId)
        }
    }

    /** 删掉 [subjectId] 的缓存; 返回立刻删掉的条数 (播放页正开着的那一集不算, 它稍后再删). */
    internal suspend fun deleteCaches(subjectId: Int): Int {
        val caches = downloadManager.downloads.first()
            .map { it.cache }
            .filter { !it.isDeleted.value && it.metadata.subjectId.toIntOrNull() == subjectId }
            .distinctBy { it.cacheId }
        if (caches.isEmpty()) return 0
        val playing = playbackActivity.currentValue
            ?.takeIf { it.subjectId == subjectId && playbackActivity.onScreen.value }?.episodeId
        val (inUse, now) = caches.partition { playing != null && it.metadata.episodeId.toIntOrNull() == playing }
        val deleted = deleteAll(now)
        val metadata = caches.first().metadata
        if (deleted > 0 || inUse.isNotEmpty()) _deletions.tryEmit(
            Deleted(
                subjectName = metadata.subjectNameCN?.takeIf { it.isNotBlank() } ?: metadata.subjectNames.firstOrNull().orEmpty(),
                count = deleted,
                waitingForPlayback = inUse.size,
            ),
        )
        if (inUse.isNotEmpty()) {
            // 播放页不在放这一集了 (退出、换集、播完), 或退到了后台 (TV 保留着的会话) 再删
            combine(playbackActivity.current, playbackActivity.onScreen) { current, onScreen ->
                !onScreen || current == null || current.subjectId != subjectId || current.episodeId != playing
            }.first { it }
            deleteAll(inUse)
        }
        return deleted
    }

    /**
     * 各集一起发出去, 返回删掉的条数. 在线缓存的存储只有一把锁, 新建一条要持锁解析与探测 (几秒, 节点卡住时更久), 锁按先来后到发:
     * 逐集删的话, 遇上别处正在批量新建, 每删一集都要排在一整条新建后面; 一起排上则连着删完, 最多等当前那一条.
     */
    private suspend fun deleteAll(caches: List<MediaCache>): Int = coroutineScope {
        caches.map { async { delete(it) } }.awaitAll().count { it }
    }

    private suspend fun delete(cache: MediaCache): Boolean {
        val m = cache.metadata
        // 每条都留一行, 取证同其它删除入口 (Delete cache requested)
        logger.info {
            "Delete cache requested (marked done): subject=${m.subjectId} ep=${m.episodeId} sort=${m.episodeSort} cacheId=${cache.cacheId}"
        }
        return try {
            downloadManager.deleteDownload(cache)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Delete failed for cache ${cache.cacheId}" }
            false
        }
    }

    /**
     * 删了一部番的缓存.
     *
     * @param count 立刻删掉的集数
     * @param waitingForPlayback 播放页正开着、退出后才删的集数
     */
    class Deleted(val subjectName: String, val count: Int, val waitingForPlayback: Int)

    companion object {
        private val logger = logger<DeleteCacheWhenMarkedDoneUseCase>()

        private val _deletions = MutableSharedFlow<Deleted>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** 标成看过后删了缓存; 电视根界面收这个弹提示. */
        val deletions: SharedFlow<Deleted> = _deletions.asSharedFlow()
    }
}

/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.repository.subject.SubjectNsfw
import me.him188.ani.app.data.models.trending.TrendingSubjectInfo
import me.him188.ani.app.data.models.trending.TrendsInfo
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.runWrappingExceptionAsLoadResult
import me.him188.ani.app.tools.paging.SinglePagePagingSource
import me.him188.ani.datasources.bangumi.next.apis.TrendingBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextGetTrendingSubjects200Response
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.hours

class TrendsRepository(
    private val trendsApi: ApiInvoker<TrendingBangumiNextApi>,
    /** 热度榜头一页落盘的文件, 见 [firstPage]; `null` = 只在内存里共用. */
    cacheFile: SystemPath? = null,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
    private val clock: () -> Long = { currentTimeMillis() },
    /** 头一页过期时在这里后台取新的 (见 [firstPage]); `null` = 过期就当场等请求. */
    private val backgroundScope: CoroutineScope? = null,
) : Repository() {
    private val diskCache = cacheFile?.let { JsonFileCache(it, SavedFirstPage.serializer(), ioDispatcher) }
    private val firstPageLock = Mutex()
    private var firstPage: SavedFirstPage? = null
    private var refreshJob: Job? = null

    private val _firstPageRefreshed = MutableSharedFlow<TrendsInfo>(extraBufferCapacity = 1)

    /**
     * 后台取到了新的头一页, 而且条目与在用的那份不同 (见 [firstPage]). 这时轮播还放着旧的那份;
     * 探索页借它趁空闲把新的这批预热好 (条目信息、背景图), 下次打开应用时轮播直接就绪.
     */
    val firstPageRefreshed: SharedFlow<TrendsInfo> = _firstPageRefreshed.asSharedFlow()

    /**
     * 热度榜的一页.
     *
     * @param offset 从第几名起. 探索页顶上的 hero 轮播放的就是 `offset = 0` 那一页的**全部**
     *   [TRENDING_LIMIT] 条, 所以推荐区那一行必须从 [TRENDING_LIMIT] 名往后取 —— 否则整行都是
     *   用户刚在轮播里转过一遍的 (2026-09-07 用户反馈"重复太多"). 榜一共 1000 条 (实测), 往后
     *   翻几十名依然是"大家最近在看".
     */
    suspend fun getTrendsInfo(limit: Int = TRENDING_LIMIT, offset: Int = 0): TrendsInfo {
        if (limit == TRENDING_LIMIT && offset == 0) return firstPage()
        return fetch(limit, offset)
    }

    // bangumi 的每日热度榜
    fun trendsInfoPager(): Flow<PagingData<TrendsInfo>> {
        return Pager(defaultPagingConfig) {
            SinglePagePagingSource<Unit, TrendsInfo> {
                runWrappingExceptionAsLoadResult<Unit, TrendsInfo> {
                    PagingSource.LoadResult.Page(
                        listOf(firstPage()),
                        null,
                        null,
                    )
                }.also {
                    if (it is PagingSource.LoadResult.Error) {
                        logger.error(it.throwable) { "Failed to load ani trends info." }
                    }
                }
            }
        }.flow
    }

    /**
     * 热度榜头一页 ([TRENDING_LIMIT] 条). 探索页轮播、推荐重算 (躲开轮播在放的)、TV 主屏频道、屏保都要它:
     * [FIRST_PAGE_TTL] 内共用一份, 同时来问的只发一次请求; 并且落盘, 冷启动时轮播不用先等这个请求.
     *
     * 过期了也先用手上那份, 同时在后台取新的 ([backgroundScope]): 冷启动时轮播不等网络 (大陆经镜像取一次要十几秒).
     * 榜是按天滚动的, 旧一点的那份放一次轮播无妨; 取到的新一页给之后来问的 (这个进程里的推荐重算、下次启动的轮播),
     * 不去换正在放的轮播. 手上一份都没有时才当场等请求.
     */
    private suspend fun firstPage(): TrendsInfo = firstPageLock.withLock {
        val cached = firstPage ?: diskCache?.read()?.also { firstPage = it }
        if (cached != null) {
            if (clock() - cached.fetchedAt in 0 until FIRST_PAGE_TTL.inWholeMilliseconds) {
                return@withLock TrendsInfo(cached.subjects)
            }
            if (backgroundScope != null) {
                refreshInBackground(backgroundScope)
                return@withLock TrendsInfo(cached.subjects)
            }
        }
        val fetched = fetch(TRENDING_LIMIT, 0)
        val saved = SavedFirstPage(clock(), fetched.subjects)
        firstPage = saved
        diskCache?.write(saved)
        fetched
    }

    /** 持 [firstPageLock] 调用. 同一时间只有一次. */
    private fun refreshInBackground(scope: CoroutineScope) {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val fetched = try {
                fetch(TRENDING_LIMIT, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to refresh trending in background, keeping the cached page" }
                return@launch
            }
            val changed = firstPageLock.withLock {
                val previous = firstPage
                val saved = SavedFirstPage(clock(), fetched.subjects)
                firstPage = saved
                diskCache?.write(saved)
                previous?.subjects?.map { it.bangumiId } != saved.subjects.map { it.bangumiId }
            }
            if (changed) _firstPageRefreshed.tryEmit(fetched)
        }
    }

    private suspend fun fetch(limit: Int, offset: Int): TrendsInfo = withContext(ioDispatcher) {
        trendsApi {
            getTrendingSubjects(BangumiNextSubjectType.Anime, limit = limit, offset = offset)
                .body().toTrendsInfo()
        }
    }

    @Serializable
    private class SavedFirstPage(
        val fetchedAt: Long,
        val subjects: List<TrendingSubjectInfo>,
    )

    companion object {
        /**
         * 热度榜一页给多少条.
         *
         * **同时也是"用户已经在屏幕上看过的那一批"的边界**: 探索页的 hero 轮播就是拿
         * [trendsInfoPager] 这一页渲染的, 一条不落 (轮播圆点上限恰好也是 20). 推荐区想避开
         * 重复就得知道这个数.
         */
        const val TRENDING_LIMIT = 20

        /** 探索页 hero 轮播放头一页的前几条. 预热 (见 [firstPageRefreshed]) 也只热这几条. */
        const val HERO_CAROUSEL_SIZE = 10

        /** 热度榜头一页共用多久, 见 [firstPage]. 榜是按天滚动的, 一小时里前二十名变不了几个. */
        private val FIRST_PAGE_TTL = 1.hours
    }
}

fun BangumiNextGetTrendingSubjects200Response.toTrendsInfo(): TrendsInfo {
    logger<TrendsRepository>().info { "bgm-direct: trending -> ${data.size}" }
    return TrendsInfo(
        subjects = data.map {
            SubjectNsfw.record(it.subject.id, it.subject.nsfw)
            TrendingSubjectInfo(
                it.subject.id,
                it.subject.nameCN.ifEmpty { it.subject.name },
                it.subject.images?.large.orBangumiPlaceholder(),
            )
        },
    )
}

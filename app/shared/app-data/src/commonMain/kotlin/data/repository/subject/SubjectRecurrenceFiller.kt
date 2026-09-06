/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.domain.episode.EpisodeCompletionContext
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.toLocalDateOrNull
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 条目的播出周期 ([SubjectRecurrence]) 怎么进条目表.
 *
 * 播出周期只用来把分集的播出日期换成精确的播出时刻, 判断一集播了没有 (见 [EpisodeCompletionContext]);
 * 没有它时按播出日当天 0 点 (日本时间) 算, 两种算法只在开播前后约 30 小时里有差别.
 *
 * - **落库时不碰它**: 规则在时间表缓存里 (冷进程第一次读要把近 300 KB 解一遍, 一秒多), 缓存里没有还要下
 *   bangumi-data 的月文件、再把时间表缓存整份重写一遍 —— 而没见过的条目 (推荐卡、从主屏频道直接进的详情页)
 *   第一次出来时, 大图区与详情页等的就是这次落库.
 * - 库里没有的, 落库之后在后台补 ([fillLater]), 补到了只改这一列; 页面订阅的是条目表, 会自己刷新.
 * - 播完了的不补 (见 [mayStillAir]): 有没有播出周期, 这些集的结论都一样.
 */
internal class SubjectRecurrenceFiller(
    private val animeScheduleRepository: AnimeScheduleRepository,
    private val subjectCollectionDao: SubjectCollectionDao,
    private val scope: CoroutineScope,
    private val getCurrentDate: () -> PackedDate,
) {
    private val logger = logger<SubjectRecurrenceFiller>()

    /**
     * 在后台查 [subjectId] 的播出周期, 查到就写回条目表. 播完了的 (见 [mayStillAir]) 不查.
     *
     * @param firstAirDate 条目的开播日期 (`yyyy-MM-dd`), 用来定位 bangumi-data 的月文件
     * @param episodes 条目的分集, 用来判断还在不在播
     */
    fun fillLater(subjectId: Int, firstAirDate: String?, episodes: List<EpisodeCollectionEntity>) {
        if (!mayStillAir(episodes, firstAirDate, getCurrentDate())) return
        scope.launch {
            try {
                val recurrence = animeScheduleRepository.getSubjectRecurrence(subjectId, firstAirDate) ?: return@launch
                subjectCollectionDao.updateRecurrence(subjectId, recurrence.startTime, recurrence.interval)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "bgm-direct: subject $subjectId 播出周期没补上" }
            }
        }
    }
}

/**
 * 条目是不是可能还在播, 两种情况算:
 * - 有正片的播出日期在 [FINISHED_AFTER_DAYS] 天之内 (含将来). 余量盖住播出周期能影响的全部范围:
 *   精确的播出时刻最晚是播出日 0 点 (日本时间) 之后 30 小时;
 * - 有正片没有播出日期, 且条目首播在一年之内 (或不知道首播日期): 可能是还没定档的后续集.
 *   首播更早的条目没日期多半是 Bangumi 没填, 查到了规则反而会把这些集画成「未开播」.
 *
 * 没有正片 (还没出分集) 时算不在播: 没有集要判断, 分集出来之后条目再刷新时会再判一次.
 *
 * @param firstAirDate 条目的首播日期 (`yyyy-MM-dd`)
 */
internal fun mayStillAir(episodes: List<EpisodeCollectionEntity>, firstAirDate: String?, today: PackedDate): Boolean {
    val mainStory = episodes.filter { it.episodeType == EpisodeType.MainStory }
    if (mainStory.isEmpty()) return false
    val todayDate = today.toLocalDateOrNull() ?: return true
    val airDates = mainStory.map { it.airDate.toLocalDateOrNull() }
    val cutoff = todayDate.minus(FINISHED_AFTER_DAYS, DateTimeUnit.DAY)
    if (airDates.any { it != null && it > cutoff }) return true
    if (airDates.none { it == null }) return false
    val firstAir = firstAirDate?.let { PackedDate.parseFromDate(it).toLocalDateOrNull() } ?: return true
    return firstAir > todayDate.minus(1, DateTimeUnit.YEAR)
}

/** 正片最后一集播出多少天之后, 播出周期就不影响任何判断了, 见 [mayStillAir]. */
private const val FINISHED_AFTER_DAYS = 2

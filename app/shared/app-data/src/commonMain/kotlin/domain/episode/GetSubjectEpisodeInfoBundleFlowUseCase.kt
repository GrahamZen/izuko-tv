/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.usecase.UseCase
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface GetSubjectEpisodeInfoBundleFlowUseCase : UseCase {
    data class SubjectIdAndEpisodeId(
        val subjectId: Int,
        val episodeId: Int
    )

    operator fun invoke(idsFlow: Flow<SubjectIdAndEpisodeId>): Flow<SubjectEpisodeInfoBundle>
}

class GetSubjectEpisodeInfoBundleFlowUseCaseImpl(
    private val flowContext: CoroutineContext = Dispatchers.Default,
) : GetSubjectEpisodeInfoBundleFlowUseCase, KoinComponent {
    private val subjectCollectionRepository: SubjectCollectionRepository by inject()

    override fun invoke(idsFlow: Flow<GetSubjectEpisodeInfoBundleFlowUseCase.SubjectIdAndEpisodeId>): Flow<SubjectEpisodeInfoBundle> {
        return idsFlow.flatMapLatest { (subjectId, episodeId) ->
            // 这里只需要查询一个网络请求 — subject collection. 
            subjectEpisodeInfoBundleFlow(subjectCollectionRepository.subjectCollectionFlow(subjectId), subjectId, episodeId)
        }.flowOn(flowContext)
    }
}

private val logger = logger<GetSubjectEpisodeInfoBundleFlowUseCaseImpl>()

/** 本地剧集列表里暂时没有要找的那一集时, 最多等这么久. */
internal val EPISODE_ARRIVAL_WAIT: Duration = 15.seconds

/**
 * 从条目的收藏信息里取出 [episodeId] 那一集, 组成 [SubjectEpisodeInfoBundle].
 *
 * 本地库里条目常常先于剧集列表存在 (从播放记录直接进播放页时就是这样): 第一次拿到的条目里还没有这一集,
 * 剧集列表随后才从 Bangumi 拉回来, [subjectCollection] 会再发一次. 所以找不到时先跳过, 等后面的数据;
 * 等满 [episodeWait] 仍找不到, 或者 [subjectCollection] 已经结束, 才抛 [NoSuchElementException] 交给界面报错.
 * 找到过一次之后, 后来的数据里偶尔缺这一集就跳过, 沿用上一次的结果.
 */
internal fun subjectEpisodeInfoBundleFlow(
    subjectCollection: Flow<SubjectCollectionInfo>,
    subjectId: Int,
    episodeId: Int,
    episodeWait: Duration = EPISODE_ARRIVAL_WAIT,
): Flow<SubjectEpisodeInfoBundle> = flow {
    fun notFound() = NoSuchElementException("Episode $episodeId not found in subject $subjectId")
    var found = false
    coroutineScope {
        val deadline = launch {
            delay(episodeWait)
            throw notFound()
        }
        subjectCollection.collect { subject ->
            val episodeCollectionInfo = subject.episodes.find { it.episodeId == episodeId }
            if (episodeCollectionInfo == null) {
                if (!found) {
                    logger.info { "Episode $episodeId not in subject $subjectId yet (${subject.episodes.size} episodes), waiting for more data" }
                }
                return@collect
            }
            found = true
            deadline.cancel()
            emit(
                SubjectEpisodeInfoBundle(
                    subjectId, episodeId,
                    subject,
                    episodeCollectionInfo,
                    seriesInfo = SubjectSeriesInfo.compute(subject),
                    subjectCompleted = EpisodeCollections.isSubjectCompleted(
                        subject.episodes.map { it.episodeInfo },
                        subject.recurrence,
                    ),
                ),
            )
        }
        if (!found) throw notFound()
        deadline.cancel()
    }
}

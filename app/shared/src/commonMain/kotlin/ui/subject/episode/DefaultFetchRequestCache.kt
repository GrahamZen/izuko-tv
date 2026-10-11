/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import me.him188.ani.app.domain.episode.SubjectEpisodeInfoBundle
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 播放页状态里的「默认查询请求」(修改搜索名时的初始值): 只随条目与剧集信息变, 按这份信息记住.
 *
 * 播放页状态由十几个流合成, 搜索期间候选、统计这些每变一次都会重算; 而推导回退搜索关键词要把条目的每个名字与系列里的每个名字两两归一化比较
 * (系列大时几百次正则), 每次都现算的话光这一项就占后台 CPU 近一成. 只在一个合成流里用, 不能并发调用.
 */
internal class DefaultFetchRequestCache {
    private var bundle: SubjectEpisodeInfoBundle? = null
    private var request: MediaFetchRequest? = null

    fun of(bundle: SubjectEpisodeInfoBundle): MediaFetchRequest {
        request?.takeIf { this.bundle === bundle }?.let { return it }
        return MediaFetchRequest.create(
            bundle.subjectCollectionInfo.subjectInfo, bundle.episodeCollectionInfo.episodeInfo,
            bundle.subjectCollectionInfo.episodes.map { episode -> episode.episodeInfo }, bundle.seriesInfo,
        ).also {
            this.bundle = bundle
            request = it
        }
    }
}

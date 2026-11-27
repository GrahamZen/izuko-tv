/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import me.him188.ani.app.ui.foundation.AniImageLoadSuccess
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.rememberAsyncImageRetryState
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy

/**
 * 竖版封面下载失败后最多再试几次 (退避 0.5 秒 × 次数, 见 rememberAsyncImageRetryState). 一页只有这一张, 多试几次不费什么:
 * 网络卡的那一阵里一次请求要十几秒才失败 (Shield 经热点实测连着 4 次 3 秒读超时), 只试 3 次常常还没等到网络恢复就放弃了.
 */
private const val TV_DETAILS_COVER_RETRIES = 8

/**
 * 详情页上的竖版封面 (选集页右上角、没有横版背景时的首屏、播放器内嵌介绍页). 详情页用的这一档 (r/600) 列表页不用,
 * 第一次进这部的详情页才下载; 下载失败按次数退避重试, 不然一次失败封面就一直空着, 网络好了也不补.
 * NSFW 设为模糊时打码 (见 NsfwPolicy).
 */
@Composable
internal fun TvDetailsCoverImage(
    url: String,
    subjectId: Int,
    modifier: Modifier = Modifier,
    onSuccess: ((AniImageLoadSuccess) -> Unit)? = null,
) {
    val retry = rememberAsyncImageRetryState(url, TV_DETAILS_COVER_RETRIES)
    AsyncImage(
        if (retry.suppressed) null else url,
        contentDescription = null,
        modifier,
        contentScale = ContentScale.Crop,
        onSuccess = onSuccess,
        onError = { retry.onError() },
        downsampleLongEdgePx = rememberNsfwPolicy().coverDownsample(subjectId),
    )
}

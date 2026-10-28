/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.mediasource.clouddrive.DriveFolderLocation
import me.him188.ani.app.domain.mediasource.clouddrive.DriveShareLocation

/**
 * 播放线索: 一部番正在顺着看的那个资源, 记下「下一集从哪里接着找」. 切集时先顺着它找 (见 [PlaybackLineageSelection]),
 * 找不到才按搜索结果与选源设置自动选. 每种资源怎么记、怎么顺着找由 [EpisodeContinuation] 的实现决定.
 *
 * 何时换线索见 [PlaybackLineageRecorder]: 第一次播、用户手动换源、原来的线索彻底失效; 线索暂时找不到这一集时自动兜底选上的资源不换掉它.
 */
@Serializable
sealed interface PlaybackLineage {
    /** 记下时那条资源的数据源 */
    val mediaSourceId: String
}

/** 网盘分享里的一个文件夹 (分享搜索、我添加的分享): 下一集在同一个分享的同一个文件夹 (同一个版本) 里找. */
@Serializable
@SerialName("drive-share")
data class DriveShareLineage(
    override val mediaSourceId: String,
    val driveId: String,
    val share: DriveShareLocation,
) : PlaybackLineage

/** 自己网盘里的一个文件夹: 下一集在同一个文件夹里找. */
@Serializable
@SerialName("drive-folder")
data class DriveFolderLineage(
    override val mediaSourceId: String,
    val driveId: String,
    val folder: DriveFolderLocation,
) : PlaybackLineage

/**
 * 在线源 (网页抓取、规则源、JSON 接口、媒体服务器等) 上的一个条目页的一条线路: 下一集在同一个源、站点上同一个条目、同一条线路里找.
 *
 * @property subjectName 站点上的条目名 ([me.him188.ani.datasources.api.MediaProperties.subjectName]), 区分同一个源搜到的几个条目页
 * @property channel 线路名 ([me.him188.ani.datasources.api.MediaProperties.alliance]), 站点没有线路时为空
 * @property subjectUrl 这个条目页的地址 (从搜索缓存里按播放地址查到的); 有时下一集直接打开这一页挑
 *   ([me.him188.ani.app.domain.mediasource.web.SubjectPageMediaSource]), 不再让这个源整轮搜一遍. 没缓存过的为 null
 */
@Serializable
@SerialName("web-source")
data class WebSourceLineage(
    override val mediaSourceId: String,
    val subjectName: String,
    val channel: String,
    val subjectUrl: String? = null,
) : PlaybackLineage

/**
 * BT 资源: 整季包 ([packMediaId]) 在同一个种子里接着找; 单集在同一个字幕组、同分辨率、同字幕语言的发布里找.
 *
 * @property packMediaId 整季包 (一个种子含多集) 的资源 id, 单集为 null
 */
@Serializable
@SerialName("torrent")
data class TorrentLineage(
    override val mediaSourceId: String,
    val packMediaId: String?,
    val alliance: String,
    val resolution: String,
    val subtitleLanguageIds: List<String>,
) : PlaybackLineage

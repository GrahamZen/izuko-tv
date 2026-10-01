/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable

/**
 * 用户给各个条目添加的夸克分享链接 (在 Web 控制台的播放器页粘贴): 播放这个条目时「我添加的分享」数据源打开它们找剧集.
 *
 * 存在整机设置里, 所有用户共用 (与数据源一样).
 *
 * @property subjects 条目 id 到这个条目添加过的分享, 按添加顺序
 */
@Serializable
data class QuarkAddedShares(
    val subjects: Map<Int, List<QuarkAddedShare>> = emptyMap(),
) {
    fun of(subjectId: Int): List<QuarkAddedShare> = subjects[subjectId].orEmpty()

    /**
     * 给 [subjectId] 加上 [share]; 同一个分享已经加过时换成新的 (提取码、标题可能变了), 位置不变, 手动指定过的文件与新的合并,
     * 记下的剧集保留.
     */
    fun plus(subjectId: Int, share: QuarkAddedShare): QuarkAddedShares {
        val current = of(subjectId)
        val index = current.indexOfFirst { it.shareId == share.shareId }
        val updated = if (index < 0) {
            current + share
        } else {
            current.toMutableList().apply {
                val old = this[index]
                this[index] = share.copy(picks = old.picks + share.picks, files = share.files.ifEmpty { old.files })
            }
        }
        return copy(subjects = subjects + (subjectId to updated))
    }

    /** 换掉 [subjectId] 下分享 [shareId] 记下的剧集; 没有这个分享时不变. */
    fun withFiles(subjectId: Int, shareId: String, files: List<QuarkAddedShareFile>): QuarkAddedShares {
        val current = of(subjectId)
        if (current.none { it.shareId == shareId }) return this
        val updated = current.map { if (it.shareId == shareId) it.copy(files = files) else it }
        return copy(subjects = subjects + (subjectId to updated))
    }

    fun minus(subjectId: Int, shareId: String): QuarkAddedShares {
        val remaining = of(subjectId).filterNot { it.shareId == shareId }
        return copy(subjects = if (remaining.isEmpty()) subjects - subjectId else subjects + (subjectId to remaining))
    }

    companion object {
        val Default = QuarkAddedShares()
    }
}

/**
 * @property title 分享的标题 (夸克给的), 用来显示与认季
 * @property addedAtMillis 添加的时刻
 * @property picks 用户手动指定的文件: 分享里的文件 id 到它是第几集 (集号的字符串写法). 认不出集号的文件靠它对上,
 * 也盖过自动认出的集号
 * @property files 最近一次打开分享时对上的剧集. 分享后来被清空或打不开时, 其中已经转存到自己网盘的照常能播
 */
@Serializable
data class QuarkAddedShare(
    val shareId: String,
    val passcode: String = "",
    val title: String = "",
    val addedAtMillis: Long = 0,
    val picks: Map<String, String> = emptyMap(),
    val files: List<QuarkAddedShareFile> = emptyList(),
)

/**
 * 分享里对上的一个剧集文件 (转存要用的字段都在).
 *
 * @property folders 从分享根到文件所在文件夹的名字
 * @property episode 第几集 (集号的字符串写法)
 */
@Serializable
data class QuarkAddedShareFile(
    val fid: String,
    val fileName: String,
    val size: Long = 0,
    val shareFidToken: String = "",
    val folders: List<String> = emptyList(),
    val episode: String,
    /** 在分享里所在文件夹的 id, 播放时到这里找外挂字幕. */
    val parentFid: String = "",
)

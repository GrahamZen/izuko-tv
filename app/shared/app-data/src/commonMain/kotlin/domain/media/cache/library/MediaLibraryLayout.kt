/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata

/**
 * 片库里的文件怎么起名, 以及怎么从名字认回是哪部哪集:
 *
 * ```
 * <片库>/<番名> [bgm-<条目 ID>]/<番名> - <集序> [bgmep-<剧集 ID>].<扩展名>
 * <片库>/<番名> [bgm-<条目 ID>]/<番名> - <集序> [bgmep-<剧集 ID>].<扩展名>.izuko.json   (说明文件: 原资源与剧集信息)
 * <片库>/<番名> [bgm-<条目 ID>]/<番名> - <集序> [bgmep-<剧集 ID>].<标签>.ass             (外挂字幕, 标签是语言或序号)
 * ```
 *
 * 名字里带着编号: 说明文件丢了 (用户只拷了视频、在电脑上删了) 也能按名字认回条目与剧集.
 * 同一集有几份 (不同资源) 时, 后来的在编号后面加 ` (2)`、` (3)`.
 * 以 `.` 开头的文件与文件夹 (下载中的工作目录等) 不算片库里的视频.
 */
object MediaLibraryLayout {
    const val SIDECAR_SUFFIX = ".izuko.json"

    /** 认作视频的扩展名 (小写) */
    val videoExtensions: Set<String> = setOf("mp4", "mkv", "ts", "m2ts", "m4v", "webm", "flv", "avi", "mov")

    /** 能放进片库: 有条目与剧集 ID (按名字认回来全靠它们) */
    fun canPlace(metadata: MediaCacheMetadata): Boolean =
        metadata.subjectId.toIntOrNull() != null && metadata.episodeId.toIntOrNull() != null

    fun subjectDirName(metadata: MediaCacheMetadata): String = "${displayName(metadata)} [bgm-${metadata.subjectId}]"

    /** 视频文件名去掉扩展名; [copy] 从 1 起, 大于 1 时加 ` (copy)` */
    fun episodeBaseName(metadata: MediaCacheMetadata, copy: Int = 1): String = buildString {
        append(displayName(metadata)).append(" - ").append(sortText(metadata.episodeSort))
        append(" [bgmep-").append(metadata.episodeId).append(']')
        if (copy > 1) append(" (").append(copy).append(')')
    }

    /** 条目文件夹名里的条目 ID; 不是片库的条目文件夹时为 `null` */
    fun parseSubjectDir(name: String): SubjectDir? {
        if (name.startsWith(".")) return null
        val match = SUBJECT_ID.findAll(name).lastOrNull() ?: return null
        val subjectId = match.groupValues[1]
        return SubjectDir(subjectId, name.substring(0, match.range.first).trim().takeIf { it.isNotEmpty() })
    }

    /** 视频文件名 (含扩展名) 里的剧集 ID 与集序; 不是片库里的视频时为 `null` */
    fun parseEpisodeFile(fileName: String): EpisodeFile? {
        if (fileName.startsWith(".")) return null
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null
        val extension = fileName.substring(dot + 1).lowercase()
        if (extension !in videoExtensions) return null
        val base = fileName.substring(0, dot)
        val match = EPISODE_ID.findAll(base).lastOrNull() ?: return null
        val head = base.substring(0, match.range.first).trim()
        val separator = head.lastIndexOf(" - ")
        return EpisodeFile(
            episodeId = match.groupValues[1],
            sort = if (separator >= 0) head.substring(separator + 3).trim().takeIf { it.isNotEmpty() } else null,
            subjectName = if (separator > 0) head.substring(0, separator).trim().takeIf { it.isNotEmpty() } else null,
            extension = extension,
        )
    }

    class SubjectDir(val subjectId: String, val subjectName: String?)

    class EpisodeFile(val episodeId: String, val sort: String?, val subjectName: String?, val extension: String)

    /** 文件名里用的番名: 中文名优先, 去掉文件系统不认的字符, 不超过 [NAME_LIMIT] 个字 */
    internal fun displayName(metadata: MediaCacheMetadata): String {
        val raw = metadata.subjectNameCN?.takeIf { it.isNotBlank() }
            ?: metadata.subjectNames.firstOrNull { it.isNotBlank() }
            ?: ""
        return clean(raw).ifEmpty { "bgm" }
    }

    internal fun clean(name: String): String {
        val replaced = buildString(name.length) {
            for (c in name) append(if (c < ' ' || c in FORBIDDEN) ' ' else c)
        }
        // 方括号留给编号, 名字里的换成全角, 免得认错编号
        val collapsed = replaced.replace('[', '［').replace(']', '］').replace(SPACES, " ").trim().trimEnd('.', ' ')
        return if (collapsed.length <= NAME_LIMIT) collapsed else collapsed.take(NAME_LIMIT).trimEnd('.', ' ')
    }

    /** 集序: 整数集补成两位 (`05`), 其余照原样 (`12.5`、`SP1`) */
    internal fun sortText(sort: EpisodeSort): String {
        val number = (sort as? EpisodeSort.Normal)?.number ?: return sort.toString()
        val whole = number.toInt()
        return if (number == whole.toFloat()) whole.toString().padStart(2, '0') else sort.toString()
    }

    private const val NAME_LIMIT = 60
    private const val FORBIDDEN = "\\/:*?\"<>|"
    private val SPACES = Regex("\\s+")
    private val SUBJECT_ID = Regex("\\[bgm-(\\d+)]")
    private val EPISODE_ID = Regex("\\[bgmep-(\\d+)]")
}

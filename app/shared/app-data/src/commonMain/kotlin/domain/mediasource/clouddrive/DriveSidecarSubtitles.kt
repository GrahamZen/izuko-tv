/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.player.tracks.LanguageTags

/**
 * 网盘里放在视频旁边的外挂字幕 (`.ass` / `.ssa` / `.srt` / `.vtt`): 认文件、配给视频、起显示名.
 *
 * 配给视频的规则, 按顺序:
 * 1. 与视频同名: `视频名.ass`, `视频名.sc.ass`, `视频名.chs&jpn.ass`. 同一个文件夹里几个视频的名字互为前缀时
 *    (`A.mkv` 与 `A.extended.mkv`), 字幕归名字最长的那个.
 * 2. 一条同名的都没有时按集号: 字幕组单发的外挂字幕常与视频不同名 (`[字幕组][标题][01][JPSC].ass` 配
 *    `[VCB-Studio][标题][01][1080p].mkv`). 只在文件夹里只有这一个视频是这一集时才配, 也不配已经按名字归了别的视频的字幕.
 */
internal object DriveSidecarSubtitles {
    /**
     * 配给视频的一个字幕文件.
     *
     * @param label 播放器字幕菜单里显示的名字
     * @param language BCP 47 语言标记, 认不出为 null
     */
    class Match<T>(val file: T, val mimeType: String, val label: String, val language: String?)

    /** 一个视频最多配几条字幕. */
    const val MAX_PER_VIDEO = 8

    fun mimeTypeOf(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> "text/x-ssa"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        else -> null
    }

    fun isSubtitle(fileName: String): Boolean = mimeTypeOf(fileName) != null

    /** 专放字幕的子文件夹 (`Subs`, `字幕`, `外挂字幕`): 视频旁边一条都没有时到这里面找. */
    fun isSubtitleFolder(name: String): Boolean = SUBTITLE_FOLDER.matches(name.trim())

    /**
     * [files] (与视频 [videoName] 在同一个文件夹里的文件, 可以再加上字幕子文件夹里的) 里配给这个视频的字幕, 简体在前.
     * 显示名可能重复, 交给播放器前过一遍 [numbered].
     *
     * @param isVideo [files] 里哪些是视频, 用来判断字幕归谁
     * @param byEpisode 允许按集号配 (规则 2). 转存文件夹里放着各部番的文件, 只能按名字配
     */
    fun <T> match(
        videoName: String,
        files: List<T>,
        nameOf: (T) -> String,
        isVideo: (T) -> Boolean,
        byEpisode: Boolean = true,
    ): List<Match<T>> {
        val videoStem = stemOf(videoName)
        val videos = files.filter(isVideo)
        val videoStems = (videos.map { stemOf(nameOf(it)) } + videoStem).distinct()
        val subtitles = files.filter { isSubtitle(nameOf(it)) }

        /** 按名字该归哪个视频 (名字最长的那个), 没有为 null. */
        fun ownerOf(subtitle: T): String? {
            val base = stemOf(nameOf(subtitle))
            return videoStems.filter { base == it || base.startsWith("$it.") }.maxByOrNull { it.length }
        }

        val named = subtitles.filter { ownerOf(it) == videoStem }
        val found: List<Pair<T, Tags>> = if (named.isNotEmpty()) {
            named.map { it to Tags.of(stemOf(nameOf(it)).removePrefix(videoStem).removePrefix("."), wholeName = false) }
        } else if (byEpisode) {
            sameEpisode(videoName, videoStem, videos, subtitles.filter { ownerOf(it) == null }, nameOf)
                .map { it to Tags.of(stemOf(nameOf(it)), wholeName = true) }
        } else {
            emptyList()
        }

        return found.sortedWith(compareBy({ it.second.rank }, { nameOf(it.first) })).take(MAX_PER_VIDEO).map { (file, tags) ->
            Match(file, mimeTypeOf(nameOf(file))!!, tags.label, tags.language)
        }
    }

    /**
     * 用户手动挂上的字幕文件 [fileName]: 显示名按文件名里的语言标记, 认不出时用文件名. 不是字幕文件时为 null.
     */
    fun <T> picked(file: T, fileName: String): Match<T>? {
        val mimeType = mimeTypeOf(fileName) ?: return null
        val tags = Tags.of(stemOf(fileName), wholeName = true)
        val label = if (tags.language != null) tags.label else fileName.substringBeforeLast('.').take(MAX_PICKED_LABEL_LENGTH)
        return Match(file, mimeType, label, tags.language)
    }

    /** 显示名重复的从第二条起加上序号 (`简体中文 2`), 菜单里分得开. */
    fun <T> numbered(matches: List<Match<T>>): List<Match<T>> {
        val used = HashMap<String, Int>()
        return matches.map { match ->
            val count = (used[match.label] ?: 0) + 1
            used[match.label] = count
            if (count == 1) match else Match(match.file, match.mimeType, "${match.label} $count", match.language)
        }
    }

    private fun <T> sameEpisode(
        videoName: String,
        videoStem: String,
        videos: List<T>,
        orphans: List<T>,
        nameOf: (T) -> String,
    ): List<T> {
        val video = DriveNameParser.parseFile(videoName)
        val episode = video.episode ?: return emptyList()
        if (video.isExtra) return emptyList()
        fun DriveNameParser.ParsedFile.isSameEpisode() =
            !isExtra && this.episode == episode && (season == null || video.season == null || season == video.season)

        val others = videos.filter { stemOf(nameOf(it)) != videoStem }
        if (others.any { DriveNameParser.parseFile(nameOf(it)).isSameEpisode() }) return emptyList()
        return orphans.filter { subtitle ->
            val name = nameOf(subtitle)
            val parsed = DriveNameParser.parseFile(name).takeIf { it.episode != null }
            // `标题 - 01.sc.ass` 去掉 `.ass` 后还剩语言标记, 再去一层
                ?: DriveNameParser.parseFile(name.substringBeforeLast('.'))
            parsed.isSameEpisode()
        }
    }

    /** 去掉扩展名, 转小写. */
    private fun stemOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0 && fileName.length - dot <= 6) fileName.substring(0, dot) else fileName
        return stem.lowercase()
    }

    /**
     * 文件名里的语言标记 (`sc`, `chs&jpn`, `zh-Hant`, `简日双语`) 认出的显示名与语言.
     *
     * @param rank 排序用: 简体在前, 其次是没分简繁的中文、繁体、日文、英文, 认不出的最后
     */
    private class Tags(val label: String, val language: String?, val rank: Int) {
        companion object {
            /**
             * @param tag 视频名之后的那段 (规则 1), 或整个字幕文件名 (规则 2)
             * @param wholeName [tag] 是整个文件名: 认不出时不拿它当显示名
             */
            fun of(tag: String, wholeName: Boolean): Tags {
                val tags = LanguageTags.of(tag)
                val sc = tags.sc
                val tc = tags.tc
                val zh = tags.zh
                val ja = tags.ja
                val en = tags.en
                // `zh-TW` 这种同时出现 zh 与简繁标记的, 按简繁算
                return when {
                    sc && ja -> Tags("简日双语", "zh-Hans", 0)
                    sc && tc -> Tags("简繁中文", "zh", 0)
                    sc -> Tags("简体中文", "zh-Hans", 0)
                    tc && ja -> Tags("繁日双语", "zh-Hant", 2)
                    tc -> Tags("繁体中文", "zh-Hant", 2)
                    zh && ja -> Tags("中日双语", "zh", 1)
                    zh -> Tags("中文", "zh", 1)
                    ja -> Tags("日文", "ja", 3)
                    en -> Tags("英文", "en", 4)
                    !wholeName && tag.isNotBlank() && tag.length <= MAX_RAW_TAG_LENGTH -> Tags(tag, null, 5)
                    else -> Tags("外挂字幕", null, 5)
                }
            }
        }
    }

    private const val MAX_RAW_TAG_LENGTH = 12
    private const val MAX_PICKED_LABEL_LENGTH = 24

    private val SUBTITLE_FOLDER = Regex("""(?i)subs?|subtitles?|ass|srt|.*字幕.*""")
}

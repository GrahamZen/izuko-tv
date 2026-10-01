/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.Resolution
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse

/**
 * 打开一个夸克分享、列出里面的视频、对到条目的剧集. 「夸克分享搜索」与「我添加的分享」两个数据源共用.
 *
 * 只读, 不需要登录; 播放时才转存 (见 [QuarkDriveService.resolveSharePlayback]).
 */
internal class QuarkShareReader(private val shares: QuarkShareBrowser) {
    /**
     * 分享里的一个视频文件.
     *
     * @param folders 从分享根到文件所在文件夹的名字
     */
    class Entry(val file: QuarkShareFile, val folders: List<String>)

    class Contents(val title: String, val videos: List<Entry>)

    /**
     * 打开分享并列出视频: 最多往下 [MAX_DEPTH] 层、[MAX_LISTED_FOLDERS] 个文件夹、[MAX_FILES] 个文件.
     * 分享失效 (取消、违规、提取码不对) 时抛 [QuarkShareUnavailableException].
     */
    suspend fun read(shareId: String, passcode: String): Contents {
        val title = shares.open(shareId, passcode)
        return Contents(title, collectVideos(shareId, passcode))
    }

    /**
     * 从 [videos] 里挑出 [request] 这个条目的剧集 (规则同夸克网盘数据源, 见 [QuarkSubjectMatcher]).
     * [FoundShare.siteTitle] 放在最外层: 分享里没写季的文件, 按它 (例如「…第二季」) 认季.
     */
    fun match(request: MediaFetchRequest, share: FoundShare, videos: List<Entry>): List<QuarkShareMatch> {
        val byFid = videos.associateBy { it.file.fid }
        val candidates = videos.map { QuarkSubjectMatcher.Candidate(it.file.asFile(), listOf(share.siteTitle) + it.folders) }
        return QuarkSubjectMatcher.matchEpisodes(request, candidates).mapNotNull { matched ->
            val entry = byFid[matched.file.fid] ?: return@mapNotNull null
            QuarkShareMatch(share, entry.file, entry.folders, matched.episode)
        }
    }

    private suspend fun collectVideos(shareId: String, passcode: String): List<Entry> {
        val result = ArrayList<Entry>()
        var listed = 0

        suspend fun walk(folderId: String, path: List<String>, depth: Int) {
            if (listed >= MAX_LISTED_FOLDERS || result.size >= MAX_FILES) return
            listed++
            for (child in shares.listFolder(shareId, passcode, folderId)) {
                if (result.size >= MAX_FILES) return
                if (child.dir) {
                    if (depth < MAX_DEPTH) walk(child.fid, path + child.fileName, depth + 1)
                } else if (child.category == "video" && child.shareFidToken.isNotEmpty()) {
                    result += Entry(child, path)
                }
            }
        }

        walk(QuarkApi.ROOT_FOLDER_ID, emptyList(), depth = 0)
        return result
    }

    internal companion object {
        private const val MAX_LISTED_FOLDERS = 30
        private const val MAX_DEPTH = 3
        private const val MAX_FILES = 400
    }
}

/**
 * 分享里的一个文件做成资源: 地址是占位的分享文件引用 ([QuarkShareFileRef]), 播放时凭它转存再取直链.
 *
 * @param alliance 显示在「字幕组」位置的名字 (数据源名)
 * @param subjectName 已经按剧名与季匹配过, 与夸克网盘数据源一样直接标成当前条目
 */
internal fun QuarkShareMatch.toShareMedia(mediaSourceId: String, alliance: String, subjectName: String?): Media {
    val ref = QuarkShareFileRef(share.shareId, share.passcode, file.fid, file.shareFidToken, file.fileName, file.size)
    val details = RawTitleParser.getDefault().parse((listOf(share.siteTitle) + folders + file.fileName).joinToString(" "))
    return DefaultMedia(
        mediaId = "$mediaSourceId.${share.shareId}.${file.fid}",
        mediaSourceId = mediaSourceId,
        originalUrl = QuarkShareFileRef.SHARE_URL_PREFIX + share.shareId,
        download = ResourceLocation.HttpStreamingFile(ref.toUri()),
        originalTitle = (listOf(share.siteTitle) + folders.takeLast(1) + file.fileName).joinToString(" / "),
        publishedTime = file.updatedAt,
        properties = MediaProperties(
            subjectName = subjectName,
            episodeName = null,
            subtitleLanguageIds = details.subtitleLanguages.map { it.id }.ifEmpty { listOf("CHS") },
            resolution = (details.resolution ?: Resolution.R1080P).toString(),
            alliance = alliance,
            size = if (file.size > 0) file.size.bytes else FileSize.Unspecified,
            subtitleKind = details.subtitleKind,
        ),
        episodeRange = EpisodeRange.single(episode),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )
}

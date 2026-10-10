/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

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
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 打开一个网盘分享、列出里面的视频、对到条目的剧集. 「分享搜索」与「我添加的分享」两个数据源共用.
 *
 * 只读, 不需要登录; 播放时才转存 (见 [CloudDriveService.resolveSharePlayback]).
 */
internal class DriveShareReader(
    private val shares: DriveShareBrowser,
    private val numbering: TmdbEpisodeNumbering = TmdbEpisodeNumbering.None,
) {
    /**
     * 分享里的一个视频文件.
     *
     * @param folders 从分享根到文件所在文件夹的名字
     */
    class Entry(val file: DriveFile, val folders: List<String>)

    class Contents(val title: String, val videos: List<Entry>)

    /**
     * 打开分享并列出视频: 最多往下 [MAX_DEPTH] 层、[MAX_LISTED_FOLDERS] 个文件夹、[MAX_FILES] 个文件; 每层 [skipFolders] 挑出的文件夹
     * 不往下列 (一般是 [otherSeasonFolders]). 分享失效 (取消、违规、提取码不对) 时抛 [CloudDriveShareUnavailableException].
     */
    suspend fun read(shareId: String, passcode: String, skipFolders: (List<String>) -> Set<String> = { emptySet() }): Contents {
        val title = shares.open(shareId, passcode)
        return Contents(title, collectVideos(shareId, passcode, shares.rootFolderId, emptyList(), skipFolders))
    }

    /**
     * 只列分享里的文件夹 [folderId] (往下的限制同 [read]), 各视频的 [Entry.folders] 从 [path] (这个文件夹在分享里的路径) 起.
     * 分享失效时抛 [CloudDriveShareUnavailableException].
     */
    suspend fun readFolder(
        shareId: String,
        passcode: String,
        folderId: String,
        path: List<String>,
        skipFolders: (List<String>) -> Set<String> = { emptySet() },
    ): List<Entry> {
        shares.open(shareId, passcode)
        return collectVideos(shareId, passcode, folderId, path, skipFolders)
    }

    /** 按季分好的一层里, [request] 这个条目不会出现在里面的文件夹, 见 [DriveSubjectMatcher.otherSeasonFolders]. */
    suspend fun otherSeasonFolders(request: MediaFetchRequest): (List<String>) -> Set<String> =
        DriveSubjectMatcher.otherSeasonFolders(request, numbering.of(request))

    /**
     * 从 [videos] 里挑出 [request] 这个条目的剧集 (规则同自己网盘的数据源, 见 [DriveSubjectMatcher]).
     * [FoundShare.siteTitle] 放在最外层: 分享里没写季的文件, 按它 (例如「…第二季」) 认季.
     */
    suspend fun match(request: MediaFetchRequest, share: FoundShare, videos: List<Entry>): List<DriveShareMatch> {
        val byFid = videos.associateBy { it.file.fid }
        val candidates = videos.map { DriveSubjectMatcher.Candidate(it.file, listOf(share.siteTitle) + it.folders) }
        return DriveSubjectMatcher.matchEpisodes(request, candidates, numbering.of(request)).mapNotNull { matched ->
            val entry = byFid[matched.file.fid] ?: return@mapNotNull null
            DriveShareMatch(share, entry.file, entry.folders, matched.episode)
        }
    }

    private suspend fun collectVideos(
        shareId: String,
        passcode: String,
        startFolderId: String,
        startPath: List<String>,
        skipFolders: (List<String>) -> Set<String>,
    ): List<Entry> {
        val result = ArrayList<Entry>()
        var listed = 0
        var skipped = 0

        suspend fun walk(folderId: String, path: List<String>, depth: Int) {
            if (listed >= MAX_LISTED_FOLDERS || result.size >= MAX_FILES) return
            listed++
            val children = shares.listFolder(shareId, passcode, folderId)
            val skippedNames = skipFolders(children.filter { it.dir }.map { it.fileName })
            for (child in children) {
                if (result.size >= MAX_FILES) return
                if (child.dir) {
                    if (depth >= MAX_DEPTH) continue
                    if (child.fileName in skippedNames) skipped++ else walk(child.fid, path + child.fileName, depth + 1)
                } else if (child.isVideo && (child.shareToken.isNotEmpty() || !shares.needsFileToken)) {
                    // 所在文件夹播放时找外挂字幕要用, 不指望接口一定给
                    result += Entry(if (child.parentFid.isEmpty()) child.inFolder(folderId) else child, path)
                }
            }
        }

        walk(startFolderId, startPath, depth = 0)
        if (skipped > 0) logger.info { "Share $shareId: listed $listed folders, skipped $skipped of other seasons" }
        return result
    }

    internal companion object {
        private val logger = logger<DriveShareReader>()

        private const val MAX_LISTED_FOLDERS = 30
        private const val MAX_DEPTH = 3
        private const val MAX_FILES = 400
    }
}

/**
 * 分享里的一个文件做成资源: 地址是占位的分享文件引用 ([DriveShareFileRef]), 播放时凭它转存再取直链.
 *
 * @param alliance 显示在「字幕组」位置的名字 (数据源名)
 * @param subjectName 已经按剧名与季匹配过, 与自己网盘的数据源一样直接标成当前条目
 */
internal fun DriveShareMatch.toShareMedia(
    placeholders: DrivePlaceholders,
    mediaSourceId: String,
    alliance: String,
    subjectName: String?,
): Media {
    val ref = DriveShareFileRef(share.shareId, share.passcode, file.fid, file.shareToken, file.fileName, file.size, file.parentFid)
    val details = RawTitleParser.getDefault().parse((listOf(share.siteTitle) + folders + file.fileName).joinToString(" "))
    val mediaId = "$mediaSourceId.${share.shareId}.${file.fid}"
    DriveVideoBitrates.record(mediaId, file)
    return DefaultMedia(
        mediaId = mediaId,
        mediaSourceId = mediaSourceId,
        originalUrl = placeholders.shareUrl(share.shareId),
        download = ResourceLocation.HttpStreamingFile(placeholders.shareFileUri(ref)),
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

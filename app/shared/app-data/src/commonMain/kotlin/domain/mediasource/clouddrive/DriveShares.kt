/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 用户粘贴的、或站点上找到的一个分享链接.
 *
 * @property passcode 提取码, 没有为空串
 */
data class DriveShareLink(val shareId: String, val passcode: String)

/**
 * 按协议 ([DriveShareConfig.linkPattern]) 从文字里认分享链接.
 */
class DriveShareLinks(protocol: CloudDriveProtocol) {
    private val link: Regex? = protocol.share.linkPattern.ifBlank { null }?.let { runCatching { Regex(it) }.getOrNull() }

    /**
     * 从一段文字 (分享站「复制链接」给的那种, 可能带标题与「提取码：xxxx」) 里取出全部分享链接, 按出现顺序去重.
     * 提取码先看链接自己带的, 没有就在链接后面到下一个链接之前的文字里找「提取码 / 密码 / 访问码」.
     */
    fun parse(text: String): List<DriveShareLink> {
        val pattern = link ?: return emptyList()
        val matches = pattern.findAll(text).toList()
        val links = LinkedHashMap<String, DriveShareLink>()
        matches.forEachIndexed { index, match ->
            val shareId = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            val tailEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val tail = text.substring(match.range.last + 1, tailEnd)
            val passcode = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                ?: TEXT_PASSCODE.find(tail)?.groupValues?.get(1)
                ?: ""
            links.putIfAbsent(shareId, DriveShareLink(shareId, passcode))
        }
        return links.values.toList()
    }

    /** 文字里的全部分享链接, 提取码只认链接自己带的 (站点结果里常把几家网盘的链接挨着放, 后面的文字不一定属于它). */
    fun extract(text: String): List<DriveShareLink> {
        val pattern = link ?: return emptyList()
        return pattern.findAll(text).mapNotNull { match ->
            val shareId = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DriveShareLink(shareId, match.groupValues.getOrNull(2).orEmpty())
        }.distinctBy { it.shareId }.toList()
    }

    private companion object {
        private val TEXT_PASSCODE = Regex("""(?:提取码|密码|访问码|pwd)\s*[:：=]?\s*([0-9A-Za-z]{4,8})(?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)
    }
}

/**
 * 分享里的一个文件, 编在资源的占位地址里 (见 [DrivePlaceholders.shareFileUri]): 播放时要凭它转存.
 *
 * @param folderId 文件在分享里所在的文件夹, 播放时到这里找外挂字幕. 以前的版本做的资源没有这一项, 为空
 */
data class DriveShareFileRef(
    val shareId: String,
    val passcode: String,
    val fid: String,
    val shareToken: String,
    val fileName: String,
    val size: Long,
    val folderId: String = "",
) {
    val key: String get() = "$shareId/$fid"
}

/**
 * 资源的占位地址与网页地址. `HttpStreamingFile` 只收 http(s) 地址, 所以占位地址也是网址, 误打开时落在网盘的网页上;
 * 播放时由 [me.him188.ani.app.domain.media.resolver.CloudDriveMediaResolver] 认出来再取直链.
 */
class DrivePlaceholders(protocol: CloudDriveProtocol) {
    private val fileTemplate = protocol.links.file.ifBlank { "https://${protocol.id}.drive.invalid/file/{fileId}" }
    private val shareTemplate = protocol.share.url.ifBlank { "https://${protocol.id}.drive.invalid/s/{shareId}" }
    private val folderTemplate = protocol.links.folder.ifBlank { protocol.websiteUrl }

    /** 自己网盘文件 [fileId] 的占位地址. */
    fun fileUri(fileId: String): String = fileTemplate.replace(FILE_ID, fileId)

    /** [fileUri] 的逆运算, 不是这个网盘的文件时返回 null. */
    fun fileIdOf(uri: String): String? {
        val prefix = fileTemplate.substringBefore(FILE_ID)
        val suffix = fileTemplate.substringAfter(FILE_ID, "")
        if (!uri.startsWith(prefix) || !uri.endsWith(suffix) || uri.length <= prefix.length + suffix.length) return null
        // 分享文件的地址带 `#` 片段, 前缀重叠时不能当成自己网盘的文件
        return uri.substring(prefix.length, uri.length - suffix.length).takeIf { '#' !in it && '/' !in it }
    }

    fun folderUrl(folderId: String): String = folderTemplate.replace("{folderId}", folderId)

    fun shareUrl(shareId: String): String = shareTemplate.replace(SHARE_ID, shareId)

    /** 分享里的文件的占位地址: 分享页地址加上片段 `#izuko-share&fid=..&token=..&pwd=..&name=..&size=..&dir=..`. */
    fun shareFileUri(ref: DriveShareFileRef): String = buildString {
        append(shareUrl(ref.shareId)).append('#').append(MARKER)
        append("&fid=").append(ref.fid.encodeURLParameter())
        append("&token=").append(ref.shareToken.encodeURLParameter())
        append("&pwd=").append(ref.passcode.encodeURLParameter())
        append("&name=").append(ref.fileName.encodeURLParameter())
        append("&size=").append(ref.size)
        if (ref.folderId.isNotEmpty()) append("&dir=").append(ref.folderId.encodeURLParameter())
    }

    /** [shareFileUri] 的逆运算; 不是这种地址 (包括普通的分享链接) 时返回 null. */
    fun parseShareFile(uri: String): DriveShareFileRef? {
        val prefix = shareTemplate.substringBefore(SHARE_ID)
        if (!uri.startsWith(prefix)) return null
        val shareId = uri.removePrefix(prefix).substringBefore('#').substringBefore('?')
            .removeSuffix(shareTemplate.substringAfter(SHARE_ID, ""))
        val parts = uri.substringAfter('#', missingDelimiterValue = "").split('&')
        if (shareId.isEmpty() || parts.firstOrNull() != MARKER) return null
        val values = parts.drop(1).associate { part ->
            part.substringBefore('=') to part.substringAfter('=', "").decodeURLQueryComponent()
        }
        val fid = values["fid"]?.takeIf { it.isNotEmpty() } ?: return null
        return DriveShareFileRef(
            shareId = shareId,
            passcode = values["pwd"].orEmpty(),
            fid = fid,
            shareToken = values["token"].orEmpty(),
            fileName = values["name"].orEmpty(),
            size = values["size"]?.toLongOrNull() ?: 0,
            folderId = values["dir"].orEmpty(),
        )
    }

    private companion object {
        private const val FILE_ID = "{fileId}"
        private const val SHARE_ID = "{shareId}"
        private const val MARKER = "izuko-share"
    }
}

/**
 * 只读地查看分享 (不需要登录).
 */
internal interface DriveShareBrowser {
    /** 分享的根文件夹 id. */
    val rootFolderId: String

    /** 分享里的文件转存时要不要带凭证 ([DriveFile.shareToken]); 要的话没有凭证的文件不收. */
    val needsFileToken: Boolean

    /** 打开分享, 返回分享标题. 分享失效时抛 [CloudDriveShareUnavailableException]. */
    suspend fun open(shareId: String, passcode: String): String

    suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<DriveFile>
}

/** 要打开的一个分享: 站点结果里找到的, 或用户添加的. */
internal class FoundShare(
    val shareId: String,
    val passcode: String,
    /**
     * 用来认季的名字: 站点上的剧名 (分享标题常被打乱, 靠不住); 用户添加的分享没有站点剧名, 用分享标题.
     */
    val siteTitle: String,
)

/** 分享里一个对上了集的视频文件. */
internal class DriveShareMatch(
    val share: FoundShare,
    val file: DriveFile,
    /** 分享里从根到文件所在文件夹的名字 (不含站点剧名). */
    val folders: List<String>,
    val episode: EpisodeSort,
)

/** 这个文件是不是 [request] 要的这一集 (集号按条目内的序号或系列绝对集号). */
internal fun DriveShareMatch.isEpisodeOf(request: MediaFetchRequest): Boolean =
    episode == request.episodeSort || (request.episodeEp != null && episode == request.episodeEp)

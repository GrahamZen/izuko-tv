/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.preference.QuarkSubjectPicks
import me.him188.ani.app.domain.media.fetch.MediaFetchSessionRefresh
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.quark.QuarkAuthException
import me.him188.ani.app.domain.mediasource.quark.QuarkDriveService
import me.him188.ani.app.domain.mediasource.quark.QuarkFile
import me.him188.ani.app.domain.mediasource.quark.QuarkMediaSource
import me.him188.ani.app.domain.mediasource.quark.ensureQuarkMediaSourceEnabled
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.videoplayer.ui.progress.subtitleLanguage
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URLDecoder

/**
 * Web 控制台播放器页的「从夸克网盘挑」: 自动匹配对不上时, 用户在手机上搜自己的网盘或一层层点进去,
 * 指定「这部番就在这个文件夹里」或「这个文件是这一集」. 记在夸克账号的配置里 (见 [QuarkDriveService.picksOf]),
 * 「夸克网盘」数据源之后每一集都照它给出资源 (算精确匹配, 能自动选、自动接着播下一集).
 * 也能把网盘里的字幕文件挂到电视上正在播的夸克视频上 (见 [QuarkDriveService.pickSubtitle]).
 *
 * 只认最近搜到或列出来的文件 (服务端记着), 不拿手机传来的名字和大小去记.
 */
internal object RemoteQuarkDrive {
    private val logger = logger<RemoteQuarkDrive>()

    private val drive: QuarkDriveService get() = KoinPlatform.getKoin().get()
    private val manager: MediaSourceManager get() = KoinPlatform.getKoin().get()

    /** 最近搜到或列出的一项, 以及它所在的文件夹名 (搜索结果不知道在哪个文件夹里). */
    private class Seen(val file: QuarkFile, val folderName: String?)

    /** 最近搜到或列出的文件与文件夹 (id → 那一项), 手机上点「播放」「就是这个文件夹」时凭它. */
    private val seen = object : LinkedHashMap<String, Seen>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?) = size > MAX_SEEN
    }

    /** 处理 `api/player/drive` 下的请求; 路径或方法不认识返回 null. */
    fun handle(player: RemotePlayerHandle?, request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return try {
            when {
                request.path == "api/player/drive" && get -> picks(player)
                request.path == "api/player/drive/search" && get -> search(request.queryParam("q").orEmpty())
                request.path == "api/player/drive/list" && get -> list(request.queryParam("fid") ?: QuarkDriveService.ROOT_FOLDER_ID)
                !post -> null
                request.path == "api/player/drive/play" -> play(player, request.field("fid"))
                request.path == "api/player/drive/folder" -> pickFolder(player, request.field("fid"))
                request.path == "api/player/drive/forget" -> forget(player, request.field("fid"))
                request.path == "api/player/drive/subtitle" -> attachSubtitle(player, request.field("fid"))
                request.path == "api/player/drive/unsubtitle" -> detachSubtitle(player, request.field("fid"))
                else -> null
            }
        } catch (e: QuarkAuthException) {
            result(false, tr("夸克没登录或登录已失效，请在「数据源」页登录夸克网盘"))
        } catch (e: Exception) {
            logger.warn(e) { "Remote quark drive request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", e.message ?: e::class.simpleName))
        }
    }

    /** 这部番已经指定的位置、正在播的视频挂上的字幕, 搜索框里先填的名字, 以及可以点着搜的这部番的搜索名. */
    private fun picks(player: RemotePlayerHandle?): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val request = handle.page?.fetchRequest
        val loggedIn = runBlocking { drive.config.first().isLoggedIn }
        val picks = if (loggedIn) runBlocking { drive.picksOf(handle.vm.subjectId) } else QuarkSubjectPicks()
        val subtitleKey = handle.vm.loadedMedia.value?.let { drive.subtitleKeyOf(it) }
        val subtitles = if (loggedIn && subtitleKey != null) runBlocking { drive.pickedSubtitlesOf(subtitleKey) } else emptyList()
        return buildJsonObject {
            put("ok", true)
            put("message", "")
            put("loggedIn", loggedIn)
            put("keyword", request?.let { it.subjectNameCN ?: it.subjectNames.firstOrNull() }.orEmpty())
            // 这部番的搜索名 (编辑查询请求里那些, 改过就是改过的), 手机上点一下就搜, 不用手打
            putJsonArray("names") {
                val names = request?.let { listOfNotNull(it.subjectNameCN) + it.subjectNames }.orEmpty()
                for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) add(name)
            }
            putJsonArray("folders") {
                for (folder in picks.folders) addJsonObject {
                    put("fid", folder.fid)
                    put("name", folder.name)
                }
            }
            putJsonArray("files") {
                for (file in picks.files) addJsonObject {
                    put("fid", file.fid)
                    put("name", file.fileName)
                    put("meta", tr("当作第 {0} 集", file.episode))
                }
            }
            // 正在播的是夸克的视频时才能挂字幕
            put("canAttach", subtitleKey != null)
            putJsonArray("subtitles") {
                for (subtitle in subtitles) addJsonObject {
                    put("fid", subtitle.fid)
                    put("name", subtitle.fileName)
                }
            }
        }
    }

    private fun search(keyword: String): JsonObject {
        if (keyword.isBlank()) return result(false, tr("先输入要搜的名字"))
        return listing(runBlocking { drive.searchForPicking(keyword.trim()) }, folderName = null)
    }

    private fun list(folderId: String): JsonObject {
        val name = synchronized(seen) { seen[folderId]?.file?.fileName }
        return listing(runBlocking { drive.listForPicking(folderId) }, folderName = name)
    }

    /** 只列文件夹、视频与字幕文件 (文件夹在前, 接口已经排好). */
    private fun listing(files: List<QuarkFile>, folderName: String?): JsonObject {
        val shown = files.filter { it.dir || it.isVideo || drive.isSubtitleFile(it.fileName) }
        synchronized(seen) { for (file in shown) seen[file.fid] = Seen(file, folderName) }
        return buildJsonObject {
            put("ok", true)
            put("message", if (shown.isEmpty()) tr("没有找到文件夹或视频") else "")
            putJsonArray("items") {
                for (file in shown.take(MAX_LISTED)) addJsonObject {
                    put("fid", file.fid)
                    put("name", file.fileName)
                    put("dir", file.dir)
                    if (!file.dir && !file.isVideo) put("sub", true)
                    if (!file.dir) put("meta", fileMeta(file))
                }
            }
            if (shown.size > MAX_LISTED) put("more", shown.size - MAX_LISTED)
        }
    }

    private fun fileMeta(file: QuarkFile): String {
        val episode = drive.episodeInFileName(file.fileName)?.let { (season, episode) ->
            if (season != null) tr("第 {0} 季第 {1} 集", season, episode) else tr("第 {0} 集", episode)
        }
        return listOfNotNull(episode, file.size.takeIf { it > 0 }?.bytes?.toString()).joinToString(" · ")
    }

    /**
     * 把视频 [fid] 当作当前这一集播放. 选源列表里已经有它且就是这一集 (自动认出的或之前指定的) 时照电视上点选;
     * 否则先记下「这个文件是这一集」、让「夸克网盘」重查, 等它出现在选源列表里再点选 (会记住这部番用这个源), 等不到就先临时播.
     */
    private fun play(player: RemotePlayerHandle?, fid: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val request = handle.page?.fetchRequest ?: return result(false, tr("数据源还在加载，请稍后再试"))
        val item = synchronized(seen) { seen[fid] }?.takeIf { it.file.isVideo }
            ?: return result(false, tr("找不到这个文件，请重新搜索"))
        val subjectName = request.subjectNames.firstOrNull { it.isNotBlank() } ?: request.subjectNameCN
        val media = QuarkMediaSource.mediaFor(item.file, request.episodeSort, subjectName, listOfNotNull(item.folderName))
        if (handle.candidate(media.mediaId)?.isEpisode(request.episodeSort) == true) {
            handle.select(media.mediaId)?.let { return result(false, it) }
            return result(true, tr("正在电视上播放"))
        }
        runBlocking { drive.pickFile(handle.vm.subjectId, item.file, request.episodeSort) }
        val refreshed = refreshSource(handle)
        if (awaitCandidate(handle, media.mediaId, request.episodeSort)) {
            handle.select(media.mediaId)?.let { return result(false, it) }
        } else {
            handle.vm.playTemporarily(media)
        }
        return result(true, tr("已记下这个文件是第 {0} 集，正在电视上播放", request.episodeSort) + refreshed)
    }

    /**
     * 记下「这部番就在文件夹 [fid] 里」. 先看里面认得出哪几集, 一集都认不出就不记; 认得出当前这一集就接着播它.
     */
    private fun pickFolder(player: RemotePlayerHandle?, fid: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val request = handle.page?.fetchRequest ?: return result(false, tr("数据源还在加载，请稍后再试"))
        val folder = synchronized(seen) { seen[fid] }?.file?.takeIf { it.dir }
            ?: return result(false, tr("找不到这个文件夹，请重新搜索"))
        val found = runBlocking { drive.episodesInFolder(request, folder) }
        if (found.isEmpty()) {
            return result(false, tr("这个文件夹里没认出这部番的剧集（文件名认不出集号）。可以点进去挑一个文件当作这一集"))
        }
        runBlocking { drive.pickFolder(handle.vm.subjectId, folder) }
        val episodes = RemoteQuarkShares.formatEpisodes(found.map { it.third }.distinct().sorted())
        val current = found.firstOrNull { it.third == request.episodeSort }
            ?: return result(true, tr("已记下：这部番在「{0}」里，认出第 {1} 集，没有第 {2} 集", folder.fileName, episodes, request.episodeSort) + refreshSource(handle))
        val refreshed = refreshSource(handle)
        val subjectName = request.subjectNames.firstOrNull { it.isNotBlank() } ?: request.subjectNameCN
        val media = QuarkMediaSource.mediaFor(current.first, request.episodeSort, subjectName, current.second)
        if (awaitCandidate(handle, media.mediaId, request.episodeSort)) {
            handle.select(media.mediaId)?.let { return result(false, it) }
        } else {
            handle.vm.playTemporarily(media)
        }
        return result(true, tr("已记下：这部番在「{0}」里，认出第 {1} 集，正在电视上播放第 {2} 集", folder.fileName, episodes, request.episodeSort) + refreshed)
    }

    /**
     * 把字幕文件 [fid] 挂到电视上正在播的视频上: 记在账号里 (之后播这个视频都带上), 让播放器原地重新加载并选上它.
     */
    private fun attachSubtitle(player: RemotePlayerHandle?, fid: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val file = synchronized(seen) { seen[fid] }?.file?.takeIf { !it.dir && drive.isSubtitleFile(it.fileName) }
            ?: return result(false, tr("找不到这个文件，请重新搜索"))
        val media = handle.vm.loadedMedia.value ?: return result(false, tr("电视上还没有在播的视频"))
        val key = drive.subtitleKeyOf(media) ?: return result(false, tr("只能给夸克网盘里的视频挂字幕，电视上正在播的不是"))
        runBlocking { drive.pickSubtitle(key, file) }
        reload(handle, drive.subtitleLabelOf(file.fileName))
        return result(true, tr("已给正在播的视频挂上「{0}」，电视上的视频正在重新加载", file.fileName))
    }

    private fun detachSubtitle(player: RemotePlayerHandle?, fid: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val media = handle.vm.loadedMedia.value ?: return result(false, tr("电视上还没有在播的视频"))
        val key = drive.subtitleKeyOf(media) ?: return result(false, tr("只能给夸克网盘里的视频挂字幕，电视上正在播的不是"))
        runBlocking { drive.forgetSubtitle(key, fid) }
        reload(handle, label = null)
        return result(true, tr("已取下这条字幕，电视上的视频正在重新加载"))
    }

    /**
     * 让播放器原地重新加载正在播的视频 (重新取字幕), 回到现在的位置; [label] 不为 null 时装好后选上这个名字的外挂字幕.
     * 外挂字幕的轨道排在内封的后面 (id 不以 `0:` 开头), 手动挂的又排在外挂的最前; 与别的重名时播放器里加了序号.
     */
    private fun reload(handle: RemotePlayerHandle, label: String?) {
        handle.runOnUi {
            if (!handle.vm.reloadCurrentMedia()) return@runOnUi
            if (label == null) return@runOnUi
            withTimeoutOrNull(RELOAD_WAIT_MILLIS) {
                delay(1_000)
                while (true) {
                    val track = handle.subtitleState?.candidates?.firstOrNull {
                        !it.id.startsWith("0:") && (it.subtitleLanguage == label || it.subtitleLanguage.startsWith("$label "))
                    }
                    if (track != null) {
                        handle.selectSubtitle(track.id)
                        break
                    }
                    delay(500)
                }
            }
        }
    }

    private fun forget(player: RemotePlayerHandle?, fid: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        runBlocking { drive.forgetPick(handle.vm.subjectId, fid) }
        refreshSource(handle)
        return result(true, tr("已删除"))
    }

    /**
     * 让这次搜索里的「夸克网盘」重查: 没有这个源或没启用时先加上 / 启用; 搜索会话取的数据源快照里没有它时只能重建一次会话
     * (见 MediaFetchSessionRefresh), 否则只重查这一个源, 不打断正在播的视频.
     *
     * @return 接在提示后面的一句 (只重查一个源时为空)
     */
    private fun refreshSource(handle: RemotePlayerHandle): String {
        val (instanceId, changed) = runBlocking { ensureQuarkMediaSourceEnabled(manager) }
        return if (changed || !handle.hasSource(QuarkMediaSource.ID)) {
            handle.searchAllSources()
            KoinPlatform.getKoin().get<MediaFetchSessionRefresh>().request()
            tr("。正在重新搜索一次，让「夸克网盘」加入，电视上的视频会重新加载")
        } else {
            handle.vm.restartSource(instanceId)
            ""
        }
    }

    /** 等选源列表里出现 [mediaId] 且是第 [episode] 集 (重查要列文件夹, 一般几秒), 最多 [CANDIDATE_WAIT_MILLIS]. 在 HTTP 线程上调用. */
    private fun awaitCandidate(handle: RemotePlayerHandle, mediaId: String, episode: EpisodeSort): Boolean {
        val deadline = System.currentTimeMillis() + CANDIDATE_WAIT_MILLIS
        while (handle.candidate(mediaId)?.isEpisode(episode) != true) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(500)
        }
        return true
    }

    private fun Media.isEpisode(episode: EpisodeSort): Boolean = episodeRange?.contains(episode) == true

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.field(name: String): String =
        formFieldList().lastOrNull { it.first == name }?.second.orEmpty()

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private const val CANDIDATE_WAIT_MILLIS = 20_000L

    /** 挂上字幕后最多等多久让新轨道出现 (重新加载视频要几秒到十几秒). */

    private const val RELOAD_WAIT_MILLIS = 60_000L

    /** 一次最多列几项. */
    private const val MAX_LISTED = 200

    /** 服务端记着最近多少项. */
    private const val MAX_SEEN = 2000
}

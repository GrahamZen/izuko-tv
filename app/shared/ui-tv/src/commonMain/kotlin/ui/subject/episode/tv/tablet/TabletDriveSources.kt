/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.media.fetch.MediaFetchSessionRefresh
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareArguments
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.clouddrive.ensureAddedShareMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.ensureCloudDriveMediaSourceEnabled
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tablet_drive_pick
import me.him188.ani.app.ui.lang.tablet_drive_share_add
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.topic.contains
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

/*
 * 平板选源面板里的「添加网盘分享」与「从网盘挑」(电视上在 Web 控制台的播放器页做): 操作行多两颗,
 * 没有能加分享 / 能搜或能列目录的网盘时不出现。弹窗见 TabletShareAddDialog、TabletDrivePickDialog。
 */

/** 选源面板操作行里的两颗 (放在「修改搜索词」后面), 连同各自的弹窗. */
@Composable
internal fun TabletDriveSourceActions(vm: EpisodeViewModel, page: EpisodePageState) {
    val registry = remember { GlobalKoin.get<CloudDriveRegistry>() }
    val drives by registry.drives.collectAsState()
    val all = drives.orEmpty()
    val canShare = all.any { it.protocol.supportsShares }
    val canPick = all.any { it.canPick() }
    var sharing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    if (canShare) TabletSourceTextAction(Icons.Rounded.AddLink, stringResource(Lang.tablet_drive_share_add)) { sharing = true }
    if (canPick) TabletSourceTextAction(Icons.Rounded.FolderOpen, stringResource(Lang.tablet_drive_pick)) { picking = true }
    if (sharing) TabletShareAddDialog(vm, page, onDismissRequest = { sharing = false })
    if (picking) TabletDrivePickDialog(vm, page, onDismissRequest = { picking = false })
}

/** 能搜或能一层层列目录的网盘 (本地文件夹不算: 平板上没有). */
internal fun CloudDriveService.canPick(): Boolean =
    !protocol.isLocalFolder && (protocol.supportsSearch || protocol.supportsListing)

internal fun CloudDriveService.displayName(): String = protocol.displayName(Locale.current.toLanguageTag())

/**
 * 两个弹窗对播放页做的事 (同控制台的 RemotePlayerHandle 那几样): 在当前候选里找资源、照点选那样选上、让某个数据源重查,
 * 等重查出来的资源出现在候选里再选 (等不到就先临时播).
 */
internal class TabletDriveOps(val vm: EpisodeViewModel, val page: EpisodePageState) {
    private val manager: MediaSourceManager get() = GlobalKoin.get()

    /** 当前候选 (含被排除的) 里 [mediaId] 对应的资源. */
    @OptIn(UnsafeOriginalMediaAccess::class)
    fun candidate(mediaId: String): Media? =
        page.mediaSelectorState.presentationFlow.value.filteredCandidates.firstOrNull { it.original.mediaId == mediaId }?.original

    /** 这次搜索有没有数据源 [mediaSourceId]. */
    private fun hasSource(mediaSourceId: String): Boolean =
        page.mediaSourceResultListPresentation.list.any { it.mediaSourceId == mediaSourceId }

    fun select(media: Media) {
        page.mediaSelectorState.select(media)
    }

    /** 等候选里出现 [media] (给了 [episode] 时还要是那一集) 就选上; 等不到就先临时播. */
    suspend fun playWhenListed(media: Media, episode: EpisodeSort?) {
        val listed = withTimeoutOrNull(CANDIDATE_WAIT) {
            var found: Media? = null
            while (found == null) {
                found = candidate(media.mediaId)?.takeIf { episode == null || it.isEpisode(episode) }
                if (found == null) delay(500)
            }
            found
        }
        if (listed != null) select(listed) else vm.playTemporarily(media)
    }

    /**
     * 让网盘 [driveId] 的「我添加的分享」重查. 搜索会话的数据源快照里没有它 (刚建的) 时只能整个重新搜一次.
     * @return 是否整个重新搜了
     */
    suspend fun refreshAddedShares(driveId: String): Boolean {
        val (instanceId, changed) = ensureAddedShareMediaSource(manager, driveId)
        return researchOrRestart(changed || !hasSource(instanceId), instanceId)
    }

    /**
     * 让网盘 [drive] 自己网盘的数据源重查 (没启用时先启用).
     * @return 是否整个重新搜了; 没有这个网盘的数据源时为 null
     */
    suspend fun refreshDriveSource(drive: CloudDriveService): Boolean? {
        val (instanceId, changed) = ensureCloudDriveMediaSourceEnabled(manager, drive.driveId) ?: return null
        return researchOrRestart(changed || !hasSource(drive.protocol.driveMediaSourceId), instanceId)
    }

    private fun researchOrRestart(research: Boolean, instanceId: String): Boolean {
        if (research) {
            vm.setFullMediaSearch(true)
            GlobalKoin.get<MediaFetchSessionRefresh>().request()
        } else {
            vm.restartSource(instanceId)
        }
        return research
    }

    /** 网盘 [driveId] 的「我添加的分享」数据源的实例 id; 还没有时为 null. */
    suspend fun addedSharesSourceId(driveId: String): String? = manager.allInstances.first().firstOrNull {
        it.factoryId == CloudDriveAddedShareMediaSource.FactoryId &&
            it.config.deserializeArgumentsOrNull(CloudDriveAddedShareArguments.serializer())?.drive == driveId
    }?.instanceId

    private fun Media.isEpisode(episode: EpisodeSort): Boolean = episodeRange?.contains(episode) == true

    private companion object {
        val CANDIDATE_WAIT = 20.seconds
    }
}

/** 认出的集号写成一行: 连着的整数集并成 `1~12`, 其余照写 (`1~12、14、16~20`); [separator] 是分隔符. */
internal fun formatDriveEpisodes(episodes: List<EpisodeSort>, separator: String): String {
    val parts = ArrayList<String>()
    var start: Int? = null
    var end: Int? = null
    fun flush() {
        val s = start ?: return
        parts += if (end == s) "$s" else "$s~$end"
        start = null
        end = null
    }
    for (episode in episodes) {
        val number = (episode as? EpisodeSort.Normal)?.number?.takeIf { it == floor(it) }?.toInt()
        if (number == null) {
            flush()
            parts += episode.toString()
            continue
        }
        val last = end
        if (start != null && last != null && number == last + 1) {
            end = number
        } else {
            flush()
            start = number
            end = number
        }
    }
    flush()
    return parts.joinToString(separator)
}

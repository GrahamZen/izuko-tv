/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAddedShareService
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveShareUnavailableException
import me.him188.ani.app.domain.mediasource.clouddrive.DriveShareFileInfo
import me.him188.ani.app.domain.mediasource.clouddrive.DriveShareInspection
import me.him188.ani.app.domain.mediasource.clouddrive.DriveShareLink
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniPanelCloseButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tablet_drive_episode_separator
import me.him188.ani.app.ui.lang.tablet_drive_failed
import me.him188.ani.app.ui.lang.tablet_drive_loading
import me.him188.ani.app.ui.lang.tablet_drive_login_first
import me.him188.ani.app.ui.lang.tablet_drive_play_this
import me.him188.ani.app.ui.lang.tablet_drive_playing
import me.him188.ani.app.ui.lang.tablet_drive_researching
import me.him188.ani.app.ui.lang.tablet_drive_share_add
import me.him188.ani.app.ui.lang.tablet_drive_share_add_button
import me.him188.ani.app.ui.lang.tablet_drive_share_added_to_list
import me.him188.ani.app.ui.lang.tablet_drive_share_delete
import me.him188.ani.app.ui.lang.tablet_drive_share_failed
import me.him188.ani.app.ui.lang.tablet_drive_share_file_picked
import me.him188.ani.app.ui.lang.tablet_drive_share_hint
import me.him188.ani.app.ui.lang.tablet_drive_share_list
import me.him188.ani.app.ui.lang.tablet_drive_share_more_files
import me.him188.ani.app.ui.lang.tablet_drive_share_more_links
import me.him188.ani.app.ui.lang.tablet_drive_share_needs_passcode
import me.him188.ani.app.ui.lang.tablet_drive_share_no_episodes
import me.him188.ani.app.ui.lang.tablet_drive_share_no_links
import me.him188.ani.app.ui.lang.tablet_drive_share_no_videos
import me.him188.ani.app.ui.lang.tablet_drive_share_note_error
import me.him188.ani.app.ui.lang.tablet_drive_share_note_gone
import me.him188.ani.app.ui.lang.tablet_drive_share_note_replace
import me.him188.ani.app.ui.lang.tablet_drive_share_note_saved
import me.him188.ani.app.ui.lang.tablet_drive_share_placeholder
import me.him188.ani.app.ui.lang.tablet_drive_share_recognized
import me.him188.ani.app.ui.lang.tablet_drive_share_recognized_missing
import me.him188.ani.app.ui.lang.tablet_drive_share_timeout
import me.him188.ani.app.ui.lang.tablet_drive_sentence_join
import me.him188.ani.app.ui.lang.tablet_drive_share_unavailable
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * 「添加网盘分享」(同控制台播放器页那一块): 贴进分享链接 (带提取码的一起贴, 一次最多 [MAX_LINKS] 个), 按已配置的网盘认出是哪个网盘的,
 * 打开分享按这部番对出剧集, 对上了就记到这部番名下 (那个网盘的「我添加的分享」, 之后每一集的选源列表里都有它). 一集都没认出时
 * 不记下, 列出分享里的视频, 点一个当作这一集播 (这时才记下这个分享和这一集). 下面是这部番在各个网盘已添加的分享, 能删.
 */
@Composable
internal fun TabletShareAddDialog(vm: EpisodeViewModel, page: EpisodePageState, onDismissRequest: () -> Unit) {
    val registry = remember { GlobalKoin.get<CloudDriveRegistry>() }
    val drives by registry.drives.collectAsState()
    val shareDrives = drives.orEmpty().filter { it.protocol.supportsShares }
    val ops = remember(vm, page) { TabletDriveOps(vm, page) }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var outcomes by remember { mutableStateOf<List<ShareOutcome>>(emptyList()) }
    var shares by remember { mutableStateOf<List<AddedShareRow>>(emptyList()) }
    var sharesVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(shareDrives.map { it.driveId }, sharesVersion) {
        shares = shareDrives.flatMap { drive ->
            val service = registry.addedShareService(drive.driveId) ?: return@flatMap emptyList()
            val name = drive.displayName()
            service.sharesOf(vm.subjectId).map { share ->
                AddedShareRow(
                    driveId = drive.driveId,
                    driveName = name,
                    shareId = share.shareId,
                    title = share.title.ifBlank { share.shareId },
                    note = service.readStatusOf(share.shareId)?.let { noteOf(it.videos, it.error, it.savedCopies, name) },
                )
            }
        }
    }

    fun launch(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Tablet share action failed" }
                toaster.toast(getStringOffMain(Lang.tablet_drive_failed, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                busy = false
            }
        }
    }

    fun add() = launch {
        val request = page.fetchRequest ?: run {
            message = getStringOffMain(Lang.tablet_drive_loading)
            return@launch
        }
        val found = shareDrives.firstNotNullOfOrNull { drive ->
            drive.shareLinks.parse(text).takeIf { it.isNotEmpty() }?.let { drive to it }
        } ?: run {
            message = getStringOffMain(
                Lang.tablet_drive_share_no_links,
                shareDrives.joinToString(getStringOffMain(Lang.tablet_drive_episode_separator)) { it.displayName() },
            )
            return@launch
        }
        val (drive, links) = found
        val service = registry.addedShareService(drive.driveId) ?: return@launch
        val driveName = drive.displayName()
        val results = coroutineScope {
            links.take(MAX_LINKS).map { link -> async { inspect(service, driveName, request, link) } }.awaitAll()
        }
        outcomes = results
        val added = results.mapNotNull { it.inspection?.takeIf { inspection -> inspection.episodes.isNotEmpty() } }
        var line = if (links.size > MAX_LINKS) getStringOffMain(Lang.tablet_drive_share_more_links, MAX_LINKS) else null
        if (added.isNotEmpty()) {
            added.forEach { service.add(vm.subjectId, it) }
            val researched = ops.refreshAddedShares(drive.driveId)
            line = listOfNotNull(
                line,
                getStringOffMain(if (researched) Lang.tablet_drive_researching else Lang.tablet_drive_share_added_to_list),
            ).joinToString("\n")
            text = ""
            sharesVersion++
        }
        message = line
    }

    fun play(outcome: ShareOutcome, file: DriveShareFileInfo) = launch {
        val inspection = outcome.inspection ?: return@launch
        val request = page.fetchRequest ?: run {
            toaster.toast(getStringOffMain(Lang.tablet_drive_loading))
            return@launch
        }
        val service = registry.addedShareService(outcome.driveId) ?: return@launch
        // 不是自动认出的这一集 (手动挑的): 先记下这个分享与「这个文件是这一集」, 让「我添加的分享」重查
        val picked = inspection.currentFiles.none { it.fid == file.fid }
        if (picked) {
            service.pick(vm.subjectId, inspection, file.fid, request.episodeSort)
            ops.refreshAddedShares(outcome.driveId)
            sharesVersion++
        }
        val sourceId = ops.addedSharesSourceId(outcome.driveId) ?: CloudDriveAddedShareMediaSource.FactoryId.value
        val media = service.mediaOf(inspection, file.fid, sourceId, request) ?: return@launch
        val drive = drives.orEmpty().firstOrNull { it.driveId == outcome.driveId }
        val loggedIn = drive?.account?.first()?.isLoggedIn != false
        toaster.toast(
            listOfNotNull(
                getStringOffMain(if (picked) Lang.tablet_drive_share_file_picked else Lang.tablet_drive_playing, request.episodeSort.toString()),
                if (loggedIn || drive == null) null else getStringOffMain(Lang.tablet_drive_login_first, drive.displayName()),
            ).joinToString(getStringOffMain(Lang.tablet_drive_sentence_join)),
        )
        ops.playWhenListed(media, episode = null)
    }

    fun delete(row: AddedShareRow) = launch {
        val service = registry.addedShareService(row.driveId) ?: return@launch
        service.remove(vm.subjectId, row.shareId)
        ops.addedSharesSourceId(row.driveId)?.let { vm.restartSource(it) }
        sharesVersion++
    }

    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.tablet_drive_share_add), Modifier.weight(1f))
                AniPanelCloseButton(onDismissRequest, Modifier.padding(start = 12.dp))
            }
        },
        heightFraction = 0.9f,
        maxWidth = 680.dp,
        dismissOnClickOutside = false,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Lang.tablet_drive_share_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = { Text(stringResource(Lang.tablet_drive_share_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                minLines = 3,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.orEmpty(),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { add() }, enabled = !busy && text.isNotBlank()) {
                    Text(stringResource(Lang.tablet_drive_share_add_button))
                }
            }
            for (outcome in outcomes) {
                ShareOutcomeCard(outcome, enabled = !busy, onPlay = { play(outcome, it) })
            }
            if (shares.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(Lang.tablet_drive_share_list), style = MaterialTheme.typography.titleSmall)
                for (row in shares) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                row.note ?: row.driveName,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { delete(row) }, enabled = !busy) {
                            Text(stringResource(Lang.tablet_drive_share_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** 一个链接打开的结果: 认出了剧集的已经记下; [inspection] 为 null 时打不开. */
private class ShareOutcome(val driveId: String, val link: DriveShareLink, val message: String, val inspection: DriveShareInspection? = null)

private class AddedShareRow(val driveId: String, val driveName: String, val shareId: String, val title: String, val note: String?)

@Composable
private fun ShareOutcomeCard(outcome: ShareOutcome, enabled: Boolean, onPlay: (DriveShareFileInfo) -> Unit) {
    val inspection = outcome.inspection
    val ok = inspection != null && inspection.episodes.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            inspection?.title?.ifBlank { null } ?: outcome.link.shareId,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            outcome.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (inspection == null) return@Column
        // 当前这一集认出来了列它 (几个版本); 没认出列全部视频给用户挑
        val files = inspection.currentFiles.ifEmpty { inspection.files }
        for (file in files.take(MAX_LISTED_FILES)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(file.folders.lastOrNull(), file.size.takeIf { it > 0 }?.bytes?.toString()).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { onPlay(file) }, enabled = enabled) { Text(stringResource(Lang.tablet_drive_play_this)) }
            }
        }
        if (files.size > MAX_LISTED_FILES) {
            Text(
                stringResource(Lang.tablet_drive_share_more_files, files.size - MAX_LISTED_FILES),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun inspect(
    service: CloudDriveAddedShareService,
    driveName: String,
    request: MediaFetchRequest,
    link: DriveShareLink,
): ShareOutcome {
    val driveId = service.drive.driveId
    val episode = request.episodeSort.toString()
    val inspection = try {
        withTimeoutOrNull(INSPECT_TIMEOUT) { service.inspect(request, link) }
            ?: return ShareOutcome(driveId, link, getStringOffMain(Lang.tablet_drive_share_timeout))
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudDriveShareUnavailableException) {
        val needsPasscode = link.passcode.isEmpty() && e.message.orEmpty().let { it.contains("提取码") || it.contains("密码") }
        return ShareOutcome(
            driveId,
            link,
            if (needsPasscode) getStringOffMain(Lang.tablet_drive_share_needs_passcode)
            else getStringOffMain(Lang.tablet_drive_share_unavailable, e.message.orEmpty()),
        )
    } catch (e: Exception) {
        logger.warn(e) { "Failed to open $driveId share ${link.shareId}" }
        return ShareOutcome(driveId, link, getStringOffMain(Lang.tablet_drive_share_failed, e.message ?: e::class.simpleName.orEmpty()))
    }
    val separator = getStringOffMain(Lang.tablet_drive_episode_separator)
    val message = when {
        inspection.files.isEmpty() -> getStringOffMain(Lang.tablet_drive_share_no_videos, driveName)
        inspection.episodes.isEmpty() -> getStringOffMain(Lang.tablet_drive_share_no_episodes, episode)
        inspection.currentFiles.isNotEmpty() ->
            getStringOffMain(Lang.tablet_drive_share_recognized, formatDriveEpisodes(inspection.episodes, separator))

        else -> getStringOffMain(Lang.tablet_drive_share_recognized_missing, formatDriveEpisodes(inspection.episodes, separator), episode)
    }
    return ShareOutcome(driveId, link, message, inspection)
}

/** 最近一次打开这个分享时出了问题的话, 说一句是什么问题、还能不能播. 打开了且有视频时为 null. */
private suspend fun noteOf(videos: Int, error: String?, savedCopies: Int, driveName: String): String? {
    if (videos > 0) return null
    val reason = error?.let { getStringOffMain(Lang.tablet_drive_share_note_error, it) }
        ?: getStringOffMain(Lang.tablet_drive_share_note_gone, driveName)
    val playable = if (savedCopies > 0) {
        getStringOffMain(Lang.tablet_drive_share_note_saved, savedCopies)
    } else {
        getStringOffMain(Lang.tablet_drive_share_note_replace)
    }
    return reason + getStringOffMain(Lang.tablet_drive_sentence_join) + playable
}

private const val MAX_LINKS = 5
private const val MAX_LISTED_FILES = 60
private val INSPECT_TIMEOUT = 30.seconds
private val logger = logger("TabletShareAddDialog")

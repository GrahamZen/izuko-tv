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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.DrivePickedSubtitle
import me.him188.ani.app.data.models.preference.DriveSubjectPicks
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveMediaSource
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.clouddrive.DriveFile
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniPanelCloseButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tablet_drive_episode
import me.him188.ani.app.ui.lang.tablet_drive_episode_separator
import me.him188.ani.app.ui.lang.tablet_drive_failed
import me.him188.ani.app.ui.lang.tablet_drive_loading
import me.him188.ani.app.ui.lang.tablet_drive_pick
import me.him188.ani.app.ui.lang.tablet_drive_pick_attach
import me.him188.ani.app.ui.lang.tablet_drive_pick_attached
import me.him188.ani.app.ui.lang.tablet_drive_pick_detach
import me.him188.ani.app.ui.lang.tablet_drive_pick_detached
import me.him188.ani.app.ui.lang.tablet_drive_pick_file_note
import me.him188.ani.app.ui.lang.tablet_drive_pick_file_picked
import me.him188.ani.app.ui.lang.tablet_drive_pick_folder_none
import me.him188.ani.app.ui.lang.tablet_drive_pick_folder_note
import me.him188.ani.app.ui.lang.tablet_drive_pick_folder_picked
import me.him188.ani.app.ui.lang.tablet_drive_pick_folder_picked_missing
import me.him188.ani.app.ui.lang.tablet_drive_pick_forget
import me.him188.ani.app.ui.lang.tablet_drive_pick_forgotten
import me.him188.ani.app.ui.lang.tablet_drive_pick_here
import me.him188.ani.app.ui.lang.tablet_drive_pick_hint
import me.him188.ani.app.ui.lang.tablet_drive_pick_names_hint
import me.him188.ani.app.ui.lang.tablet_drive_pick_no_source
import me.him188.ani.app.ui.lang.tablet_drive_pick_not_logged_in
import me.him188.ani.app.ui.lang.tablet_drive_pick_not_this_drive
import me.him188.ani.app.ui.lang.tablet_drive_pick_remembered
import me.him188.ani.app.ui.lang.tablet_drive_pick_search
import me.him188.ani.app.ui.lang.tablet_drive_pick_search_placeholder
import me.him188.ani.app.ui.lang.tablet_drive_pick_subtitle
import me.him188.ani.app.ui.lang.tablet_drive_pick_subtitle_note
import me.him188.ani.app.ui.lang.tablet_drive_pick_up
import me.him188.ani.app.ui.lang.tablet_drive_play_this
import me.him188.ani.app.ui.lang.tablet_drive_playing
import me.him188.ani.app.ui.lang.tablet_drive_researching_drive
import me.him188.ani.app.ui.lang.tablet_drive_season_episode
import me.him188.ani.app.ui.lang.tablet_drive_sentence_join
import me.him188.ani.app.ui.settings.tabs.media.DriveBrowserList
import me.him188.ani.app.ui.settings.tabs.media.rememberDriveBrowserState
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.videoplayer.ui.progress.subtitleLanguage
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.features.subtitleTracks
import org.openani.mediamp.metadata.SubtitleTrack
import kotlin.time.Duration.Companion.seconds

/**
 * 「从网盘挑」(同控制台播放器页那一块): 自动匹配对不上时, 搜自己的网盘或一层层点进去, 指定「这部番在这个文件夹里」或「这个文件是这一集」
 * (记在这个网盘账号里, 这个网盘自己网盘的数据源之后每一集都照它给出资源); 字幕文件能挂到正在播的、来自这个网盘的视频上。
 * 顶上是这部番已经记下的位置与挂上的字幕, 能忘掉 / 取下。网盘有几个时上面一排切换。
 */
@Composable
internal fun TabletDrivePickDialog(vm: EpisodeViewModel, page: EpisodePageState, onDismissRequest: () -> Unit) {
    val registry = remember { GlobalKoin.get<CloudDriveRegistry>() }
    val drives by registry.drives.collectAsState()
    val pickDrives = drives.orEmpty().filter { it.canPick() }
    var driveId by remember { mutableStateOf<String?>(null) }
    val drive = pickDrives.firstOrNull { it.driveId == driveId } ?: pickDrives.firstOrNull()
    if (drive == null) {
        if (drives != null) LaunchedEffect(Unit) { onDismissRequest() }
        return
    }
    key(drive.driveId) {
        DrivePickContent(vm, page, drive, pickDrives, onSwitchDrive = { driveId = it }, onDismissRequest)
    }
}

@Composable
private fun DrivePickContent(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    drive: CloudDriveService,
    drives: List<CloudDriveService>,
    onSwitchDrive: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val ops = remember(vm, page) { TabletDriveOps(vm, page) }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val account by drive.account.collectAsState(null)
    val loggedIn = account?.isLoggedIn == true
    val state = rememberDriveBrowserState(drive)
    val request = page.fetchRequest
    val loaded by vm.loadedMedia.collectAsState()
    val subtitleKey = loaded?.let { drive.subtitleKeyOf(it) }

    var picks by remember { mutableStateOf(DriveSubjectPicks()) }
    var subtitles by remember { mutableStateOf<List<DrivePickedSubtitle>>(emptyList()) }
    var picksVersion by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf(request?.let { it.subjectNameCN ?: it.subjectNames.firstOrNull() }.orEmpty()) }

    LaunchedEffect(loggedIn, subtitleKey, picksVersion) {
        if (!loggedIn) return@LaunchedEffect
        picks = drive.picksOf(vm.subjectId)
        subtitles = subtitleKey?.let { drive.pickedSubtitlesOf(it) }.orEmpty()
    }

    fun launch(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: CloudDriveAuthException) {
                toaster.toast(getStringOffMain(Lang.tablet_drive_pick_not_logged_in, drive.displayName()))
            } catch (e: Exception) {
                logger.warn(e) { "Tablet drive pick action failed" }
                toaster.toast(getStringOffMain(Lang.tablet_drive_failed, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                busy = false
            }
        }
    }

    /** 让这个网盘的数据源重查; 返回接在提示后面的一句. */
    suspend fun refresh(): String? = when (ops.refreshDriveSource(drive)) {
        null -> getStringOffMain(Lang.tablet_drive_pick_no_source, drive.displayName())
        true -> getStringOffMain(Lang.tablet_drive_researching_drive, drive.displayName())
        false -> null
    }

    fun play(file: DriveFile, folderName: String?) = launch {
        val req = page.fetchRequest ?: return@launch toaster.toast(getStringOffMain(Lang.tablet_drive_loading))
        val media = CloudDriveMediaSource.mediaFor(
            drive, file, req.episodeSort, CloudDriveMediaSource.subjectNameOf(req), listOfNotNull(folderName),
        )
        val listed = ops.candidate(media.mediaId)
        if (listed != null && listed.episodeRange?.contains(req.episodeSort) == true) {
            ops.select(listed)
            toaster.toast(getStringOffMain(Lang.tablet_drive_playing))
            return@launch
        }
        drive.pickFile(vm.subjectId, file, req.episodeSort)
        picksVersion++
        val refreshed = refresh()
        toaster.toast(
            listOfNotNull(getStringOffMain(Lang.tablet_drive_pick_file_picked, req.episodeSort.toString()), refreshed).joinToString(getStringOffMain(Lang.tablet_drive_sentence_join)),
        )
        ops.playWhenListed(media, req.episodeSort)
    }

    fun pickFolder(folder: DriveFile) = launch {
        val req = page.fetchRequest ?: return@launch toaster.toast(getStringOffMain(Lang.tablet_drive_loading))
        val found = drive.episodesInFolder(req, folder)
        if (found.isEmpty()) return@launch toaster.toast(getStringOffMain(Lang.tablet_drive_pick_folder_none))
        drive.pickFolder(vm.subjectId, folder)
        picksVersion++
        val episodes = formatDriveEpisodes(found.map { it.third }.distinct().sorted(), getStringOffMain(Lang.tablet_drive_episode_separator))
        val current = found.firstOrNull { it.third == req.episodeSort }
        val refreshed = refresh()
        val message = if (current != null) {
            getStringOffMain(Lang.tablet_drive_pick_folder_picked, folder.fileName, episodes)
        } else {
            getStringOffMain(Lang.tablet_drive_pick_folder_picked_missing, folder.fileName, episodes, req.episodeSort.toString())
        }
        toaster.toast(listOfNotNull(message, refreshed).joinToString(getStringOffMain(Lang.tablet_drive_sentence_join)))
        if (current != null) {
            val media = CloudDriveMediaSource.mediaFor(
                drive, current.first, req.episodeSort, CloudDriveMediaSource.subjectNameOf(req), current.second,
            )
            ops.playWhenListed(media, req.episodeSort)
        }
    }

    fun attach(file: DriveFile) = launch {
        val media = vm.loadedMedia.value
        val key = media?.let { drive.subtitleKeyOf(it) }
            ?: return@launch toaster.toast(getStringOffMain(Lang.tablet_drive_pick_not_this_drive, drive.displayName()))
        drive.pickSubtitle(key, file)
        picksVersion++
        toaster.toast(getStringOffMain(Lang.tablet_drive_pick_attached, file.fileName))
        reloadWithSubtitle(vm, drive.subtitleLabelOf(file.fileName))
    }

    fun detach(subtitle: DrivePickedSubtitle) = launch {
        val key = subtitleKey ?: return@launch
        drive.forgetSubtitle(key, subtitle.fid)
        picksVersion++
        toaster.toast(getStringOffMain(Lang.tablet_drive_pick_detached))
        reloadWithSubtitle(vm, label = null)
    }

    fun forget(fid: String) = launch {
        drive.forgetPick(vm.subjectId, fid)
        picksVersion++
        refresh()
        toaster.toast(getStringOffMain(Lang.tablet_drive_pick_forgotten))
    }

    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canGoUp) {
                    IconButton(onClick = { state.up() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Lang.tablet_drive_pick_up))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Lang.tablet_drive_pick))
                    if (request != null) {
                        Text(
                            stringResource(Lang.tablet_drive_pick_subtitle, request.episodeSort.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AniPanelCloseButton(onDismissRequest, Modifier.padding(start = 12.dp))
            }
        },
        heightFraction = 0.9f,
        maxWidth = 760.dp,
        dismissOnClickOutside = false,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (drives.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (each in drives) {
                        FilterChip(
                            selected = each.driveId == drive.driveId,
                            onClick = { onSwitchDrive(each.driveId) },
                            label = { Text(each.displayName()) },
                        )
                    }
                }
            }
            if (!loggedIn) {
                Text(
                    stringResource(Lang.tablet_drive_pick_not_logged_in, drive.displayName()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            RememberedPicks(picks, subtitles, enabled = !busy, onForget = ::forget, onDetach = ::detach)
            if (drive.protocol.supportsSearch) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        keyword,
                        { keyword = it },
                        Modifier.weight(1f),
                        placeholder = { Text(stringResource(Lang.tablet_drive_pick_search_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { state.search(keyword) }),
                    )
                    TextButton(onClick = { state.search(keyword) }, enabled = keyword.isNotBlank()) {
                        Text(stringResource(Lang.tablet_drive_pick_search))
                    }
                }
                val names = request?.let { listOfNotNull(it.subjectNameCN) + it.subjectNames }.orEmpty()
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (names.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (name in names) {
                            SuggestionChip(
                                onClick = {
                                    keyword = name
                                    state.search(name)
                                },
                                label = { Text(name, maxLines = 1) },
                            )
                        }
                        Text(
                            stringResource(Lang.tablet_drive_pick_names_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                state.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val folderName = state.current.takeIf { !it.search && state.canGoUp }?.name
            DriveBrowserList(
                state,
                Modifier.weight(1f, fill = false),
                shown = { it.isVideo || drive.isSubtitleFile(it.fileName) },
                meta = { file -> if (file.dir) null else fileMeta(drive, file) },
            ) { file ->
                when {
                    file.dir -> TextButton(onClick = { pickFolder(file) }, enabled = !busy) {
                        Text(stringResource(Lang.tablet_drive_pick_here))
                    }

                    file.isVideo -> TextButton(onClick = { play(file, folderName) }, enabled = !busy) {
                        Text(stringResource(Lang.tablet_drive_play_this))
                    }

                    else -> TextButton(onClick = { attach(file) }, enabled = !busy && subtitleKey != null) {
                        Text(stringResource(Lang.tablet_drive_pick_attach))
                    }
                }
            }
            Text(
                stringResource(Lang.tablet_drive_pick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 这部番已经记下的文件夹与文件、正在播的视频挂上的字幕; 都没有时不画. */
@Composable
private fun RememberedPicks(
    picks: DriveSubjectPicks,
    subtitles: List<DrivePickedSubtitle>,
    enabled: Boolean,
    onForget: (String) -> Unit,
    onDetach: (DrivePickedSubtitle) -> Unit,
) {
    if (picks.isEmpty && subtitles.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(stringResource(Lang.tablet_drive_pick_remembered), style = MaterialTheme.typography.titleSmall)
        for (folder in picks.folders) {
            PickRow(stringResource(Lang.tablet_drive_pick_folder_note, folder.name), stringResource(Lang.tablet_drive_pick_forget), enabled) {
                onForget(folder.fid)
            }
        }
        for (file in picks.files) {
            PickRow(stringResource(Lang.tablet_drive_pick_file_note, file.fileName, file.episode), stringResource(Lang.tablet_drive_pick_forget), enabled) {
                onForget(file.fid)
            }
        }
        for (subtitle in subtitles) {
            PickRow(stringResource(Lang.tablet_drive_pick_subtitle_note, subtitle.fileName), stringResource(Lang.tablet_drive_pick_detach), enabled) {
                onDetach(subtitle)
            }
        }
    }
}

@Composable
private fun PickRow(text: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

/** 视频认出的集 (带季) 与大小. */
@Composable
private fun fileMeta(drive: CloudDriveService, file: DriveFile): String? {
    val episode = drive.episodeInFileName(file.fileName)?.let { (season, episode) ->
        if (season != null) stringResource(Lang.tablet_drive_season_episode, season, episode.toString())
        else stringResource(Lang.tablet_drive_episode, episode.toString())
    }
    return listOfNotNull(episode, file.size.takeIf { it > 0 }?.bytes?.toString()).joinToString(" · ").ifEmpty { null }
}

/**
 * 让播放器原地重新加载正在播的视频 (重新取字幕), 回到现在的位置; [label] 不为 null 时装好后选上这个名字的外挂字幕.
 * 外挂字幕的轨道排在内封的后面 (id 不以 `0:` 开头). 在播放页的 scope 上跑 (弹窗关了也做完).
 */
private fun reloadWithSubtitle(vm: EpisodeViewModel, label: String?) {
    vm.backgroundScope.launch(Dispatchers.Main) {
        if (!vm.reloadCurrentMedia()) return@launch
        if (label == null) return@launch
        val group = vm.player.subtitleTracks ?: return@launch
        withTimeoutOrNull(RELOAD_WAIT) {
            delay(1_000)
            val track = group.candidates.first { list -> list.any { it.matches(label) } }.first { it.matches(label) }
            group.select(track)
        }
    }
}

private fun SubtitleTrack.matches(label: String): Boolean =
    !id.startsWith("0:") && (subtitleLanguage == label || subtitleLanguage.startsWith("$label "))

private val RELOAD_WAIT = 60.seconds
private val logger = logger("TabletDrivePickDialog")

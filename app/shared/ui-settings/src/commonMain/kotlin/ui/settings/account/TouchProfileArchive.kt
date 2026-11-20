/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.domain.profile.ProfileArchive
import me.him188.ani.app.domain.profile.ProfileArchiver
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniPanelCloseButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_profile_export_done
import me.him188.ani.app.ui.lang.touch_profile_export_failed
import me.him188.ani.app.ui.lang.touch_profile_import
import me.him188.ani.app.ui.lang.touch_profile_import_choose
import me.him188.ani.app.ui.lang.touch_profile_import_done
import me.him188.ani.app.ui.lang.touch_profile_import_hint
import me.him188.ani.app.ui.lang.touch_profile_import_invalid
import me.him188.ani.app.ui.lang.touch_profile_import_progress
import me.him188.ani.app.ui.lang.touch_profile_import_start
import me.him188.ani.app.ui.lang.touch_profile_import_summary
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/*
 * 触屏版设置页里本地用户的导出与导入, 同 Web 控制台的那一组 (见 ProfileArchiver): 导出成 JSON 文件,
 * 换设备、重装后再导进当前的本地用户。文件格式同控制台导出的那份, 两边导出的都能互相导入。
 */

private val logger = logger("TouchProfileArchive")

/** 一次写进去的播放进度条数 (同控制台网页一批发的量). */
private const val PLAYBACK_BATCH = 200

/** 把当前的本地用户导出成文件 (系统的存储面板里选位置); 用户取消就什么都不做. */
suspend fun exportCurrentProfileToFile(toast: (String) -> Unit) {
    val profile = UserProfiles.current
    try {
        val archive = GlobalKoin.get<ProfileArchiver>().export(profile)
        val json = ProfileArchive.Json.encodeToString(ProfileArchive.serializer(), archive)
        val date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString().replace("-", "")
        val name = profile.name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").ifEmpty { "user${profile.id}" }
        val target = FileKit.openFileSaver(suggestedName = "izuko-$name-$date", extension = "json") ?: return
        withContext(Dispatchers.IO_) { target.write(json.encodeToByteArray()) }
        toast(getStringOffMain(Lang.touch_profile_export_done, archive.collections.size, archive.playback.size))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to export user profile ${profile.id}" }
        toast(getStringOffMain(Lang.touch_profile_export_failed, e.message ?: e::class.simpleName.orEmpty()))
    }
}

/**
 * 从文件导入到当前的本地用户: 选文件 → 写着有几部收藏、几条播放进度 → 导入 (一部一部来, 条目信息库里没有的要去 Bangumi 取, 一部一两秒,
 * 有进度条) → 结果。已经收藏了的不动。导着的时候点外面不关。
 */
@Composable
fun TouchProfileImportDialog(onDismissRequest: () -> Unit) {
    val archiver = remember { GlobalKoin.get<ProfileArchiver>() }
    val scope = rememberCoroutineScope()
    var archive by remember { mutableStateOf<ProfileArchive?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var finished by remember { mutableStateOf(false) }

    val picker = rememberFilePickerLauncher(type = FileKitType.File("json", "txt")) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            archive = try {
                val text = withContext(Dispatchers.IO_) { file.readBytes().decodeToString() }
                ProfileArchive.Json.decodeFromString(ProfileArchive.serializer(), text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            message = if (archive == null) getStringOffMain(Lang.touch_profile_import_invalid) else null
        }
    }

    val running = progress != null && !finished
    AniCenteredPanelDialog(
        onDismissRequest = { if (!running) onDismissRequest() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.touch_profile_import), Modifier.weight(1f))
                if (!running) AniPanelCloseButton(onDismissRequest, Modifier.padding(start = 12.dp))
            }
        },
        heightFraction = null,
        maxWidth = 560.dp,
        dismissOnClickOutside = !running,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Lang.touch_profile_import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val loaded = archive
            if (loaded != null && !finished) {
                Text(
                    stringResource(Lang.touch_profile_import_summary, loaded.collections.size, loaded.playback.size),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            progress?.let { (done, total) ->
                if (!finished) {
                    LinearProgressIndicator(progress = { if (total == 0) 1f else done.toFloat() / total }, Modifier.fillMaxWidth())
                    Text(stringResource(Lang.touch_profile_import_progress, done, total), style = MaterialTheme.typography.bodySmall)
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running && !finished) {
                    TextButton(onClick = { picker.launch() }) { Text(stringResource(Lang.touch_profile_import_choose)) }
                }
                if (loaded != null && !running && !finished) {
                    Button(
                        onClick = {
                            scope.launch {
                                message = importArchive(archiver, loaded) { done, total -> progress = done to total }
                                finished = true
                            }
                        },
                    ) { Text(stringResource(Lang.touch_profile_import_start)) }
                }
            }
        }
    }
}

/** 照控制台网页的顺序导: 先一部一部导收藏, 再一批批导播放进度. @return 结果那一句 */
private suspend fun importArchive(
    archiver: ProfileArchiver,
    archive: ProfileArchive,
    onProgress: (done: Int, total: Int) -> Unit,
): String {
    val total = archive.collections.size
    var added = 0
    var skipped = 0
    var failed = 0
    onProgress(0, total)
    archive.collections.forEachIndexed { index, item ->
        try {
            when (archiver.restoreCollection(item)) {
                is ProfileArchiver.CollectionOutcome.Added -> added++
                ProfileArchiver.CollectionOutcome.Skipped -> skipped++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore subject ${item.subjectId}" }
            failed++
        }
        onProgress(index + 1, total)
    }
    var playback = 0
    for (batch in archive.playback.chunked(PLAYBACK_BATCH)) {
        playback += try {
            archiver.restorePlayback(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore playback records" }
            0
        }
    }
    return getStringOffMain(Lang.touch_profile_import_done, added, skipped, failed, playback)
}

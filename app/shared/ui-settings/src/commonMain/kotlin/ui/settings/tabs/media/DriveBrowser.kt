/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.clouddrive.DriveFile
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.drive_browser_auth_failed
import me.him188.ani.app.ui.lang.drive_browser_empty
import me.him188.ani.app.ui.lang.drive_browser_failed
import me.him188.ani.app.ui.lang.drive_browser_more
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.stringResource

/**
 * 网盘目录浏览 (「从网盘挑」与 WebDAV 选媒体文件夹共用): 一层层点进去、回上一层; 搜索结果算一层, 挂在根下面.
 * 列表经 [CloudDriveService.listForPicking] / [CloudDriveService.searchForPicking] 取, 换层时取消还没回来的那次.
 */
@Stable
class DriveBrowserState(val service: CloudDriveService, private val scope: CoroutineScope) {
    /** 走进来的一层: [fid] = 文件夹 id (搜索那层为空), [name] = 文件夹名或搜索词. */
    data class Level(val fid: String, val name: String, val search: Boolean = false)

    sealed interface Failure {
        data object Auth : Failure
        data class Other(val message: String) : Failure
    }

    var levels: List<Level> by mutableStateOf(listOf(Level(service.rootFolderId, "")))
        private set

    /** 这一层的内容; 还在取时为 null. */
    var items: List<DriveFile>? by mutableStateOf(null)
        private set

    var failure: Failure? by mutableStateOf(null)
        private set

    private var job: Job? = null

    val current: Level get() = levels.last()
    val canGoUp: Boolean get() = levels.size > 1

    /** 这一层在界面上的路径: 根是 `/`, 搜索那层写搜索词. */
    val path: String get() = "/" + levels.drop(1).joinToString(" / ") { it.name }

    init {
        load()
    }

    fun open(folder: DriveFile) {
        if (!folder.dir) return
        levels = levels + Level(folder.fid, folder.fileName)
        load()
    }

    fun up() {
        if (!canGoUp) return
        levels = levels.dropLast(1)
        load()
    }

    fun search(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        levels = listOf(levels.first(), Level("", trimmed, search = true))
        load()
    }

    fun reload() = load()

    private fun load() {
        job?.cancel()
        items = null
        failure = null
        val level = current
        job = scope.launch {
            try {
                items = if (level.search) service.searchForPicking(level.name) else service.listForPicking(level.fid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: CloudDriveAuthException) {
                items = emptyList()
                failure = Failure.Auth
            } catch (e: Exception) {
                logger.warn(e) { "Failed to list ${service.driveId} ${level.fid.ifEmpty { level.name }}" }
                items = emptyList()
                failure = Failure.Other(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    private companion object {
        private val logger = logger<DriveBrowserState>()
    }
}

@Composable
fun rememberDriveBrowserState(service: CloudDriveService): DriveBrowserState {
    val scope = rememberCoroutineScope()
    return remember(service.driveId) { DriveBrowserState(service, scope) }
}

/**
 * [state] 这一层的列表: 文件夹在前 (点了进去), 只列 [shown] 认的项, 最多 [maxItems] 项. 每项右边放 [trailing] (按钮),
 * 名字下面一行小字是 [meta] (没有就不写).
 */
@Composable
fun DriveBrowserList(
    state: DriveBrowserState,
    modifier: Modifier = Modifier,
    shown: (DriveFile) -> Boolean = { true },
    meta: @Composable (DriveFile) -> String? = { null },
    maxItems: Int = 200,
    trailing: @Composable RowScope.(DriveFile) -> Unit = {},
) {
    val items = state.items
    when {
        items == null -> Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }

        else -> {
            val visible = items.filter { it.dir || shown(it) }
            val failure = state.failure
            if (visible.isEmpty() || failure != null) {
                Text(
                    when (failure) {
                        DriveBrowserState.Failure.Auth -> stringResource(Lang.drive_browser_auth_failed)
                        is DriveBrowserState.Failure.Other -> stringResource(Lang.drive_browser_failed, failure.message)
                        null -> stringResource(Lang.drive_browser_empty)
                    },
                    modifier.fillMaxWidth().padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return
            }
            LazyColumn(modifier.fillMaxWidth()) {
                items(visible.take(maxItems), key = { it.fid }) { file ->
                    DriveBrowserRow(file, meta(file), onOpen = { state.open(file) }, trailing = { trailing(file) })
                    HorizontalDivider()
                }
                if (visible.size > maxItems) {
                    item("more") {
                        Text(
                            stringResource(Lang.drive_browser_more, visible.size - maxItems),
                            Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveBrowserRow(
    file: DriveFile,
    meta: String?,
    onOpen: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (file.dir) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val (icon, tint) = when {
            file.dir -> Icons.Rounded.Folder to MaterialTheme.colorScheme.primary
            file.isVideo -> Icons.Rounded.Movie to MaterialTheme.colorScheme.tertiary
            else -> Icons.Rounded.Subtitles to MaterialTheme.colorScheme.secondary
        }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(file.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta != null) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
        if (file.dir) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

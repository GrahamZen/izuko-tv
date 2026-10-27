/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess
import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationProblem
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.download.DownloadSpaceGuard
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.aniFocusContainerColor
import me.him188.ani.app.ui.foundation.widgets.aniFocusContentColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_cache_location_fat32
import me.him188.ani.app.ui.lang.settings_storage_cache_location_internal
import me.him188.ani.app.ui.lang.settings_storage_cache_location_read_only
import me.him188.ani.app.ui.lang.settings_storage_cache_location_space
import me.him188.ani.app.ui.lang.settings_storage_media_library_confirm_delete_when_done
import me.him188.ani.app.ui.lang.settings_storage_media_library_confirm_text
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_adb_text
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_app
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_details
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_list
import me.him188.ani.app.ui.lang.settings_storage_media_library_unsupported
import me.him188.ani.app.ui.lang.storage_guide_busy
import me.him188.ani.app.ui.lang.storage_guide_done_description
import me.him188.ani.app.ui.lang.storage_guide_done_finish
import me.him188.ani.app.ui.lang.storage_guide_done_keep_existing
import me.him188.ani.app.ui.lang.storage_guide_done_move
import me.him188.ani.app.ui.lang.storage_guide_done_nothing_to_move
import me.him188.ani.app.ui.lang.storage_guide_done_title
import me.him188.ani.app.ui.lang.storage_guide_done_where
import me.him188.ani.app.ui.lang.storage_guide_drive_description
import me.him188.ani.app.ui.lang.storage_guide_drive_empty
import me.him188.ani.app.ui.lang.storage_guide_drive_has
import me.him188.ani.app.ui.lang.storage_guide_drive_title
import me.him188.ani.app.ui.lang.storage_guide_existing_cross_drive
import me.him188.ani.app.ui.lang.storage_guide_existing_description
import me.him188.ani.app.ui.lang.storage_guide_existing_downloads
import me.him188.ani.app.ui.lang.storage_guide_existing_keep
import me.him188.ani.app.ui.lang.storage_guide_existing_keep_description
import me.him188.ani.app.ui.lang.storage_guide_existing_library
import me.him188.ani.app.ui.lang.storage_guide_existing_move
import me.him188.ani.app.ui.lang.storage_guide_existing_move_back
import me.him188.ani.app.ui.lang.storage_guide_existing_move_back_description
import me.him188.ani.app.ui.lang.storage_guide_existing_move_description
import me.him188.ani.app.ui.lang.storage_guide_existing_note
import me.him188.ani.app.ui.lang.storage_guide_existing_only_new
import me.him188.ani.app.ui.lang.storage_guide_existing_only_new_description
import me.him188.ani.app.ui.lang.storage_guide_existing_published
import me.him188.ani.app.ui.lang.storage_guide_existing_space
import me.him188.ani.app.ui.lang.storage_guide_existing_title
import me.him188.ani.app.ui.lang.storage_guide_mode_app
import me.him188.ani.app.ui.lang.storage_guide_mode_app_description
import me.him188.ani.app.ui.lang.storage_guide_mode_description
import me.him188.ani.app.ui.lang.storage_guide_mode_library
import me.him188.ani.app.ui.lang.storage_guide_mode_library_description
import me.him188.ani.app.ui.lang.storage_guide_mode_title
import me.him188.ani.app.ui.lang.storage_guide_permission_description
import me.him188.ani.app.ui.lang.storage_guide_permission_go
import me.him188.ani.app.ui.lang.storage_guide_permission_granted
import me.him188.ani.app.ui.lang.storage_guide_permission_skip
import me.him188.ani.app.ui.lang.storage_guide_permission_title
import me.him188.ani.app.ui.lang.storage_guide_permission_waiting
import me.him188.ani.app.ui.lang.storage_guide_step_drive
import me.him188.ani.app.ui.lang.storage_guide_step_existing
import me.him188.ani.app.ui.lang.storage_guide_step_mode
import me.him188.ani.app.ui.lang.storage_guide_step_permission
import me.him188.ani.app.ui.lang.tv_onboarding_recommended
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * 「缓存放在哪」引导 (设置「存储」那一行按下去): 全屏, 一步一步问 —— 放在哪块盘、下完的放进片库还是应用目录、(选了片库又没权限时) 去授权、
 * 已有的缓存怎么办, 最后确认. 各步先选好现在的设置; 按「完成」才写设置、开始搬 ([MediaStorageSetup.apply]), 中途退出什么都不改.
 * 返回键退回上一步, 在第一步关掉引导. 步骤逻辑在 [StorageGuideState].
 */
@Composable
internal fun StorageGuideDialog(dismiss: () -> Unit) {
    val setup = remember { GlobalKoin.get<MediaStorageSetup>() }
    val locations = remember { GlobalKoin.get<MediaCacheLocations>() }
    val access = remember { GlobalKoin.getOrNull<MediaLibraryAccess>() }
    val initial by produceState<MediaStorageSetup.Choice?>(null) { value = setup.current() }
    val state = initial?.let { remember(it) { StorageGuideState(it, access?.isSupported == true) } }
    Dialog(
        onDismissRequest = { if (state?.back() != true) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val focus = rememberTvFocusScope()
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .tvFocusNavSignal(focus)
                    .padding(horizontal = PAGE_HORIZONTAL_PADDING, vertical = PAGE_VERTICAL_PADDING),
            ) {
                if (state != null) StorageGuideContent(state, setup, locations, access, focus, dismiss)
            }
        }
    }
}

@Composable
private fun StorageGuideContent(
    state: StorageGuideState,
    setup: MediaStorageSetup,
    locations: MediaCacheLocations,
    access: MediaLibraryAccess?,
    focus: TvFocusScope,
    dismiss: () -> Unit,
) {
    val drives by locations.locations.collectAsState()
    LaunchedEffect(Unit) { locations.refresh() }
    val granted by produceState(access?.isGranted() == true) {
        while (true) {
            value = access?.isGranted() == true
            delay(1.seconds)
        }
    }
    LaunchedEffect(granted, state.step) { if (granted) state.permissionGranted() }
    Column(Modifier.fillMaxSize()) {
        GuideHeader(state)
        Spacer(Modifier.height(SECTION_GAP))
        when (state.step) {
            StorageGuideStep.Drive -> DriveStep(state, setup, drives, focus)
            StorageGuideStep.Mode -> ModeStep(state, granted, focus)
            StorageGuideStep.Permission -> PermissionStep(state, access, granted, focus)
            StorageGuideStep.Existing -> ExistingStep(state, setup, drives, focus)
            StorageGuideStep.Confirm -> ConfirmStep(state, setup, drives, focus, dismiss)
        }
    }
}

/** 标题 (左) 与几步的进度 (右); 确认那一步算最后一步走完 */
@Composable
private fun GuideHeader(state: StorageGuideState) {
    val steps = state.visibleSteps
    val current = steps.indexOf(state.step).takeIf { it >= 0 } ?: steps.size
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(state.step.title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(28.dp)
                        .height(1.dp)
                        .background(scheme.outlineVariant),
                )
            }
            Text(
                "${index + 1} ${stringResource(step.label)}",
                style = MaterialTheme.typography.labelLarge,
                color = if (index <= current) scheme.primary else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DriveStep(state: StorageGuideState, setup: MediaStorageSetup, drives: List<MediaCacheLocation>, focus: TvFocusScope) {
    val usage by produceState(emptyMap<String?, MediaStorageSetup.Group>()) { value = setup.usage() }
    val recommended = remember(drives) { recommendedDrive(drives) }
    GuideColumns(
        description = stringResource(Lang.storage_guide_drive_description),
        options = {
            for (drive in drives) {
                val used = usage[drive.id]
                GuideOption(
                    title = driveName(drive),
                    description = listOfNotNull(
                        unusableReason(drive) ?: spaceText(drive),
                        if (used == null || used.isEmpty) {
                            stringResource(Lang.storage_guide_drive_empty)
                        } else {
                            stringResource(Lang.storage_guide_drive_has, used.units, used.bytes.bytes.toString())
                        },
                    ).joinToString("\n"),
                    recommended = drive.id == recommended,
                    selected = drive.id == state.location,
                    enabled = drive.unusable == null,
                    onClick = { state.chooseDrive(drive.id) },
                    modifier = Modifier.tvFocusAnchor(focus, GuideFocus("drive:${drive.id}")),
                )
            }
        },
    )
    val initialFocus = state.location.takeIf { id -> drives.any { it.id == id && it.unusable == null } } ?: recommended
    LaunchedEffect(Unit) { focus.request(GuideFocus("drive:$initialFocus")) }
}

@Composable
private fun ModeStep(state: StorageGuideState, granted: Boolean, focus: TvFocusScope) {
    GuideColumns(
        description = stringResource(Lang.storage_guide_mode_description),
        options = {
            GuideOption(
                title = stringResource(Lang.storage_guide_mode_library),
                description = if (state.librarySupported) {
                    stringResource(Lang.storage_guide_mode_library_description)
                } else {
                    stringResource(Lang.settings_storage_media_library_unsupported)
                },
                recommended = state.librarySupported,
                selected = state.library,
                enabled = state.librarySupported,
                onClick = { state.chooseMode(library = true, granted = granted) },
                modifier = Modifier.tvFocusAnchor(focus, GuideFocus("mode:library")),
            )
            GuideOption(
                title = stringResource(Lang.storage_guide_mode_app),
                description = stringResource(Lang.storage_guide_mode_app_description),
                recommended = false,
                selected = !state.library,
                onClick = { state.chooseMode(library = false, granted = granted) },
                modifier = Modifier.tvFocusAnchor(focus, GuideFocus("mode:app")),
            )
        },
    )
    LaunchedEffect(Unit) {
        focus.request(GuideFocus(if (state.library || (state.librarySupported && !state.wasLibrary)) "mode:library" else "mode:app"))
    }
}

@Composable
private fun PermissionStep(state: StorageGuideState, access: MediaLibraryAccess?, granted: Boolean, focus: TvFocusScope) {
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    GuideColumns(
        description = stringResource(Lang.storage_guide_permission_description),
        details = {
            Text(
                stringResource(if (granted) Lang.storage_guide_permission_granted else Lang.storage_guide_permission_waiting),
                style = MaterialTheme.typography.titleMedium,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        },
        options = {
            GuideOption(
                title = stringResource(Lang.storage_guide_permission_go),
                description = null,
                recommended = false,
                selected = false,
                onClick = {
                    scope.launch {
                        hint = when (access?.openGrantPage()) {
                            MediaLibraryAccess.GrantPage.APP -> getString(Lang.settings_storage_media_library_grant_app)
                            MediaLibraryAccess.GrantPage.LIST -> getString(Lang.settings_storage_media_library_grant_list)
                            MediaLibraryAccess.GrantPage.APP_DETAILS -> getString(Lang.settings_storage_media_library_grant_details)
                            null -> access?.adbGrantCommand?.let { getString(Lang.settings_storage_media_library_grant_adb_text, it) }
                        }
                    }
                },
                modifier = Modifier.tvFocusAnchor(focus, GuideFocus("permission:go")),
            )
            GuideOption(
                title = stringResource(Lang.storage_guide_permission_skip),
                description = stringResource(Lang.storage_guide_mode_app_description),
                recommended = false,
                selected = false,
                onClick = { state.skipPermission() },
                modifier = Modifier.tvFocusAnchor(focus, GuideFocus("permission:skip")),
            )
        },
    )
    LaunchedEffect(Unit) { focus.request(GuideFocus("permission:go")) }
}

@Composable
private fun ExistingStep(state: StorageGuideState, setup: MediaStorageSetup, drives: List<MediaCacheLocation>, focus: TvFocusScope) {
    val plan by produceState<MediaStorageSetup.Plan?>(null, state.choice) { value = setup.plan(state.choice) }
    val current = plan
    LaunchedEffect(current) { if (current != null && current.total.isEmpty) state.nothingToMove() }
    if (current == null) return
    val turningOff = state.turningLibraryOff && !current.library.isEmpty
    GuideColumns(
        description = stringResource(Lang.storage_guide_existing_description),
        details = { PlanLines(current, drives) },
        options = {
            if (turningOff) {
                GuideOption(
                    title = stringResource(Lang.storage_guide_existing_move_back),
                    description = stringResource(Lang.storage_guide_existing_move_back_description),
                    recommended = false,
                    selected = !state.keepLibraryInFolder,
                    onClick = { state.chooseExisting(move = true, keepLibraryInFolder = false) },
                    modifier = Modifier.tvFocusAnchor(focus, GuideFocus("existing:move")),
                )
                GuideOption(
                    title = stringResource(Lang.storage_guide_existing_keep),
                    description = stringResource(Lang.storage_guide_existing_keep_description),
                    recommended = false,
                    selected = state.keepLibraryInFolder,
                    onClick = { state.chooseExisting(move = true, keepLibraryInFolder = true) },
                    modifier = Modifier.tvFocusAnchor(focus, GuideFocus("existing:keep")),
                )
            } else {
                GuideOption(
                    title = stringResource(Lang.storage_guide_existing_move),
                    description = stringResource(Lang.storage_guide_existing_move_description),
                    recommended = false,
                    selected = state.moveExisting,
                    onClick = { state.chooseExisting(move = true) },
                    modifier = Modifier.tvFocusAnchor(focus, GuideFocus("existing:move")),
                )
                GuideOption(
                    title = stringResource(Lang.storage_guide_existing_only_new),
                    description = stringResource(Lang.storage_guide_existing_only_new_description),
                    recommended = false,
                    selected = !state.moveExisting,
                    onClick = { state.chooseExisting(move = false) },
                    modifier = Modifier.tvFocusAnchor(focus, GuideFocus("existing:keep")),
                )
            }
        },
    )
    LaunchedEffect(Unit) { focus.request(GuideFocus("existing:move")) }
}

/** 要搬的几类、跨盘复制多少、目标盘放不放得下; 会关掉「标记看过后删除缓存」时说一句 */
@Composable
private fun PlanLines(plan: MediaStorageSetup.Plan, drives: List<MediaCacheLocation>) {
    val lines = buildList {
        if (!plan.downloads.isEmpty) {
            add(stringResource(Lang.storage_guide_existing_downloads, plan.downloads.units, plan.downloads.bytes.bytes.toString()))
        }
        if (!plan.published.isEmpty) {
            add(stringResource(Lang.storage_guide_existing_published, plan.published.units, plan.published.bytes.bytes.toString()))
        }
        if (!plan.library.isEmpty) {
            add(stringResource(Lang.storage_guide_existing_library, plan.library.units, plan.library.bytes.bytes.toString()))
        }
        if (plan.otherDriveBytes > 0) add(stringResource(Lang.storage_guide_existing_cross_drive, plan.otherDriveBytes.bytes.toString()))
        val free = drives.firstOrNull { it.id == plan.choice.location }?.freeBytes
        if (free != null && free - plan.total.bytes < DownloadSpaceGuard.LOW_SPACE_BYTES) {
            add(stringResource(Lang.storage_guide_existing_space, free.bytes.toString()))
        }
        if (!plan.total.isEmpty) add(stringResource(Lang.storage_guide_existing_note))
        if (plan.turnsOffDeleteWhenDone) add(stringResource(Lang.settings_storage_media_library_confirm_delete_when_done))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in lines) Text(line, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConfirmStep(
    state: StorageGuideState,
    setup: MediaStorageSetup,
    drives: List<MediaCacheLocation>,
    focus: TvFocusScope,
    dismiss: () -> Unit,
) {
    val plan by produceState<MediaStorageSetup.Plan?>(null, state.choice, state.keepLibraryInFolder) {
        value = setup.plan(state.choice, state.keepLibraryInFolder)
    }
    val toaster = LocalToaster.current
    val busy = stringResource(Lang.storage_guide_busy)
    val driveName = drives.firstOrNull { it.id == state.location }?.let { driveName(it) }
        ?: stringResource(Lang.settings_storage_cache_location_internal)
    val modeName = stringResource(if (state.library) Lang.storage_guide_mode_library else Lang.storage_guide_mode_app)
    GuideColumns(
        description = stringResource(Lang.storage_guide_done_description),
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Lang.storage_guide_done_where, driveName, modeName), style = MaterialTheme.typography.titleMedium)
                val current = plan
                val total = current?.total
                Text(
                    when {
                        total == null -> ""
                        !state.moveExisting -> stringResource(Lang.storage_guide_done_keep_existing)
                        total.isEmpty -> stringResource(Lang.storage_guide_done_nothing_to_move)
                        else -> stringResource(Lang.storage_guide_done_move, total.units, total.bytes.bytes.toString())
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.library && !state.wasLibrary) {
                    Text(stringResource(Lang.settings_storage_media_library_confirm_text), style = MaterialTheme.typography.bodyLarge)
                }
                if (current?.turnsOffDeleteWhenDone == true) {
                    Text(stringResource(Lang.settings_storage_media_library_confirm_delete_when_done), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        options = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AniFocusActionButton(
                    onClick = {
                        if (setup.isMoving) {
                            toaster.toast(busy)
                        } else {
                            val choice = state.choice
                            val move = state.moveExisting
                            val keep = state.keepLibraryInFolder
                            val moving = move && plan?.total?.isEmpty == false
                            // 写设置、开始搬不随引导关掉而取消; 要搬的时候弹一次动作面板看进度
                            tvSettingsWriteScope.launch {
                                if (setup.apply(choice, move, keep) && moving) showMoveInActionPanel()
                            }
                        }
                        dismiss()
                    },
                    modifier = Modifier.tvFocusAnchor(focus, GuideFocus("confirm:done")),
                ) {
                    Text(stringResource(Lang.storage_guide_done_finish))
                }
            }
        },
    )
    LaunchedEffect(Unit) { focus.request(GuideFocus("confirm:done")) }
}

/** 一步的版面: 左边说明 (与 [details]), 右边选项 */
@Composable
private fun GuideColumns(
    description: String,
    details: (@Composable () -> Unit)? = null,
    options: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
        Column(Modifier.weight(1f)) {
            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (details != null) {
                Spacer(Modifier.height(SECTION_GAP))
                details()
            }
        }
        Column(
            Modifier
                .width(OPTIONS_WIDTH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OPTION_GAP),
        ) {
            options()
        }
    }
}

/**
 * 一个选项: 名字 (推荐的带标签、现在的设置打勾) 与说明. 聚焦是实底; 不能选的变淡、按了不响应.
 */
@Composable
private fun GuideOption(
    title: String,
    description: String?,
    recommended: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 聚焦样式读真实焦点 (一次性的 Focus 事件会丢)
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .onFocusChanged { focused = it.isFocused }
            .tvTouchFocusOnTap(),
        shape = RoundedCornerShape(12.dp),
        color = if (focused) aniFocusContainerColor() else scheme.surfaceContainerHigh,
        contentColor = if (focused) aniFocusContentColor() else scheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    val badgeColor = if (focused) LocalContentColor.current else scheme.primary
                    Text(
                        stringResource(Lang.tv_onboarding_recommended),
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                    )
                }
                if (selected) {
                    Spacer(Modifier.weight(1f))
                    Text("✓", style = MaterialTheme.typography.titleMedium, color = if (focused) LocalContentColor.current else scheme.primary)
                }
            }
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.78f))
            }
        }
    }
}

@Composable
private fun driveName(drive: MediaCacheLocation): String =
    if (drive.id == null || drive.label.isBlank()) stringResource(Lang.settings_storage_cache_location_internal) else drive.label

@Composable
private fun spaceText(drive: MediaCacheLocation): String? {
    val free = drive.freeBytes ?: return null
    val total = drive.totalBytes ?: return null
    return stringResource(Lang.settings_storage_cache_location_space, free.bytes.toString(), total.bytes.toString())
}

@Composable
private fun unusableReason(drive: MediaCacheLocation): String? = when (drive.unusable) {
    null -> null
    MediaCacheLocationProblem.FAT32 -> stringResource(Lang.settings_storage_cache_location_fat32, driveName(drive))
    MediaCacheLocationProblem.READ_ONLY -> stringResource(Lang.settings_storage_cache_location_read_only, driveName(drive))
}

/** 推荐的盘: 能用的外接盘里剩余空间最大的; 没有外接盘时是内部存储 */
internal fun recommendedDrive(drives: List<MediaCacheLocation>): String? =
    drives.filter { it.id != null && it.unusable == null }.maxByOrNull { it.freeBytes ?: 0L }?.id

private val StorageGuideStep.title
    get() = when (this) {
        StorageGuideStep.Drive -> Lang.storage_guide_drive_title
        StorageGuideStep.Mode -> Lang.storage_guide_mode_title
        StorageGuideStep.Permission -> Lang.storage_guide_permission_title
        StorageGuideStep.Existing -> Lang.storage_guide_existing_title
        StorageGuideStep.Confirm -> Lang.storage_guide_done_title
    }

private val StorageGuideStep.label
    get() = when (this) {
        StorageGuideStep.Drive -> Lang.storage_guide_step_drive
        StorageGuideStep.Mode -> Lang.storage_guide_step_mode
        StorageGuideStep.Permission -> Lang.storage_guide_step_permission
        StorageGuideStep.Existing, StorageGuideStep.Confirm -> Lang.storage_guide_step_existing
    }

private data class GuideFocus(val name: String) : TvFocusKey

private val PAGE_HORIZONTAL_PADDING = 48.dp
private val PAGE_VERTICAL_PADDING = 32.dp
private val SECTION_GAP = 28.dp
private val COLUMN_GAP = 40.dp
private val OPTIONS_WIDTH = 460.dp
private val OPTION_GAP = 8.dp
private const val DISABLED_ALPHA = 0.38f

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.profile.LocalProfileImporter
import me.him188.ani.app.domain.profile.ProfileSwitchTransition
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.platform.ProfileSwitchFrame
import me.him188.ani.app.platform.ProfileSwitchFrameDrawable
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.foundation.tv.tvFieldBorderStroke
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tv.tvTouchTap
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniFocusSelectableSurface
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_profile_add
import me.him188.ani.app.ui.lang.tv_profile_add_description
import me.him188.ani.app.ui.lang.tv_profile_cancel
import me.him188.ani.app.ui.lang.tv_profile_create
import me.him188.ani.app.ui.lang.tv_profile_current
import me.him188.ani.app.ui.lang.tv_profile_default_name
import me.him188.ani.app.ui.lang.tv_profile_delete
import me.him188.ani.app.ui.lang.tv_profile_delete_current
import me.him188.ani.app.ui.lang.tv_profile_delete_description
import me.him188.ani.app.ui.lang.tv_profile_delete_primary
import me.him188.ani.app.ui.lang.subject_collection_doing
import me.him188.ani.app.ui.lang.subject_collection_done
import me.him188.ani.app.ui.lang.subject_collection_dropped
import me.him188.ani.app.ui.lang.subject_collection_on_hold
import me.him188.ani.app.ui.lang.subject_collection_wish
import me.him188.ani.app.ui.lang.tv_profile_delete_title
import me.him188.ani.app.ui.lang.tv_profile_import
import me.him188.ani.app.ui.lang.tv_profile_import_busy
import me.him188.ani.app.ui.lang.tv_profile_import_confirm
import me.him188.ani.app.ui.lang.tv_profile_import_done
import me.him188.ani.app.ui.lang.tv_profile_import_error
import me.him188.ani.app.ui.lang.tv_profile_import_failed
import me.him188.ani.app.ui.lang.tv_profile_import_loading
import me.him188.ani.app.ui.lang.tv_profile_import_more
import me.him188.ani.app.ui.lang.tv_profile_import_nothing
import me.him188.ani.app.ui.lang.tv_profile_import_ok
import me.him188.ani.app.ui.lang.tv_profile_import_running
import me.him188.ani.app.ui.lang.tv_profile_import_running_hint
import me.him188.ani.app.ui.lang.tv_profile_import_skipped
import me.him188.ani.app.ui.lang.tv_profile_import_summary
import me.him188.ani.app.ui.lang.tv_profile_import_title
import me.him188.ani.app.ui.lang.tv_profile_import_warning
import me.him188.ani.app.ui.lang.tv_profile_kind_bangumi_description
import me.him188.ani.app.ui.lang.tv_profile_kind_bangumi_title
import me.him188.ani.app.ui.lang.tv_profile_kind_local_description
import me.him188.ani.app.ui.lang.tv_profile_kind_local_title
import me.him188.ani.app.ui.lang.tv_profile_local
import me.him188.ani.app.ui.lang.tv_profile_name_placeholder
import me.him188.ani.app.ui.lang.tv_profile_picker_hint
import me.him188.ani.app.ui.lang.tv_profile_picker_title
import me.him188.ani.app.ui.lang.tv_profile_rename
import me.him188.ani.app.ui.lang.tv_profile_save
import me.him188.ani.app.ui.lang.tv_profile_switching
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * 选人页 (像 Apple TV 那样): 这台设备上有两个以上用户时, 每次打开应用先选人 (TV 根部打开);
 * 侧边栏头像的「切换用户」也打开它.
 *
 * 选的还是当前用户就淡出、露出下面的主页; 选了别人就记下来并重启应用 (见 [UserProfileManager]). 重启前放换人的过场
 * ([beginSwitch]): 那个人从原位移到正中放大, 其余的淡出, 定格的这一帧一直接力显示到新用户的首页出来 (见 [TvProfileSwitchLandingHost]).
 * 长按一个人可以改名或删除, 最后一格是添加用户.
 */
object TvUserProfilePicker {
    val visible = MutableStateFlow(false)

    /** 正在换过去的人 (选人页定格在「正在切换」那一帧, 见 [beginSwitch]); 非 null 期间选人页关不掉. */
    val switchTarget = MutableStateFlow<UserProfile?>(null)

    /** 这次过场的那一帧截好 (或截不了) 时完成. 只在主线程读写. */
    private var frameCaptured: CompletableDeferred<Unit>? = null

    /** 开始换人的时刻 ([SystemClock.elapsedRealtime]): 过场的进度条从这时起按时间走, 重启后的几个进程用同一个时钟接着算. */
    internal var switchStartElapsed: Long = 0L
        private set

    fun show() {
        visible.value = true
    }

    /**
     * 开始换人的过场: 打开选人页 (开着就接着用), 把 [target] 移到正中定格, 截下这一帧写进 [ProfileSwitchFrame]
     * 留给重启途中与新进程接着显示. 已经在为同一个人放时不重来. 返回的在截好 (或截不了) 时完成. 主线程调用.
     */
    fun beginSwitch(target: UserProfile): Deferred<Unit> {
        frameCaptured?.let { pending -> if (switchTarget.value?.id == target.id) return pending }
        val captured = CompletableDeferred<Unit>()
        frameCaptured = captured
        switchStartElapsed = SystemClock.elapsedRealtime()
        switchTarget.value = target
        visible.value = true
        return captured
    }

    /** 换人 / 改成本地用户重启前的过场 ([UserProfileManager.transition]): 放到截好那一帧为止. */
    suspend fun playSwitch(target: UserProfile) {
        withContext(Dispatchers.Main) { beginSwitch(target) }.await()
    }

    internal fun onSwitchFrameCaptured() {
        frameCaptured?.complete(Unit)
    }
}

/** 选人页 (独立窗口, 盖在主页上). 装在 TV 根部. */
@Composable
fun TvUserProfilePickerHost() {
    val manager = remember { KoinPlatform.getKoin().get<UserProfileManager>() }
    // 换人 / 改成本地用户重启前的过场由选人页来放, 界面在场时装上
    DisposableEffect(manager) {
        manager.transition = ProfileSwitchTransition { TvUserProfilePicker.playSwitch(it) }
        onDispose { manager.transition = null }
    }
    val visible by TvUserProfilePicker.visible.collectAsStateWithLifecycle()
    if (!visible || !manager.isSupported) return
    val switchTarget by TvUserProfilePicker.switchTarget.collectAsStateWithLifecycle()
    // 切换中 (正在重启) 不许关: 关掉会露出旧用户的主页, 紧接着进程又没了
    val close = { if (TvUserProfilePicker.switchTarget.value == null) TvUserProfilePicker.visible.value = false }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // 不压暗背后: 选的还是自己时整页淡出, 露出来的主页就是正常亮度
        DialogWindowDimAmount(0f)
        PickerContent(manager, switchTarget, onClose = close)
    }
}

@Composable
private fun PickerContent(
    manager: UserProfileManager,
    switchTarget: UserProfile?,
    onClose: () -> Unit,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = rememberTvFocusScope()
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    // 当前用户登录了 Bangumi 时, 本地用户的长按菜单里能「导入到当前用户的 Bangumi 账号」 (见 LocalProfileImporter)
    val sessionState by remember { KoinPlatform.getKoin().get<SessionStateProvider>().stateFlow }.collectAsStateWithLifecycle(null)
    val canImport = !UserProfiles.current.isLocal && (sessionState as? SessionState.Valid)?.bangumiConnected == true
    // 导入在跑时不能换人: 换人会重启应用, 导入跟着断掉
    val importing = ProfileImportSession.state.collectAsStateWithLifecycle().value is ProfileImportSession.State.Running

    // 进场: 整页淡入, 标题与头像依次微微上浮 (头像的错开在 ProfileTile 里)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(ENTER_MILLIS, easing = FastOutSlowInEasing)) }
    // 选的还是自己: 那个头像再抬一点, 整页淡出, 露出下面已经加载好的主页, 像是「走进」应用
    var entering by remember { mutableStateOf(false) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(entering) {
        if (entering) {
            exit.animateTo(1f, tween(EXIT_MILLIS, easing = FastOutLinearInEasing))
            onClose()
        }
    }

    // 添加用户「创建并切换」之后到过场开始之间 (先要把人建出来) 也不接受别的操作
    var creating by remember { mutableStateOf(false) }
    val switching = switchTarget != null || creating
    // 各人头像在本页里的位置与当前聚焦的是谁: 换人的过场从那里起飞 (只在过场开始时读一次, 不必是快照状态)
    val tiles = remember { TilePlacements() }
    val switchStage = rememberSwitchStage(switchTarget, tiles, entrance)
    val context = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(switchTarget) {
        if (switchTarget == null) return@LaunchedEffect
        withContext(Dispatchers.IO) { ProfileSwitchFrame.delete(context) }
        switchStage.play(entrance)
        // 定格: 等最后一帧上屏再截 (PixelCopy 读的是窗口已经画出来的内容)
        repeat(2) { withFrameNanos { } }
        val frame = view.findDialogWindow()?.let { captureWindowFrame(it) }?.let {
            ProfileSwitchFrame.Frame(it, switchStage.track, TvUserProfilePicker.switchStartElapsed)
        }
        // 截好了就换成「这一帧 + 进度条」接着画: 画面不变, 进度条开始走, 与重启途中各进程画的是同一个东西
        frame?.let { switchStage.frameDrawable = ProfileSwitchFrameDrawable(it, ProfileSwitchFrameDrawable.FLOOR_CAPTURED) }
        withContext(Dispatchers.IO) {
            if (frame == null || !ProfileSwitchFrame.write(context, frame)) ProfileSwitchFrame.delete(context)
        }
        TvUserProfilePicker.onSwitchFrameCaptured()
    }

    val select: (UserProfile) -> Unit = select@{ profile ->
        if (switching || entering) return@select
        if (profile.id == manager.currentId) {
            entering = true
        } else if (!importing) {
            TvUserProfilePicker.beginSwitch(profile)
            scope.launch { manager.switchTo(profile.id) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .tvOverlayWindowKeys(onClose)
            // 过场期间什么键都不接
            .onPreviewKeyEvent { switching }
            .graphicsLayer { alpha = entrance.value * (1f - exit.value) }
            // Apple TV 的深色页面底, 中间略亮、四周压暗, 头像像是浮在一块舞台上
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(PICKER_BACKGROUND_CENTER, PICKER_BACKGROUND_EDGE),
                        center = Offset(size.width / 2, size.height * 0.45f),
                        radius = size.maxDimension * 0.65f,
                    ),
                )
            }
            .tvFocusNavSignal(focus),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.graphicsLayer { alpha = 1f - switchStage.othersFade.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Lang.tv_profile_picker_title),
                Modifier.graphicsLayer { translationY = (1f - entrance.value) * ENTER_RISE.toPx() },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(56.dp))
            Row(
                // 上下留出放大与投影的地方, 不然会被 Row 的边裁掉
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                val currentLabel = stringResource(Lang.tv_profile_current)
                val localLabel = stringResource(Lang.tv_profile_local)
                state.profiles.forEachIndexed { index, profile ->
                    val name = profile.displayName()
                    ProfileTile(
                        label = name,
                        sublabel = listOfNotNull(
                            localLabel.takeIf { profile.isLocal },
                            currentLabel.takeIf { profile.id == manager.currentId },
                        ).joinToString(" · "),
                        focus = focus,
                        key = ProfileFocusKey(profile.id),
                        enterDelayMillis = index * ENTER_STAGGER_MILLIS,
                        chosen = entering && profile.id == manager.currentId,
                        // 过场里他由台上那个头像接着画
                        hidden = switchTarget?.id == profile.id,
                        onPlaced = { center, focused -> tiles.update(profile.id, center, focused) },
                        onClick = { select(profile) },
                        menu = { expanded, onDismiss ->
                            ProfileMenu(
                                profile = profile,
                                isCurrent = profile.id == manager.currentId,
                                expanded = expanded,
                                onDismiss = onDismiss,
                                onRename = { dialog = ProfileDialog.Rename(profile) },
                                onDelete = { dialog = ProfileDialog.Delete(profile, name) },
                                onImport = if (canImport && profile.isLocal) {
                                    { dialog = ProfileDialog.Import(profile, name) }
                                } else {
                                    null
                                },
                            )
                        },
                    ) {
                        ProfileAvatar(profile, name)
                    }
                }
                ProfileTile(
                    label = stringResource(Lang.tv_profile_add),
                    sublabel = "",
                    focus = focus,
                    key = AddFocusKey,
                    enterDelayMillis = state.profiles.size * ENTER_STAGGER_MILLIS,
                    chosen = false,
                    hidden = false,
                    onPlaced = { _, _ -> },
                    onClick = { if (!switching && !entering) dialog = ProfileDialog.Add(state.nextId) },
                    menu = null,
                ) {
                    Box(Modifier.fillMaxSize().background(ADD_TILE_COLOR), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(56.dp), tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(if (importing) Lang.tv_profile_import_busy else Lang.tv_profile_picker_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = SECONDARY_LABEL,
            )
        }
        switchTarget?.let { SwitchStageContent(it, switchStage) }
        switchStage.frameDrawable?.let { ProfileSwitchFrameImage(it) }
    }
    focus.InitialFocus(ProfileFocusKey(manager.currentId))

    when (val d = dialog) {
        null -> {}
        is ProfileDialog.Add -> {
            val defaultName = stringResource(Lang.tv_profile_default_name, d.id)
            var kind by remember { mutableStateOf(UserProfileKind.BANGUMI) }
            NameDialog(
                title = stringResource(Lang.tv_profile_add),
                description = stringResource(Lang.tv_profile_add_description),
                initialName = "",
                placeholder = defaultName,
                confirmText = stringResource(Lang.tv_profile_create),
                onConfirm = { name ->
                    dialog = null
                    creating = true
                    // 没填就把默认名存下来: 名字还要显示在侧边栏等处, 那里没有编号可拼
                    scope.launch { manager.addAndSwitch(name.ifBlank { defaultName }, kind) }
                },
                onDismissRequest = { dialog = null },
                fieldDownTarget = DialogFocus.KindBangumi,
                options = { focus -> ProfileKindOptions(kind, onKindChange = { kind = it }, focus) },
            )
        }

        is ProfileDialog.Rename -> {
            val defaultName = stringResource(Lang.tv_profile_default_name, d.profile.id)
            NameDialog(
                title = stringResource(Lang.tv_profile_rename),
                description = null,
                initialName = d.profile.name,
                placeholder = defaultName,
                confirmText = stringResource(Lang.tv_profile_save),
                onConfirm = { name ->
                    dialog = null
                    // 清空了同样存默认名 (同添加)
                    scope.launch { manager.rename(d.profile.id, name.ifBlank { defaultName }) }
                },
                onDismissRequest = { dialog = null },
            )
        }

        is ProfileDialog.Delete -> DeleteDialog(
            name = d.name,
            onConfirm = {
                dialog = null
                scope.launch { manager.delete(d.profile.id) }
            },
            onDismissRequest = { dialog = null },
        )

        is ProfileDialog.Import -> ImportDialog(d.profile, d.name, onDismissRequest = { dialog = null })
    }
}

/** 列表里的名字: 没起过名字的显示「用户 N」. */
@Composable
private fun UserProfile.displayName(): String =
    name.ifBlank { stringResource(Lang.tv_profile_default_name, id) }

private sealed interface ProfileDialog {
    /** @property id 新用户将拿到的编号 (默认名「用户 N」用) */
    data class Add(val id: Int) : ProfileDialog
    data class Rename(val profile: UserProfile) : ProfileDialog
    data class Delete(val profile: UserProfile, val name: String) : ProfileDialog
    data class Import(val profile: UserProfile, val name: String) : ProfileDialog
}

private data class ProfileFocusKey(val id: Int) : TvFocusKey

private data object AddFocusKey : TvFocusKey

/**
 * 一个人 (或「添加用户」): 圆形头像 + 名字, 照 Apple TV 的聚焦样式 —— 不画描边, 头像原地放大「抬起来」,
 * 身后的投影从贴身一圈淡影换成往下拖的一大片软影; 名字聚焦时变白, 其余灰.
 * 有 [menu] 时长按确认键弹出它. 名字下面一行恒占位 ([sublabel]: 本地档标「本地」、当前用户标「当前」), 出不出现不会让整排上下跳.
 *
 * @param chosen 选中了它、整页正在淡出进入应用: 再抬一点
 * @param hidden 头像不画 (换人的过场里由台上那个头像接着画)
 * @param onPlaced 头像在页面里的中心 (位置变了或聚焦变了时报), 换人的过场从这里起飞
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileTile(
    label: String,
    sublabel: String,
    focus: TvFocusScope,
    key: TvFocusKey,
    enterDelayMillis: Int,
    chosen: Boolean,
    hidden: Boolean,
    onPlaced: (center: Offset, focused: Boolean) -> Unit,
    onClick: () -> Unit,
    menu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)?,
    avatar: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val placed by rememberUpdatedState(onPlaced)
    val spot = remember { TileSpot() }
    var menuExpanded by remember { mutableStateOf(false) }
    // 放大与两层投影的交叉淡入都按这一个进度走, 在绘制阶段读 (换焦点不重组整排)
    val focusWeight = animateFloatAsState(
        if (focused) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
    )
    val chosenLift = animateFloatAsState(if (chosen) 1f else 0f, tween(EXIT_MILLIS, easing = FastOutSlowInEasing))
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(enterDelayMillis.toLong())
        enter.animateTo(1f, tween(ENTER_MILLIS, easing = FastOutSlowInEasing))
    }
    val labelColor by animateColorAsState(if (focused) Color.White else SECONDARY_LABEL)
    Column(
        Modifier.width(TILE_WIDTH).graphicsLayer {
            // 逐笔乘透明度, 不先画进离屏缓冲: 离屏缓冲只有这一格那么大, 淡入期间放大的头像与投影
            // 超出格子的那截会被裁掉 (进页时聚焦那个头像顶上被横着切掉一条)
            compositingStrategy = CompositingStrategy.ModulateAlpha
            alpha = enter.value
            translationY = (1f - enter.value) * ENTER_RISE.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                // 缩放绕中心, 中心与缩放无关: 在缩放之前量
                .onGloballyPositioned {
                    spot.center = it.boundsInRoot().center
                    placed(spot.center, focused)
                }
                .graphicsLayer {
                    val scale = 1f + (FOCUS_SCALE - 1f) * focusWeight.value + CHOSEN_EXTRA_SCALE * chosenLift.value
                    scaleX = scale
                    scaleY = scale
                    if (hidden) alpha = 0f
                },
        ) {
            // 两层投影参数恒定 (模糊只算一次), 按聚焦进度交叉淡入
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = 1f - focusWeight.value
                    }
                    .dropShadow(CircleShape, IDLE_SHADOW),
            )
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = focusWeight.value
                    }
                    .dropShadow(CircleShape, FOCUSED_SHADOW),
            )
            Box(
                Modifier
                    .size(AVATAR_SIZE)
                    .tvFocusAnchor(focus, key)
                    .onFocusChanged {
                        focused = it.isFocused
                        placed(spot.center, it.isFocused)
                    }
                    .then(
                        if (menu == null) {
                            Modifier
                        } else {
                            Modifier.tvLongPressKey(onLongPress = { menuExpanded = true }, onShortPress = onClick)
                        },
                    )
                    .tvTouchFocusOnTap()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                        onLongClick = menu?.let { { menuExpanded = true } },
                    )
                    .clip(CircleShape)
                    // 玻璃边: 一圈 1 像素的细线, 头像与深色底色接近时也看得出圆
                    .border(Dp.Hairline, HAIRLINE, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                avatar()
            }
            menu?.invoke(menuExpanded) { menuExpanded = false }
        }
        // 往下让出放大多出来的那截, 名字不被头像盖住
        Spacer(Modifier.height(20.dp + AVATAR_SIZE * ((FOCUS_SCALE - 1f) / 2)))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sublabel,
            style = MaterialTheme.typography.labelMedium,
            color = SECONDARY_LABEL,
        )
    }
}

/** Bangumi 头像; 没登录的人用名字的第一个字 (底色按编号取, 同一个人每次一样). */
@Composable
private fun ProfileAvatar(profile: UserProfile, name: String) {
    val url = profile.avatarUrl
    if (url != null) {
        AvatarImage(url = url, modifier = Modifier.fillMaxSize())
    } else {
        Box(
            Modifier.fillMaxSize().background(AVATAR_COLORS[(profile.id - 1).mod(AVATAR_COLORS.size)]),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), color = Color.White, fontSize = 56.sp)
        }
    }
}

/** 一格头像量到的中心 (普通字段: 只在换人的过场开始时读一次). */
private class TileSpot {
    var center: Offset = Offset.Unspecified
}

/** 选人页上各人头像的中心与现在聚焦的是谁: 换人的过场从那里起飞. */
private class TilePlacements {
    private val centers = HashMap<Int, Offset>()
    var focusedId: Int? = null
        private set

    fun update(id: Int, center: Offset, focused: Boolean) {
        if (center.isSpecified) centers[id] = center
        if (focused) {
            focusedId = id
        } else if (focusedId == id) {
            focusedId = null
        }
    }

    fun centerOf(id: Int): Offset? = centers[id]
}

/** 换人的过场怎么开场. */
private enum class SwitchEntry {
    /** 在选人页上选的: 他的头像从原位移到正中 */
    Fly,

    /** 选人页上还没有他 (刚添加的): 其余淡出, 他在正中淡入 */
    FadeIn,

    /** 选人页是为过场才打开的 (控制台换人、改成本地用户): 直接定格在正中, 随整页淡入 */
    Direct,
}

/**
 * 换人的过场: 要换过去的人移到正中、放大到 [SWITCH_SCALE], 其余 (标题、别的头像、提示) 淡出, 名字与「正在切换」在他下面淡入.
 * 放完定格, 这一帧截下来接力显示到重启后 (见 [TvUserProfilePicker.beginSwitch]).
 *
 * @property from 起飞的位置 (只有 [SwitchEntry.Fly] 有), [fromScale] 是那时的放大倍数 (聚焦着就是聚焦的放大)
 */
@Stable
private class SwitchStage(val entry: SwitchEntry?, val from: Offset?, val fromScale: Float) {
    /** 0 = 在原位, 1 = 在正中 */
    val move = Animatable(if (entry == SwitchEntry.Fly) 0f else 1f)

    /** 其余内容淡出的进度 */
    val othersFade = Animatable(if (entry == SwitchEntry.Direct) 1f else 0f)

    /** 台上 (正中的头像与名字) 的透明度 */
    val stageAlpha = Animatable(if (entry == SwitchEntry.FadeIn) 0f else 1f)

    /** 进度条的槽在窗口里的位置 (像素), 随截下的那一帧交给重启后的进程画填充. 只在截图时读, 不必是快照状态 */
    var track: Rect? = null

    /** 截好之后接着画的「这一帧 + 进度条」 */
    var frameDrawable by mutableStateOf<ProfileSwitchFrameDrawable?>(null)

    /** 放完返回 (为过场才打开的选人页要等整页淡入完). */
    suspend fun play(entrance: Animatable<Float, *>) {
        when (entry) {
            null -> {}
            SwitchEntry.Direct -> snapshotFlow { entrance.value }.first { it >= 1f }
            SwitchEntry.Fly -> coroutineScope {
                launch { othersFade.animateTo(1f, tween(OTHERS_FADE_MILLIS)) }
                move.animateTo(1f, tween(SWITCH_MOVE_MILLIS, easing = FastOutSlowInEasing))
            }

            SwitchEntry.FadeIn -> coroutineScope {
                launch { othersFade.animateTo(1f, tween(OTHERS_FADE_MILLIS)) }
                stageAlpha.animateTo(1f, tween(STAGE_FADE_MILLIS))
            }
        }
    }
}

@Composable
private fun rememberSwitchStage(target: UserProfile?, tiles: TilePlacements, entrance: Animatable<Float, *>): SwitchStage =
    remember(target?.id) {
        // 只在开场时看一眼进场动画走完没有, 不订阅 (不然进场期间每帧重组整页)
        val entered = Snapshot.withoutReadObservation { entrance.value >= 1f }
        val from = target?.let { tiles.centerOf(it.id) }
        val entry = when {
            target == null -> null
            !entered -> SwitchEntry.Direct
            from != null -> SwitchEntry.Fly
            else -> SwitchEntry.FadeIn
        }
        SwitchStage(
            entry,
            from.takeIf { entry == SwitchEntry.Fly },
            fromScale = if (target != null && tiles.focusedId == target.id) FOCUS_SCALE else 1f,
        )
    }

/** 台上: 正中放大的头像 (带聚焦时那层投影), 下面是名字与「正在切换」. 位置与缩放在布局 / 绘制阶段读动画, 不逐帧重组. */
@Composable
private fun SwitchStageContent(target: UserProfile, stage: SwitchStage) {
    val name = target.displayName()
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = stage.stageAlpha.value }) {
        val density = LocalDensity.current
        val avatarPx = with(density) { AVATAR_SIZE.toPx() }
        val end = with(density) { Offset(maxWidth.toPx() / 2, maxHeight.toPx() * SWITCH_CENTER_Y) }
        val labelTop = with(density) { end.y + avatarPx * SWITCH_SCALE / 2 + SWITCH_LABEL_GAP.toPx() }
        Box(
            Modifier
                .offset {
                    val center = lerp(stage.from ?: end, end, stage.move.value)
                    IntOffset((center.x - avatarPx / 2).roundToInt(), (center.y - avatarPx / 2).roundToInt())
                }
                .graphicsLayer {
                    val scale = lerp(stage.fromScale, SWITCH_SCALE, stage.move.value)
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Box(Modifier.matchParentSize().dropShadow(CircleShape, FOCUSED_SHADOW))
            Box(
                Modifier.size(AVATAR_SIZE).clip(CircleShape).border(Dp.Hairline, HAIRLINE, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ProfileAvatar(target, name)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, labelTop.roundToInt()) }
                // 飞到一半才开始出现
                .graphicsLayer { alpha = ((stage.move.value - 0.5f) / 0.5f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Lang.tv_profile_switching), style = MaterialTheme.typography.bodyLarge, color = SECONDARY_LABEL)
            // 进度条的空槽: 跟着截进那一帧, 填充由 ProfileSwitchFrameDrawable 现画
            Spacer(Modifier.height(SWITCH_PROGRESS_GAP))
            Box(
                Modifier
                    .size(SWITCH_PROGRESS_WIDTH, SWITCH_PROGRESS_HEIGHT)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(SWITCH_PROGRESS_TRACK)
                    .onGloballyPositioned { stage.track = it.boundsInWindow().toAndroidRect() },
            )
        }
    }
}

/**
 * 长按一个人的菜单: 改名; 能删时有删除, 不能删时写明为什么; 本地用户在当前用户登录了 Bangumi 时还能导入 ([onImport] 非 null).
 */
@Composable
private fun ProfileMenu(
    profile: UserProfile,
    isCurrent: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onImport: (() -> Unit)?,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // 菜单只有长按一个入口: 吞掉那次长按剩下的确认键, 免得落下来就点中第一项
        modifier = Modifier.consumeHeldConfirmKey().tvOverlayWindowKeys(onDismiss),
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(Lang.tv_profile_rename)) },
            onClick = {
                onDismiss()
                onRename()
            },
        )
        when {
            profile.isPrimary -> DropdownMenuItem(
                text = { Text(stringResource(Lang.tv_profile_delete_primary)) },
                onClick = {},
                enabled = false,
            )

            isCurrent -> DropdownMenuItem(
                text = { Text(stringResource(Lang.tv_profile_delete_current)) },
                onClick = {},
                enabled = false,
            )

            else -> DropdownMenuItem(
                text = { Text(stringResource(Lang.tv_profile_delete)) },
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
        if (onImport != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Lang.tv_profile_import)) },
                onClick = {
                    onDismiss()
                    onImport()
                },
            )
        }
    }
}

/**
 * 起名的小弹窗 (添加用户 / 改名).
 *
 * @param fieldDownTarget 在名字框里按下键 (或输入法的完成) 去哪
 * @param options 名字框与按钮之间的选项 (添加时选这个人是哪一种, 见 [ProfileKindOptions])
 */
@Composable
private fun NameDialog(
    title: String,
    description: String?,
    initialName: String,
    placeholder: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
    fieldDownTarget: TvFocusKey = DialogFocus.Confirm,
    options: (@Composable (focus: TvFocusScope) -> Unit)? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialName)) }
    ProfileDialogSurface(title, onDismissRequest) { focus ->
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
        NameField(
            value = value,
            onValueChange = { value = it },
            placeholder = placeholder,
            focus = focus,
            downTarget = fieldDownTarget,
        )
        if (options != null) {
            Spacer(Modifier.height(16.dp))
            options(focus)
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvHeroButton(
                confirmText,
                Icons.Rounded.Check,
                filled = true,
                onClick = { onConfirm(value.text) },
                onFocused = {},
                modifier = Modifier.tvFocusAnchor(focus, DialogFocus.Confirm),
            )
            TvHeroButton(
                stringResource(Lang.tv_profile_cancel),
                Icons.Rounded.Close,
                filled = false,
                onClick = onDismissRequest,
                onFocused = {},
                modifier = Modifier.tvFocusAnchor(focus, DialogFocus.Cancel),
            )
        }
        focus.InitialFocus(DialogFocus.Field)
    }
}

@Composable
private fun DeleteDialog(name: String, onConfirm: () -> Unit, onDismissRequest: () -> Unit) {
    ProfileDialogSurface(stringResource(Lang.tv_profile_delete_title, name), onDismissRequest) { focus ->
        Text(
            stringResource(Lang.tv_profile_delete_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvHeroButton(
                stringResource(Lang.tv_profile_delete),
                Icons.Rounded.Delete,
                filled = true,
                onClick = onConfirm,
                onFocused = {},
                modifier = Modifier.tvFocusAnchor(focus, DialogFocus.Confirm),
            )
            TvHeroButton(
                stringResource(Lang.tv_profile_cancel),
                Icons.Rounded.Close,
                filled = false,
                onClick = onDismissRequest,
                onFocused = {},
                modifier = Modifier.tvFocusAnchor(focus, DialogFocus.Cancel),
            )
        }
        // 默认落在「取消」: 删除不可恢复
        focus.InitialFocus(DialogFocus.Cancel)
    }
}

private enum class DialogFocus : TvFocusKey { Field, KindBangumi, KindLocal, Confirm, Cancel }

private enum class ImportStage { Loading, Preview, Empty, Running, Result, Error }

/**
 * 把本地用户的收藏导入当前用户的 Bangumi 账号 (见 [LocalProfileImporter]): 先读出预览 (要加哪些、哪些已经有了) 给人确认,
 * 确认了在后台导 ([ProfileImportSession], 关掉弹窗也接着导), 导完显示结果. 写进 Bangumi 的撤不回来, 确认前把后果写明,
 * 焦点默认落在「取消」.
 */
@Composable
private fun ImportDialog(profile: UserProfile, name: String, onDismissRequest: () -> Unit) {
    val importer = remember { KoinPlatform.getKoin().get<LocalProfileImporter>() }
    val session by ProfileImportSession.state.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<LocalProfileImporter.Preview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 打开时有导入在跑 (或上一次的结果还没看) 就显示它; 否则读预览
    LaunchedEffect(profile.id) {
        if (ProfileImportSession.state.value != ProfileImportSession.State.Idle) return@LaunchedEffect
        try {
            preview = importer.preview(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName.orEmpty()
        }
    }
    val close = {
        ProfileImportSession.acknowledge()
        onDismissRequest()
    }
    val p = preview
    val stage = when (session) {
        is ProfileImportSession.State.Running -> ImportStage.Running
        is ProfileImportSession.State.Finished, is ProfileImportSession.State.Failed -> ImportStage.Result
        ProfileImportSession.State.Idle -> when {
            error != null -> ImportStage.Error
            p == null -> ImportStage.Loading
            p.toAdd.isEmpty() -> ImportStage.Empty
            else -> ImportStage.Preview
        }
    }
    // 显示的是在跑或上一次的导入时, 标题写那一次导的是谁 (可能是控制台上发起、导的别人)
    val sourceName = when (val s = session) {
        is ProfileImportSession.State.Running -> s.sourceName
        is ProfileImportSession.State.Finished -> s.sourceName
        is ProfileImportSession.State.Failed -> s.sourceName
        ProfileImportSession.State.Idle -> name
    }
    ProfileDialogSurface(stringResource(Lang.tv_profile_import_title, sourceName), close) { focus ->
        val body = MaterialTheme.typography.bodyLarge
        val small = MaterialTheme.typography.bodyMedium
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (val s = session) {
            is ProfileImportSession.State.Running -> {
                Text(stringResource(Lang.tv_profile_import_running, s.done, s.total), style = body)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Lang.tv_profile_import_running_hint), style = small, color = muted)
            }

            is ProfileImportSession.State.Finished -> {
                val r = s.result
                Text(stringResource(Lang.tv_profile_import_done, r.added, r.episodesMarked), style = body)
                if (r.skipped > 0) {
                    Text(stringResource(Lang.tv_profile_import_skipped, r.skipped), style = small, color = muted)
                }
                if (r.failures.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            Lang.tv_profile_import_failed,
                            r.failures.size,
                            r.failures.take(IMPORT_LIST_LIMIT).joinToString(" · ") { it.entry.name },
                        ),
                        style = small,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is ProfileImportSession.State.Failed -> Text(stringResource(Lang.tv_profile_import_error, s.message), style = body)

            ProfileImportSession.State.Idle -> when (stage) {
                ImportStage.Error -> Text(stringResource(Lang.tv_profile_import_error, error.orEmpty()), style = body)
                ImportStage.Loading -> Text(stringResource(Lang.tv_profile_import_loading), style = body, color = muted)
                ImportStage.Empty -> Text(stringResource(Lang.tv_profile_import_nothing), style = body)
                else -> if (p != null) {
                    Text(stringResource(Lang.tv_profile_import_summary, p.toAdd.size), style = body)
                    if (p.alreadyCollected.isNotEmpty()) {
                        Text(stringResource(Lang.tv_profile_import_skipped, p.alreadyCollected.size), style = body)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Lang.tv_profile_import_warning), style = small, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    for (entry in p.toAdd.take(IMPORT_LIST_LIMIT)) {
                        Text(
                            "· " + entry.name + " · " + entry.type.label(),
                            style = small,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (p.toAdd.size > IMPORT_LIST_LIMIT) {
                        Text(stringResource(Lang.tv_profile_import_more, p.toAdd.size - IMPORT_LIST_LIMIT), style = small, color = muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (stage == ImportStage.Preview && p != null) {
                TvHeroButton(
                    stringResource(Lang.tv_profile_import_confirm, p.toAdd.size),
                    Icons.Rounded.Check,
                    filled = true,
                    onClick = { ProfileImportSession.start(importer, p, name) },
                    onFocused = {},
                    modifier = Modifier.tvFocusAnchor(focus, DialogFocus.Confirm),
                )
            }
            // 还没导时是「取消」, 其余 (没有要导的、在导、导完、出错) 是「知道了」
            val cancels = stage == ImportStage.Preview || stage == ImportStage.Loading
            TvHeroButton(
                stringResource(if (cancels) Lang.tv_profile_cancel else Lang.tv_profile_import_ok),
                if (cancels) Icons.Rounded.Close else Icons.Rounded.Check,
                filled = false,
                onClick = close,
                onFocused = {},
                modifier = Modifier.tvFocusAnchor(focus, DialogFocus.Cancel),
            )
        }
        // 每换一种状态焦点都送回「取消 / 知道了」: 原来聚焦的「导入」随状态消失, 不接住焦点就丢了; 预览时默认也落在它上面
        LaunchedEffect(stage) { focus.request(DialogFocus.Cancel) }
    }
}

@Composable
private fun UnifiedCollectionType.label(): String = when (this) {
    UnifiedCollectionType.WISH -> stringResource(Lang.subject_collection_wish)
    UnifiedCollectionType.DOING -> stringResource(Lang.subject_collection_doing)
    UnifiedCollectionType.DONE -> stringResource(Lang.subject_collection_done)
    UnifiedCollectionType.ON_HOLD -> stringResource(Lang.subject_collection_on_hold)
    UnifiedCollectionType.DROPPED -> stringResource(Lang.subject_collection_dropped)
    UnifiedCollectionType.NOT_COLLECTED -> ""
}

/** 导入弹窗里最多列几部 (其余写「还有 N 部」): 电视上一屏放不下长列表, 焦点又在底下的按钮上. */
private const val IMPORT_LIST_LIMIT = 5

/**
 * 添加用户时选这个人是哪一种: 登录 Bangumi (默认; 进来先弹登录, 可以跳过) / 不登录 (本地档: 收藏、看过与评分只存在这台电视上).
 * 单选, 当前项只在右端打勾, 不铺选中色 (同菜单与筛选值网格).
 */
@Composable
private fun ProfileKindOptions(kind: UserProfileKind, onKindChange: (UserProfileKind) -> Unit, focus: TvFocusScope) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileKindOption(
            title = stringResource(Lang.tv_profile_kind_bangumi_title),
            description = stringResource(Lang.tv_profile_kind_bangumi_description),
            selected = kind == UserProfileKind.BANGUMI,
            onClick = { onKindChange(UserProfileKind.BANGUMI) },
            modifier = Modifier.tvFocusAnchor(focus, DialogFocus.KindBangumi),
        )
        ProfileKindOption(
            title = stringResource(Lang.tv_profile_kind_local_title),
            description = stringResource(Lang.tv_profile_kind_local_description),
            selected = kind == UserProfileKind.LOCAL,
            onClick = { onKindChange(UserProfileKind.LOCAL) },
            // 下键去主按钮: 选项整行宽, 按几何找最近的会落到离中线更近的「取消」
            modifier = Modifier.tvFocusAnchor(focus, DialogFocus.KindLocal)
                .focusProperties { down = focus.requesterOf(DialogFocus.Confirm) },
        )
    }
}

@Composable
private fun ProfileKindOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = false,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) { _ ->
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                // 跟着底座给的内容色走 (聚焦时是实底上的浅色字), 只是淡一些
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.72f),
                )
            }
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(20.dp))
            }
        }
    }
}

/** 选人页上的小弹窗 (独立窗口). */
@Composable
private fun ProfileDialogSurface(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(focus: TvFocusScope) -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = rememberTvFocusScope()
        Surface(
            Modifier.width(DIALOG_WIDTH).tvOverlayWindowKeys(onDismissRequest),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            // 可滚动兜底: 添加用户那个 (说明 + 名字 + 两个选项) 在字大的时候可能比屏幕高, 焦点走到哪滚到哪
            Column(Modifier.tvFocusNavSignal(focus).verticalScroll(rememberScrollState()).padding(32.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                content(focus)
            }
        }
    }
}

/**
 * 名字输入框, 同搜索页的搜索框分两态 (见 `TvSearchInputPane`):
 *  - 平时焦点落在**框这个整体**上 (只高亮描边), 里面的输入框不可聚焦、只读 —— Compose 的输入框一获焦就自己开输入会话、
 *    弹出键盘, 所以打开弹窗、从下面的选项走回来都不能让它拿到焦点;
 *  - 按确认键 (或点一下) 进编辑态: 焦点交给输入框, 键盘随之弹出. 输入法的「完成」先收起键盘再走到 [downTarget]
 *    (焦点先走掉的话输入会话跟着结束, 再收键盘就不灵了, 键盘会一直挡在下面的选项上); 返回回到框上
 *    (键盘开着时这一下被输入法自己吃掉; 看得到键盘收起时当场回到框上).
 *
 * 下键去 [downTarget], 上键留在原地 (往上没有别的可聚焦的, 放行会飘出弹窗).
 */
@Composable
private fun NameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focus: TvFocusScope,
    downTarget: TvFocusKey,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    var frameFocused by remember { mutableStateOf(false) }
    // 输入框是否真持焦: 进编辑态时框等它接住焦点才让位 (持焦的框当场变成不可聚焦时 Compose 清空整窗焦点)
    var editorFocused by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    // 退出编辑态回到框上: 同样先让框接住焦点 (框此刻重新可聚焦), 输入框失焦再退出编辑态, 焦点不会落空
    var returningToFrame by remember { mutableStateOf(false) }
    // 焦点交接放在效应里: canFocus 是组合期读的, 在按键回调里当场送焦时对方还不可聚焦, 请求会被拒
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { editorFocus.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(returningToFrame) {
        if (returningToFrame) focus.request(DialogFocus.Field)
    }
    // 键盘被收起 (编辑态里的返回多半被输入法自己吃掉) 就回到框上, 不然光标还留在框里, 要再按一次返回.
    // 只认「先看见它弹出、再看见它消失」: 拿不到 IME insets 的形态下这里静默不生效, 编辑态里的返回键照样回到框上.
    // 用 rememberUpdatedState 保住 state 身份, 效应里的 snapshotFlow 才观察得到变化 (同搜索框)
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        snapshotFlow { imeVisible.value }.first { it }
        snapshotFlow { imeVisible.value }.first { !it }
        returningToFrame = true
    }
    // 编辑态、键盘没开着时的返回: 回到框上, 不关弹窗 (注册在弹窗自己的返回分发器上, 先于弹窗的关闭).
    // 不拦按键事件里的返回键: 新系统上返回走系统的返回回调, 不以按键的形式送进界面
    BackHandler(enabled = editing) { returningToFrame = true }
    val scheme = MaterialTheme.colorScheme
    // 占位字与输入的字同一套样式: 行高不同的话一打字 (占位字消失) 框就变矮
    val textStyle = MaterialTheme.typography.bodyLarge
    Surface(
        Modifier.fillMaxWidth()
            // 锚点、焦点属性与 onFocusChanged 都要排在 clickable (框的焦点目标) 之前, 否则认到的是里面的输入框.
            // 锚点只认框自己持焦: 焦点在里面的输入框上时也算的话, 回到框上的请求会被当成已经到了
            .tvFocusAnchor(focus, DialogFocus.Field, includeDescendants = false)
            .focusProperties { canFocus = !editing || !editorFocused || returningToFrame }
            .onFocusChanged {
                frameFocused = it.isFocused
                if (it.isFocused) returningToFrame = false
            }
            // 输入框持焦时这里也先看到按键 (preview 从外往里): 编辑态里键盘开着时方向键归键盘, 到不了这里
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) focus.request(downTarget)
                        true
                    }

                    Key.DirectionUp -> true
                    else -> false
                }
            }
            // 触屏 (平板装了 TV 包): 点在文字上时里面的输入框会自己吃掉这一下, 在 Initial pass 旁听. 电视上不装
            .tvTouchTap(onTap = { if (!editing) editing = true })
            // 确认键 (抬起时) / 点一下进编辑态; 已在编辑态 (键盘被收起了) 再按确认把键盘叫回来
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (editing) keyboard?.show() else editing = true
            },
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        border = tvFieldBorderStroke(frameFocused || editing, scheme.outlineVariant),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .focusRequester(editorFocus)
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    editorFocused = it.isFocused
                    // 焦点被带走 (下键 / 完成 / 回到框上) 即退出编辑
                    if (!it.isFocused && editing) editing = false
                },
            readOnly = !editing,
            textStyle = textStyle.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboard?.hide()
                    focus.request(downTarget)
                },
            ),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(
                            placeholder.ifEmpty { stringResource(Lang.tv_profile_name_placeholder) },
                            style = textStyle,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

private val AVATAR_SIZE = 140.dp
private val TILE_WIDTH = 170.dp
private val TILE_GAP = 44.dp
private val DIALOG_WIDTH = 560.dp

/** 聚焦放大倍数. Apple TV 卡片 ×1.09~1.10, 单独一排的大圆头像再多一点. */
private const val FOCUS_SCALE = 1.12f

/** 选中自己、淡出进入应用时再多放大的量. */
private const val CHOSEN_EXTRA_SCALE = 0.08f

private const val ENTER_MILLIS = 420
private const val ENTER_STAGGER_MILLIS = 60
private const val EXIT_MILLIS = 260
private val ENTER_RISE = 18.dp

/** 换人的过场: 头像移到正中用时、其余淡出用时、没有起飞位置时台上淡入用时 */
private const val SWITCH_MOVE_MILLIS = 520
private const val OTHERS_FADE_MILLIS = 220
private const val STAGE_FADE_MILLIS = 300

/** 换人的过场里正中那个头像放大到几倍、中心在页面高度的哪里、与下面名字的间距 */
private const val SWITCH_SCALE = 1.5f
private const val SWITCH_CENTER_Y = 0.42f
private val SWITCH_LABEL_GAP = 32.dp

/** 换人过场里的进度条: 在「正在切换」下面多远、多宽多高、空槽的颜色 (填充色见 ProfileSwitchFrameDrawable) */
private val SWITCH_PROGRESS_GAP = 28.dp
private val SWITCH_PROGRESS_WIDTH = 168.dp
private val SWITCH_PROGRESS_HEIGHT = 4.dp
private val SWITCH_PROGRESS_TRACK = Color.White.copy(alpha = 0.2f)

/** 页面底: Apple TV 深色灰阶, 中间 Gray4、四周 Gray6. 与应用主题无关, 恒为深色. */
private val PICKER_BACKGROUND_CENTER = Color(0xFF3A3A3C)
private val PICKER_BACKGROUND_EDGE = Color(0xFF1C1C1E)

/** 次要文字 (没聚焦的名字、提示): Apple 深色 LabelSecondary, 白 50%. */
private val SECONDARY_LABEL = Color.White.copy(alpha = 0.5f)

/** 「添加用户」那一格的底: Apple 深色 Gray3. */
private val ADD_TILE_COLOR = Color(0xFF48484A)

/** 玻璃边: Apple 深色 hairline, 白 8%. */
private val HAIRLINE = Color.White.copy(alpha = 0.08f)

/** 静止投影: Apple TV 卡片的静止阴影 (下移 4 pt、模糊 20 pt、黑 40%; 1 pt = 0.5 dp). */
private val IDLE_SHADOW = Shadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.4f), offset = DpOffset(0.dp, 2.dp))

/** 聚焦投影: Apple TV 卡片的聚焦阴影 (下移 40 pt、模糊 50 pt、黑 30%), 抬起来的一大片软影. */
private val FOCUSED_SHADOW = Shadow(radius = 25.dp, color = Color.Black.copy(alpha = 0.3f), offset = DpOffset(0.dp, 20.dp))

private val AVATAR_COLORS = listOf(
    Color(0xFF5E81AC),
    Color(0xFFBF616A),
    Color(0xFFA3BE8C),
    Color(0xFFD08770),
    Color(0xFFB48EAD),
    Color(0xFF88C0D0),
    Color(0xFFEBCB8B),
)

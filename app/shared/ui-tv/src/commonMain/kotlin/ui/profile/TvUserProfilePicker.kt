/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.foundation.tv.tvFieldBorderStroke
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
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
import me.him188.ani.app.ui.lang.tv_profile_delete_title
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
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * 选人页 (像 Apple TV 那样): 这台设备上有两个以上用户时, 每次打开应用先选人 (TV 根部打开);
 * 侧边栏头像的「切换用户」也打开它.
 *
 * 选的还是当前用户就淡出、露出下面的主页; 选了别人就记下来并重启应用 (见 [UserProfileManager]).
 * 长按一个人可以改名或删除, 最后一格是添加用户.
 */
object TvUserProfilePicker {
    val visible = MutableStateFlow(false)

    fun show() {
        visible.value = true
    }
}

/** 选人页 (独立窗口, 盖在主页上). 装在 TV 根部. */
@Composable
fun TvUserProfilePickerHost() {
    val visible by TvUserProfilePicker.visible.collectAsStateWithLifecycle()
    if (!visible) return
    val manager = remember { KoinPlatform.getKoin().get<UserProfileManager>() }
    if (!manager.isSupported) return
    // 切换中 (正在重启) 不许关: 关掉会露出旧用户的主页, 紧接着进程又没了
    var switching by remember { mutableStateOf(false) }
    val close = { if (!switching) TvUserProfilePicker.visible.value = false }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // 不压暗背后: 选的还是自己时整页淡出, 露出来的主页就是正常亮度
        DialogWindowDimAmount(0f)
        PickerContent(manager, switching, onSwitching = { switching = true }, onClose = close)
    }
}

@Composable
private fun PickerContent(
    manager: UserProfileManager,
    switching: Boolean,
    onSwitching: () -> Unit,
    onClose: () -> Unit,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = rememberTvFocusScope()
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

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

    val select: (UserProfile) -> Unit = select@{ profile ->
        if (switching || entering) return@select
        if (profile.id == manager.currentId) {
            entering = true
        } else {
            onSwitching()
            scope.launch { manager.switchTo(profile.id) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .tvOverlayWindowKeys(onClose)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        onClick = { select(profile) },
                        menu = { expanded, onDismiss ->
                            ProfileMenu(
                                profile = profile,
                                isCurrent = profile.id == manager.currentId,
                                expanded = expanded,
                                onDismiss = onDismiss,
                                onRename = { dialog = ProfileDialog.Rename(profile) },
                                onDelete = { dialog = ProfileDialog.Delete(profile, name) },
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
                stringResource(if (switching) Lang.tv_profile_switching else Lang.tv_profile_picker_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = SECONDARY_LABEL,
            )
        }
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
                    onSwitching()
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
}

private data class ProfileFocusKey(val id: Int) : TvFocusKey

private data object AddFocusKey : TvFocusKey

/**
 * 一个人 (或「添加用户」): 圆形头像 + 名字, 照 Apple TV 的聚焦样式 —— 不画描边, 头像原地放大「抬起来」,
 * 身后的投影从贴身一圈淡影换成往下拖的一大片软影; 名字聚焦时变白, 其余灰.
 * 有 [menu] 时长按确认键弹出它. 名字下面一行恒占位 ([sublabel]: 本地档标「本地」、当前用户标「当前」), 出不出现不会让整排上下跳.
 *
 * @param chosen 选中了它、整页正在淡出进入应用: 再抬一点
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
    onClick: () -> Unit,
    menu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)?,
    avatar: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
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
            alpha = enter.value
            translationY = (1f - enter.value) * ENTER_RISE.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.graphicsLayer {
                val scale = 1f + (FOCUS_SCALE - 1f) * focusWeight.value + CHOSEN_EXTRA_SCALE * chosenLift.value
                scaleX = scale
                scaleY = scale
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
                    .onFocusChanged { focused = it.isFocused }
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

/** 长按一个人的菜单: 改名; 能删时有删除, 不能删时写明为什么. */
@Composable
private fun ProfileMenu(
    profile: UserProfile,
    isCurrent: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
            onDone = { focus.request(fieldDownTarget) },
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
            modifier = Modifier.tvFocusAnchor(focus, DialogFocus.KindLocal),
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

/** 名字输入框: 聚焦时弹出系统键盘 (电视上没有物理键盘). 下键去 [downTarget], 上键留在原地. */
@Composable
private fun NameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focus: TvFocusScope,
    downTarget: TvFocusKey,
    onDone: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focused) {
        if (focused) keyboard?.show() else keyboard?.hide()
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        border = tvFieldBorderStroke(focused, scheme.outlineVariant),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            focus.request(downTarget)
                            true
                        }

                        // 往上没有别的可聚焦的, 放行会飘出弹窗
                        Key.DirectionUp -> true
                        else -> false
                    }
                }
                .tvFocusAnchor(focus, DialogFocus.Field)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(color = scheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(scheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(
                        placeholder.ifEmpty { stringResource(Lang.tv_profile_name_placeholder) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                    )
                }
                inner()
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

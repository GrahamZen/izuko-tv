/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.TV_CONFIRM_KEYS
import me.him188.ani.app.ui.foundation.interaction.isImeVisible
import me.him188.ani.app.ui.foundation.isAutoRepeat
import me.him188.ani.app.ui.foundation.navigation.BackHandler

/**
 * 遥控器上的输入框, 同搜索页的搜索框 (`TvSearchInputPane`) 分两态:
 *  - 平时焦点落在**框**上 (外面套的这一层, 不画任何东西), 里面的输入框不可聚焦、只读 —— Compose 的输入框一获焦就自己开输入会话、
 *    弹出键盘, 所以打开弹窗、方向键路过、从别处走回来都不能让它拿到焦点;
 *  - 按确认键 (触屏上点一下) 进编辑态: 焦点交给输入框, 键盘随之弹出. 键盘被收起 (编辑态里的返回多半被输入法自己吃掉) 或按返回,
 *    退出编辑态回到框上; 方向键把焦点带走也退出编辑态 (焦点留在用户走到的地方). 编辑态里键盘被收起之后再按确认, 把键盘叫回来.
 *
 * 框持焦时往 [TvTextFieldFrameScope.editorInteractionSource] 里报「聚焦」, 输入框的聚焦样式 (描边、浮起的标签) 跟它自己持焦时一样.
 *
 * 用法: 原来挂在输入框上的 modifier (宽度、外边距、weight、初始焦点、focusRequester) 整个交给 [modifier], 输入框只挂
 * `Modifier.textEditor()`, 并传 `readOnly = editorReadOnly`、`interactionSource = editorInteractionSource`.
 * Material 的输入框直接用 [AniOutlinedTextField] / [AniTextField].
 *
 * 不是遥控器形态 ([me.him188.ani.app.ui.foundation.AniUiBehavior.focusDrivenNavigation]), 或 [enabled] 为 false (禁用 / 只读的框不会弹键盘) 时
 * 原样: 不套框, [modifier] 原封不动挂到输入框上.
 */
@Composable
fun TvTextFieldFrame(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 输入框原本的 interactionSource; `null` 时用一个新的. */
    interactionSource: MutableInteractionSource? = null,
    content: @Composable TvTextFieldFrameScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (!LocalAniUiBehavior.current.focusDrivenNavigation || !enabled) {
        PassThroughTextFieldScope(modifier, source).content()
        return
    }
    val state = remember(source) { TvTextFieldFrameState(source) }
    val keyboard = LocalSoftwareKeyboardController.current
    // 焦点交接放在效应里: canFocus 是组合期读的, 在按键回调里当场送焦时对方还不可聚焦, 请求会被拒
    LaunchedEffect(state.editing) {
        if (state.editing) {
            state.everEdited = true
            runCatching { state.editorFocus.requestFocus() }
            keyboard?.show()
        } else if (state.everEdited) {
            keyboard?.hide()
        }
    }
    // 退出编辑态回到框上: 先让框接住焦点 (此刻框重新可聚焦), 输入框失焦再退出编辑态, 焦点不会落空
    LaunchedEffect(state.returningToFrame) {
        if (state.returningToFrame) runCatching { state.frameFocus.requestFocus() }
    }
    // 键盘被收起就回到框上, 不然光标还留在框里, 要再按一次返回. 只认「先看见它弹出、再看见它消失」: 拿不到 IME insets 的形态下
    // 这里静默不生效, 编辑态里的返回照样回到框上. 用 rememberUpdatedState 保住 state 身份, 效应里的 snapshotFlow 才观察得到变化
    val imeVisible = rememberUpdatedState(isImeVisible())
    LaunchedEffect(state.editing) {
        if (!state.editing) return@LaunchedEffect
        snapshotFlow { imeVisible.value }.first { it }
        snapshotFlow { imeVisible.value }.first { !it }
        state.returningToFrame = true
    }
    // 编辑态、键盘没开着时的返回: 回到框上 (弹窗里注册在弹窗自己的返回分发器上, 先于弹窗的关闭).
    // 不在按键事件里拦返回键: 新系统上返回走系统的返回回调, 不以按键的形式送进界面
    BackHandler(enabled = state.editing) { state.returningToFrame = true }
    LaunchedEffect(state.frameFocused) {
        if (!state.frameFocused) return@LaunchedEffect
        val focus = FocusInteraction.Focus()
        source.emit(focus)
        try {
            awaitCancellation()
        } finally {
            source.tryEmit(FocusInteraction.Unfocus(focus))
        }
    }
    Box(
        Modifier
            // 框持焦时确认键归框, 排在调用方的 modifier 之前: 调用方挂的回车处理 (硬件键盘回车提交) 只在编辑态里生效,
            // 有的遥控器确认键发的就是回车. 只认在框上按下的那一次, 别处按下、落到框上的抬起不进编辑态.
            // 焦点在输入框里的尾部按钮上时不拦
            .onPreviewKeyEvent { event ->
                if (state.editing || !state.frameFocused || event.key !in TV_CONFIRM_KEYS) {
                    return@onPreviewKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> if (event.isAutoRepeat != true) state.confirmPressed = true
                    KeyEventType.KeyUp -> if (state.confirmPressed) {
                        state.confirmPressed = false
                        state.editing = true
                    }
                }
                true
            }
            .then(modifier)
            // 焦点属性与 onFocusChanged 要排在 clickable (框的焦点目标) 之前, 否则认到的是里面的输入框.
            // 编辑态里等输入框真接住焦点才让位: 持焦的框当场变成不可聚焦时 Compose 清空整窗焦点
            .focusRequester(state.frameFocus)
            .focusProperties { canFocus = !state.editing || !state.editorFocused || state.returningToFrame }
            .onFocusChanged {
                state.frameFocused = it.isFocused
                if (it.isFocused) state.returningToFrame = false else state.confirmPressed = false
            }
            // 触屏 (平板装了 TV 包): 点在文字上时里面的输入框会自己吃掉这一下, 在 Initial pass 旁听. 电视上不装
            .tvTouchTap(onTap = { if (!state.editing) state.editing = true })
            // 确认键在抬起时生效 (见上面的按键拦截): 在按下时就进编辑态的话, 键盘一弹出, 同一次按键的抬起落到输入法里.
            // clickable 管无障碍的点击与编辑态里框短暂持焦时的确认
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (state.editing) keyboard?.show() else state.editing = true
            },
        propagateMinConstraints = true,
    ) {
        state.content()
    }
}

/** [TvTextFieldFrame] 里给输入框用的. */
@Stable
interface TvTextFieldFrameScope {
    /** 挂在输入框上 (输入框自己的 modifier 从它开始). */
    fun Modifier.textEditor(): Modifier

    /** 传给输入框的 readOnly: 不在编辑态时只读. */
    val editorReadOnly: Boolean

    /** 传给输入框的 interactionSource: 框持焦时也报「聚焦」. */
    val editorInteractionSource: MutableInteractionSource
}

private class TvTextFieldFrameState(
    override val editorInteractionSource: MutableInteractionSource,
) : TvTextFieldFrameScope {
    var editing by mutableStateOf(false)
    var everEdited = false

    /** 确认键是在框上按下的 (见框的按键拦截). */
    var confirmPressed = false
    var frameFocused by mutableStateOf(false)

    /** 输入框是否真持焦: 进编辑态时框等它接住焦点才让位. */
    var editorFocused by mutableStateOf(false)
    var returningToFrame by mutableStateOf(false)
    val frameFocus = FocusRequester()
    val editorFocus = FocusRequester()

    override val editorReadOnly: Boolean get() = !editing

    override fun Modifier.textEditor(): Modifier = this
        .focusRequester(editorFocus)
        .focusProperties { canFocus = editing }
        .onFocusChanged {
            editorFocused = it.isFocused
            // 焦点被带走 (方向键 / 回到框上) 即退出编辑
            if (!it.isFocused && editing) editing = false
        }
}

private class PassThroughTextFieldScope(
    private val frameModifier: Modifier,
    override val editorInteractionSource: MutableInteractionSource,
) : TvTextFieldFrameScope {
    override fun Modifier.textEditor(): Modifier = this.then(frameModifier)
    override val editorReadOnly: Boolean get() = false
}

/** [OutlinedTextField] 加上遥控器上的两态 (见 [TvTextFieldFrame]); 参数与样式同 [OutlinedTextField]. */
@Composable
fun AniOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    TvTextFieldFrame(modifier, enabled = enabled && !readOnly, interactionSource = interactionSource) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.textEditor(),
            enabled = enabled,
            readOnly = readOnly || editorReadOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            prefix = prefix,
            suffix = suffix,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = editorInteractionSource,
            shape = shape,
            colors = colors,
        )
    }
}

/** [OutlinedTextField] ([TextFieldValue] 版) 加上遥控器上的两态 (见 [TvTextFieldFrame]). */
@Composable
fun AniOutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    TvTextFieldFrame(modifier, enabled = enabled && !readOnly, interactionSource = interactionSource) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.textEditor(),
            enabled = enabled,
            readOnly = readOnly || editorReadOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            prefix = prefix,
            suffix = suffix,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = editorInteractionSource,
            shape = shape,
            colors = colors,
        )
    }
}

/** [TextField] 加上遥控器上的两态 (见 [TvTextFieldFrame]); 参数与样式同 [TextField]. */
@Composable
fun AniTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = TextFieldDefaults.shape,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    TvTextFieldFrame(modifier, enabled = enabled && !readOnly, interactionSource = interactionSource) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.textEditor(),
            enabled = enabled,
            readOnly = readOnly || editorReadOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            prefix = prefix,
            suffix = suffix,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = editorInteractionSource,
            shape = shape,
            colors = colors,
        )
    }
}

/** [TextField] ([TextFieldValue] 版) 加上遥控器上的两态 (见 [TvTextFieldFrame]). */
@Composable
fun AniTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = TextFieldDefaults.shape,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    TvTextFieldFrame(modifier, enabled = enabled && !readOnly, interactionSource = interactionSource) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.textEditor(),
            enabled = enabled,
            readOnly = readOnly || editorReadOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            prefix = prefix,
            suffix = suffix,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = editorInteractionSource,
            shape = shape,
            colors = colors,
        )
    }
}

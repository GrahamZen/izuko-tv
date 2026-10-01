/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.mediasource.selector.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.effects.moveFocusOnEnter
import me.him188.ani.app.ui.foundation.layout.cardVerticalPadding
import me.him188.ani.app.ui.foundation.layout.currentWindowAdaptiveInfo1
import me.him188.ani.app.ui.foundation.tv.AniOutlinedTextField
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_mediasource_selector_cookies
import me.him188.ani.app.ui.lang.settings_mediasource_selector_cookies_description
import me.him188.ani.app.ui.lang.settings_mediasource_selector_enable_nested_link
import me.him188.ani.app.ui.lang.settings_mediasource_selector_enable_nested_link_description
import me.him188.ani.app.ui.lang.settings_mediasource_selector_match_nested_link
import me.him188.ani.app.ui.lang.settings_mediasource_selector_match_nested_link_description
import me.him188.ani.app.ui.lang.settings_mediasource_selector_match_video_link
import me.him188.ani.app.ui.lang.settings_mediasource_selector_match_video_link_description
import me.him188.ani.app.ui.lang.settings_mediasource_selector_step1
import me.him188.ani.app.ui.lang.settings_mediasource_selector_step2
import me.him188.ani.app.ui.lang.settings_mediasource_selector_step3
import org.jetbrains.compose.resources.stringResource

/**
 * @see me.him188.ani.app.domain.mediasource.web.SelectorMediaSourceArguments
 */
object SelectorConfigurationDefaults {
    val STEP_NAME_1 @Composable get() = stringResource(Lang.settings_mediasource_selector_step1)
    val STEP_NAME_2 @Composable get() = stringResource(Lang.settings_mediasource_selector_step2)
    val STEP_NAME_3 @Composable get() = stringResource(Lang.settings_mediasource_selector_step3)

    val verticalSpacing: Dp
        @Composable
        get() = currentWindowAdaptiveInfo1().windowSizeClass.cardVerticalPadding

    val textFieldShape
        @Composable
        get() = MaterialTheme.shapes.medium
}

@Suppress("UnusedReceiverParameter")
@Composable
internal fun SelectorConfigurationDefaults.MatchVideoSection(
    state: SelectorConfigState,
    modifier: Modifier = Modifier,
    textFieldShape: Shape = SelectorConfigurationDefaults.textFieldShape,
    verticalSpacing: Dp = SelectorConfigurationDefaults.verticalSpacing,
) {
    Column(modifier) {
        val matchVideoConfig = state.matchVideoConfig
        ListItem(
            headlineContent = { Text(stringResource(Lang.settings_mediasource_selector_enable_nested_link)) },
            Modifier
                .padding(bottom = (verticalSpacing - 8.dp).coerceAtLeast(0.dp))
                .clickable(
                    enabled = state.enableEdit,
                ) { matchVideoConfig.enableNestedUrl = !matchVideoConfig.enableNestedUrl },
            supportingContent = { Text(stringResource(Lang.settings_mediasource_selector_enable_nested_link_description)) },
            trailingContent = {
                Switch(
                    matchVideoConfig.enableNestedUrl, { matchVideoConfig.enableNestedUrl = it },
                    enabled = state.enableEdit,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Transparent),
        )

        AniAnimatedVisibility(visible = matchVideoConfig.enableNestedUrl) {
            AniOutlinedTextField(
                matchVideoConfig.matchNestedUrl, { matchVideoConfig.matchNestedUrl = it },
                Modifier
                    .fillMaxWidth()
                    .moveFocusOnEnter()
                    .padding(bottom = verticalSpacing),
                label = { Text(stringResource(Lang.settings_mediasource_selector_match_nested_link)) },
                supportingText = { Text(stringResource(Lang.settings_mediasource_selector_match_nested_link_description)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = textFieldShape,
                isError = matchVideoConfig.matchNestedUrlIsError,
                enabled = state.enableEdit,
            )
        }

        AniOutlinedTextField(
            matchVideoConfig.matchVideoUrl, { matchVideoConfig.matchVideoUrl = it },
            Modifier.fillMaxWidth().moveFocusOnEnter().padding(bottom = verticalSpacing),
            label = { Text(stringResource(Lang.settings_mediasource_selector_match_video_link)) },
            supportingText = { Text(stringResource(Lang.settings_mediasource_selector_match_video_link_description)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = textFieldShape,
            isError = matchVideoConfig.matchVideoUrlIsError,
            enabled = state.enableEdit,
        )
        AniOutlinedTextField(
            matchVideoConfig.cookies, { matchVideoConfig.cookies = it },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(Lang.settings_mediasource_selector_cookies)) },
            supportingText = { Text(stringResource(Lang.settings_mediasource_selector_cookies_description)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = textFieldShape,
            enabled = state.enableEdit,
        )
    }
}

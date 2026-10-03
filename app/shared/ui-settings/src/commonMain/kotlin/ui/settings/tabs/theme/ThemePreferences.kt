/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.theme

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvVisualEffectsLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.data.models.preference.TvScheduleLayout
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.data.models.preference.TvTitleLogoLanguage
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.LocalPlatform
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.isPlatformSupportDynamicTheme
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_theme_always_dark_episode
import me.him188.ani.app.ui.lang.settings_theme_always_dark_episode_description
import me.him188.ani.app.ui.lang.settings_theme_animated_gradient_subject
import me.him188.ani.app.ui.lang.settings_theme_animated_gradient_subject_description
import me.him188.ani.app.ui.lang.settings_theme_dynamic_colors
import me.him188.ani.app.ui.lang.settings_theme_dynamic_colors_description
import me.him188.ani.app.ui.lang.settings_theme_dynamic_subject
import me.him188.ani.app.ui.lang.settings_theme_dynamic_subject_description
import me.him188.ani.app.ui.lang.settings_theme_frosted_glass
import me.him188.ani.app.ui.lang.settings_theme_frosted_glass_description
import me.him188.ani.app.ui.lang.settings_theme_palette
import me.him188.ani.app.ui.lang.settings_theme_title
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_light
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_medium
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_none
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_strong
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_both
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_description
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_pair
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_auto
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_description
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_language
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_language_app
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_language_description
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_language_original
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_off
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_original
import me.him188.ani.app.ui.lang.settings_theme_tv_title_logo_text
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_description
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_details
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_hero
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_play
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_balanced
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_description
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_full
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_smooth
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_layout
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_layout_description
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_upstream
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_grid
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_timeline
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.DropdownItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import me.him188.ani.app.ui.theme.themeColorOptions
import me.him188.ani.utils.platform.isMobile
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScope.ThemeGroup(
    state: SettingsState<ThemeSettings>,
) {
    val themeSettings by state

    Group(
        title = { Text(stringResource(Lang.settings_theme_title)) },
    ) {
        // 深色模式与高对比度 (纯黑) 合成一个四选一面板: 高对比度只在深色下起作用, 单拎一个开关时三种组合里有一种是空的
        DarkModeSelectPanel(
            currentMode = themeSettings.darkMode,
            blackBackground = themeSettings.useBlackBackground,
            onSelected = { mode, black -> state.update(themeSettings.copy(darkMode = mode, useBlackBackground = black)) },
            modifier = Modifier.padding(vertical = SettingsScope.itemVerticalSpacing),
        )

        if (isPlatformSupportDynamicTheme()) {
            SwitchItem(
                checked = themeSettings.useDynamicTheme,
                onCheckedChange = { checked ->
                    state.update(themeSettings.copy(useDynamicTheme = checked))
                },
                title = { Text(stringResource(Lang.settings_theme_dynamic_colors)) },
                description = { Text(stringResource(Lang.settings_theme_dynamic_colors_description)) },
            )
        }

        // 播放页本来就恒为深色的形态 (遥控器) 上这条开关按下去什么都不会变, 见 EpisodePage 里
        // `alwaysDarkInEpisodePage || forceDarkInPlayer`
        if (!LocalAniUiBehavior.current.forceDarkInPlayer) {
            SwitchItem(
                checked = themeSettings.alwaysDarkInEpisodePage,
                onCheckedChange = { checked ->
                    state.update(themeSettings.copy(alwaysDarkInEpisodePage = checked))
                },
                title = { Text(stringResource(Lang.settings_theme_always_dark_episode)) },
                description = { Text(stringResource(Lang.settings_theme_always_dark_episode_description)) },
            )
        }

        SwitchItem(
            checked = themeSettings.useDynamicSubjectPageTheme,
            onCheckedChange = { checked ->
                state.update(themeSettings.copy(useDynamicSubjectPageTheme = checked))
            },
            title = { Text(stringResource(Lang.settings_theme_dynamic_subject)) },
            description = { Text(stringResource(Lang.settings_theme_dynamic_subject_description)) },
        )

        // 电视端不给这条: 沉浸式详情页的背景是整屏 backdrop, 光斑几乎永远被它盖着 (代码里还专门
        // 为此在盖住时暂停动画, 见 SubjectDetailsTvPage 的 paused), 而且色块要往 surface 混 85%,
        // 深色+纯黑背景下本就近乎全黑 —— 开了看不出效果, 滚动露出来那段却要照付一层全屏 blur.
        // 与"倍速范围"同一处理: 遥控器上没有意义的开关直接不显示
        if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
            SwitchItem(
                checked = themeSettings.enableAnimatedGradientSubjectPage,
                onCheckedChange = { checked ->
                    state.update(themeSettings.copy(enableAnimatedGradientSubjectPage = checked))
                },
                title = { Text(stringResource(Lang.settings_theme_animated_gradient_subject)) },
                description = {
                    Text(stringResource(Lang.settings_theme_animated_gradient_subject_description))
                },
            )
        }

        // isMobile() 在 Android TV 上也是真, 但沉浸式外壳既没有顶栏也没有导航栏, 这条毛玻璃
        // 开关在那儿是纯摆设 (见 AppChromeFrostedGlass 的调用点)
        if (LocalPlatform.current.isMobile() && !LocalAniUiBehavior.current.immersiveShell) {
            SwitchItem(
                checked = themeSettings.enableFrostedGlassEffect,
                onCheckedChange = { checked ->
                    state.update(themeSettings.copy(enableFrostedGlassEffect = checked))
                },
                title = { Text(stringResource(Lang.settings_theme_frosted_glass)) },
                description = { Text(stringResource(Lang.settings_theme_frosted_glass_description)) },
            )
        }

        // 沉浸式外壳专属 (探索 / 追番 / 搜索 / 详情在这一形态下恒为沉浸式布局, 没有回退开关)
        if (LocalAniUiBehavior.current.immersiveShell) {
            // 三版都留着可选 (见 TvScheduleLayout): 改版换掉的东西未必人人都想要
            DropdownItem(
                selected = { themeSettings.tvScheduleLayout },
                values = { TvScheduleLayout.entries },
                itemText = {
                    Text(
                        stringResource(
                            when (it) {
                                TvScheduleLayout.Upstream -> Lang.settings_theme_tv_schedule_upstream
                                TvScheduleLayout.Grid -> Lang.settings_theme_tv_schedule_grid
                                TvScheduleLayout.Timeline -> Lang.settings_theme_tv_schedule_timeline
                            },
                        ),
                    )
                },
                onSelect = { state.update(themeSettings.copy(tvScheduleLayout = it)) },
                title = { Text(stringResource(Lang.settings_theme_tv_schedule_layout)) },
                description = { Text(stringResource(Lang.settings_theme_tv_schedule_layout_description)) },
            )

            // 海报墙的卡片上按确定: 先看简介 (hero 态) / 直接播放 / 直接进详情页 (见 TvPosterConfirmAction)
            DropdownItem(
                selected = { themeSettings.tvPosterConfirm },
                values = { TvPosterConfirmAction.entries },
                itemText = {
                    Text(
                        stringResource(
                            when (it) {
                                TvPosterConfirmAction.Hero -> Lang.settings_theme_tv_poster_confirm_hero
                                TvPosterConfirmAction.Play -> Lang.settings_theme_tv_poster_confirm_play
                                TvPosterConfirmAction.Details -> Lang.settings_theme_tv_poster_confirm_details
                            },
                        ),
                    )
                },
                onSelect = { state.update(themeSettings.copy(tvPosterConfirm = it)) },
                title = { Text(stringResource(Lang.settings_theme_tv_poster_confirm)) },
                description = { Text(stringResource(Lang.settings_theme_tv_poster_confirm_description)) },
            )

            // 模糊背景 (见 ThemeSettings.tvWallBackdropBlur / tvDetailsBackdropBlur): 海报墙 (连新番时间表) 与详情页两处按搭配一起选 ——
            // 海报墙的底糊重一点不抢眼, 详情页同一张图糊轻一点认得出画面, 两处要的程度不同, 合成一档就搭不出来.
            // 存着的搭配不在预设里 (以前分开选过) 时也列在最后, 不改它就一直是它
            DropdownItem(
                selected = { themeSettings.backdropBlurCombo },
                values = {
                    val current = themeSettings.backdropBlurCombo
                    if (current in TV_BACKDROP_BLUR_COMBOS) TV_BACKDROP_BLUR_COMBOS else TV_BACKDROP_BLUR_COMBOS + current
                },
                itemText = { Text(it.label()) },
                onSelect = { state.update(themeSettings.copy(tvWallBackdropBlur = it.wall, tvDetailsBackdropBlur = it.details)) },
                title = { Text(stringResource(Lang.settings_theme_tv_backdrop_blur)) },
                description = { Text(stringResource(Lang.settings_theme_tv_backdrop_blur_description)) },
            )

            // hero 标题换成 TMDB 的标题 logo, 看不清时怎么办 (见 ThemeSettings.tvTitleLogoDisplay)
            DropdownItem(
                selected = { themeSettings.tvTitleLogoDisplay },
                values = { TvTitleLogoDisplay.entries },
                itemText = {
                    Text(
                        stringResource(
                            when (it) {
                                TvTitleLogoDisplay.Auto -> Lang.settings_theme_tv_title_logo_auto
                                TvTitleLogoDisplay.TextWhenUnreadable -> Lang.settings_theme_tv_title_logo_text
                                TvTitleLogoDisplay.Original -> Lang.settings_theme_tv_title_logo_original
                                TvTitleLogoDisplay.Off -> Lang.settings_theme_tv_title_logo_off
                            },
                        ),
                    )
                },
                onSelect = { state.update(themeSettings.copy(tvTitleLogoDisplay = it)) },
                title = { Text(stringResource(Lang.settings_theme_tv_title_logo)) },
                description = { Text(stringResource(Lang.settings_theme_tv_title_logo_description)) },
            )
            if (themeSettings.tvTitleLogoDisplay != TvTitleLogoDisplay.Off) {
                DropdownItem(
                    selected = { themeSettings.tvTitleLogoLanguage },
                    values = { TvTitleLogoLanguage.entries },
                    itemText = {
                        Text(
                            stringResource(
                                when (it) {
                                    TvTitleLogoLanguage.Original -> Lang.settings_theme_tv_title_logo_language_original
                                    TvTitleLogoLanguage.AppLanguage -> Lang.settings_theme_tv_title_logo_language_app
                                },
                            ),
                        )
                    },
                    onSelect = { state.update(themeSettings.copy(tvTitleLogoLanguage = it)) },
                    title = { Text(stringResource(Lang.settings_theme_tv_title_logo_language)) },
                    description = { Text(stringResource(Lang.settings_theme_tv_title_logo_language_description)) },
                )
            }

            // 三档 (见 TvVisualEffectsLevel). 写 tvVisualEffects 而不是老的布尔: 一旦显式选过, 读取就不再看那个布尔
            DropdownItem(
                selected = { themeSettings.visualEffects },
                values = { TvVisualEffectsLevel.entries },
                itemText = {
                    Text(
                        stringResource(
                            when (it) {
                                TvVisualEffectsLevel.Smooth -> Lang.settings_theme_tv_visual_effects_smooth
                                TvVisualEffectsLevel.Balanced -> Lang.settings_theme_tv_visual_effects_balanced
                                TvVisualEffectsLevel.Full -> Lang.settings_theme_tv_visual_effects_full
                            },
                        ),
                    )
                },
                onSelect = { state.update(themeSettings.copy(tvVisualEffects = it)) },
                title = { Text(stringResource(Lang.settings_theme_tv_visual_effects)) },
                description = { Text(stringResource(Lang.settings_theme_tv_visual_effects_description)) },
            )
        }
        // 「退出播放页后保留播放状态」在播放器那一类里 (见 PlayerGroup), 「界面缩放」在界面那一类里
        // (见 AppearanceGroup) —— 都存在 ThemeSettings 里只是存储位置, 不代表要摆在主题这一页.
    }

    Box(
        modifier = Modifier.alpha(if (themeSettings.useDynamicTheme) 0.5f else 1f),
    ) {
        Group(title = { Text(stringResource(Lang.settings_theme_palette)) }) {
            FlowRow(
                // focusGroup: 色块只在组内横向移动. 不包组时色块是详情滚动 scope 里的散装
                // 候选, 且位于内容左缘 —— 其他行按左键会被它捕获 (2D 搜索在最内层 scope 内
                // 命中即止, 到不了左侧导航); 包组后组矩形全宽, 不满足横向候选条件.
                modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.Center,
            ) {
                AniThemeDefaults.themeColorOptions.forEach { color ->
                    ColorButton(
                        color = color,
                        themeSettings = themeSettings,
                        state = state,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorButton(
    color: Color,
    themeSettings: ThemeSettings,
    state: SettingsState<ThemeSettings>,
    modifier: Modifier = Modifier,
) {
    ColorButton(
        modifier = modifier,
        selected = color.value == themeSettings.seedColorValue && !themeSettings.useDynamicTheme,
        onClick = {
            state.update(
                themeSettings.copy(
                    seedColorValue = color.value,
                    useDynamicTheme = false,
                ),
            )

        },
        baseColor = color,
    )
}

/** 模糊背景的一种搭配: 海报墙 (连新番时间表) 那一档 [wall] 与详情页那一档 [details]. */
private data class TvBackdropBlurCombo(val wall: TvBackdropBlurLevel, val details: TvBackdropBlurLevel)

private val ThemeSettings.backdropBlurCombo: TvBackdropBlurCombo
    get() = TvBackdropBlurCombo(tvWallBackdropBlur, tvDetailsBackdropBlur)

/** 设置里给的搭配: 不模糊 / 海报墙重、详情页轻 (默认) / 海报墙中、详情页轻 / 都轻 / 都中 / 都重. */
private val TV_BACKDROP_BLUR_COMBOS = listOf(
    TvBackdropBlurCombo(TvBackdropBlurLevel.None, TvBackdropBlurLevel.None),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Strong, TvBackdropBlurLevel.Light),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Medium, TvBackdropBlurLevel.Light),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Light, TvBackdropBlurLevel.Light),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Medium, TvBackdropBlurLevel.Medium),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Strong, TvBackdropBlurLevel.Strong),
)

@Composable
private fun TvBackdropBlurCombo.label(): String = when {
    wall == TvBackdropBlurLevel.None && details == TvBackdropBlurLevel.None -> stringResource(Lang.settings_theme_tv_backdrop_blur_none)
    wall == details -> stringResource(Lang.settings_theme_tv_backdrop_blur_both, stringResource(wall.labelRes))
    else -> stringResource(Lang.settings_theme_tv_backdrop_blur_pair, stringResource(wall.labelRes), stringResource(details.labelRes))
}

/** 模糊背景四档的名字. */
private val TvBackdropBlurLevel.labelRes: StringResource
    get() = when (this) {
        TvBackdropBlurLevel.None -> Lang.settings_theme_tv_backdrop_blur_none
        TvBackdropBlurLevel.Light -> Lang.settings_theme_tv_backdrop_blur_light
        TvBackdropBlurLevel.Medium -> Lang.settings_theme_tv_backdrop_blur_medium
        TvBackdropBlurLevel.Strong -> Lang.settings_theme_tv_backdrop_blur_strong
    }

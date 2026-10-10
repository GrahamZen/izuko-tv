/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.data.models.preference.NoticeSoundKind
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.preference.SeekPreviewDisplay
import me.him188.ani.app.data.models.preference.SkipOpEdMode
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvEpisodeSpecialsPlacement
import me.him188.ani.app.data.models.preference.TvExitBehavior
import me.him188.ani.app.data.models.preference.TvLongPressAction
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.data.models.preference.TvScheduleLayout
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.data.models.preference.TvTitleLogoLanguage
import me.him188.ani.app.data.models.preference.TvVisualEffectsLevel
import me.him188.ani.app.data.models.preference.VideoEnhancementDefaultMode
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.SettingsTab
import kotlinx.coroutines.flow.map
import me.him188.ani.app.platform.AppLocales
import me.him188.ani.app.ui.foundation.lan.TvRemoteSettingsBridge
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.SupportedLocales
import me.him188.ani.app.ui.lang.main_screen_page_cache_management
import me.him188.ani.app.ui.lang.main_screen_page_collection
import me.him188.ani.app.ui.lang.main_screen_page_exploration
import me.him188.ani.app.ui.lang.search_tv_remote_reset
import me.him188.ani.app.ui.lang.search_tv_remote_unavailable
import me.him188.ani.app.ui.lang.settings_app_danmaku_refresh_rate
import me.him188.ani.app.ui.lang.settings_app_danmaku_refresh_rate_description
import me.him188.ani.app.ui.lang.settings_app_initial_page
import me.him188.ani.app.ui.lang.settings_app_initial_page_description
import me.him188.ani.app.ui.lang.settings_app_language
import me.him188.ani.app.ui.lang.settings_app_language_system
import me.him188.ani.app.ui.lang.settings_app_not_show_done_and_dropped_subjects
import me.him188.ani.app.ui.lang.settings_app_nsfw_blur
import me.him188.ani.app.ui.lang.settings_app_nsfw_content
import me.him188.ani.app.ui.lang.settings_app_nsfw_content_description
import me.him188.ani.app.ui.lang.settings_app_nsfw_display
import me.him188.ani.app.ui.lang.settings_app_nsfw_hide
import me.him188.ani.app.ui.lang.settings_app_search
import me.him188.ani.app.ui.lang.settings_app_subject_title
import me.him188.ani.app.ui.lang.settings_app_use_original_title
import me.him188.ani.app.ui.lang.settings_app_use_original_title_description
import me.him188.ani.app.ui.lang.settings_category_app_ui
import me.him188.ani.app.ui.lang.settings_category_data_playback
import me.him188.ani.app.ui.lang.settings_category_network_storage
import me.him188.ani.app.ui.lang.settings_category_others
import me.him188.ani.app.ui.lang.settings_player
import me.him188.ani.app.ui.lang.settings_player_audio_time_stretch
import me.him188.ani.app.ui.lang.settings_player_audio_time_stretch_description
import me.him188.ani.app.ui.lang.settings_player_auto_mark_done
import me.him188.ani.app.ui.lang.settings_player_auto_play_next
import me.him188.ani.app.ui.lang.settings_player_auto_skip_op_ed
import me.him188.ani.app.ui.lang.settings_player_auto_skip_op_ed_description
import me.him188.ani.app.ui.lang.settings_player_auto_switch_media_on_error
import me.him188.ani.app.ui.lang.settings_player_disk_cache
import me.him188.ani.app.ui.lang.settings_player_disk_cache_description
import me.him188.ani.app.ui.lang.settings_player_enable_regex_filter
import me.him188.ani.app.ui.lang.settings_player_frame_preview
import me.him188.ani.app.ui.lang.settings_player_frame_preview_description
import me.him188.ani.app.ui.lang.settings_player_hide_selector_on_select
import me.him188.ani.app.ui.lang.settings_player_hls_ad_filter
import me.him188.ani.app.ui.lang.settings_player_hls_ad_filter_description
import me.him188.ani.app.ui.lang.settings_player_idle_progress_bar
import me.him188.ani.app.ui.lang.settings_player_idle_progress_bar_description
import me.him188.ani.app.ui.lang.settings_player_idle_progress_bar_off
import me.him188.ani.app.ui.lang.settings_player_long_press_fast_forward_speed
import me.him188.ani.app.ui.lang.settings_player_long_press_fast_forward_speed_description
import me.him188.ani.app.ui.lang.settings_player_notice_sound
import me.him188.ani.app.ui.lang.settings_player_notice_sound_alert
import me.him188.ani.app.ui.lang.settings_player_notice_sound_confirm
import me.him188.ani.app.ui.lang.settings_player_notice_sound_delete
import me.him188.ani.app.ui.lang.settings_player_notice_sound_description
import me.him188.ani.app.ui.lang.settings_player_notice_sound_none
import me.him188.ani.app.ui.lang.settings_player_notice_sound_space
import me.him188.ani.app.ui.lang.settings_player_notice_sound_standard
import me.him188.ani.app.ui.lang.settings_player_notice_sound_tick
import me.him188.ani.app.ui.lang.settings_player_op_ed_skip_duration
import me.him188.ani.app.ui.lang.settings_player_op_ed_skip_duration_description
import me.him188.ani.app.ui.lang.settings_player_op_ed_skip_duration_seconds
import me.him188.ani.app.ui.lang.settings_player_pause_on_edit_danmaku
import me.him188.ani.app.ui.lang.settings_player_pause_on_scrub
import me.him188.ani.app.ui.lang.settings_player_pause_on_scrub_description
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display_description
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display_full_screen
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display_window
import me.him188.ani.app.ui.lang.settings_player_skip_op_ed_auto
import me.him188.ani.app.ui.lang.settings_player_skip_op_ed_auto_then_manual
import me.him188.ani.app.ui.lang.settings_player_skip_op_ed_manual
import me.him188.ani.app.ui.lang.settings_player_skip_op_ed_off
import me.him188.ani.app.ui.lang.settings_player_start_playback_speed
import me.him188.ani.app.ui.lang.settings_player_start_playback_speed_description
import me.him188.ani.app.ui.lang.settings_player_start_playback_speed_remember
import me.him188.ani.app.ui.lang.settings_player_up_next_tip
import me.him188.ani.app.ui.lang.settings_player_up_next_tip_description
import me.him188.ani.app.ui.lang.settings_player_up_next_tip_off
import me.him188.ani.app.ui.lang.settings_player_up_next_tip_seconds
import me.him188.ani.app.ui.lang.settings_player_video_enhancement_confirm_button
import me.him188.ani.app.ui.lang.settings_player_video_enhancement_confirm_text
import me.him188.ani.app.ui.lang.settings_player_video_enhancement_confirm_title
import me.him188.ani.app.ui.lang.settings_player_video_enhancement_default
import me.him188.ani.app.ui.lang.settings_player_video_enhancement_default_description
import me.him188.ani.app.ui.lang.settings_tab_about
import me.him188.ani.app.ui.lang.settings_tab_account
import me.him188.ani.app.ui.lang.settings_tab_appearance
import me.him188.ani.app.ui.lang.settings_tab_bt
import me.him188.ani.app.ui.lang.settings_tab_debug
import me.him188.ani.app.ui.lang.settings_tab_log
import me.him188.ani.app.ui.lang.settings_tab_media_selector
import me.him188.ani.app.ui.lang.settings_tab_media_source
import me.him188.ani.app.ui.lang.settings_tab_player
import me.him188.ani.app.ui.lang.settings_tab_proxy
import me.him188.ani.app.ui.lang.settings_tab_storage
import me.him188.ani.app.ui.lang.settings_tab_theme
import me.him188.ani.app.ui.lang.settings_tab_update
import me.him188.ani.app.ui.lang.settings_theme_dynamic_colors
import me.him188.ani.app.ui.lang.settings_theme_dynamic_colors_description
import me.him188.ani.app.ui.lang.settings_theme_dynamic_subject
import me.him188.ani.app.ui.lang.settings_theme_dynamic_subject_description
import me.him188.ani.app.ui.lang.settings_theme_mode_auto
import me.him188.ani.app.ui.lang.settings_theme_mode_black
import me.him188.ani.app.ui.lang.settings_theme_mode_dark
import me.him188.ani.app.ui.lang.settings_theme_mode_light
import me.him188.ani.app.ui.lang.settings_theme_palette
import me.him188.ani.app.ui.lang.settings_theme_title
import me.him188.ani.app.ui.lang.settings_theme_tv_back_long_press
import me.him188.ani.app.ui.lang.settings_theme_tv_back_long_press_description
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_both
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_description
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_light
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_medium
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_none
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_pair
import me.him188.ani.app.ui.lang.settings_theme_tv_backdrop_blur_strong
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_after_main
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_by_number
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_description
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_hidden
import me.him188.ani.app.ui.lang.settings_theme_tv_episodes
import me.him188.ani.app.ui.lang.settings_theme_tv_exit_behavior
import me.him188.ani.app.ui.lang.settings_theme_tv_exit_behavior_description
import me.him188.ani.app.ui.lang.settings_theme_tv_exit_direct
import me.him188.ani.app.ui.lang.settings_theme_tv_exit_double
import me.him188.ani.app.ui.lang.settings_theme_tv_exit_panel
import me.him188.ani.app.ui.lang.settings_theme_tv_long_press_none
import me.him188.ani.app.ui.lang.settings_theme_tv_long_press_panel
import me.him188.ani.app.ui.lang.settings_theme_tv_long_press_resume
import me.him188.ani.app.ui.lang.settings_theme_tv_play_long_press
import me.him188.ani.app.ui.lang.settings_theme_tv_play_long_press_description
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_description
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_details
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_hero
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_play
import me.him188.ani.app.ui.lang.settings_theme_tv_remote_reset
import me.him188.ani.app.ui.lang.settings_theme_tv_remote_reset_confirm
import me.him188.ani.app.ui.lang.settings_theme_tv_remote_reset_description
import me.him188.ani.app.ui.lang.settings_theme_tv_remote_show_on_launch
import me.him188.ani.app.ui.lang.settings_theme_tv_remote_show_on_launch_description
import me.him188.ani.app.ui.lang.settings_theme_tv_retain_playback_session
import me.him188.ani.app.ui.lang.settings_theme_tv_retain_playback_session_description
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_grid
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_layout
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_layout_description
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_timeline
import me.him188.ani.app.ui.lang.settings_theme_tv_schedule_upstream
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
import me.him188.ani.app.ui.lang.settings_theme_tv_ui_scale
import me.him188.ani.app.ui.lang.settings_theme_tv_ui_scale_description
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_balanced
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_description
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_full
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_smooth
import me.him188.ani.app.ui.lang.tv_settings_dark_mode
import me.him188.ani.app.ui.lang.tv_settings_dark_mode_description
import me.him188.ani.app.ui.lang.tv_settings_palette_color
import me.him188.ani.app.ui.lang.tv_settings_palette_default
import me.him188.ani.app.ui.lang.tv_settings_qr_console
import me.him188.ani.app.ui.lang.video_player_off
import me.him188.ani.app.ui.lang.video_player_performance
import me.him188.ani.app.ui.lang.video_player_quality
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.theme.DefaultSeedColor
import me.him188.ani.app.ui.theme.themeColorOptions
import me.him188.ani.app.utils.formatSpeedValue
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** 电视设置页用到的几份设置. */
object TvSettingKeys {
    val ui = TvSettingKey.Stored("ui") { it.uiSettings }
    val theme = TvSettingKey.Stored("theme") { it.themeSettings }
    val player = TvSettingKey.Stored("player") { it.videoScaffoldConfig }
    val danmakuFilter = TvSettingKey.Stored("danmakuFilter") { it.danmakuFilterConfig }
    val debug = TvSettingKey.Stored("debug") { it.debugSettings }

    /** 应用语言的 BCP 47 标签 (多个时取第一个); 空 = 跟随系统. */
    val language = TvSettingKey.Platform("language") { deps ->
        AppLocales.get(deps.context).toLanguageTags().substringBefore(',')
    }

    /** 屏幕支持的显示模式 (Android 11 起才能取到; 更早为空). */
    val displayModes = TvSettingKey.Platform("displayModes") { deps ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            deps.context.display?.supportedModes.orEmpty().map { TvDisplayMode(it.modeId, it.refreshRate.roundToInt()) }
        } else {
            emptyList()
        }
    }

    /** 弹幕正则过滤规则 (逐条). */
    val danmakuRules = TvSettingKey.Live("danmakuRules") { GlobalKoin.get<DanmakuRegexFilterRepository>().flow }

    /** Web 控制台的地址 (说明栏里「在手机上设置」的二维码用; 没连局域网时没有). */
    val consoleUrl = TvSettingKey.Live("consoleUrl") { TvRemoteControl.url.map { TvConsoleUrl(it) } }
}

/** Web 控制台的地址 (实时值不能是 null, 包一层). */
data class TvConsoleUrl(val url: String?)

/**
 * 说明栏里打开 Web 控制台的二维码, 带上 [fragment] 让网页直接停在相关的那一页 (如 `#settings/network`、`#sources`).
 * 控制台没在运行 (没连局域网) 时只写一句不可用.
 */
internal fun consoleQr(values: TvSettingsValues, fragment: String): TvQr {
    val url = values[TvSettingKeys.consoleUrl]?.url ?: return TvQr(null, status = tvText(Lang.search_tv_remote_unavailable))
    return TvQr("$url$fragment", caption = tvText(Lang.tv_settings_qr_console))
}

/** 一个显示模式: [id] = 0 表示「自动」(不指定). */
data class TvDisplayMode(val id: Int, val refreshRate: Int)

/**
 * 电视设置页的全部设置. 加一项就在对应分类里加一行. 分类与先后同原来的设置页 (左栏分组一样); 要打字才能设的 (地址、账号密码、规则文本)
 * 不放这里, 在手机 Web 控制台里改. 设置备份 (剪贴板复制 / 导入) 在电视上用不了, 不列 (控制台里能导出导入文件).
 */
val TvSettingsCatalogEntries: List<TvSettingsCategory> = tvSettingsCatalog {
    category(SettingsTab.PROFILE, Lang.settings_tab_account) { accountItems() }

    section(Lang.settings_category_app_ui)
    category(SettingsTab.APPEARANCE, Lang.settings_tab_appearance) { appearanceItems() }
    category(SettingsTab.THEME, Lang.settings_tab_theme) { themeItems() }

    section(Lang.settings_category_data_playback)
    category(SettingsTab.PLAYER, Lang.settings_tab_player) { playerItems() }
    category(SettingsTab.MEDIA_SOURCE, Lang.settings_tab_media_source) { mediaSourceItems() }
    category(SettingsTab.MEDIA_SELECTOR, Lang.settings_tab_media_selector) { mediaSelectorItems() }

    section(Lang.settings_category_network_storage)
    category(SettingsTab.PROXY, Lang.settings_tab_proxy) { networkItems() }
    category(SettingsTab.BT, Lang.settings_tab_bt) { btItems() }
    category(SettingsTab.STORAGE, Lang.settings_tab_storage) { storageItems() }

    section(Lang.settings_category_others)
    category(SettingsTab.UPDATE, Lang.settings_tab_update) { updateItems() }
    category(SettingsTab.LOG, Lang.settings_tab_log) { logItems() }
    category(SettingsTab.ABOUT, Lang.settings_tab_about) { aboutItems() }
    category(SettingsTab.DEBUG, Lang.settings_tab_debug, visible = { it[TvSettingKeys.debug]?.enabled == true }) { debugItems() }
}

private fun TvSettingsItemsBuilder.appearanceItems() {
    val theme = TvSettingKeys.theme
    val ui = TvSettingKeys.ui
    // 放在最前: 同原来的设置页 (界面缩放改了整页重排)
    choice(
        theme, Lang.settings_theme_tv_ui_scale, Lang.settings_theme_tv_ui_scale_description,
        options = { UI_SCALE_PERCENTS },
        label = { tvText("$it%") },
        read = { (it.effectiveUiScale * 100).roundToInt().roundToStep(UI_SCALE_STEP_PERCENT) },
        write = { copy(uiScale = it / 100f) },
    )
    choice(
        theme, Lang.settings_theme_tv_exit_behavior, Lang.settings_theme_tv_exit_behavior_description,
        options = { TvExitBehavior.entries },
        label = {
            tvText(
                when (it) {
                    TvExitBehavior.Direct -> Lang.settings_theme_tv_exit_direct
                    TvExitBehavior.Panel -> Lang.settings_theme_tv_exit_panel
                    TvExitBehavior.DoubleBack -> Lang.settings_theme_tv_exit_double
                },
            )
        },
        read = { it.exitBehavior },
        // 写 tvExitBehavior 而不是老的布尔: 一旦显式选过, 读取就不再看那个布尔 (见 exitBehavior)
        write = { copy(tvExitBehavior = it) },
    )
    choice(
        theme, Lang.settings_theme_tv_back_long_press, Lang.settings_theme_tv_back_long_press_description,
        // 返回键是精简遥控器唯一够得到面板的入口, 所以三档全给 (含「不做任何事」)
        options = { TvLongPressAction.entries },
        label = { tvText(longPressLabel(it)) },
        read = { it.tvBackLongPress },
        write = { copy(tvBackLongPress = it) },
    )
    choice(
        theme, Lang.settings_theme_tv_play_long_press, Lang.settings_theme_tv_play_long_press_description,
        // 播放键不给「不做任何事」: 那样这个手势就彻底空了
        options = { TvLongPressAction.entries.filter { it != TvLongPressAction.None } },
        label = { tvText(longPressLabel(it)) },
        read = { it.tvPlayLongPress },
        write = { copy(tvPlayLongPress = it) },
    )
    toggle(
        theme, Lang.settings_theme_tv_remote_show_on_launch, Lang.settings_theme_tv_remote_show_on_launch_description,
        read = { it.tvRemoteShowOnLaunch },
        write = { copy(tvRemoteShowOnLaunch = it) },
    )
    run(
        Lang.settings_theme_tv_remote_reset, Lang.settings_theme_tv_remote_reset_description,
        confirm = TvSettingsConfirm(
            title = null,
            text = tvText(Lang.settings_theme_tv_remote_reset_confirm),
            confirmLabel = tvText(Lang.search_tv_remote_reset),
        ),
        visible = { TvRemoteSettingsBridge.resetAddress != null },
    ) { TvRemoteSettingsBridge.resetAddress?.invoke() }
    choiceOf(
        listOf(TvSettingKeys.language),
        Lang.settings_app_language,
        options = { listOf("") + SupportedLocales.map { it.toLanguageTag() } },
        label = { tag -> if (tag.isEmpty()) tvText(Lang.settings_app_language_system) else tvText(localeName(tag)) },
        read = { it[TvSettingKeys.language] },
        write = { tag ->
            listOf(
                TvSettingsEdit.Run { env ->
                    val locales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                    if (AppLocales.get(env.context).toLanguageTags() != locales.toLanguageTags()) {
                        AppLocales.set(env.context, locales)
                    }
                },
            )
        },
    )
    // 管所有页面与 Web 控制台 (见 SubjectNsfw / NsfwPolicy); 存在 searchSettings 里是沿用的字段位置
    choice(
        ui, Lang.settings_app_nsfw_content, Lang.settings_app_nsfw_content_description,
        options = { NsfwMode.entries },
        label = {
            tvText(
                when (it) {
                    NsfwMode.HIDE -> Lang.settings_app_nsfw_hide
                    NsfwMode.BLUR -> Lang.settings_app_nsfw_blur
                    NsfwMode.DISPLAY -> Lang.settings_app_nsfw_display
                },
            )
        },
        read = { it.searchSettings.nsfwMode },
        write = { copy(searchSettings = searchSettings.copy(nsfwMode = it)) },
    )
    choice(
        ui, Lang.settings_app_initial_page, Lang.settings_app_initial_page_description,
        options = { MainScreenPage.visibleEntries },
        label = {
            tvText(
                when (it) {
                    MainScreenPage.Exploration -> Lang.main_screen_page_exploration
                    MainScreenPage.Collection -> Lang.main_screen_page_collection
                    MainScreenPage.CacheManagement -> Lang.main_screen_page_cache_management
                },
            )
        },
        read = { it.mainSceneInitialPage },
        write = { copy(mainSceneInitialPage = it) },
    )

    header(Lang.settings_app_search)
    toggle(
        ui, Lang.settings_app_not_show_done_and_dropped_subjects,
        read = { it.searchSettings.ignoreDoneAndDroppedSubjects },
        write = { copy(searchSettings = searchSettings.copy(ignoreDoneAndDroppedSubjects = it)) },
    )

    header(Lang.settings_app_subject_title)
    toggle(
        ui, Lang.settings_app_use_original_title, Lang.settings_app_use_original_title_description,
        read = { it.subjectAppearance.useOriginalTitle },
        write = { copy(subjectAppearance = subjectAppearance.copy(useOriginalTitle = it)) },
    )

    header(Lang.settings_theme_tv_episodes)
    choice(
        theme, Lang.settings_theme_tv_episode_specials, Lang.settings_theme_tv_episode_specials_description,
        options = { TvEpisodeSpecialsPlacement.entries },
        label = {
            tvText(
                when (it) {
                    TvEpisodeSpecialsPlacement.Hidden -> Lang.settings_theme_tv_episode_specials_hidden
                    TvEpisodeSpecialsPlacement.AfterMain -> Lang.settings_theme_tv_episode_specials_after_main
                    TvEpisodeSpecialsPlacement.ByNumber -> Lang.settings_theme_tv_episode_specials_by_number
                },
            )
        },
        read = { it.tvEpisodeSpecials },
        write = { copy(tvEpisodeSpecials = it) },
    )
}

private fun TvSettingsItemsBuilder.themeItems() {
    val theme = TvSettingKeys.theme
    header(Lang.settings_theme_title)
    // 深色与纯黑合成一项: 纯黑只在深色下起作用, 单拎一个开关时三种组合里有一种是空的
    choice(
        theme, Lang.tv_settings_dark_mode, Lang.tv_settings_dark_mode_description,
        options = { DARK_MODES },
        label = {
            tvText(
                when (it) {
                    TvDarkModeOption.Light -> Lang.settings_theme_mode_light
                    TvDarkModeOption.Dark -> Lang.settings_theme_mode_dark
                    TvDarkModeOption.Black -> Lang.settings_theme_mode_black
                    TvDarkModeOption.Auto -> Lang.settings_theme_mode_auto
                },
            )
        },
        read = { it.darkModeOption() },
        // 浅色与自动不改 useBlackBackground (自动在系统深色时照它画)
        write = {
            when (it) {
                TvDarkModeOption.Light -> copy(darkMode = DarkMode.LIGHT)
                TvDarkModeOption.Dark -> copy(darkMode = DarkMode.DARK, useBlackBackground = false)
                TvDarkModeOption.Black -> copy(darkMode = DarkMode.DARK, useBlackBackground = true)
                TvDarkModeOption.Auto -> copy(darkMode = DarkMode.AUTO)
            }
        },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        toggle(
            theme, Lang.settings_theme_dynamic_colors, Lang.settings_theme_dynamic_colors_description,
            read = { it.useDynamicTheme },
            write = { copy(useDynamicTheme = it) },
        )
    }
    toggle(
        theme, Lang.settings_theme_dynamic_subject, Lang.settings_theme_dynamic_subject_description,
        read = { it.useDynamicSubjectPageTheme },
        write = { copy(useDynamicSubjectPageTheme = it) },
    )
    // 三版都留着可选 (见 TvScheduleLayout): 改版换掉的东西未必人人都想要
    choice(
        theme, Lang.settings_theme_tv_schedule_layout, Lang.settings_theme_tv_schedule_layout_description,
        options = { TvScheduleLayout.entries },
        label = {
            tvText(
                when (it) {
                    TvScheduleLayout.Upstream -> Lang.settings_theme_tv_schedule_upstream
                    TvScheduleLayout.Grid -> Lang.settings_theme_tv_schedule_grid
                    TvScheduleLayout.Timeline -> Lang.settings_theme_tv_schedule_timeline
                },
            )
        },
        read = { it.tvScheduleLayout },
        write = { copy(tvScheduleLayout = it) },
    )
    choice(
        theme, Lang.settings_theme_tv_poster_confirm, Lang.settings_theme_tv_poster_confirm_description,
        options = { TvPosterConfirmAction.entries },
        label = {
            tvText(
                when (it) {
                    TvPosterConfirmAction.Hero -> Lang.settings_theme_tv_poster_confirm_hero
                    TvPosterConfirmAction.Play -> Lang.settings_theme_tv_poster_confirm_play
                    TvPosterConfirmAction.Details -> Lang.settings_theme_tv_poster_confirm_details
                },
            )
        },
        read = { it.tvPosterConfirm },
        write = { copy(tvPosterConfirm = it) },
    )
    // 海报墙与详情页两处按搭配一起选; 存着的搭配不在预设里 (以前分开选过) 时也列在最后, 不改它就一直是它
    choice(
        theme, Lang.settings_theme_tv_backdrop_blur, Lang.settings_theme_tv_backdrop_blur_description,
        options = { settings ->
            val current = settings.backdropBlurCombo()
            if (current in TV_BACKDROP_BLUR_COMBOS) TV_BACKDROP_BLUR_COMBOS else TV_BACKDROP_BLUR_COMBOS + current
        },
        label = { it.label() },
        read = { it.backdropBlurCombo() },
        write = { copy(tvWallBackdropBlur = it.wall, tvDetailsBackdropBlur = it.details) },
    )
    choice(
        theme, Lang.settings_theme_tv_title_logo, Lang.settings_theme_tv_title_logo_description,
        options = { TvTitleLogoDisplay.entries },
        label = {
            tvText(
                when (it) {
                    TvTitleLogoDisplay.Auto -> Lang.settings_theme_tv_title_logo_auto
                    TvTitleLogoDisplay.TextWhenUnreadable -> Lang.settings_theme_tv_title_logo_text
                    TvTitleLogoDisplay.Original -> Lang.settings_theme_tv_title_logo_original
                    TvTitleLogoDisplay.Off -> Lang.settings_theme_tv_title_logo_off
                },
            )
        },
        read = { it.tvTitleLogoDisplay },
        write = { copy(tvTitleLogoDisplay = it) },
    )
    choice(
        theme, Lang.settings_theme_tv_title_logo_language, Lang.settings_theme_tv_title_logo_language_description,
        options = { TvTitleLogoLanguage.entries },
        label = {
            tvText(
                when (it) {
                    TvTitleLogoLanguage.Original -> Lang.settings_theme_tv_title_logo_language_original
                    TvTitleLogoLanguage.AppLanguage -> Lang.settings_theme_tv_title_logo_language_app
                },
            )
        },
        read = { it.tvTitleLogoLanguage },
        write = { copy(tvTitleLogoLanguage = it) },
        visible = { it.tvTitleLogoDisplay != TvTitleLogoDisplay.Off },
    )
    // 写 tvVisualEffects 而不是老的布尔: 一旦显式选过, 读取就不再看那个布尔
    choice(
        theme, Lang.settings_theme_tv_visual_effects, Lang.settings_theme_tv_visual_effects_description,
        options = { TvVisualEffectsLevel.entries },
        label = {
            tvText(
                when (it) {
                    TvVisualEffectsLevel.Smooth -> Lang.settings_theme_tv_visual_effects_smooth
                    TvVisualEffectsLevel.Balanced -> Lang.settings_theme_tv_visual_effects_balanced
                    TvVisualEffectsLevel.Full -> Lang.settings_theme_tv_visual_effects_full
                },
            )
        },
        read = { it.visualEffects },
        write = { copy(tvVisualEffects = it) },
    )
    // 调色板: 选了就不再用动态取色 (同原来的色块)
    choice(
        theme, Lang.settings_theme_palette,
        options = { PALETTE },
        label = { color ->
            // 默认色单独叫「默认」, 其余按先后编号 (不把默认色那一格算进去)
            val index = PALETTE.filter { it != DefaultSeedColor.value }.indexOf(color)
            if (color == DefaultSeedColor.value) tvText(Lang.tv_settings_palette_default) else tvText(Lang.tv_settings_palette_color, index + 1)
        },
        read = { it.seedColorValue.takeUnless { _ -> it.useDynamicTheme } },
        write = { copy(seedColorValue = it, useDynamicTheme = false) },
        swatch = { Color(it).toArgb() },
    )
}

private fun TvSettingsItemsBuilder.playerItems() {
    val player = TvSettingKeys.player
    val theme = TvSettingKeys.theme
    header(Lang.settings_player)
    // 选「关闭」以外的档位先问 (很多电视与盒子上黑屏有声、画面卡住或解码失败连着换源); 改回「关闭」直接写
    choice(
        player, Lang.settings_player_video_enhancement_default, Lang.settings_player_video_enhancement_default_description,
        options = { listOf(VideoEnhancementDefaultMode.PERFORMANCE, VideoEnhancementDefaultMode.QUALITY, VideoEnhancementDefaultMode.OFF) },
        label = {
            tvText(
                when (it) {
                    VideoEnhancementDefaultMode.OFF -> Lang.video_player_off
                    VideoEnhancementDefaultMode.PERFORMANCE -> Lang.video_player_performance
                    VideoEnhancementDefaultMode.QUALITY -> Lang.video_player_quality
                },
            )
        },
        read = { it.videoEnhancementDefaultMode },
        write = { copy(videoEnhancementDefaultMode = it) },
        confirm = { mode ->
            if (mode == VideoEnhancementDefaultMode.OFF) {
                null
            } else {
                TvSettingsConfirm(
                    title = tvText(Lang.settings_player_video_enhancement_confirm_title),
                    text = tvText(Lang.settings_player_video_enhancement_confirm_text),
                    confirmLabel = tvText(Lang.settings_player_video_enhancement_confirm_button),
                    destructive = true,
                )
            }
        },
    )
    toggle(
        theme, Lang.settings_theme_tv_retain_playback_session, Lang.settings_theme_tv_retain_playback_session_description,
        read = { it.tvRetainPlaybackSession },
        write = { copy(tvRetainPlaybackSession = it) },
    )
    toggle(
        TvSettingKeys.danmakuFilter, Lang.settings_player_enable_regex_filter,
        read = { it.enableRegexFilter },
        write = { copy(enableRegexFilter = it) },
    )
    toggle(
        player, Lang.settings_player_pause_on_edit_danmaku,
        read = { it.pauseVideoOnEditDanmaku },
        write = { copy(pauseVideoOnEditDanmaku = it) },
    )
    toggle(player, Lang.settings_player_auto_mark_done, read = { it.autoMarkDone }, write = { copy(autoMarkDone = it) })
    toggle(
        player, Lang.settings_player_hide_selector_on_select,
        read = { it.hideSelectorOnSelect },
        write = { copy(hideSelectorOnSelect = it) },
    )
    toggle(player, Lang.settings_player_auto_play_next, read = { it.autoPlayNext }, write = { copy(autoPlayNext = it) })
    // 最左一档 = 不显示, 往右依次 2~8dp; 写的时候顺手把旧开关置真 (从此这一项说了算)
    choice(
        player, Lang.settings_player_idle_progress_bar, Lang.settings_player_idle_progress_bar_description,
        options = { listOf(0) + VideoScaffoldConfig.IDLE_PROGRESS_BAR_HEIGHT_RANGE.toList() },
        label = { if (it <= 0) tvText(Lang.settings_player_idle_progress_bar_off) else tvText("$it dp") },
        read = { it.effectiveIdleProgressBarHeightDp },
        write = { copy(showIdleProgressBar = true, idleProgressBarHeightDp = it) },
    )
    choice(
        player, Lang.settings_player_up_next_tip, Lang.settings_player_up_next_tip_description,
        options = {
            val range = VideoScaffoldConfig.UP_NEXT_TIP_LEAD_SECONDS_RANGE
            (range.first..range.last step VideoScaffoldConfig.UP_NEXT_TIP_LEAD_SECONDS_STEP).toList()
        },
        label = { if (it <= 0) tvText(Lang.settings_player_up_next_tip_off) else tvText(Lang.settings_player_up_next_tip_seconds, it) },
        read = { it.upNextTipLeadSeconds },
        write = { copy(upNextTipLeadSeconds = it) },
    )
    choice(
        player, Lang.settings_player_auto_skip_op_ed, Lang.settings_player_auto_skip_op_ed_description,
        options = { SkipOpEdMode.entries },
        label = {
            tvText(
                when (it) {
                    SkipOpEdMode.AUTO -> Lang.settings_player_skip_op_ed_auto
                    SkipOpEdMode.AUTO_THEN_MANUAL -> Lang.settings_player_skip_op_ed_auto_then_manual
                    SkipOpEdMode.MANUAL -> Lang.settings_player_skip_op_ed_manual
                    SkipOpEdMode.OFF -> Lang.settings_player_skip_op_ed_off
                },
            )
        },
        read = { it.effectiveSkipOpEdMode },
        write = { copy(skipOpEdMode = it) },
    )
    choice(
        player, Lang.settings_player_op_ed_skip_duration, Lang.settings_player_op_ed_skip_duration_description,
        options = { listOf(80, 85, 90) },
        label = { tvText(Lang.settings_player_op_ed_skip_duration_seconds, it) },
        read = { it.opEdSkipDuration.inWholeSeconds.toInt() },
        write = { copy(opEdSkipDuration = it.seconds) },
    )
    toggle(
        player, Lang.settings_player_auto_switch_media_on_error,
        read = { it.autoSwitchMediaOnPlayerError },
        write = { copy(autoSwitchMediaOnPlayerError = it) },
    )
    toggle(
        player, Lang.settings_player_audio_time_stretch, Lang.settings_player_audio_time_stretch_description,
        read = { it.enableHighQualityAudioTimeStretch },
        write = { copy(enableHighQualityAudioTimeStretch = it) },
    )
    toggle(
        player, Lang.settings_player_disk_cache, Lang.settings_player_disk_cache_description,
        read = { it.enablePlaybackDiskCache },
        write = { copy(enablePlaybackDiskCache = it) },
    )
    toggle(
        player, Lang.settings_player_hls_ad_filter, Lang.settings_player_hls_ad_filter_description,
        read = { it.enableHlsAdFiltering },
        write = { copy(enableHlsAdFiltering = it) },
    )
    toggle(
        player, Lang.settings_player_frame_preview, Lang.settings_player_frame_preview_description,
        read = { it.enableFramePreview },
        write = { copy(enableFramePreview = it) },
    )
    toggle(
        player, Lang.settings_player_pause_on_scrub, Lang.settings_player_pause_on_scrub_description,
        read = { it.pauseVideoOnScrub },
        write = { copy(pauseVideoOnScrub = it) },
    )
    choice(
        player, Lang.settings_player_seek_preview_display, Lang.settings_player_seek_preview_display_description,
        options = { SeekPreviewDisplay.entries },
        label = {
            tvText(
                when (it) {
                    SeekPreviewDisplay.WINDOW -> Lang.settings_player_seek_preview_display_window
                    SeekPreviewDisplay.FULL_SCREEN -> Lang.settings_player_seek_preview_display_full_screen
                },
            )
        },
        read = { it.seekPreviewDisplay },
        write = { copy(seekPreviewDisplay = it) },
    )
    // 进播放器时的倍速: 第一档 = 记住上次 (在播放器里调过的倍速跨剧集、重启保持), 往后是固定的起始倍速 (单位 0.01x)
    choice(
        player, Lang.settings_player_start_playback_speed, Lang.settings_player_start_playback_speed_description,
        options = { listOf(0) + SPEED_HUNDREDTHS },
        label = { if (it == 0) tvText(Lang.settings_player_start_playback_speed_remember) else tvText(speedLabel(it)) },
        read = { if (it.rememberPlaybackSpeed) 0 else speedHundredths(it.playbackSpeed) },
        write = { if (it == 0) copy(rememberPlaybackSpeed = true) else copy(rememberPlaybackSpeed = false, playbackSpeed = it / 100f) },
    )
    choice(
        player, Lang.settings_player_long_press_fast_forward_speed, Lang.settings_player_long_press_fast_forward_speed_description,
        options = { SPEED_HUNDREDTHS },
        label = { tvText(speedLabel(it)) },
        read = { speedHundredths(it.fastForwardSpeed) },
        write = { copy(fastForwardSpeed = it / 100f) },
    )
    // 播放时屏幕刷新率 (DisplayModeEffect): 第一档自动 = 不指定; 选项是屏幕支持的显示模式
    choiceOf(
        listOf(player, TvSettingKeys.displayModes),
        Lang.settings_app_danmaku_refresh_rate, Lang.settings_app_danmaku_refresh_rate_description,
        options = { values -> listOf(TvDisplayMode(0, 0)) + values[TvSettingKeys.displayModes].orEmpty() },
        label = { if (it.id == 0) tvText(Lang.settings_theme_mode_auto) else tvText(it.refreshRate.toString()) },
        read = { values ->
            val id = values[player]?.displayModeId ?: return@choiceOf null
            values[TvSettingKeys.displayModes]?.firstOrNull { it.id == id } ?: TvDisplayMode(0, 0)
        },
        write = { mode -> listOf(TvSettingsEdit.Update(player) { it.copy(displayModeId = mode.id) }) },
        visible = { !it[TvSettingKeys.displayModes].isNullOrEmpty() },
    )
    // 选完立刻响一次: 这些都是系统按键音, 光看名字听不出是什么
    choice(
        theme, Lang.settings_player_notice_sound, Lang.settings_player_notice_sound_description,
        options = { NoticeSoundKind.entries },
        label = {
            tvText(
                when (it) {
                    NoticeSoundKind.None -> Lang.settings_player_notice_sound_none
                    NoticeSoundKind.Confirm -> Lang.settings_player_notice_sound_confirm
                    NoticeSoundKind.Standard -> Lang.settings_player_notice_sound_standard
                    NoticeSoundKind.Alert -> Lang.settings_player_notice_sound_alert
                    NoticeSoundKind.Tick -> Lang.settings_player_notice_sound_tick
                    NoticeSoundKind.Delete -> Lang.settings_player_notice_sound_delete
                    NoticeSoundKind.Space -> Lang.settings_player_notice_sound_space
                },
            )
        },
        read = { it.tvNoticeSound },
        write = { copy(tvNoticeSound = it) },
        after = { kind -> TvSettingsEdit.Run { env -> env.playNoticeSound(kind) } },
    )

    danmakuRuleItems()
}

// ---- 档位与选项名 ----

private const val UI_SCALE_STEP_PERCENT = 10

/** 界面缩放的档位 (百分比): 50%~250%, 每档 10%. */
private val UI_SCALE_PERCENTS: List<Int> =
    ((ThemeSettings.UI_SCALE_MIN * 100).roundToInt()..(ThemeSettings.UI_SCALE_MAX * 100).roundToInt() step UI_SCALE_STEP_PERCENT).toList()

private fun Int.roundToStep(step: Int): Int = ((this + step / 2) / step) * step

/** 倍速档位 (单位 0.01x): 播放器支持的全范围, 每档 0.25x. */
private val SPEED_HUNDREDTHS: List<Int> =
    ((VideoScaffoldConfig.MIN_SUPPORTED_PLAYBACK_SPEED * 100).roundToInt()..(VideoScaffoldConfig.MAX_SUPPORTED_PLAYBACK_SPEED * 100).roundToInt() step 25)
        .toList()

private fun speedHundredths(speed: Float): Int = ((speed * 100 / 25f).roundToInt() * 25)
    .coerceIn(SPEED_HUNDREDTHS.first(), SPEED_HUNDREDTHS.last())

private fun speedLabel(hundredths: Int): String = "${(hundredths / 100f).formatSpeedValue()}x"

private fun longPressLabel(action: TvLongPressAction) = when (action) {
    TvLongPressAction.Panel -> Lang.settings_theme_tv_long_press_panel
    TvLongPressAction.Resume -> Lang.settings_theme_tv_long_press_resume
    TvLongPressAction.None -> Lang.settings_theme_tv_long_press_none
}

/** 语言用它自己的写法, 在哪种界面语言下都认得出 (同原来设置页的 renderLocale). */
private fun localeName(tag: String): String = when (tag) {
    "en" -> "English"
    "zh-CN" -> "简体中文"
    "zh-HK" -> "繁體中文(香港)"
    "zh-TW" -> "正體中文"
    else -> tag
}

private enum class TvDarkModeOption { Light, Dark, Black, Auto }

private val DARK_MODES = TvDarkModeOption.entries

private fun ThemeSettings.darkModeOption(): TvDarkModeOption = when (darkMode) {
    DarkMode.LIGHT -> TvDarkModeOption.Light
    DarkMode.DARK -> if (useBlackBackground) TvDarkModeOption.Black else TvDarkModeOption.Dark
    DarkMode.AUTO -> TvDarkModeOption.Auto
}

private val PALETTE: List<ULong> get() = AniThemeDefaults.themeColorOptions.map { it.value }

/** 模糊背景的一种搭配: 海报墙 (连新番时间表) 那一档 [wall] 与详情页那一档 [details]. */
private data class TvBackdropBlurCombo(val wall: TvBackdropBlurLevel, val details: TvBackdropBlurLevel) {
    fun label(): TvText = when {
        wall == TvBackdropBlurLevel.None && details == TvBackdropBlurLevel.None -> tvText(Lang.settings_theme_tv_backdrop_blur_none)
        wall == details -> tvText(Lang.settings_theme_tv_backdrop_blur_both, tvText(wall.labelRes))
        else -> tvText(Lang.settings_theme_tv_backdrop_blur_pair, tvText(wall.labelRes), tvText(details.labelRes))
    }
}

private fun ThemeSettings.backdropBlurCombo() = TvBackdropBlurCombo(tvWallBackdropBlur, tvDetailsBackdropBlur)

/** 设置里给的搭配: 不模糊 / 海报墙重、详情页轻 (默认) / 海报墙中、详情页轻 / 都轻 / 都中 / 都重. */
private val TV_BACKDROP_BLUR_COMBOS = listOf(
    TvBackdropBlurCombo(TvBackdropBlurLevel.None, TvBackdropBlurLevel.None),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Strong, TvBackdropBlurLevel.Light),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Medium, TvBackdropBlurLevel.Light),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Light, TvBackdropBlurLevel.Light),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Medium, TvBackdropBlurLevel.Medium),
    TvBackdropBlurCombo(TvBackdropBlurLevel.Strong, TvBackdropBlurLevel.Strong),
)

private val TvBackdropBlurLevel.labelRes
    get() = when (this) {
        TvBackdropBlurLevel.None -> Lang.settings_theme_tv_backdrop_blur_none
        TvBackdropBlurLevel.Light -> Lang.settings_theme_tv_backdrop_blur_light
        TvBackdropBlurLevel.Medium -> Lang.settings_theme_tv_backdrop_blur_medium
        TvBackdropBlurLevel.Strong -> Lang.settings_theme_tv_backdrop_blur_strong
    }

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.content.Intent
import androidx.core.net.toUri
import kotlinx.coroutines.flow.map
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.QQ_GROUP_ID
import me.him188.ani.app.navigation.QQ_GROUP_JOIN_LINK
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.foundation.Res
import me.him188.ani.app.ui.foundation.bangumi
import me.him188.ani.app.ui.foundation.dandanplay
import me.him188.ani.app.ui.foundation.dmhy
import me.him188.ani.app.ui.foundation.mikan
import me.him188.ani.app.ui.foundation.tmdb
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.acknowledgements
import me.him188.ani.app.ui.lang.developer_list
import me.him188.ani.app.ui.lang.settings_about_chat_groups
import me.him188.ani.app.ui.lang.settings_about_feedback
import me.him188.ani.app.ui.lang.settings_about_qq_group
import me.him188.ani.app.ui.lang.settings_about_source_code
import me.him188.ani.app.ui.lang.settings_about_version
import me.him188.ani.app.ui.lang.settings_acknowledgements_bangumi
import me.him188.ani.app.ui.lang.settings_acknowledgements_bangumi_description
import me.him188.ani.app.ui.lang.settings_acknowledgements_dandanplay
import me.him188.ani.app.ui.lang.settings_acknowledgements_dandanplay_description
import me.him188.ani.app.ui.lang.settings_acknowledgements_dmhy
import me.him188.ani.app.ui.lang.settings_acknowledgements_dmhy_description
import me.him188.ani.app.ui.lang.settings_acknowledgements_mikan
import me.him188.ani.app.ui.lang.settings_acknowledgements_mikan_description
import me.him188.ani.app.ui.lang.settings_acknowledgements_oss_licenses
import me.him188.ani.app.ui.lang.settings_acknowledgements_oss_licenses_description
import me.him188.ani.app.ui.lang.settings_acknowledgements_tmdb
import me.him188.ani.app.ui.lang.settings_acknowledgements_tmdb_description
import me.him188.ani.app.ui.lang.settings_debug_mode_enabled
import me.him188.ani.app.ui.lang.settings_developers_main_contributors
import me.him188.ani.app.ui.lang.settings_developers_outstanding_contributors
import me.him188.ani.app.ui.lang.settings_developers_view_more_on_github
import me.him188.ani.app.ui.lang.settings_help_telegram
import me.him188.ani.app.ui.lang.tv_settings_build_info
import me.him188.ani.app.ui.lang.tv_settings_developers_more
import me.him188.ani.app.ui.lang.tv_settings_link_description
import me.him188.ani.app.ui.lang.tv_settings_link_no_browser
import me.him188.ani.app.ui.lang.tv_settings_oss_license
import me.him188.ani.app.ui.lang.tv_settings_oss_loading
import me.him188.ani.app.ui.lang.tv_settings_oss_no_license
import me.him188.ani.app.ui.lang.tv_settings_paragraphs
import me.him188.ani.app.ui.lang.tv_settings_rerun_onboarding
import me.him188.ani.app.ui.lang.tv_settings_rerun_onboarding_confirm
import me.him188.ani.app.ui.lang.tv_settings_rerun_onboarding_description
import me.him188.ani.app.ui.lang.tv_settings_rerun_onboarding_restart
import me.him188.ani.app.ui.onboarding.TvOnboardingGate
import me.him188.ani.app.ui.settings.tabs.AniHelperDestination
import me.him188.ani.app.ui.settings.tabs.about.DeveloperCredit
import me.him188.ani.app.ui.settings.tabs.about.OpenSourceLibraryInfo
import me.him188.ani.app.ui.settings.tabs.about.developerCredits
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** 「关于」用到的实时值. */
internal object TvAboutSettingKeys {
    /** 开源库列表 (进「开源许可」时才读, 见 [TvSettingsDeps.loadOpenSourceLibraries]). */
    val openSourceLibraries = TvSettingKey.Live("openSourceLibraries") { deps ->
        deps.openSourceLibraries.map { TvOpenSourceLibraries(it) }
    }
}

/** [list] 为 null = 还没读完. */
internal data class TvOpenSourceLibraries(val list: List<OpenSourceLibraryInfo>?)

/** 关于: 版本 (连点进调试模式, 说明栏写构建信息)、重新走一遍首次引导、反馈、源代码、开发者与鸣谢 (各进一层)、交流群. */
internal fun TvSettingsItemsBuilder.aboutItems() {
    action(
        Lang.settings_about_version,
        value = { tvText(currentAniBuildConfig.versionName) },
        details = {
            val config = currentAniBuildConfig
            tvText(Lang.tv_settings_build_info, config.gitBranch, config.gitCommitSha.take(BUILD_SHA_LENGTH), config.gitCommitTime, config.distroChannel)
        },
    ) {
        listOf(
            TvSettingsEdit.Run { env ->
                if (env.deps.settings.debugTriggerState.triggerDebugMode()) env.toast(getString(Lang.settings_debug_mode_enabled))
            },
        )
    }
    // 引导只看「做没做过」的标记 (见 TvOnboardingGate): 清掉再重启, 下次启动就是完整的首次引导
    run(
        Lang.tv_settings_rerun_onboarding,
        Lang.tv_settings_rerun_onboarding_description,
        confirm = TvSettingsConfirm(
            title = null,
            text = tvText(Lang.tv_settings_rerun_onboarding_confirm),
            confirmLabel = tvText(Lang.tv_settings_rerun_onboarding_restart),
        ),
    ) { env ->
        TvOnboardingGate.reset(env.context)
        GlobalKoin.get<AppRestarter>().restart()
    }
    link(Lang.settings_about_feedback) { AniHelperDestination.ISSUE_TRACKER }
    link(Lang.settings_about_source_code) { AniHelperDestination.GITHUB_HOME }
    group(Lang.developer_list) { developerItems() }
    group(Lang.acknowledgements) { acknowledgementItems() }
    header(Lang.settings_about_chat_groups)
    link(Lang.settings_about_qq_group, value = { tvText(QQ_GROUP_ID) }) { QQ_GROUP_JOIN_LINK }
    link(Lang.settings_help_telegram) { TELEGRAM_GROUP_LINK }
}

/** 开发者名单 (同原来设置页的开发者页): 每人一行, 行尾写分工, 说明栏画头像与 GitHub 主页的码. */
private fun TvSettingsItemsBuilder.developerItems() {
    val main = developerCredits.take(MAIN_CONTRIBUTORS)
    val outstanding = developerCredits.subList(MAIN_CONTRIBUTORS, developerCredits.lastIndex)
    header(Lang.settings_developers_main_contributors)
    developers(main)
    header(Lang.settings_developers_outstanding_contributors)
    developers(outstanding)
    header(Lang.tv_settings_developers_more)
    developers(listOf(developerCredits.last()))
    link(Lang.settings_developers_view_more_on_github) { "${AniHelperDestination.GITHUB_HOME}/graphs/contributors" }
}

private fun TvSettingsItemsBuilder.developers(credits: List<DeveloperCredit>) = entries(
    keys = emptyList(),
    entries = { credits },
    key = { it.url },
    title = { tvText(it.name) },
    value = { _, credit -> tvText(credit.role) },
    description = { _, credit -> paragraphs(tvText(credit.role), tvText(Lang.tv_settings_link_description)) },
    qr = { _, credit -> linkQr(credit.url) },
    image = { TvImage(it.avatar) },
) { credit -> listOf(openLink(credit.url)) }

/** 鸣谢: 数据来源各一行 (说明栏画它的标志与网址的码), 最后是开源许可 (再进一层, 一个库一行). */
private fun TvSettingsItemsBuilder.acknowledgementItems() {
    thanks(Lang.settings_acknowledgements_bangumi, Lang.settings_acknowledgements_bangumi_description, Res.drawable.bangumi, "https://bangumi.tv")
    thanks(
        Lang.settings_acknowledgements_dandanplay, Lang.settings_acknowledgements_dandanplay_description,
        Res.drawable.dandanplay, "https://www.dandanplay.com",
    )
    thanks(Lang.settings_acknowledgements_mikan, Lang.settings_acknowledgements_mikan_description, Res.drawable.mikan, "https://mikanani.me")
    thanks(Lang.settings_acknowledgements_dmhy, Lang.settings_acknowledgements_dmhy_description, Res.drawable.dmhy, "https://www.dmhy.org")
    // TMDB 的品牌条款要求原样使用官方标志, 不裁成圆形
    thanks(
        Lang.settings_acknowledgements_tmdb, Lang.settings_acknowledgements_tmdb_description,
        Res.drawable.tmdb, "https://www.themoviedb.org", round = false,
    )
    group(
        Lang.settings_acknowledgements_oss_licenses,
        Lang.settings_acknowledgements_oss_licenses_description,
        // 焦点停在这一行就开始读, 进去时多半已经读好
        whileFocused = { env -> env.deps.loadOpenSourceLibraries() },
        onOpen = { env -> env.deps.loadOpenSourceLibraries() },
    ) { openSourceLibraryItems() }
}

private fun TvSettingsItemsBuilder.thanks(
    title: StringResource,
    description: StringResource,
    logo: DrawableResource,
    url: String,
    round: Boolean = true,
) = link(title, details = paragraphs(tvText(description), tvText(Lang.tv_settings_link_description)), image = TvImage(logo, round)) { url }

/** 开源许可: 一个库一行, 行尾写许可证的短名; 说明栏写构件坐标、许可证与它的网址, 画主页的码, 确定在电视上打开主页. */
private fun TvSettingsItemsBuilder.openSourceLibraryItems() {
    val libraries = TvAboutSettingKeys.openSourceLibraries
    action(Lang.tv_settings_oss_loading, keys = listOf(libraries), visible = { it[libraries]?.list == null }) { emptyList() }
    entries(
        keys = listOf(libraries),
        entries = { it[libraries]?.list.orEmpty() },
        key = { it.id },
        title = { tvText(it.name) },
        value = { _, library -> library.licenses.takeIf { it.isNotEmpty() }?.let { tvText(it.joinToString(", ") { l -> l.spdxId ?: l.name }) } },
        description = { _, library -> libraryDescription(library) },
        qr = { _, library -> library.homepage?.let(::linkQr) },
    ) { library -> library.homepage?.let { listOf(openLink(it)) }.orEmpty() }
}

private fun libraryDescription(library: OpenSourceLibraryInfo): TvText {
    val coordinates = tvText(listOfNotNull(library.id, library.version).joinToString(" "))
    val licenses = if (library.licenses.isEmpty()) {
        tvText(Lang.tv_settings_oss_no_license)
    } else {
        val names = library.licenses.joinToString(", ") { it.name }
        val urls = library.licenses.mapNotNull { it.url?.takeIf(String::isNotBlank) }.distinct()
        tvText(Lang.tv_settings_oss_license, (listOf(names) + urls).joinToString("\n"))
    }
    val parts = listOfNotNull(coordinates, licenses, library.homepage?.let { tvText(Lang.tv_settings_link_description) })
    return parts.reduce { acc, part -> paragraphs(acc, part) }
}

private fun paragraphs(first: TvText, second: TvText): TvText = tvText(Lang.tv_settings_paragraphs, first, second)

/** 交流群 (同 BrowserNavigator.openJoinTelegram). */
private const val TELEGRAM_GROUP_LINK = "https://t.me/+FxlyUgaL5XlmZmY1"

/** 开发者名单里「主要贡献者」的人数 (同原来设置页的开发者页). */
private const val MAIN_CONTRIBUTORS = 2

/** 版本号说明里提交号的长度 (同构建信息页). */
private const val BUILD_SHA_LENGTH = 8

/**
 * 打开一个网页的一行: 说明栏画它的二维码 (电视上多半没有浏览器, 手机扫了打开), 确定时电视上有浏览器就直接打开, 没有就提示扫码.
 * [details] 不给时说明栏只写怎么打开.
 */
internal fun TvSettingsItemsBuilder.link(
    title: StringResource,
    value: ((TvSettingsValues) -> TvText?)? = null,
    details: TvText? = null,
    image: TvImage? = null,
    url: () -> String,
) = action(
    title, Lang.tv_settings_link_description,
    value = value,
    details = details?.let { d -> { d } },
    chevron = true,
    qr = { linkQr(url()) },
    image = image,
) { listOf(openLink(url())) }

/** 网址的码: 码下面印网址 (扫不了还能手敲). */
private fun linkQr(url: String) = TvQr(url, caption = tvText(url))

/** 在电视的浏览器里打开 [url]; 没有浏览器时提示扫码. */
private fun openLink(url: String) = TvSettingsEdit.Run { env ->
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { env.context.startActivity(intent) }.onFailure { env.toast(getString(Lang.tv_settings_link_no_browser)) }
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import me.him188.ani.app.data.models.subject.tvPrefersTmdbText
import me.him188.ani.app.data.models.subject.tvUseOriginalTitle
import me.him188.ani.app.ui.foundation.LocalSubjectAppearanceSettings

/**
 * 电视页面上番名、集名是否用原名 (见 [me.him188.ani.app.data.models.subject.tvUseOriginalTitle]).
 * 界面语言取当前语言环境 (电视的语言设置改的是应用的语言环境).
 */
@Composable
fun tvUseOriginalTitle(): Boolean =
    tvUseOriginalTitle(LocalSubjectAppearanceSettings.current.useOriginalTitle, Locale.current.language)

/** 电视页面上简介、单集文字是否先取 TMDB 界面语言的 (见 [me.him188.ani.app.data.models.subject.tvPrefersTmdbText]). */
@Composable
fun tvPrefersTmdbText(): Boolean = tvPrefersTmdbText(Locale.current.language)

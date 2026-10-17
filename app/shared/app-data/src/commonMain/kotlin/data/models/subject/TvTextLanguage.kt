/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.subject

/**
 * 界面语言是中文 (简体、繁体都算). [uiLanguage] 是语言代码或语言标签, 如 `zh`、`zh-TW`、`zh_HK`.
 *
 * Bangumi 的文字 (名字、简介、单集名与简介) 只有中文与原语言 (通常是日文) 两种, 电视上按它分两路:
 * 中文界面照用 Bangumi 的; 别的界面番名、简介与单集文字先取 TMDB 对应语言的, 没有时番名用原名.
 */
fun isChineseUiLanguage(uiLanguage: String): Boolean =
    uiLanguage.substringBefore('-').substringBefore('_').equals("zh", ignoreCase = true)

/**
 * 电视上按 Bangumi 取番名、集名时是否用原名: 开了「显示原名」([useOriginalTitle]), 或界面不是中文 (看不懂中文名, 原名至少与官方叫法一致).
 * 界面不是中文且没开「显示原名」时, 界面上先用 TMDB 界面语言的番名与集名, 没有才轮到这里.
 */
fun tvUseOriginalTitle(useOriginalTitle: Boolean, uiLanguage: String): Boolean =
    useOriginalTitle || !isChineseUiLanguage(uiLanguage)

/**
 * 电视上简介、单集名、单集简介 (以及没开「显示原名」时的番名) 是否先取 TMDB 界面语言的文字: 界面不是中文时.
 * 中文界面照用 Bangumi 的 (按季写、写给中文用户).
 */
fun tvPrefersTmdbText(uiLanguage: String): Boolean = !isChineseUiLanguage(uiLanguage)

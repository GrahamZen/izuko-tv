/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.account

import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.app.platform.ManualRelaunchRestarter
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_convert_bangumi_text
import me.him188.ani.app.ui.lang.profile_convert_bangumi_text_manual_relaunch
import me.him188.ani.app.ui.lang.profile_convert_local_downloading_hint
import me.him188.ani.app.ui.lang.profile_convert_local_downloading_hint_manual_relaunch
import me.him188.ani.app.ui.lang.profile_convert_local_text
import me.him188.ani.app.ui.lang.profile_convert_local_text_manual_relaunch
import org.jetbrains.compose.resources.StringResource

/**
 * 改用户类型的几句说明里「Izuko 会重新打开」: 应用不能自己重开的平台 (iOS, 见 [ManualRelaunchRestarter]) 上换成请用户从后台关掉再打开的说法,
 * 同上游按平台各写一条、在用的地方挑.
 */
internal fun relaunchText(text: StringResource): StringResource =
    if (needsManualRelaunch()) MANUAL_RELAUNCH_TEXTS[text] ?: text else text

private fun needsManualRelaunch(): Boolean =
    runCatching { GlobalKoin.get<AppRestarter>() }.getOrNull() === ManualRelaunchRestarter

private val MANUAL_RELAUNCH_TEXTS: Map<StringResource, StringResource> = mapOf(
    Lang.profile_convert_local_text to Lang.profile_convert_local_text_manual_relaunch,
    Lang.profile_convert_local_downloading_hint to Lang.profile_convert_local_downloading_hint_manual_relaunch,
    Lang.profile_convert_bangumi_text to Lang.profile_convert_bangumi_text_manual_relaunch,
)

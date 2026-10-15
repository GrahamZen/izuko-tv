/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_tv_episodes_fetch_failed_message
import me.him188.ani.app.ui.lang.subject_details_tv_episodes_fetch_failed_title
import me.him188.ani.app.ui.lang.subject_details_tv_no_episodes_message
import me.him188.ani.app.ui.lang.subject_details_tv_no_episodes_ok
import me.him188.ani.app.ui.lang.subject_details_tv_no_episodes_title
import org.jetbrains.compose.resources.stringResource

/**
 * 详情页播放键进不了播放页时弹的说明 (见 [TvPlayEpisodesGate]): 播放页要靠 Bangumi 的分集进入, 没有分集就进不去.
 * 少有人维护的条目 (美漫居多) 常常过了开播日还没录分集; 等 Bangumi 补上就能看.
 *
 * @param fetchFailed 不是 Bangumi 没录, 而是分集没取到 (网络); 再按一次播放会重取
 */
@Composable
internal fun TvSubjectNoEpisodesDialog(fetchFailed: Boolean = false, onDismiss: () -> Unit) {
    AniAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (fetchFailed) Lang.subject_details_tv_episodes_fetch_failed_title
                    else Lang.subject_details_tv_no_episodes_title,
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (fetchFailed) Lang.subject_details_tv_episodes_fetch_failed_message
                    else Lang.subject_details_tv_no_episodes_message,
                ),
            )
        },
        confirmButton = {
            AniTextButton(onClick = onDismiss, modifier = Modifier.tvWindowInitialFocus()) {
                Text(stringResource(Lang.subject_details_tv_no_episodes_ok))
            }
        },
    )
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.profile.SelfCollectionRecords
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_clear_records_confirm
import me.him188.ani.app.ui.lang.profile_clear_records_text
import me.him188.ani.app.ui.lang.profile_clear_records_title
import me.him188.ani.app.ui.lang.profile_convert_bangumi_confirm
import me.him188.ani.app.ui.lang.profile_convert_bangumi_kept
import me.him188.ani.app.ui.lang.profile_convert_bangumi_text
import me.him188.ani.app.ui.lang.profile_convert_bangumi_title
import me.him188.ani.app.ui.lang.profile_convert_local_account
import me.him188.ani.app.ui.lang.profile_convert_local_account_unknown
import me.him188.ani.app.ui.lang.profile_convert_local_cached_only
import me.him188.ani.app.ui.lang.profile_convert_local_clear
import me.him188.ani.app.ui.lang.profile_convert_local_confirm
import me.him188.ani.app.ui.lang.profile_convert_local_downloading
import me.him188.ani.app.ui.lang.profile_convert_local_downloading_hint
import me.him188.ani.app.ui.lang.profile_convert_local_downloading_list
import me.him188.ani.app.ui.lang.profile_convert_local_failed
import me.him188.ani.app.ui.lang.profile_convert_local_keep
import me.him188.ani.app.ui.lang.profile_convert_local_leftover
import me.him188.ani.app.ui.lang.profile_convert_local_logout
import me.him188.ani.app.ui.lang.profile_convert_local_text
import me.him188.ani.app.ui.lang.profile_convert_local_title
import me.him188.ani.app.ui.lang.settings_account_popup_cancel
import me.him188.ani.app.ui.lang.tv_profile_import_ok
import org.jetbrains.compose.resources.stringResource

/** 改成本地用户进行到哪了: 登录着、选了留下记录时先从 Bangumi 取账号里的收藏 (见 [me.him188.ani.app.domain.profile.LocalProfileConversion]). */
sealed interface ConvertToLocalProgress {
    /** @property total 要取看过的集的条数; 0 = 还在取收藏列表 */
    data class Downloading(val done: Int, val total: Int) : ConvertToLocalProgress

    /** 账号里的收藏没取全, 什么都没改 (没退出登录, 还是 Bangumi 用户). */
    data class Failed(val message: String) : ConvertToLocalProgress
}

/**
 * 改成本地用户前的确认 (见 [me.him188.ani.app.domain.profile.LocalProfileConversion]).
 * 有收藏记录要处理时让人选留下 (当作本地收藏) 还是清除, 默认焦点在不丢数据的「留下」; 没有就只确认一下.
 * 登录着时要处理的是账号里的收藏 ([accountCollections] 部, 留下的话先取下来, 进度见 [progress]); 没登录时是库里存着的 ([leftover]).
 *
 * @param loggedIn 现在登录着 Bangumi: 写一句会先退出登录
 * @param accountCollections 登录着时账号里的收藏条数; null = 还不知道
 * @param progress 选了之后的进度; 取收藏期间没有按钮 (按返回也不关), 取失败时只有「知道了」
 */
@Composable
fun ConvertToLocalProfileDialog(
    leftover: SelfCollectionRecords.Counts,
    onConvert: (clearRecords: Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    loggedIn: Boolean = false,
    accountCollections: Int? = null,
    progress: ConvertToLocalProgress? = null,
) {
    val defaultFocus = rememberDialogDefaultFocus(progress is ConvertToLocalProgress.Failed)
    val hasRecords = if (loggedIn) (accountCollections ?: 1) > 0 || !leftover.isEmpty else !leftover.isEmpty
    AniAlertDialog(
        onDismissRequest = { if (progress !is ConvertToLocalProgress.Downloading) onDismissRequest() },
        title = { Text(stringResource(Lang.profile_convert_local_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (progress) {
                    is ConvertToLocalProgress.Downloading -> {
                        Text(
                            if (progress.total == 0) stringResource(Lang.profile_convert_local_downloading_list)
                            else stringResource(Lang.profile_convert_local_downloading, progress.done, progress.total),
                        )
                        Text(stringResource(Lang.profile_convert_local_downloading_hint))
                    }

                    is ConvertToLocalProgress.Failed -> Text(stringResource(Lang.profile_convert_local_failed, progress.message))

                    null -> {
                        Text(stringResource(Lang.profile_convert_local_text))
                        if (loggedIn) {
                            Text(stringResource(Lang.profile_convert_local_logout))
                            if (hasRecords) {
                                Text(
                                    accountCollections?.let { stringResource(Lang.profile_convert_local_account, it) }
                                        ?: stringResource(Lang.profile_convert_local_account_unknown),
                                )
                            }
                        } else if (hasRecords) {
                            Text(stringResource(Lang.profile_convert_local_leftover, leftover.collections, leftover.watchedEpisodes))
                            Text(stringResource(Lang.profile_convert_local_cached_only))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                progress is ConvertToLocalProgress.Downloading -> {}
                progress is ConvertToLocalProgress.Failed ->
                    AniTextButton(onDismissRequest, Modifier.focusRequester(defaultFocus)) {
                        Text(stringResource(Lang.tv_profile_import_ok))
                    }

                !hasRecords -> AniTextButton({ onConvert(false) }, Modifier.focusRequester(defaultFocus)) {
                    Text(stringResource(Lang.profile_convert_local_confirm))
                }

                else -> {
                    AniTextButton(
                        { onConvert(true) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(Lang.profile_convert_local_clear))
                    }
                    AniTextButton({ onConvert(false) }, Modifier.focusRequester(defaultFocus)) {
                        Text(stringResource(Lang.profile_convert_local_keep))
                    }
                }
            }
        },
        dismissButton = if (progress != null) null else {
            { AniTextButton(onDismissRequest) { Text(stringResource(Lang.settings_account_popup_cancel)) } }
        },
    )
}

/**
 * 本地用户改成 Bangumi 用户前的确认 (见 [me.him188.ani.app.domain.profile.BangumiProfileConversion]).
 * 不用选: 本地收藏 ([records]) 一律留着, 登录后再选合并到账号还是删除, 这里只说一声. 默认焦点在「改成 Bangumi 用户」(不丢数据).
 */
@Composable
fun ConvertToBangumiProfileDialog(
    records: SelfCollectionRecords.Counts,
    onConvert: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val defaultFocus = rememberDialogDefaultFocus()
    AniAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.profile_convert_bangumi_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Lang.profile_convert_bangumi_text))
                if (!records.isEmpty) {
                    Text(stringResource(Lang.profile_convert_bangumi_kept, records.collections, records.watchedEpisodes))
                }
            }
        },
        confirmButton = {
            AniTextButton(onConvert, Modifier.focusRequester(defaultFocus)) {
                Text(stringResource(Lang.profile_convert_bangumi_confirm))
            }
        },
        dismissButton = {
            AniTextButton(onDismissRequest) { Text(stringResource(Lang.settings_account_popup_cancel)) }
        },
    )
}

/** 本地用户清除自己的收藏记录前的确认. 清了找不回来, 默认焦点在「取消」. */
@Composable
fun ClearSelfRecordsDialog(
    counts: SelfCollectionRecords.Counts,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val cancelFocus = rememberDialogDefaultFocus()
    AniAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.profile_clear_records_title)) },
        text = { Text(stringResource(Lang.profile_clear_records_text, counts.collections, counts.watchedEpisodes)) },
        confirmButton = {
            AniTextButton(
                onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(Lang.profile_clear_records_confirm))
            }
        },
        dismissButton = {
            AniTextButton(onDismissRequest, Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(Lang.settings_account_popup_cancel))
            }
        },
    )
}

/**
 * 焦点驱动的界面上对话框窗口不会自动给初始焦点, 要显式请求 (否则整个对话框按不动); 挂在想默认聚焦的按钮上.
 * [key] 变了再请求一次 (对话框换了一组按钮时).
 */
@Composable
private fun rememberDialogDefaultFocus(key: Any? = Unit): FocusRequester {
    val requester = remember { FocusRequester() }
    if (LocalAniUiBehavior.current.focusDrivenNavigation) {
        LaunchedEffect(key) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    return requester
}

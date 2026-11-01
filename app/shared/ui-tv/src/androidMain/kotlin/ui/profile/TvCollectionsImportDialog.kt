/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.domain.profile.LocalProfileImporter
import me.him188.ani.app.ui.foundation.TV_CONFIRM_KEYS
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_merge_discard
import me.him188.ani.app.ui.lang.profile_merge_discard_confirm
import me.him188.ani.app.ui.lang.profile_merge_discard_text
import me.him188.ani.app.ui.lang.subject_collection_doing
import me.him188.ani.app.ui.lang.subject_collection_done
import me.him188.ani.app.ui.lang.subject_collection_dropped
import me.him188.ani.app.ui.lang.subject_collection_on_hold
import me.him188.ani.app.ui.lang.subject_collection_wish
import me.him188.ani.app.ui.lang.tv_profile_cancel
import me.him188.ani.app.ui.lang.tv_profile_import_done
import me.him188.ani.app.ui.lang.tv_profile_import_error
import me.him188.ani.app.ui.lang.tv_profile_import_failed
import me.him188.ani.app.ui.lang.tv_profile_import_loading
import me.him188.ani.app.ui.lang.tv_profile_import_more
import me.him188.ani.app.ui.lang.tv_profile_import_nothing
import me.him188.ani.app.ui.lang.tv_profile_import_ok
import me.him188.ani.app.ui.lang.tv_profile_import_rate_limited
import me.him188.ani.app.ui.lang.tv_profile_import_running
import me.him188.ani.app.ui.lang.tv_profile_import_running_hint
import me.him188.ani.app.ui.lang.tv_profile_import_skipped
import me.him188.ani.app.ui.lang.tv_profile_import_summary
import me.him188.ani.app.ui.lang.tv_profile_import_warning
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

private enum class ImportFocus : TvFocusKey { Confirm, Cancel, Discard }

private enum class ImportStage { Loading, Preview, Empty, Running, Result, Error, ConfirmDiscard }

/**
 * 把一批本地收藏导入当前用户 Bangumi 账号的弹窗 (见 [LocalProfileImporter]): 先读出预览 (要加哪些、哪些已经有了) 给人确认,
 * 确认了在后台导 ([ProfileImportSession], 关掉弹窗也接着导), 导完显示结果. 写进 Bangumi 的撤不回来, 确认前把后果写明,
 * 焦点默认落在「取消」.
 *
 * 选人页导入别的本地用户, 与本地用户改成 Bangumi 用户后合并留下的收藏 ([TvPendingCollectionsHost]) 共用.
 *
 * @param title 还没开始导时的标题
 * @param sessionTitle 显示在跑或上一次的导入时的标题 (那一次可能是 Web 控制台上发起的), 参数是那一次的 sourceName
 * @param intro 预览前面的一段说明
 * @param cancelLabel 还没导时关掉弹窗的按钮
 * @param onDiscard 不为 null 时预览与出错时多一个「删除」(再确认一次才删), 没有要导的时「知道了」也调它: 这批收藏处理完了
 * @param discardCount 「删除」要删的条数, 确认时写给人看
 * @param confirmGuard 弹窗自己冒出来的 (不是人点开的) 给多久: 出现后与每换一组按钮后这么久里不认确认键 (按下抬起都吞),
 *   人正连着按键时冒出来, 下一下就不会点到还没看清的按钮; 方向键与返回照常
 */
@Composable
internal fun CollectionsImportDialog(
    title: String,
    sessionTitle: @Composable (sourceName: String) -> String,
    confirmLabel: @Composable (count: Int) -> String,
    cancelLabel: String,
    loadPreview: suspend () -> LocalProfileImporter.Preview,
    onStart: (LocalProfileImporter.Preview) -> Unit,
    onDismissRequest: () -> Unit,
    intro: String? = null,
    onDiscard: (() -> Unit)? = null,
    discardCount: Int = 0,
    confirmGuard: Duration = Duration.ZERO,
) {
    val session by ProfileImportSession.state.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<LocalProfileImporter.Preview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    // 打开时有导入在跑 (或上一次的结果还没看) 就显示它; 否则读预览
    LaunchedEffect(Unit) {
        if (ProfileImportSession.state.value != ProfileImportSession.State.Idle) return@LaunchedEffect
        try {
            preview = loadPreview()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName.orEmpty()
        }
    }
    val close = {
        ProfileImportSession.acknowledge()
        onDismissRequest()
    }
    val p = preview
    val stage = when (session) {
        is ProfileImportSession.State.Running -> ImportStage.Running
        is ProfileImportSession.State.Finished, is ProfileImportSession.State.Failed -> ImportStage.Result
        ProfileImportSession.State.Idle -> when {
            confirmingDiscard -> ImportStage.ConfirmDiscard
            error != null -> ImportStage.Error
            p == null -> ImportStage.Loading
            p.toAdd.isEmpty() -> ImportStage.Empty
            else -> ImportStage.Preview
        }
    }
    val dialogTitle = when (val s = session) {
        is ProfileImportSession.State.Running -> sessionTitle(s.sourceName)
        is ProfileImportSession.State.Finished -> sessionTitle(s.sourceName)
        is ProfileImportSession.State.Failed -> sessionTitle(s.sourceName)
        ProfileImportSession.State.Idle -> title
    }
    ProfileDialogSurface(dialogTitle, close) { focus ->
        val body = MaterialTheme.typography.bodyLarge
        val small = MaterialTheme.typography.bodyMedium
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (val s = session) {
            is ProfileImportSession.State.Running -> {
                Text(stringResource(Lang.tv_profile_import_running, s.done, s.total), style = body)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        if (s.waitingForRateLimit) Lang.tv_profile_import_rate_limited else Lang.tv_profile_import_running_hint,
                    ),
                    style = small,
                    color = muted,
                )
            }

            is ProfileImportSession.State.Finished -> {
                val r = s.result
                Text(stringResource(Lang.tv_profile_import_done, r.added, r.episodesMarked), style = body)
                if (r.skipped > 0) {
                    Text(stringResource(Lang.tv_profile_import_skipped, r.skipped), style = small, color = muted)
                }
                if (r.failures.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            Lang.tv_profile_import_failed,
                            r.failures.size,
                            r.failures.take(IMPORT_LIST_LIMIT).joinToString(" · ") { it.entry.name },
                        ),
                        style = small,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is ProfileImportSession.State.Failed -> Text(stringResource(Lang.tv_profile_import_error, s.message), style = body)

            ProfileImportSession.State.Idle -> when (stage) {
                ImportStage.ConfirmDiscard -> Text(
                    stringResource(Lang.profile_merge_discard_text, discardCount),
                    style = body,
                )

                ImportStage.Error -> Text(stringResource(Lang.tv_profile_import_error, error.orEmpty()), style = body)
                ImportStage.Loading -> Text(stringResource(Lang.tv_profile_import_loading), style = body, color = muted)
                ImportStage.Empty -> Text(stringResource(Lang.tv_profile_import_nothing), style = body)
                else -> if (p != null) {
                    if (intro != null) {
                        Text(intro, style = body)
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(stringResource(Lang.tv_profile_import_summary, p.toAdd.size), style = body)
                    if (p.alreadyCollected.isNotEmpty()) {
                        Text(stringResource(Lang.tv_profile_import_skipped, p.alreadyCollected.size), style = body)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Lang.tv_profile_import_warning), style = small, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    for (entry in p.toAdd.take(IMPORT_LIST_LIMIT)) {
                        Text(
                            "· " + entry.name + " · " + entry.type.label(),
                            style = small,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (p.toAdd.size > IMPORT_LIST_LIMIT) {
                        Text(stringResource(Lang.tv_profile_import_more, p.toAdd.size - IMPORT_LIST_LIMIT), style = small, color = muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        // 每换一组按钮重新计时 (读预览时的「以后再说」换成「知道了」, 意思变了)
        var buttonsShownAt by remember { mutableStateOf(TimeSource.Monotonic.markNow()) }
        LaunchedEffect(stage) { buttonsShownAt = TimeSource.Monotonic.markNow() }
        Row(
            Modifier.onPreviewKeyEvent { it.key in TV_CONFIRM_KEYS && buttonsShownAt.elapsedNow() < confirmGuard },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (stage == ImportStage.ConfirmDiscard && onDiscard != null) {
                TvHeroButton(
                    stringResource(Lang.profile_merge_discard_confirm),
                    Icons.Rounded.Delete,
                    filled = false,
                    onClick = {
                        onDiscard()
                        close()
                    },
                    onFocused = {},
                    modifier = Modifier.tvFocusAnchor(focus, ImportFocus.Discard),
                )
                TvHeroButton(
                    stringResource(Lang.tv_profile_cancel),
                    Icons.Rounded.Close,
                    filled = false,
                    onClick = { confirmingDiscard = false },
                    onFocused = {},
                    modifier = Modifier.tvFocusAnchor(focus, ImportFocus.Cancel),
                )
            } else {
                if (stage == ImportStage.Preview && p != null) {
                    TvHeroButton(
                        confirmLabel(p.toAdd.size),
                        Icons.Rounded.Check,
                        filled = true,
                        onClick = { onStart(p) },
                        onFocused = {},
                        modifier = Modifier.tvFocusAnchor(focus, ImportFocus.Confirm),
                    )
                }
                // 还没导时是「取消」, 其余 (没有要导的、在导、导完、出错) 是「知道了」; 能删除的, 出错时也还是「以后再说」
                val cancels = stage == ImportStage.Preview || stage == ImportStage.Loading || (stage == ImportStage.Error && onDiscard != null)
                TvHeroButton(
                    if (cancels) cancelLabel else stringResource(Lang.tv_profile_import_ok),
                    if (cancels) Icons.Rounded.Close else Icons.Rounded.Check,
                    filled = false,
                    onClick = {
                        // 没有要导的: 这批收藏账号里都有了, 处理完了
                        if (stage == ImportStage.Empty) onDiscard?.invoke()
                        close()
                    },
                    onFocused = {},
                    modifier = Modifier.tvFocusAnchor(focus, ImportFocus.Cancel),
                )
                if (onDiscard != null && (stage == ImportStage.Preview || stage == ImportStage.Error)) {
                    TvHeroButton(
                        stringResource(Lang.profile_merge_discard),
                        Icons.Rounded.Delete,
                        filled = false,
                        onClick = { confirmingDiscard = true },
                        onFocused = {},
                        modifier = Modifier.tvFocusAnchor(focus, ImportFocus.Discard),
                    )
                }
            }
        }
        // 每换一种状态焦点都送回「取消 / 知道了」: 原来聚焦的按钮可能随状态消失, 不接住焦点就丢了; 预览与确认删除时默认也落在它上面
        LaunchedEffect(stage) { focus.request(ImportFocus.Cancel) }
    }
}

@Composable
private fun UnifiedCollectionType.label(): String = when (this) {
    UnifiedCollectionType.WISH -> stringResource(Lang.subject_collection_wish)
    UnifiedCollectionType.DOING -> stringResource(Lang.subject_collection_doing)
    UnifiedCollectionType.DONE -> stringResource(Lang.subject_collection_done)
    UnifiedCollectionType.ON_HOLD -> stringResource(Lang.subject_collection_on_hold)
    UnifiedCollectionType.DROPPED -> stringResource(Lang.subject_collection_dropped)
    UnifiedCollectionType.NOT_COLLECTED -> ""
}

/** 导入弹窗里最多列几部 (其余写「还有 N 部」): 电视上一屏放不下长列表, 焦点又在底下的按钮上. */
private const val IMPORT_LIST_LIMIT = 5

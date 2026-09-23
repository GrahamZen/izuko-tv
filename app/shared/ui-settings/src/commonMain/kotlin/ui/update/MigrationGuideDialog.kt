/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tv.tvPageScrollKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_about_app_name
import me.him188.ani.app.ui.lang.settings_update_migration_cache_warning
import me.him188.ani.app.ui.lang.settings_update_migration_carried
import me.him188.ani.app.ui.lang.settings_update_migration_installed
import me.him188.ani.app.ui.lang.settings_update_migration_intro
import me.him188.ani.app.ui.lang.settings_update_migration_later
import me.him188.ani.app.ui.lang.settings_update_migration_not_carried
import me.him188.ani.app.ui.lang.settings_update_migration_open
import me.him188.ani.app.ui.lang.settings_update_migration_start
import me.him188.ani.app.ui.lang.settings_update_migration_steps
import me.him188.ani.app.ui.lang.settings_update_migration_title
import org.jetbrains.compose.resources.stringResource

/**
 * 跳板包里点"自动更新"时先出的迁移说明 (见 [NewVersion.isMigration]).
 *
 * 这次更新和平常不一样: 装上的是另一个应用 (新包名), 旧的这个不会被替换. 不先讲清楚的话, 用户看到的是
 * 系统突然问要不要安装另一个应用, 装完桌面上多出一个图标而旧的还在 —— 很容易以为装错了.
 *
 * 新应用已经装好时 (装完没打开, 又回到了旧版) 主按钮改成直接打开它, 不再下载一遍. 从别处回来时重新看一眼.
 *
 * 遥控器形态用居中大面板 (与其余面板同一形态), 指针设备用普通对话框.
 *
 * @param onStart 开始下载安装新应用
 */
@Composable
fun MigrationGuideDialog(
    onStart: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var targetInstalled by remember { mutableStateOf(context.isMigrationTargetInstalled()) }
    LifecycleResumeEffect(Unit) {
        targetInstalled = context.isMigrationTargetInstalled()
        onPauseOrDispose { }
    }
    val onPrimary = {
        if (targetInstalled && context.launchMigrationTarget()) {
            onDismissRequest()
        } else {
            onStart()
        }
    }

    val appName = stringResource(Lang.settings_about_app_name)
    val title = stringResource(Lang.settings_update_migration_title, appName)
    val primaryText = stringResource(
        if (targetInstalled) Lang.settings_update_migration_open else Lang.settings_update_migration_start,
    )
    val laterText = stringResource(Lang.settings_update_migration_later)

    if (LocalAniUiBehavior.current.panelsAsCenteredDialogs) {
        // 五段说明要一屏放下: 0.6 × 0.7 时最后一段 (接下来怎么做) 整个被挤出去了 (2026-09-22 真机)
        AniCenteredPanelDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            widthFraction = 0.7f,
            heightFraction = 0.85f,
        ) {
            Column(Modifier.fillMaxSize()) {
                val scrollState = rememberScrollState()
                MigrationGuideBody(
                    appName = appName,
                    targetInstalled = targetInstalled,
                    // 还放不下时 (字大、外语更长) 的兜底: 从按钮按上键进到正文, 上下键翻页 (见 tvPageScrollKeys)
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .tvPageScrollKeys(scrollState)
                        .focusable()
                        .verticalScroll(scrollState),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismissRequest) { Text(laterText) }
                    Spacer(Modifier.width(8.dp))
                    // 弹窗是独立窗口, 不指定的话遥控器上焦点不在任何按钮上
                    Button(onClick = onPrimary, modifier = Modifier.tvWindowInitialFocus()) { Text(primaryText) }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            text = {
                MigrationGuideBody(
                    appName = appName,
                    targetInstalled = targetInstalled,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { Button(onClick = onPrimary) { Text(primaryText) } },
            dismissButton = { TextButton(onClick = onDismissRequest) { Text(laterText) } },
        )
    }
}

@Composable
private fun MigrationGuideBody(
    appName: String,
    targetInstalled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val style = MaterialTheme.typography.bodyLarge
        Text(stringResource(Lang.settings_update_migration_intro, appName), style = style)
        Text(stringResource(Lang.settings_update_migration_carried), style = style)
        Text(stringResource(Lang.settings_update_migration_not_carried), style = style)
        // 会让缓存搬不全的几件事, 单独一句醒目地说: 搬运要旧版的文件原样在, 旧版一卸载, 没搬完的就没了
        Text(
            stringResource(Lang.settings_update_migration_cache_warning),
            style = style,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(
                if (targetInstalled) Lang.settings_update_migration_installed else Lang.settings_update_migration_steps,
            ),
            style = style,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tv.tvFieldBorder
import me.him188.ani.app.ui.foundation.tv.tvPageScrollKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.richtext.RichText
import me.him188.ani.app.ui.richtext.UIRichElement
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_update_popup_close
import me.him188.ani.app.ui.lang.settings_update_popup_new_version
import me.him188.ani.app.ui.lang.settings_update_popup_see_details
import org.jetbrains.compose.resources.stringResource

/**
 * 完整更新内容弹窗.
 *
 * 气泡上只放得下前几条 ([NewVersion.majorChanges]), 而"查看详情"以前直接跳浏览器 —— 电视上
 * 跳出去看网页几乎不可用 (没有指针, 也未必装着浏览器). 现在先在应用内看全文, 底部仍留一个
 * 跳浏览器的按钮.
 *
 * 遥控器形态用居中大面板 (与其余面板同一形态), 指针设备用普通对话框.
 */
@Composable
fun NewVersionDetailsDialog(
    version: String,
    changes: String,
    onOpenInBrowser: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val title = stringResource(Lang.settings_update_popup_new_version)
    if (LocalAniUiBehavior.current.panelsAsCenteredDialogs) {
        AniCenteredPanelDialog(
            onDismissRequest = onDismissRequest,
            title = { Text("$title $version") },
            widthFraction = 0.6f,
            heightFraction = 0.8f,
        ) {
            Column(Modifier.fillMaxSize()) {
                ChangelogBody(
                    changes = changes,
                    // 遥控器上滚动区自己得是焦点目标, 否则一片纯文本没有任何可聚焦元素, 方向键
                    // 无从翻页 (焦点会直接落到底部按钮上, 长文永远看不到后面)
                    focusable = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                DialogActions(onOpenInBrowser = onOpenInBrowser, onDismissRequest = onDismissRequest)
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text("$title $version") },
            text = {
                ChangelogBody(
                    changes = changes,
                    focusable = false,
                    modifier = Modifier.heightIn(max = 420.dp).fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = onOpenInBrowser) {
                    Icon(Icons.AutoMirrored.Outlined.Launch, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Lang.settings_update_popup_see_details))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(Lang.settings_update_popup_close))
                }
            },
        )
    }
}

@Composable
private fun DialogActions(
    onOpenInBrowser: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpenInBrowser) {
            Icon(Icons.AutoMirrored.Outlined.Launch, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Lang.settings_update_popup_see_details))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismissRequest) {
            Text(stringResource(Lang.settings_update_popup_close))
        }
    }
}

/**
 * 更新内容正文: 按 release 说明里的小节标题 (`### 修复`) 分段, 条目前面画点, 正文按 markdown 渲染.
 *
 * **走的是评论那套富文本** ([RichText]): release body 本来就是 markdown, 逐行纯文本显示会把
 * `**加粗**`、`[文字](链接)` 原样糊在正文里 —— 写说明的人越用心显示得越乱. 解析见
 * [parseChangelogBlocks]; 图片也因此能显示, 与评论区同一条加载链路.
 */
@Composable
private fun ChangelogBody(
    changes: String,
    focusable: Boolean,
    modifier: Modifier = Modifier,
) {
    // 富文本要的字号/颜色在这里取 (解析器不是 @Composable): 正文对齐 bodyMedium, 小节对齐 titleSmall
    val bodyStyle = ChangelogTextStyle(
        size = MaterialTheme.typography.bodyMedium.fontSize.value,
        linkColor = MaterialTheme.colorScheme.primary,
    )
    val sectionStyle = ChangelogTextStyle(
        size = MaterialTheme.typography.titleSmall.fontSize.value,
        color = MaterialTheme.colorScheme.primary,
        linkColor = MaterialTheme.colorScheme.primary,
        bold = true,
    )
    val blocks = remember(changes, bodyStyle, sectionStyle) {
        parseChangelogBlocks(changes, bodyStyle, sectionStyle)
    }
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()
    // focused 只剩一个用途: 下方 tvFieldBorder 的聚焦描边 (不再当送焦的到位判据)
    var focused by remember { mutableStateOf(false) }
    // 打开就把焦点放在正文上: 用户点"查看详情"就是来看内容的, 让他直接能翻.
    // 弹窗是独立窗口, 组合刚建立时 requestFocus 会被静默拒绝 —— 交给 tvWindowInitialFocus,
    // 它把正文登记成锚点, 请求悬挂到附着事件再送, 一次到位 (原先是每帧重发直到到位)

    Box(
        modifier
            .ifThen(focusable) {
                // 上下键翻页; 翻到底/顶放行, 焦点落到下方按钮行 (见 tvPageScrollKeys)
                tvPageScrollKeys(scrollState)
                    .tvWindowInitialFocus()
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clip(RoundedCornerShape(CHANGELOG_BLOCK_CORNER))
                    .tvFieldBorder(
                        focused,
                        idleColor = MaterialTheme.colorScheme.outlineVariant,
                        cornerRadius = CHANGELOG_BLOCK_CORNER,
                    )
                    .padding(12.dp)
            },
    ) {
        Column(Modifier.verticalScroll(scrollState)) {
            // 链接一律交给系统浏览器: 与弹窗底部那颗「查看详情」同一个去处.
            // 遥控器上点不到 (纯文本不吃焦点), 但至少不再是一串方括号加网址
            val onClickUrl: (String) -> Unit = { runCatching { uriHandler.openUri(it) } }
            blocks.forEachIndexed { index, block ->
                when (block) {
                    is ChangelogBlock.Section -> RichText(
                        elements = listOf(UIRichElement.AnnotatedText(block.slice)),
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp, bottom = 4.dp),
                        onClickUrl = onClickUrl,
                    )

                    is ChangelogBlock.Item -> Row(
                        Modifier
                            .padding(start = (block.indent * CHANGELOG_INDENT_STEP.value).dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // 嵌套层用空心点, 与上一级分得开
                        Text(if (block.indent == 0) "•" else "◦", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        RichText(
                            elements = listOf(UIRichElement.AnnotatedText(block.slice)),
                            onClickUrl = onClickUrl,
                        )
                    }

                    is ChangelogBlock.Paragraph -> RichText(
                        elements = listOf(UIRichElement.AnnotatedText(block.slice)),
                        modifier = Modifier.padding(bottom = 6.dp),
                        onClickUrl = onClickUrl,
                    )

                    is ChangelogBlock.Quote -> RichText(
                        elements = listOf(UIRichElement.Quote(listOf(UIRichElement.AnnotatedText(block.slice)))),
                        modifier = Modifier.padding(bottom = 6.dp),
                        onClickUrl = onClickUrl,
                    )

                    is ChangelogBlock.Image -> RichText(
                        elements = listOf(UIRichElement.Image(block.url, jumpUrl = null)),
                        modifier = Modifier.padding(bottom = 6.dp),
                        onClickUrl = onClickUrl,
                    )
                }
            }
        }
    }
}

/** 正文块的圆角: 边框与裁剪共用. */
private val CHANGELOG_BLOCK_CORNER = 12.dp

/** 嵌套条目每级往右让多少. */
private val CHANGELOG_INDENT_STEP = 16.dp

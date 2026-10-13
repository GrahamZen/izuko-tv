/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.whatsnew

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.foundation.Res
import me.him188.ani.app.ui.foundation.app_icon
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_settings_update_whats_new
import me.him188.ani.app.ui.lang.tv_whats_new_close
import me.him188.ani.app.ui.lang.tv_whats_new_current_version
import me.him188.ani.app.ui.lang.tv_whats_new_got_it
import me.him188.ani.app.ui.lang.tv_whats_new_loading
import me.him188.ani.app.ui.lang.tv_whats_new_minor_heading
import me.him188.ani.app.ui.lang.tv_whats_new_missing
import me.him188.ani.app.ui.lang.tv_whats_new_more
import me.him188.ani.app.ui.lang.tv_whats_new_notice_heading
import me.him188.ani.app.ui.lang.tv_whats_new_notice_title
import me.him188.ani.app.ui.lang.tv_whats_new_prerelease_description
import me.him188.ani.app.ui.lang.tv_whats_new_scroll_hint
import me.him188.ani.app.ui.lang.tv_whats_new_start
import me.him188.ani.app.ui.lang.tv_whats_new_title
import me.him188.ani.app.ui.lang.tv_whats_new_title_prerelease
import me.him188.ani.app.ui.lang.tv_whats_new_unreachable
import me.him188.ani.app.ui.lang.tv_whats_new_updated_to
import me.him188.ani.app.ui.update.ReleaseHighlights
import me.him188.ani.app.ui.update.ReleaseNoteItem
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.resume

/**
 * 启动时的「本版新功能」页 ([TvWhatsNew.launchContent]): 装在 TV 根部, 盖在主页上的独立窗口. 确定或返回关掉.
 * 冷启动时它盖在启动页上出现 (见 [TvWhatsNew.showOnLaunch]): 窗口画出来、进场动画放完才告诉启动页撤, 主页一直不露出来.
 */
@Composable
fun TvWhatsNewHost() {
    val content = TvWhatsNew.launchContent.collectAsState().value ?: return
    val close = { TvWhatsNew.launchContent.value = null }
    WhatsNewWindow(onDismiss = close) {
        // 不压暗: 下面是启动页 (或主页), 整页不透明地盖上去, 进场时从启动页直接淡到这一页
        DialogWindowDimAmount(0f)
        WhatsNewPage(content, onClose = close)
        val view = LocalView.current
        LaunchedEffect(view) {
            view.awaitPreDraw()
            delay(DIALOG_ENTER_MILLIS)
            TvWhatsNew.onCovered()
        }
    }
}

/** 等这个视图所在的窗口下一次绘制 (先让它重画一次, 已经画过也等得到). */
private suspend fun View.awaitPreDraw() = suspendCancellableCoroutine { cont ->
    val listener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
            cont.resume(Unit)
            return true
        }
    }
    viewTreeObserver.addOnPreDrawListener(listener)
    invalidate()
    cont.invokeOnCancellation { if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(listener) }
}

/** 设置「软件更新 - 本版新功能」打开的那一页: 当前版本的完整说明, 见 [TvWhatsNew.settingsContent]. */
@Composable
fun TvWhatsNewSettingsPage(onDismiss: () -> Unit) {
    val load by remember { TvWhatsNew.settingsContent() }.collectAsState(WhatsNewLoad.Loading)
    WhatsNewWindow(onDismiss) {
        when (val state = load) {
            is WhatsNewLoad.Ready -> WhatsNewPage(state.content, onClose = onDismiss)
            else -> WhatsNewStatusPage(state, onClose = onDismiss)
        }
    }
}

@Composable
private fun WhatsNewWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        content()
    }
}

/**
 * 左右两栏: 左边是版本、标题与「开始使用」(焦点一直在它上面), 右边是逐条的新功能, 太长时按上下键翻 (右栏不抢焦点).
 * 只有通知时右栏列通知; 介绍新功能时通知放在最上面的卡片里.
 */
@Composable
private fun WhatsNewPage(content: WhatsNewContent, onClose: () -> Unit) {
    val noticeOnly = content.releases.isEmpty()
    // 启动时那页只列有新功能的版本 (只修了问题的版本算进「另有几项」); 设置里那页小改动也逐条列
    val shown = content.releases.filter { it.features.isNotEmpty() || (content.full && it.minor.isNotEmpty()) }
    // 几版一起列 = 测试版从 alpha01 起的各版 (见 releasesToShow)
    val merged = shown.size > 1
    val title = when {
        noticeOnly -> stringResource(Lang.tv_whats_new_notice_title)
        content.full -> stringResource(Lang.tv_settings_update_whats_new)
        merged -> stringResource(Lang.tv_whats_new_title_prerelease, content.version.substringBefore('-'))
        else -> stringResource(Lang.tv_whats_new_title)
    }
    WhatsNewScaffold(
        overline = if (noticeOnly || content.full) {
            stringResource(Lang.tv_whats_new_current_version, content.version)
        } else {
            stringResource(Lang.tv_whats_new_updated_to, content.version)
        },
        title = title,
        description = if (merged) {
            stringResource(Lang.tv_whats_new_prerelease_description, content.releases.last().version, content.releases.first().version)
        } else {
            null
        },
        footnote = content.minorCount.takeIf { !content.full && it > 0 }?.let { stringResource(Lang.tv_whats_new_more, it) },
        buttonText = when {
            content.full -> stringResource(Lang.tv_whats_new_close)
            noticeOnly -> stringResource(Lang.tv_whats_new_got_it)
            else -> stringResource(Lang.tv_whats_new_start)
        },
        buttonIcon = if (content.full || noticeOnly) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
        onClose = onClose,
    ) {
        if (noticeOnly) {
            content.notice.forEach { FeatureRow(it) }
        } else if (content.notice.isNotEmpty()) {
            NoticeCard(content.notice)
        }
        for (release in shown) {
            if (merged) VersionHeader(release.version)
            release.features.forEach { FeatureRow(it) }
            if (content.full && release.minor.isNotEmpty()) MinorSection(release)
        }
    }
}

/** 设置里那页还在读、或读不到时: 同一个版面, 右栏写状态. */
@Composable
private fun WhatsNewStatusPage(load: WhatsNewLoad, onClose: () -> Unit) {
    WhatsNewScaffold(
        overline = stringResource(Lang.tv_whats_new_current_version, currentAniBuildConfig.versionName),
        title = stringResource(Lang.tv_settings_update_whats_new),
        description = null,
        footnote = null,
        buttonText = stringResource(Lang.tv_whats_new_close),
        buttonIcon = Icons.Rounded.Check,
        onClose = onClose,
    ) {
        if (load == WhatsNewLoad.Loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Lang.tv_whats_new_loading), style = detailStyle(), color = secondaryColor())
            }
        } else {
            Text(
                stringResource(if (load == WhatsNewLoad.Missing) Lang.tv_whats_new_missing else Lang.tv_whats_new_unreachable),
                style = detailStyle(),
                color = secondaryColor(),
            )
        }
    }
}

@Composable
private fun WhatsNewScaffold(
    overline: String,
    title: String,
    description: String?,
    footnote: String?,
    buttonText: String,
    buttonIcon: ImageVector,
    onClose: () -> Unit,
    list: @Composable ColumnScope.() -> Unit,
) {
    val focus = rememberTvFocusScope()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollStep = with(LocalDensity.current) { SCROLL_STEP.toPx() }
    val scheme = MaterialTheme.colorScheme
    val background = scheme.background
    val glow = scheme.primary.copy(alpha = GLOW_ALPHA)
    Surface(Modifier.fillMaxSize(), color = background, contentColor = scheme.onBackground) {
        Row(
            Modifier
                .fillMaxSize()
                // 左上角一点主题色的光: 与引导页那种纯底色区分开, 一眼看出是「新版本」这件事
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(glow, Color.Transparent),
                            center = Offset.Zero,
                            radius = size.maxDimension * GLOW_RADIUS_FRACTION,
                        ),
                    )
                }
                .tvFocusNavSignal(focus)
                // 焦点只有那一个按钮: 上下键拿来翻右栏, 左右键吞掉
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionDown -> 1
                        Key.DirectionUp -> -1
                        Key.DirectionLeft, Key.DirectionRight -> 0
                        else -> return@onPreviewKeyEvent false
                    }
                    if (direction != 0 && event.type == KeyEventType.KeyDown) {
                        scope.launch { scroll.animateScrollBy(direction * scrollStep) }
                    }
                    true
                }
                .padding(horizontal = PAGE_HORIZONTAL_PADDING, vertical = PAGE_VERTICAL_PADDING),
        ) {
            Column(Modifier.width(SIDE_WIDTH).fillMaxHeight()) {
                Image(
                    painterResource(Res.drawable.app_icon),
                    contentDescription = null,
                    Modifier.size(ICON_SIZE).clip(RoundedCornerShape(ICON_CORNER)),
                )
                Spacer(Modifier.height(24.dp))
                Text(overline, style = MaterialTheme.typography.titleMedium, color = scheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(title, style = MaterialTheme.typography.headlineLarge)
                if (description != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(description, style = MaterialTheme.typography.bodyLarge, color = secondaryColor())
                }
                Spacer(Modifier.weight(1f))
                if (footnote != null) {
                    Text(footnote, style = MaterialTheme.typography.bodyMedium, color = secondaryColor())
                    Spacer(Modifier.height(20.dp))
                }
                TvHeroButton(
                    buttonText,
                    buttonIcon,
                    filled = true,
                    onClick = onClose,
                    onFocused = {},
                    modifier = Modifier.tvFocusAnchor(focus, WhatsNewFocus.Close),
                )
                // 提示位一直占着: 右栏量出来能翻时才出字, 按钮不跟着上下跳
                Box(Modifier.height(HINT_HEIGHT), contentAlignment = Alignment.BottomStart) {
                    if (scroll.maxValue > 0) {
                        Text(stringResource(Lang.tv_whats_new_scroll_hint), style = MaterialTheme.typography.labelLarge, color = secondaryColor())
                    }
                }
            }
            Spacer(Modifier.width(COLUMN_GAP))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = LIST_EDGE_PADDING),
                    verticalArrangement = Arrangement.spacedBy(ITEM_GAP),
                    content = list,
                )
                if (scroll.canScrollBackward) {
                    Box(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(FADE_HEIGHT)
                            .background(Brush.verticalGradient(listOf(background, Color.Transparent))),
                    )
                }
                if (scroll.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(FADE_HEIGHT)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
                    )
                }
            }
        }
    }
    focus.InitialFocus(WhatsNewFocus.Close)
}

/** 一条新功能: 主题色圆点 + 标题, 有冒号后的说明时压暗写在下面. */
@Composable
private fun FeatureRow(item: ReleaseNoteItem) {
    Row {
        Box(
            Modifier.padding(top = DOT_TOP_PADDING).size(DOT_SIZE).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(16.dp))
        ItemText(item)
    }
}

@Composable
private fun ItemText(item: ReleaseNoteItem) {
    Column {
        Text(item.title, style = titleStyle())
        item.detail?.let { detail ->
            Text(detail, Modifier.padding(top = 4.dp), style = detailStyle(), color = secondaryColor())
        }
    }
}

/** 介绍新功能时当前版本的通知: 放在最上面的卡片里, 与新功能分开. */
@Composable
private fun NoticeCard(notice: List<ReleaseNoteItem>) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, Modifier.size(20.dp), tint = scheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Lang.tv_whats_new_notice_heading), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            }
            notice.forEach { ItemText(it) }
        }
    }
}

/** 合并了几版时每版前面的版本号 + 一条细线. */
@Composable
private fun VersionHeader(version: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(version, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** 设置里那页: 小改动与修复逐条列, 字小一号、压暗, 不与新功能抢. */
@Composable
private fun MinorSection(release: ReleaseHighlights) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Lang.tv_whats_new_minor_heading), style = MaterialTheme.typography.titleSmall, color = secondaryColor())
        for (item in release.minor) {
            Row {
                Text("·", style = detailStyle(), color = secondaryColor())
                Spacer(Modifier.width(10.dp))
                Text(
                    item.detail?.let { "${item.title}：$it" } ?: item.title,
                    style = detailStyle(),
                    color = secondaryColor(),
                )
            }
        }
    }
}

@Composable
private fun titleStyle(): TextStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 27.sp)

@Composable
private fun detailStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)

@Composable
private fun secondaryColor(): Color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA)

private enum class WhatsNewFocus : TvFocusKey { Close }

private val PAGE_HORIZONTAL_PADDING = 56.dp
private val PAGE_VERTICAL_PADDING = 40.dp
private val SIDE_WIDTH = 300.dp
private val COLUMN_GAP = 56.dp
private val ICON_SIZE = 56.dp
private val ICON_CORNER = 14.dp
private val ITEM_GAP = 22.dp
private val DOT_SIZE = 8.dp

/** 圆点对齐标题第一行的中线: (行高 27 - 圆点 8) / 2. */
private val DOT_TOP_PADDING = 10.dp
private val HINT_HEIGHT = 28.dp
private val LIST_EDGE_PADDING = 8.dp
private val FADE_HEIGHT = 40.dp

/** 弹窗窗口的进场动画 (系统的淡入缩放) 大约这么长; 放完才撤下面的启动页. */
private const val DIALOG_ENTER_MILLIS = 300L

/** 上下键一次翻这么多 (约三条). */
private val SCROLL_STEP = 160.dp
private const val SECONDARY_ALPHA = 0.64f
private const val GLOW_ALPHA = 0.10f
private const val GLOW_RADIUS_FRACTION = 0.45f

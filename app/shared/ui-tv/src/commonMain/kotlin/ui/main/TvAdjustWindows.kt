/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem
import me.him188.ani.app.data.models.preference.TvPlayerChromePresets
import me.him188.ani.app.ui.foundation.TvPageAdjustAction
import me.him188.ani.app.ui.foundation.TvPageAdjustActions
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_wall_scale_title
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.subject.collection.TvCollectionTabOrderPage
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerChromeLayoutPage
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource

/**
 * 页面里的「调整」入口打开的编辑页, 做成**盖在页面上的全屏窗口** (独立 Dialog), 不走导航.
 *
 * 入口: 追番页长按分类标签、三页 (探索 / 追番 / 搜索结果) 动作面板里的「海报墙大小」与追番页的「自定义标签顺序」、播放器控制层长按按钮.
 * 这几页 (骨架假页面 / 标签行 / 不播放的播放器) 只从这些入口进. 不做成导航页: 导航换页时底下的页面移出组合, 返回要整页重建 (索尼上
 * 约 0.7 秒, 低配电视更久); 窗口关掉只是把它撤走, 底下的页面一直活着, 没改就什么都不用重画. 编辑页里只改草稿、离开时才写设置, 窗口开着
 * 期间底下的页面也不跟着动.
 *
 * 装在 TV 根部 (与动作面板同一层, 用应用的主题而不是底下那一页自己的配色, 见 [TvAdjustWindowHost]); 页面经 [LocalTvAdjustWindows] 请求打开.
 */
@Stable
class TvAdjustWindows {
    /** 此刻开着的那一个; null = 没开. */
    var current: TvAdjustWindow? by mutableStateOf(null)
        private set

    fun open(window: TvAdjustWindow) {
        current = window
    }

    internal fun dismiss() {
        current = null
    }
}

/** 能开的几种编辑页, 连同关掉之后要告诉谁. 各 `onClosed` 在编辑页离开时调 (Web 控制台跳页撤掉的那种不调). */
sealed interface TvAdjustWindow {
    /** 「海报墙大小」: [page] 那一页的假页面. [onClosed] 带着改没改 (改了的话新值马上写进设置, 页面随之按新尺寸重建). */
    class WallScale(
        val page: TvPosterWallPreviewPage,
        val onClosed: (changed: Boolean) -> Unit = {},
    ) : TvAdjustWindow

    /** 追番页标签顺序; [focused] = 进页焦点落在哪个标签上 (长按它进来的; 不拿起). [onClosed] 带着改没改. */
    class TabOrder(
        val focused: UnifiedCollectionType?,
        val onClosed: (changed: Boolean) -> Unit = {},
    ) : TvAdjustWindow

    /**
     * 播放器按钮; [focused] = 进页焦点落在哪一颗上 (长按它进来的; 不拿起). [onClosed] 带着写进设置的新版式 (没改是 null) ——
     * 写入在后台, 调用方要等播放器读到它再按新版式摆.
     */
    class PlayerChrome(
        val focused: TvPlayerChromeItem?,
        val onClosed: (committed: TvPlayerChromePresets?) -> Unit = {},
    ) : TvAdjustWindow
}

val LocalTvAdjustWindows: ProvidableCompositionLocal<TvAdjustWindows?> = staticCompositionLocalOf { null }

/**
 * 把 [windows] 此刻要开的那个画出来: 铺满全屏的窗口, 不压暗 (编辑页本身不透明, 压暗只会在开合那一下把底下的页面闪暗一次).
 * 返回键归编辑页 (拿着东西时先撤销), 窗口不因返回键自己关.
 */
@Composable
fun TvAdjustWindowHost(windows: TvAdjustWindows) {
    val window = windows.current ?: return
    // Web 控制台把电视带去别的页面 (搜索 / 播放 / 打开详情): 窗口是独立的, 页面换了它还挡在上面, 自己撤掉 (草稿照样写, 见各编辑页)
    LaunchedEffect(windows) { TvRemoteControl.remoteNavigations.collect { windows.dismiss() } }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        DialogWindowDimAmount(0f)
        // 长按开出来的 (标签 / 播放器按钮): 那次按住剩下的连发与抬起转投到本窗口, 落在编辑页里就是"刚拿起就放下了".
        // 内容色同主壳 (MainScreen 的沉浸式外壳给页面的是 onSurface): 窗口在主壳外面, 不给的话假页面里取内容色的字 (hero 信息行、组标题) 是黑的
        Box(Modifier.fillMaxSize().consumeHeldConfirmKey()) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                key(window) {
                    // 编辑页在写入之前报出要写的值, 离开时连同改没改一起交给打开它的那一页
                    var committed by remember { mutableStateOf<Any?>(null) }
                    when (window) {
                        is TvAdjustWindow.WallScale -> TvPosterWallScalePage(
                            page = window.page,
                            onNavigateBack = {
                                windows.dismiss()
                                window.onClosed(committed != null)
                            },
                            onCommit = { committed = it },
                        )

                        is TvAdjustWindow.TabOrder -> TvCollectionTabOrderPage(
                            onNavigateBack = {
                                windows.dismiss()
                                window.onClosed(committed != null)
                            },
                            initialFocus = window.focused,
                            onCommit = { committed = it },
                        )

                        is TvAdjustWindow.PlayerChrome -> TvPlayerChromeLayoutPage(
                            onNavigateBack = {
                                windows.dismiss()
                                window.onClosed(committed as? TvPlayerChromePresets)
                            },
                            initialFocus = window.focused,
                            onCommit = { committed = it },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 海报墙页面 (探索 / 追番 / 搜索结果) 上「海报墙大小」的入口: 本页在前台时往动作面板里放那颗圆钮, 按下打开 [page] 的编辑窗口.
 *
 * 原生卡片的尺寸建好就定了, 所以值变了之后调用方要把原生海报墙整个重建 —— 包在 `key(LocalTvPosterWallScale.current)` 里, 页面侧的状态
 * (停位、聚焦的卡) 放在外面, 重建时照返回本页那样恢复. 墙上的节点换过之后焦点不会自己回来: 打开前焦点在墙上的话, 重建之后由 [refocus]
 * 送回去 (与返回本页时的落点同一条路). 没改就什么都不动 —— 窗口关掉之后焦点还在原处.
 *
 * @param wallHasFocus 此刻焦点在不在海报墙上 (卡片 / hero 按钮)
 * @param beforeOpen 打开之前记下墙的停位 (重建时按它恢复)
 * @return 重建之后焦点要回墙上 (网格页据此在建视图时就按住那张卡的聚焦态, 见 TvNativeGridPageHost 的 landingIndex)
 */
@Composable
internal fun TvWallScaleEntry(
    page: TvPosterWallPreviewPage,
    wallHasFocus: () -> Boolean,
    beforeOpen: () -> Unit,
    refocus: () -> Unit,
): Boolean {
    val windows = LocalTvAdjustWindows.current ?: return false
    // 打开时焦点在墙上、关掉时又改了值: 等墙按新尺寸重建完再送回去
    var refocusAfterRebuild by remember { mutableStateOf(false) }
    val scale = LocalTvPosterWallScale.current
    var builtScale by remember { mutableFloatStateOf(scale) }
    LaunchedEffect(scale) {
        if (scale == builtScale) return@LaunchedEffect
        builtScale = scale
        if (refocusAfterRebuild) {
            refocusAfterRebuild = false
            refocus()
        }
    }
    TvPageAdjustActions(
        TvPageAdjustAction(Icons.Rounded.GridView, stringResource(Lang.tv_wall_scale_title)) {
            val focused = wallHasFocus()
            beforeOpen()
            windows.open(
                TvAdjustWindow.WallScale(page) { changed ->
                    refocusAfterRebuild = focused && changed
                },
            )
        },
    )
    return refocusAfterRebuild
}

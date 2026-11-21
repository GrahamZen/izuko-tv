/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import me.him188.ani.app.ui.comment.UIComment

/**
 * 平板模式播放页侧边显示的那一页: 分栏时盖住右栏的推荐, 全屏时是从右边滑出的抽屉.
 */
internal enum class TabletSidePanel {
    /** 全部选集 (正片 / 特别篇, 剧照网格). 视频下面「选集」那一节的标题行打开, 全屏时是画面底下那一排的「选集」. */
    EPISODES,

    /** 数据源 (上游的选源界面: 自动匹配 / 手动查找 / BT). 数据源卡片的「更换」打开, 全屏时是画面右上的数据源. */
    SOURCES,

    /** 这一部的详情 (视频下面的「详情 ›」打开; 只在分栏). */
    DETAILS,

    /** 本集评论 (视频下面的评论卡片打开; 只在分栏). */
    COMMENTS,

    /** 一整楼 (评论面板里点「N 条回复」推进的那一层, 楼是 [TabletPlayerState.thread]). */
    THREAD,

    /** 弹幕面板: 弹幕列表 / 弹幕设置两页 (视频下面那一排的「弹幕」打开, 全屏时是画面底下那一排的; 分栏盖住右栏, 全屏是抽屉). */
    DANMAKU,
}

/**
 * 平板模式播放页 (分栏 / 全屏两态) 的界面状态. 只在平板模式 (`LocalTvTouchInputEnabled`) 下创建, 电视上没有这一层.
 *
 * - 分栏 (默认): 左边视频 + 视频下面这一部的信息, 右栏是推荐; [panels] 不空时栈顶那一页盖住右栏.
 * - 全屏: 只有视频; [drawer] 是从右边滑出、盖在画面上的那一页 (选集 / 数据源), 不缩画面.
 *
 * 全屏与分栏各记各的: 退出全屏时抽屉收起, 右栏回到进全屏之前那一页 (同 YouTube 平板).
 */
@Stable
internal class TabletPlayerState(
    fullScreen: Boolean = false,
    panels: List<TabletSidePanel> = emptyList(),
) {
    var fullScreen: Boolean by mutableStateOf(fullScreen)
        private set

    /** 分栏时盖住右栏的面板, 栈顶在最后. 空 = 右栏显示推荐. */
    var panels: List<TabletSidePanel> by mutableStateOf(panels)
        private set

    /** 全屏时右侧抽屉里那一页, null = 没开. */
    var drawer: TabletSidePanel? by mutableStateOf(null)
        private set

    val topPanel: TabletSidePanel? get() = panels.lastOrNull()

    /** 此刻侧边显示的那一页: 全屏看抽屉, 分栏看右栏面板 (null = 全屏没开抽屉, 或分栏显示推荐). */
    val shownPanel: TabletSidePanel? get() = if (fullScreen) drawer else topPanel

    fun enterFullScreen() {
        fullScreen = true
        drawer = null
    }

    fun exitFullScreen() {
        fullScreen = false
        drawer = null
        gesturesLocked = false
    }

    /**
     * 锁屏 (只在全屏, 同上游播放器 / 哔哩哔哩): 锁着时画面上只剩锁的按钮, 双击、长按倍速、拖进度、调亮度音量都不响应;
     * 点一下画面照样叫出锁的按钮. 退出全屏就解锁. 不存档 (重进播放页不锁).
     */
    var gesturesLocked: Boolean by mutableStateOf(false)

    /** 弹幕面板停在「弹幕设置」那页 (否则「弹幕列表」): 关了再开回到上次那页. 不存档. */
    var danmakuSettingsShown: Boolean by mutableStateOf(false)

    /**
     * 数据源面板的在线源用详细模式 (每个源一张卡片, 每条写资源全名); 否则简单模式: 一个源一行, 条目名与集数都对得上的
     * 做成线路名小胶囊 (同上游选源), 对不上的照样写全名与原因. 关了再开面板还是上次那样. 不存档.
     */
    var webSourcesDetailed: Boolean by mutableStateOf(false)

    fun toggleFullScreen() {
        if (fullScreen) exitFullScreen() else enterFullScreen()
    }

    /**
     * 开 / 关 [panel]: 全屏时是右侧抽屉, 分栏时盖住右栏 (换掉现在那一页, 不叠). 正开着的再点一下 = 关.
     */
    fun toggle(panel: TabletSidePanel) {
        if (fullScreen) {
            drawer = if (drawer == panel) null else panel
        } else {
            panels = if (topPanel == panel) emptyList() else listOf(panel)
            thread = null
        }
    }

    /** 关掉此刻显示的那一页 (栏顶的 ✕, 点抽屉外面): 全屏收抽屉; 分栏清空面板, 右栏回到推荐. */
    fun closeShown() {
        if (fullScreen) {
            drawer = null
        } else {
            panels = emptyList()
            thread = null
        }
    }

    /**
     * 正在看的那一楼 ([TabletSidePanel.THREAD] 的内容). 不存进 [Saver]: 评论是分页现取的, 回来时这一层收着.
     */
    var thread: UIComment? by mutableStateOf(null)
        private set

    /** 在分栏的右栏里往里推一层看 [comment] 这一整楼 (栏顶出现 ←). */
    fun openThread(comment: UIComment) {
        thread = comment
        panels = panels.filter { it != TabletSidePanel.THREAD } + TabletSidePanel.THREAD
    }

    /** 栏顶的 ←: 退一层. */
    fun pop() {
        val top = topPanel ?: return
        panels = panels.dropLast(1)
        if (top == TabletSidePanel.THREAD) thread = null
    }

    /**
     * 系统返回在平板这一层能收的东西: 抽屉 → 全屏退回分栏 → 右栏面板退一层. 收了一样返回 true;
     * 都没有时 false (交给播放器原来的路由, 再没有就退出播放页).
     */
    fun back(): Boolean = when {
        fullScreen && drawer != null -> {
            drawer = null
            true
        }

        fullScreen -> {
            exitFullScreen()
            true
        }

        panels.isNotEmpty() -> {
            pop()
            true
        }

        else -> false
    }

    /** [back] 此刻会不会收东西 (不改状态). */
    val canGoBack: Boolean get() = fullScreen || panels.isNotEmpty()

    companion object {
        /** 存全屏与右栏面板 (旋转屏幕、从播放页跳去别处再回来时恢复); 抽屉不存, 回来时收着. */
        val Saver: Saver<TabletPlayerState, Any> = listSaver(
            save = { listOf(it.fullScreen, ArrayList(it.panels.map { panel -> panel.name })) },
            restore = { saved ->
                val names = saved[1] as List<*>
                TabletPlayerState(
                    fullScreen = saved[0] as Boolean,
                    // 那一楼没存, 回来时不恢复这一层
                    panels = names.mapNotNull { name -> TabletSidePanel.entries.firstOrNull { it.name == name } }
                        .filter { it != TabletSidePanel.THREAD },
                )
            },
        )
    }
}

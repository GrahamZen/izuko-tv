/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.playback

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import me.him188.ani.app.platform.Context
import me.him188.ani.app.domain.player.VideoLoadingState

/**
 * 被保留在后台的那个播放会话是哪一集.
 *
 * 分两层:
 *
 * - **身份** ([subjectId] / [episodeId]): 入口按钮拿它导航回播放页, 宿主也拿它认"要播的正是
 *   某个会话此刻在播的那一集". **认会话只能比这两个字段**, 不能比整个对象 —— 展示字段是
 *   随条目信息加载才补上的, 拿一个刚构造的空壳去 `==` 现有会话必然不等, 于是每次回播放页都会
 *   把热好的会话销毁重建, 而且不报错 (保留会话整个功能静默失效). 用 [isSameEpisodeAs].
 * - **展示** ([subjectTitle] / [coverUrl] / [episodeSort]): 给入口面板显示"后台在播什么"用的
 *   (见 TV 的快捷菜单). 原先刻意不带这些, 理由是"要等条目信息加载" —— 那条对**保留会话**不
 *   成立: 会话的 ViewModel 就活在内存里, 用户能走到入口的时候它早加载完了, 宿主只是顺着
 *   已有的那条 flow 多取两个值, 不产生任何额外请求.
 *
 * 展示字段可能为空 (刚建会话、条目信息还没到的那一小会儿), 显示端要能接受空值.
 */
@Immutable
data class RetainedPlaybackSessionInfo(
    val subjectId: Int,
    val episodeId: Int,
    val subjectTitle: String = "",
    val coverUrl: String = "",
    /** 集号 (如 "12", "20.5"); 空 = 还不知道. */
    val episodeSort: String = "",
) {
    /** 是不是同一集 —— **只比身份字段**, 见类文档. */
    fun isSameEpisodeAs(subjectId: Int, episodeId: Int): Boolean =
        this.subjectId == subjectId && this.episodeId == episodeId
}

/**
 * 后台会话的播放进度.
 *
 * **故意不放进 [RetainedPlaybackSessionInfo]**: 那个对象被侧边栏"正在播放"条目等路径读着,
 * 而进度每秒变一次 —— 混在一起会让那些与进度无关的读者每秒重组一次. 单独一个状态, 只有
 * 动作面板在读, 面板不开就没人订阅.
 *
 * 实际上它基本是静止的: 退出播放页会把会话按暂停 (跟随房主那种情形除外).
 */
@Immutable
data class PlaybackProgress(
    val positionMillis: Long,
    /** 总时长; 0 = 还不知道 (容器头没读完). */
    val durationMillis: Long,
)

/**
 * 后台会话此刻处在哪一步.
 *
 * 入口面板拿它当"正在播放"那行小字显示 (成品文案见 `playbackSessionStatusText`): 慢的源要十几秒,
 * 中途还可能自动换源、最后卡在需要手选 —— 用户退出播放页正是为了不干等, 这行字让他**打开面板的
 * 那一刻**就知道进行到哪了, 而不是只能等一声就绪提示 (或者等不到).
 *
 * 与 `RetainedPlaybackNotice` 同源但性质不同: 那个是**事件** (只在后台、只发一次、要响铃),
 * 这个是**状态** (随时可读, 前后台都更新). 两套判据一致, 见 holder 里 `statusOf` 与 `problemOf`.
 */
@Immutable
sealed interface PlaybackSessionStatus {
    /** 还在搜数据源 / 解析播放地址. */
    data object Preparing : PlaybackSessionStatus

    /** 地址已经交给播放器, 在缓冲首帧. */
    data object Buffering : PlaybackSessionStatus

    /** 缓冲够了, 回去就能看. */
    data object Ready : PlaybackSessionStatus

    /** 查完了但没有自动选中 (偏好不是 WEB 时不自动选), 在等用户自己挑. */
    data object NeedsSelection : PlaybackSessionStatus

    /** 所有数据源都查完了, 没有可播放的结果. */
    data object NoMedia : PlaybackSessionStatus

    /** 流解析出来了但播放器打不开 (常见于 web 源嗅探到的地址失效). */
    data object PlayerError : PlaybackSessionStatus

    /** 解析数据源失败; [cause] 决定显示哪一句原因. */
    data class LoadFailed(val cause: VideoLoadingState.Failed) : PlaybackSessionStatus
}

/**
 * 后台保留着的播放会话的入口把手.
 *
 * 会话本体 (播放器 + 整条起播流水线) 挂在应用根部一个应用级 ViewModel 上, 退出播放页不销毁;
 * 而"回到会话"的入口按钮散落在各页面里 (遥控器形态是侧边栏的"正在播放"条目). 入口经本把手
 * 判断该不该显示、结束会话, 导航则由入口自己按 [RetainedPlaybackSessionInfo] 发起 ——
 * 入口**绝不自己去持有会话**, 那样会造出第二个播放器.
 *
 * 只在开了 `AniUiBehavior.retainPlaybackSession` 的形态下有非空的 [session]; 其余形态拿到的是
 * [None], 入口自然不渲染.
 */
@Stable
interface PlaybackSessionEntry {
    /** 当前保留着的会话; `null` = 没有 (没进过播放页, 或已被结束/被新的会话替换后又结束). */
    val session: RetainedPlaybackSessionInfo?

    /** 会话的播放进度; `null` = 还不知道 (刚建会话/没有会话). 见 [PlaybackProgress]. */
    val progress: PlaybackProgress?

    /** 会话进行到哪一步了; `null` = 没有会话. 见 [PlaybackSessionStatus]. */
    val status: PlaybackSessionStatus?

    /**
     * 结束会话: 销毁播放器与整条流水线.
     *
     * 必须有这个出口 —— 否则会话会一直活着 (占着解码器与缓冲), 用户只能靠再点开另一集来替换它.
     */
    fun close()

    /**
     * **不进播放页, 直接在后台把某一集起起来**: 建会话 + 建它的 ViewModel, 整条"搜数据源 → 选源 →
     * 起播"的流水线就跑起来了, 就绪时照常提示 (与退出播放页留下的那种会话完全同源, 之后进播放页
     * 拿回的就是这一个).
     *
     * 它是这套机制的另一半: 原先只能"进去等着它加载, 或者进去了再退出来等", 而慢的源要十几秒 ——
     * 现在可以在动作面板上一键让它先热起来, 人继续浏览.
     *
     * 已经有会话在播这一集时什么都不做 (返回 true); 播别的则**替换**掉 (同"只留一个会话"那条规矩).
     *
     * @return false = 这个形态不支持 (没有保留会话的机制, 见 [None])
     */
    fun startInBackground(subjectId: Int, episodeId: Int, context: Context): Boolean

    /** 不保留会话的形态 (手机 / 桌面) 用的空实现. */
    object None : PlaybackSessionEntry {
        override val session: RetainedPlaybackSessionInfo? get() = null
        override val progress: PlaybackProgress? get() = null
        override val status: PlaybackSessionStatus? get() = null
        override fun close() {}
        override fun startInBackground(subjectId: Int, episodeId: Int, context: Context): Boolean = false
    }
}

/**
 * 由 AniAppContent 在应用根部 provide (NavHost 与入口按钮都在里面).
 *
 * 默认 [PlaybackSessionEntry.None]: 预览与测试里没有宿主, 入口按钮自然不渲染, 不必到处判空.
 */
val LocalPlaybackSessionEntry = compositionLocalOf<PlaybackSessionEntry> { PlaybackSessionEntry.None }

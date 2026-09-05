/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem

/*
 * 片尾「接下来播放」的**状态**: 什么时候该提示、还剩几秒、下一集是哪一集.
 *
 * 界面不在本文件: 它是选集条自己展开成的倒计时态 (见 TvPlayerEpisodeStrip 与
 * TvPlayerOverlayState.upNextCountdown).
 *
 * 触发分两档, 与业界一致 (Plex/Netflix 用片尾标记, Kodi/Jellyfin 用固定秒数):
 * - **有 ED 标记**: **片尾放完**就摆出来 (那才是"这一集的内容播完了"; ED 进行中该给的是
 *   「跳过 ED」按钮, 两者因此天然错开), 但在最后 N 秒之前**不倒计时** —— 还想看次回预告的人
 *   不该被催, 想直接走的人按一下确认就走;
 * - **没有 ED 标记**, 或 ED 结束后剩得太多 (那多半是误判): 距结尾不足 N 秒时出现, 出现即倒计时.
 *
 * **倒计时到 0 不由这里切集**: 归零那一刻就是视频自然结束, 切集照旧由
 * `SwitchNextEpisodeExtension` 的 MediaEnded 负责 —— 本状态只是把那件本来就会发生的事提前
 * 可视化, 不新增第二条切集路径 (两条路径一定会在边界上打架). 也因此"取消"只是关掉界面:
 * 真要让片子播完停下来, 开关在设置 - 播放 - 自动连播.
 *
 * 关掉自动连播时照常展开, 只是不倒计时也不自动播: 那时它就是一句"下一集是这个, 按确定就播".
 */

/**
 * 「接下来播放」提示的状态. 位置每 100ms 一跳, 而本状态里只有 [countdownSeconds] 按秒变、
 * [visible] 整段只翻一两次 —— 相同值写回快照状态不触发重组, 所以驱动侧可以放心地按播放位置
 * 全速算, 读侧 (播放器根部) 只会在真正翻转时重组.
 */
@Stable
class TvUpNextState {
    /** 倒计时态该不该在场 (触发窗口内, 且这一趟没被收掉). */
    var visible: Boolean by mutableStateOf(false)
        internal set

    /**
     * **预告段**: 触发窗口开始前的 [TV_UP_NEXT_PREROLL_MILLIS], 只把选集条摆出来 (焦点还在当前
     * 播放集), 倒计时那套装饰还不给.
     *
     * 这一段的意义是让"到点滚到下一集"这个动作有个起点: 直接凭空冒出一张已经在倒数的卡, 人得先
     * 找一遍"这是哪一集"; 先摆出选集条、焦点在**正在看的这一集**上, 到点行往左滑一格, 意思就自明了.
     * 只在纯视频态做 (调用方判): 其余层级本来就有东西占着屏幕.
     */
    var preRoll: Boolean by mutableStateOf(false)
        internal set

    /** 剩余秒数; null = 不倒计时 (片尾刚放完那一段, 或关掉了自动连播). */
    var countdownSeconds: Int? by mutableStateOf(null)
        internal set

    /**
     * 倒计时**已经走了**多少 (0..1), 给卡片底部那条进度条用; 0 = 不倒计时.
     *
     * 与 [countdownSeconds] 分开存: 那个按秒跳 (文字), 这个按位置采样跳 (~100ms, 进度条要顺滑).
     * 卡片在绘制 lambda 里读它, 每次变化只失效绘制不重组.
     */
    var countdownFraction: Float by mutableFloatStateOf(0f)
        internal set

    /** 下一集; null = 没有下一集 (或确定还没播出), 此时卡片不出现. */
    var nextEpisode: EpisodeListItem? by mutableStateOf(null)
        internal set

    /**
     * 用户按返回收掉了**这一次**提示.
     *
     * 只压住当前这一趟触发: 播放位置走出触发窗口 (拖回片尾之前) 就由驱动侧自动复位, 再放过来
     * 卡片照样出现; 换集也复位. 做成"这一集再也不提示"的话, 拖回去重看那一段会毫无反应, 与
     * OP/ED 那边"每次拖回段落之前都要重新武装"是同一个道理.
     */
    internal var dismissed: Boolean by mutableStateOf(false)

    fun dismiss() {
        dismissed = true
        visible = false
        // 预告段一并压住, **当场**置假: 驱动侧要等下一次位置采样 (~100ms) 才算到 dismissed,
        // 那段空窗里调用方刚 hideAll 完, 预告段那条效应会立刻把选集条又开回来
        preRoll = false
    }
}

/**
 * 驱动 [TvUpNextState].
 *
 * 两条效应各管一件事, 不合并: 换集重算下一集是低频的, 位置驱动是高频的.
 */
@Composable
internal fun rememberTvUpNextState(vm: EpisodeViewModel): TvUpNextState {
    val state = remember { TvUpNextState() }

    // 下一集: 走 EpisodeViewModel 的单一来源 —— 卡片上写的那一集必须与自动连播真正会播的
    // 那一集是同一集 (它还挡掉了"确定还没播出"的下一集). 每次发射同时也是"换集了"的信号
    LaunchedEffect(state, vm) {
        vm.autoPlayNextEpisodeIdFlow.collect { nextId ->
            state.dismissed = false
            state.visible = false
            state.preRoll = false
            state.countdownSeconds = null
            state.nextEpisode = if (nextId == null) {
                null
            } else {
                vm.episodeListUiStateFlow.mapNotNull { it?.allEpisodes }
                    .map { list -> list.firstOrNull { it.episodeId == nextId } }
                    .first { it != null }
            }
        }
    }

    // 位置驱动: 决定卡片在不在场、倒不倒计时
    LaunchedEffect(state, vm) {
        combine(
            vm.player.currentPositionMillis,
            vm.player.mediaProperties.map { it?.durationMillis ?: 0L }.distinctUntilChanged(),
            snapshotFlow { vm.playerSkipOpEdState.edChapterEndMillis },
            snapshotFlow { vm.videoScaffoldConfig.upNextTipLeadSeconds to vm.videoScaffoldConfig.autoPlayNext },
            // 一起看: 自动连播被这道闸拦着 (见 SwitchNextEpisodeExtension), 卡片也得跟着不出现 ——
            // 否则它会倒数完然后什么都不发生, 是句假话
            vm.playbackAutomationSuppressed,
        ) { pos, duration, edEnd, (leadSeconds, autoPlayNext), automationSuppressed ->
            val enabled = leadSeconds > 0 && duration > 0L &&
                    state.nextEpisode != null && !automationSuppressed
            val leadMillis = leadSeconds * 1000L
            val remaining = duration - pos
            // 片尾标记只用来"提前把卡片摆出来", 倒计时一律按距结尾算.
            //
            // **认的是 ED 结束, 不是 ED 开始**: 片尾放完才是"这一集的内容播完了"; ED 进行中这一集
            // 还没完, 那会儿该给的也是「跳过 ED」按钮 (按它多半正是为了直奔后面的次回预告),
            // 卡片顶掉它就是功能倒退. 按 ED 结束算, 两者天然错开 —— 按钮只在人**处于** ED 段内时
            // 给, 卡片只在走出 ED 之后出.
            //
            // 还要求 ED 结束点确实靠近片尾 ([ED_TAIL_MAX_REMAINDER_MILLIS]): OP/ED 是按"章节中点
            // 落在时间轴后半段"判的 ED, 万一把中间某段认成 ED, 卡片会从那里一直挂到结尾.
            val edEndsNearTail = edEnd != null && duration - edEnd <= ED_TAIL_MAX_REMAINDER_MILLIS
            val afterEndCredits = edEndsNearTail && pos >= edEnd
            val inLeadWindow = remaining in 0..leadMillis
            val inWindow = enabled && (afterEndCredits || inLeadWindow)
            // 预告段 = 同样两条判据各往前挪 [TV_UP_NEXT_PREROLL_MILLIS], 减去窗口本身
            val preRollMillis = TV_UP_NEXT_PREROLL_MILLIS
            val inPreRoll = enabled && !inWindow && (
                    (edEndsNearTail && pos >= edEnd - preRollMillis) ||
                            remaining in 0..(leadMillis + preRollMillis)
                    )
            // **走出触发窗口就重新武装**: 按返回只收掉"这一次", 不是"这一集再也不提示" ——
            // 拖回片尾之前再放过来, 卡片该照样出现 (同 PlayerSkipOpEdState 对 OP/ED 的重新武装:
            // 一锤子买卖会让"拖回去重看"这件事变得没法预期).
            // 判据连预告段一起算: 只判触发窗口的话, 预告段里 (还没 inWindow) 就会把用户刚按下的
            // "收掉"复位, 于是返回键在那一秒半里等于没按
            if (!inWindow && !inPreRoll) state.dismissed = false
            state.visible = inWindow && !state.dismissed
            state.preRoll = inPreRoll && !state.dismissed
            val countingDown = state.visible && autoPlayNext && inLeadWindow
            state.countdownSeconds = if (countingDown) {
                // 向上取整并且不显示 0: 归零那一刻靠的是播放器自己报 MediaEnded, 时长与真实结尾
                // 差个几百毫秒是常事, "0 秒后播放"挂在那里像卡死了
                ((remaining + 999) / 1000).toInt().coerceAtLeast(1)
            } else {
                null
            }
            state.countdownFraction = if (countingDown && leadMillis > 0) {
                ((leadMillis - remaining).toFloat() / leadMillis).coerceIn(0f, 1f)
            } else {
                0f
            }
        }.collect()
    }

    return state
}

/**
 * 预告段的长度: 触发窗口开始前多久先把选集条摆出来 (见 [TvUpNextState.preRoll]).
 *
 * 1.5 秒: 选集条自己的滑入是 250ms, 剩下一秒多刚够眼睛落到"正在看的这一集"上; 再短就与随后
 * 那一格滚动糊成一个动作 (等于没有起点), 再长就是白占着屏幕 —— 这一段本身不传达任何新信息.
 */
private const val TV_UP_NEXT_PREROLL_MILLIS = 1_500L

/**
 * ED 结束之后最多还剩多少, 才认它是"这一集的片尾".
 *
 * 90 秒: 装得下次回预告 (普遍 30 秒上下) 加黑场/台标. 判宽了的坏处是万一某段正片被误判成 ED
 * (判据是"章节中点落在时间轴后半段"), 提示会从那儿一直挂到结尾; 超出这个数就退回按距结尾
 * N 秒的走法, 与没有 ED 标记的集数一样.
 */
private const val ED_TAIL_MAX_REMAINDER_MILLIS = 90_000L

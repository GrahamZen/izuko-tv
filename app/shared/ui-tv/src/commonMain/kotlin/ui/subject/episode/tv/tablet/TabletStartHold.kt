/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.PlaybackStartHold
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_play
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * 平板模式的播放页 (连同数据到之前的占位) 一出现就按设置「打开时先不播放」决定这个会话停不停 ([PlaybackStartHold.decide],
 * 每个会话只算第一次)。组合时当场记下页面出现了 ([PlaybackStartHold.pageShown]), 设置读到之前流水线就装片子 (记住的源一选就中、
 * 直链当场解析) 的先停着装; 读到后不停的, 同流水线自己的「装好就播」一样先问保留会话的门 (页面不在眼前、应用在后台时由它记账, 回来再播)。
 * 设置在会话的作用域里读: 页面刚出现就退出也读得完。
 */
@Composable
internal fun TabletStartHoldDecision(vm: EpisodeViewModel) {
    remember(vm) {
        if (vm.startHold.pageShown()) {
            vm.backgroundScope.launch(Dispatchers.Main) {
                val enabled = GlobalKoin.get<SettingsRepository>().videoScaffoldConfig.flow.first().holdStartOnOpen
                when {
                    vm.startHold.decide(vm.subjectId, enabled) -> if (vm.playbackStartGate.admit()) vm.player.play()
                    // 读设置期间别处已经让播放器播了 (控件上的播放键): 不再停
                    vm.player.state.value.playWhenReady -> vm.startHold.release()
                }
            }
        }
    }
}

/**
 * 平板播放页: [TabletStartHoldDecision], 再加上两种不再停的情况: 页面上的那一集 [episodeId] 换了
 * ([PlaybackStartHold.onEpisodeChanged]); 别处让播放器播了 —— 控件上的播放键、Web 控制台的播放 / 暂停、系统小窗里的播放
 * 都直接让播放器播, 这时剧照与播放键一起收起, 只看进页之后的翻转 (进页那一刻的值不算)。
 */
@Composable
internal fun TabletStartHoldEffects(vm: EpisodeViewModel, episodeId: Int) {
    TabletStartHoldDecision(vm)
    LaunchedEffect(vm, episodeId) { vm.startHold.onEpisodeChanged(episodeId) }
    LaunchedEffect(vm) {
        vm.player.state.map { it.playWhenReady }.distinctUntilChanged().drop(1).filter { it }.collect {
            vm.startHold.release()
        }
    }
}

/** 这一集是不是停着等点播放 (换了集就不是, 见 [PlaybackStartHold]). */
@Composable
internal fun rememberTabletStartHeld(vm: EpisodeViewModel, episodeId: Int): Boolean {
    val held by vm.startHold.heldEpisodeId.collectAsState()
    return held == episodeId
}

/**
 * 停着等点播放时画面正中那颗播放键: 片子已经交给播放器的 (包括还在打开的) 当场起播; 资源还没选好的, 换成平时的加载提示,
 * 装上时照常起播 (那时 [PlaybackStartHold] 已经放开)。还没有片子时按播放同控件上的播放键, 不碍事。
 *
 * 只画一个白色三角, 不加底, 同画面中央的暂停反馈 (白色图标 + 错开一点的黑色投影); 剧照已经压暗一层, 亮处也看得清。
 * 可点的范围比三角大一圈, 不用点得很准。
 */
@Composable
internal fun TabletStartHoldButton(vm: EpisodeViewModel, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(TABLET_START_HOLD_TOUCH_SIZE)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                if (vm.startHold.release()) vm.player.play()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = Color.Black.copy(alpha = TABLET_START_HOLD_SHADOW_ALPHA),
            modifier = Modifier.size(TABLET_START_HOLD_ICON_SIZE).offset(x = 1.dp, y = 1.5.dp),
        )
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = stringResource(Lang.video_player_play),
            tint = Color.White,
            modifier = Modifier.size(TABLET_START_HOLD_ICON_SIZE),
        )
    }
}

private val TABLET_START_HOLD_TOUCH_SIZE = 96.dp
private val TABLET_START_HOLD_ICON_SIZE = 72.dp

/** 三角下面那层投影的不透明度, 同暂停反馈 */
private const val TABLET_START_HOLD_SHADOW_ALPHA = 0.55f

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.TV_PLAY_KEYS
import me.him188.ani.app.ui.foundation.tvLongPressKey

/**
 * 遥控器播放键**短按 = [onPlay]** (直达播放); 长按刻意不在这里处理.
 *
 * 播放键长按是全局手势「打开动作面板」(2026-08-18 之前是"直接跳回正在播放"), 由应用根部的统一
 * 跟踪器认领 (见 TvPageVariants 与
 * [TvKeyLongPressHost][me.him188.ani.app.ui.foundation.TvKeyLongPressHost] 的并存规则):
 * 根部拦截器先收到事件, 认领那一刻起本节点再也看不到后续连发与 KeyUp, 所以这里的
 * onLongPress 永远到不了阈值 (传空只为占位). 旧的「长按 = 强制刷新」挪进了长按返回的
 * 快捷菜单 (TvPageRefreshHandler 注册, 各页自理).
 *
 * 仍要用 [tvLongPressKey] 而不是裸的 onKeyEvent: 短按要等到 KeyUp 才能确定 (期间不能让
 * KeyDown 漏下去被别人当成"按了播放"), 且要同一套残余免疫 —— 本 modifier 必须挂在**已经
 * 拥有播放键的那个节点**上并接管它 (网格的键路由把 onPlayKey 让给了它).
 *
 * @param onPlay 短按触发, 返回是否已处理 (焦点不在卡片上时返回 false)
 */
@Composable
fun tvPlayKeyShortPress(
    onPlay: () -> Boolean = { false },
): Modifier {
    // 不 remember 这个 Modifier: 它要读到最新的 onPlay (remember 会把首次组合那一份闭包
    // 永久留下), 而 modifier 元素本身很轻, 每次重组重建无所谓
    return Modifier.tvLongPressKey(
        onLongPress = {
            // 到不了这里 (根部拦截器在阈值那一发就把手势认领走了); 万一根部宿主不在场
            // (预览/测试), 空操作也比误触发好
        },
        onShortPress = { onPlay() },
        keys = TV_PLAY_KEYS,
    )
}

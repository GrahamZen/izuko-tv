/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 触屏设备上的电视界面里, 照上游写法的页面 (见 app 的 ProvideTouchUpstreamUi) 换掉之前的界面行为, 即外壳 (电视界面) 的; 别处为 null.
 *
 * 这种页面里个别入口仍打开电视版的弹窗 (人物 / 角色页的「查看全部」与评论, 同详情页): 按 [touchShellUiBehavior] 判断开哪种,
 * 弹窗包在 [ProvideTouchShellUiBehavior] 里, 画法与详情页打开的一样. 页面本身 (卡片点一下就打开、人物预览) 照上游写法不变.
 */
val LocalTouchShellUiBehavior: ProvidableCompositionLocal<AniUiBehavior?> = staticCompositionLocalOf { null }

/** 外壳的界面行为: 在照上游写法的页面里是换掉之前的那份, 别处就是当前的. */
val touchShellUiBehavior: AniUiBehavior
    @Composable get() = LocalTouchShellUiBehavior.current ?: LocalAniUiBehavior.current

/** [content] 照外壳的界面行为 ([touchShellUiBehavior]); 不在照上游写法的页面里时原样. */
@Composable
fun ProvideTouchShellUiBehavior(content: @Composable () -> Unit) {
    val shell = LocalTouchShellUiBehavior.current
    if (shell == null) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalAniUiBehavior provides shell,
        LocalTouchShellUiBehavior provides null,
        content = content,
    )
}

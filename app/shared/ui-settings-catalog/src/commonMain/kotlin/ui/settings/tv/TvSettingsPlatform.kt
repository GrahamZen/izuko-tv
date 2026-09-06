/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.platform.Context

/**
 * 设置清单里只有电视版才有的东西: Web 控制台、首次引导、性能诊断、经 Worker 扫码登录 Bangumi、「本版新功能」页.
 * 电视版 (ui-tv) 全给; 别的端没有的留默认 (null / 空), 用到它的项不显示 (控制台的二维码照样建, 由那一端换成自己的入口, 见 [TvQr.console]).
 */
interface TvSettingsPlatform {
    /** Web 控制台的地址; 没有控制台或没连局域网时为 null. */
    val consoleUrl: Flow<String?> get() = flowOf(null)

    /** 清掉「做过首次引导」的标记 (重启后重走引导); null = 没有首次引导. */
    val resetOnboarding: ((Context) -> Unit)? get() = null

    /** 性能诊断此刻在做什么 (录制时随剩余秒数更新, 空闲时为 null); null = 没有性能诊断. */
    val perfStatus: Flow<TvText?>? get() = null

    /** 最近一次性能诊断的结论 (还没诊断过为 null); null = 没有性能诊断. */
    val perfLatest: Flow<String?>? get() = null

    /** 发起一次经 Worker 中转的 Bangumi 授权 (手机扫码授权, 授权完跳回本机); null = 没有. */
    val startRelayLogin: (() -> TvRelayLoginStart)? get() = null

    /** 「本版新功能」整页 (盖在设置页上); null = 没有. */
    val whatsNewPage: (@Composable (dismiss: () -> Unit) -> Unit)? get() = null

    /** 都不给: 没有这些功能的端. */
    object None : TvSettingsPlatform
}

/** 经 Worker 中转发起授权的结果 (见 [TvSettingsPlatform.startRelayLogin]). */
sealed interface TvRelayLoginStart {
    /** 起好了: [url] 是授权页地址, 画成二维码给手机扫. */
    data class Ready(val url: String) : TvRelayLoginStart

    /** 这个构建没配中转. */
    data object Unsupported : TvRelayLoginStart

    /** 没连局域网 (跳回来要经局域网). */
    data object NoLan : TvRelayLoginStart
}

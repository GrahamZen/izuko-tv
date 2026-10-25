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
 * 设置清单里只有电视版才有的东西: Web 控制台、首次引导、性能诊断、经 Worker 扫码登录 Bangumi、「本版新功能」页、选人页.
 * 电视版 (ui-tv) 全给; 别的端没有的留默认 (null / 空), 用到它的项不显示 (控制台的二维码照样建, 由那一端换成自己的入口, 见 [TvQr.console]).
 */
interface TvSettingsPlatform {
    /** Web 控制台的地址; 没有控制台或没连局域网时为 null. */
    val consoleUrl: Flow<String?> get() = flowOf(null)

    /** 这一端有 Web 控制台 (电视版). 没有的端 (iOS) 不列只能在控制台里做的项, 也不画控制台的二维码 (见 consoleQr). */
    val hasConsole: Boolean get() = false

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

    /** 打开选人页 (切换用户, 见多用户); null = 没有. */
    val showProfilePicker: (() -> Unit)? get() = null

    /** 有触摸屏时界面用平板模式还是电视模式 (见 [TvInterfaceModeSwitch]); null = 没有这个选择. */
    val interfaceMode: TvInterfaceModeSwitch? get() = null

    /**
     * 打开应用内的反馈弹窗 (触屏端: 平板 / iPad): 「我的反馈」点了直接开它.
     * null = 没有 (电视上写反馈用 Web 控制台, 「我的反馈」进下一层、画控制台的码).
     */
    val openFeedback: (() -> Unit)? get() = null

    /** debug 包的调试接口 (开发机经局域网连进来测这一端): 「调试」里列出它的地址, 点一下复制; null = 没有. */
    val debugConsole: TvDebugConsoleEntry? get() = null

    /** 都不给: 没有这些功能的端. */
    object None : TvSettingsPlatform
}

/** 平板模式 / 电视模式的读写 (见 [TvSettingsPlatform.interfaceMode]); 换了模式要重启应用才生效. */
interface TvInterfaceModeSwitch {
    fun isTvMode(context: Context): Boolean

    /** 写下新的模式 (同步写盘: 调用方紧接着重启). */
    fun setTvMode(context: Context, tvMode: Boolean)
}

/** 调试接口的地址与复制 (见 [TvSettingsPlatform.debugConsole]). */
interface TvDebugConsoleEntry {
    /** 没在监听或还没拿到本机地址时为 null. */
    val url: Flow<String?>

    fun copy(url: String)
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

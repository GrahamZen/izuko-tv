/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.him188.ani.app.ui.theme.DefaultSeedColor

@Serializable
enum class DarkMode {
    AUTO, LIGHT, DARK,
}

/** TV: 在主页 (探索页 hero) 上按返回键那一下做什么. 见 [ThemeSettings.tvExitBehavior]. */
@Serializable
enum class TvExitBehavior {
    /** 直接退出应用 —— 加确认之前的老行为. */
    Direct,

    /** 弹出动作面板 (焦点落「退出应用」), 顺带能看到后台在播什么. */
    Panel,

    /** 连按两次: 第一次只提示"再按一次退出", 窗口内再按一下才真退. */
    DoubleBack,
}

/**
 * TV: 某个键**长按**时做什么.
 *
 * 两个键各配各的 (见 [ThemeSettings.tvBackLongPress] / [ThemeSettings.tvPlayLongPress]), 而不是
 * 一个"哪些键能开面板"的三选一 —— 后者有个空档: 选"只有返回键开面板"时, 长按播放键就闲置了,
 * 而那个手势本身是有用的 (它原先就是"一步回到正在播放").
 */
@Serializable
enum class TvLongPressAction {
    /** 打开动作面板. */
    Panel,

    /** 直接回到后台正在播的那一集 (没有会话时什么都不做). */
    Resume,

    /**
     * 不认领, 保持这个键的普通语义.
     *
     * 只给返回键用: 播放键长按不认领的话那个手势就彻底空了, 没有意义.
     * 注意选它之后, 若遥控器又没有播放键 (Chromecast 那类精简遥控器), 动作面板就没有入口了 ——
     * 设置项的说明里写着这句.
     */
    None,
}

@Serializable
@Immutable
data class ThemeSettings(
    val darkMode: DarkMode = DarkMode.AUTO,
    val useDynamicTheme: Boolean = false, // only supported on Android with Build.VERSION.SDK_INT >= 31
    // TODO: Default "true" if supported (on Android, Build.VERSION.SDK_INT >= 31)
    val useBlackBackground: Boolean = false,
    val alwaysDarkInEpisodePage: Boolean = false,
    val useDynamicSubjectPageTheme: Boolean = false,
    val seedColorValue: ULong = DefaultSeedColor.value,
    val enableAnimatedGradientSubjectPage: Boolean = false,
    val enableFrostedGlassEffect: Boolean = false,
    /** TV: 探索页使用沉浸式布局 (Hero 轮播); 关闭则回退上游原布局 (低端机可关以降低开销). */
    val tvImmersiveExploration: Boolean = true,
    /** TV: 条目详情页使用沉浸式布局 (Hero 首屏); 关闭则回退上游通用多栏布局. */
    val tvImmersiveDetails: Boolean = true,
    /** TV: 新番时间表使用日期胶囊 + 海报网格布局; 关闭则回退上游 15 天并排的纵向列表. */
    val tvImmersiveSchedule: Boolean = true,
    /**
     * TV: 退出播放页后保留播放会话 (播放器与整条"搜索数据源 → 选源 → 起播"的流水线),
     * 由侧边栏"正在播放"条目回去; 数据源在后台就绪时弹一次提示.
     *
     * 默认开: 它解决的是"等数据源要十几秒"这个真实痛点 —— 退出去干别的, 加载好了再回来.
     * 关掉则回到上游行为: 退出即销毁, 每次进来从头搜索. 想省内存 (保留的会话占着一个
     * 暂停中的解码器与缓冲区) 或觉得"退出了还占着资源"不放心的用户可以关.
     */
    val tvRetainPlaybackSession: Boolean = true,
    /**
     * TV: [tvRetainPlaybackSession] 的后台提示响哪一声 ([NoticeSoundKind.None] = 只弹 toast 不出声).
     *
     * 存在这里而不是 `VideoScaffoldConfig`: 它跟着上面那条开关走, 同一个功能的两个参数放一起.
     */
    val tvNoticeSound: NoticeSoundKind = NoticeSoundKind.Default,
    /**
     * **已被 [tvExitBehavior] 取代, 只留着做迁移** —— 判断行为一律用 [exitBehavior], 别读这个.
     *
     * 它原先是个布尔: 开 = 在主页按返回先弹确认框, 关 = 直接退出. 升级成三选一之后不能直接删:
     * 这套设置的 JSON 是 `encodeDefaults = false`, 显式关过它的人存着 `{"tvExitConfirmation":false}`,
     * 字段一没这份选择就丢了 (表现为"我明明关了确认, 更新完又回来了").
     */
    val tvExitConfirmation: Boolean = true,
    /**
     * TV: 在主页按返回键那一下的行为 (三选一, 见 [TvExitBehavior]).
     *
     * **`null` = 还没显式选过**, 这时按老的布尔开关 [tvExitConfirmation] 推导 —— 读取一律走
     * [exitBehavior], 别直接读这个字段. 这套设置的 JSON 是 `encodeDefaults = false`, 显式关过
     * 旧开关的人存着 `{"tvExitConfirmation":false}`, 直接换字段会把他们的选择丢掉.
     */
    val tvExitBehavior: TvExitBehavior? = null,
    /**
     * TV: **长按返回键**做什么 (见 [TvLongPressAction]).
     *
     * 默认开面板. 它是精简遥控器 (没有播放键) 唯一够得到面板的入口, 所以三档里唯独它允许 [TvLongPressAction.None].
     */
    val tvBackLongPress: TvLongPressAction = TvLongPressAction.Panel,
    /**
     * TV: **长按播放键**做什么 (见 [TvLongPressAction]).
     *
     * 默认开面板. 选 [TvLongPressAction.Resume] 就是旧行为"一步跳回正在播放" —— 与返回键配成
     * "返回开面板 / 播放直接回去"的分工, 两个手势各司其职而不是重复.
     */
    val tvPlayLongPress: TvLongPressAction = TvLongPressAction.Panel,
    /**
     * TV: 完整视觉效果 (**默认关**), 即不为低端设备让步的那一档.
     *
     * 一个开关打包全部"好看但费机器"的取舍, 因为需要其中一项的设备通常三项都扛得住:
     * - 过渡动画: 跨分类切换的卡片滑动 (关 = 渐隐渐现);
     * - 常驻装饰动画: 加载占位脉动、hero 长标题无限跑马灯 (关 = 静态 / 滚固定次数即停);
     * - 图片档位: "继续观看"hero 背景剧照用 TMDB 原图 (关 = w1280).
     *
     * 默认关: 实测这三项分别贡献了换分类的掉帧、页面永远进不了静止态的常驻底噪、
     * 每次换卡 8-33MB 的位图解码 —— 而收益在 10-foot 观看距离上本就不明显.
     * 高性能盒子的用户在设置里一键开回完整档.
     */
    val tvFullVisualEffects: Boolean = false,
    /**
     * TV: 界面整体缩放系数, 叠加在系统 density 之上 (1f = 跟随系统).
     *
     * 不少电视 / 盒子上报的 densityDpi 与实际面板不匹配 (常见于强制 4K UI、厂商魔改 ROM),
     * 导致界面整体偏大或偏小, 而这在系统设置里无从调整. 这里给用户一个纯客户端的补偿系数.
     *
     * 缩放的是 density 而非 fontScale: `sp -> px` 本身就要乘 density, 所以只改 density
     * 就能让文字和布局等比缩放; 两个都改会导致文字被缩放两次.
     */
    val uiScale: Float = 1f,
    @Suppress("PropertyName") @Transient val _placeholder: Int = 0,
) {
    @Transient
    val seedColor: Color = Color(seedColorValue).let {
        // 4.4.0-alpha01 的默认是 Color.Unspecified, 4.4.0-alpha02 默认是 DEFAULT_SEED_COLOR. 所以要替换一下
        if (it == Color.Unspecified) DefaultSeedColor else it
    }

    /**
     * 已 clamp 的 [uiScale], 供渲染直接使用: 持久化的值可能来自旧版本或损坏的配置.
     *
     * clamp 用的是 [UI_SCALE_MIN] / [UI_SCALE_MAX] 这两个 `const` 而不是 [UI_SCALE_RANGE]:
     * `const` 在编译期就内联成字面量, 而 companion 里的 `val` 是运行期字段 —— 构造函数若去读它,
     * 就会和同一个 companion 里的 [Default] 抢初始化顺序 (`Default` 先初始化 → range 还是 null → NPE).
     */
    @Transient
    val effectiveUiScale: Float =
        if (uiScale.isFinite()) uiScale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX) else 1f

    /**
     * 实际生效的"主页按返回"行为 —— **读这个, 别读 [tvExitBehavior]**.
     *
     * 没显式选过时 (`null`) 由老的布尔开关推导: 显式关过确认的人继续得到"直接退出", 其余人得到
     * 新的默认 [TvExitBehavior.DoubleBack]. 之所以默认从"弹面板"改成"连按两次": 它比直接退出安全
     * (挡住单次误按), 又比面板快 (不用看、不用挪焦点), 而面板本身并没有因此失去 —— 长按随时能开.
     */
    @Transient
    val exitBehavior: TvExitBehavior =
        tvExitBehavior ?: if (tvExitConfirmation) TvExitBehavior.DoubleBack else TvExitBehavior.Direct

    companion object {
        @Stable
        val Default = ThemeSettings()

        /**
         * [uiScale] 的下界. 够小到能救回"全是巨型卡片"的机器.
         */
        const val UI_SCALE_MIN = 0.5f

        /**
         * [uiScale] 的上界.
         *
         * 2.5 是留了余量的 2.0: 最典型的故障是 4K 面板仍上报 1080p 的 densityDpi (320 而非 640),
         * 需要的补偿恰好是 2.0 —— 若把上界就设成 2.0, 这类设备只能顶着满档用, 想再大一点都没有余地.
         */
        const val UI_SCALE_MAX = 2.5f

        /** [uiScale] 的步进, 即一次方向键 / 一格刻度的变化量. */
        const val UI_SCALE_STEP = 0.1f

        /** [UI_SCALE_MIN]..[UI_SCALE_MAX], 供 Slider 之类需要 range 的调用方使用. */
        @Stable
        val UI_SCALE_RANGE = UI_SCALE_MIN..UI_SCALE_MAX
    }
}

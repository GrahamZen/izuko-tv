/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings

/**
 * 一个 TV 页面里「有没有卡片容器正在滚动」的汇总信号.
 *
 * 用途只有一个: 跟着聚焦卡片换内容的文字块 (探索/追番/搜索页的 hero 文字、详情页选集轮播上方的集
 * 信息行) **滚动 / 方向键连发期间不显示, 停稳后再显示最后聚焦那张的**; 背景图走
 * `rememberTvSettledHeroProvider`, 同一条规则, 只是藏不掉 (停在换挡前那张). **两档相同** —— 2026-09-10
 * 之前只在低特效档, 用户看过 Prime Video 之后定为统一行为: 完整档在 Shield 上 janky 8~10%, 也需要.
 *
 * ## 为什么值得做 (2026-09-06 Shield 1080p framestats 实测, 详情页选集轮播)
 *
 * 那一行是三行 CJK 简介 + 时长/日期, 每按一格方向键整段文字全换 —— 字形在 glyph atlas 里全部
 * miss, 要现场光栅化再上传纹理, 而这一笔恰好撞在卡片吸附滚动的动画帧里. 四个版本对比:
 * 不处理 janky 17.66%; 防抖 150ms 8.88%; 等 200ms 上限 7.34%; **等滚动完全停下 2.14%**.
 * 后三者的差别全在"文字重排的长帧落在哪": 落在 spring 中段照样掉帧, 落在滚动结束后才是空闲处.
 *
 * ## 用法
 *
 * - 页面根包 [ProvideTvScrollActivity] (TV 页面变体注入处统一做);
 * - 每个卡片容器 (LazyRow / LazyColumn / LazyVerticalGrid) 挂一句 [ReportTvScrollActivity];
 * - 文字块用 [rememberTvScrollSettledProvider] 拿"停稳后的目标", 用 [rememberTvCardsScrollingProvider]
 *   决定画不画; 两者合一的便捷版是 [rememberTvScrollHiddenProvider].
 *
 * 没装信号的地方 (手机布局、播放器选集条) 不藏、不等滚动, 只剩连发静默这一条 (那不依赖信号).
 */
@Stable
class TvScrollActivity {
    private var scrollingCount by mutableIntStateOf(0)

    /** 至少一个登记过的容器正在滚动. 在组合里读它只会让读者自己重组. */
    val isScrolling: Boolean get() = scrollingCount > 0

    internal fun report(scrolling: Boolean) {
        scrollingCount += if (scrolling) 1 else -1
    }
}

/** 当前页面的滚动信号; null = 本页没装 (手机布局 / 播放器), 各 provider 退化成透传. */
val LocalTvScrollActivity = staticCompositionLocalOf<TvScrollActivity?> { null }

/** 给一个 TV 页面装上自己的 [TvScrollActivity]. 每页一份, 别的页面 (转场期间仍在组合) 滚不到这里. */
@Composable
fun ProvideTvScrollActivity(content: @Composable () -> Unit) {
    val activity = remember { TvScrollActivity() }
    CompositionLocalProvider(LocalTvScrollActivity provides activity, content = content)
}

/**
 * 把 [state] 的滚动状态登记进当前页面的 [TvScrollActivity]; 没装信号时什么都不做.
 *
 * "在滚"的判据**不是** `isScrollInProgress` 本身, 而是它为 true 期间**布局真的在挪**: 每帧读一次
 * [layoutOffset], 连续 [TV_SCROLL_STILL_FRAMES] 帧每帧挪动不超过 [TV_SCROLL_STILL_PX] 就算停稳. 焦点滚动是
 * 临界阻尼 spring, 卡片肉眼停下之后还要拖一两百毫秒才落进可见阈值、`isScrollInProgress` 才变 false
 * (2026-09-09 Shield 录屏: 卡片 +400ms 停, 文字 +630ms 才开始出现, 空白 400ms 里有一半是这条数学上的
 * 尾巴), 而尾巴的末段是每隔一两帧挪 1px, 也看不出来. 尾巴里再按一格键, 偏移重新大幅变化, 又算回"在滚".
 *
 * 容器退出组合 (行滚出视口 / 换 tab) 时自动注销, 滚到一半被销毁也不会把计数卡在 1.
 *
 * @param layoutOffset 当前滚动位置的整数表示 (像素级即可), 只在滚动期间逐帧读.
 */
@Composable
fun ReportTvScrollActivity(state: ScrollableState, layoutOffset: () -> Int) {
    val activity = LocalTvScrollActivity.current ?: return
    LaunchedEffect(activity, state) {
        var reported = false
        fun report(scrolling: Boolean) {
            if (scrolling != reported) {
                reported = scrolling
                activity.report(scrolling)
            }
        }
        try {
            snapshotFlow { state.isScrollInProgress }.collectLatest { inProgress ->
                if (!inProgress) {
                    report(false)
                    return@collectLatest
                }
                report(true)
                var last = layoutOffset()
                var stillFrames = 0
                while (true) {
                    withFrameNanos { }
                    val now = layoutOffset()
                    if (kotlin.math.abs(now - last) <= TV_SCROLL_STILL_PX) {
                        stillFrames++
                        if (stillFrames >= TV_SCROLL_STILL_FRAMES) report(false)
                    } else {
                        stillFrames = 0
                        report(true)
                    }
                    last = now
                }
            }
        } finally {
            if (reported) activity.report(false)
        }
    }
}

/** 布局偏移连续多少帧都没怎么挪就算停稳: 2 帧 ≈ 33ms, 再少会把 60fps 下偶尔的等值帧误判成停. */
private const val TV_SCROLL_STILL_FRAMES = 2

/** 一帧挪动不超过这么多像素算"没挪": spring 尾段每帧只挪两三像素时肉眼已经是停的, 而 Prime 是淡出一结束就接淡入, 中间没有空白 —— 等到 1px 以内还要再多等一百多毫秒 (2026-09-09 Shield 录屏). */
private const val TV_SCROLL_STILL_PX = 3

/** 首项下标与首项偏移合成一个整数: 下标每进一位偏移量不可能超过这个数 (一张卡不会超过一百万像素). */
private const val TV_SCROLL_OFFSET_STRIDE = 1_000_000

/** [ReportTvScrollActivity] 的 LazyRow / LazyColumn 版本. */
@Composable
fun ReportTvScrollActivity(state: LazyListState) = ReportTvScrollActivity(state) {
    state.firstVisibleItemIndex * TV_SCROLL_OFFSET_STRIDE + state.firstVisibleItemScrollOffset
}

/** [ReportTvScrollActivity] 的 LazyVerticalGrid 版本. */
@Composable
fun ReportTvScrollActivity(state: LazyGridState) = ReportTvScrollActivity(state) {
    state.firstVisibleItemIndex * TV_SCROLL_OFFSET_STRIDE + state.firstVisibleItemScrollOffset
}

/**
 * 页面里是否有卡片在滚动; 没装信号时恒 false.
 *
 * 返回 lambda 而不是值: 谁调用谁订阅, 调用方 body 不因滚动起止而重组.
 */
@Composable
fun rememberTvCardsScrollingProvider(): () -> Boolean {
    val activity = LocalTvScrollActivity.current
    return remember(activity) { { activity?.isScrolling == true } }
}

/** [rememberTvScrollSettled] 的结果: "停稳后的目标" 与 "是否在连发中" 两个可观察状态. */
@Stable
class TvScrollSettled<T> internal constructor(initial: T) {
    internal var settledValue: T by mutableStateOf(initial)
    internal var burst: Boolean by mutableStateOf(false)
    internal var pending: Boolean by mutableStateOf(false)
    internal var scrolled: Boolean by mutableStateOf(false)
    /** 这一拍已经把文字藏过 (连发 / 滚过 / 按住连发), 藏了就藏到停稳, 中途不因条件消失而露出旧值. */
    internal var latched: Boolean by mutableStateOf(false)
    internal var navKeys: TvNavKeyTracker? = null

    /** 最后一次停稳时的目标. */
    val value: T get() = settledValue

    /**
     * 目标变了、还没停稳放行. 文字只在这期间因滚动 / 按键而藏起来:
     * - 只按了键但目标没变 (行尾再按、按去侧边栏、没反应的方向) 不藏 —— 否则藏一下再显回同一段文字;
     * - 停稳、文字已经出来之后, 聚焦卡的放大动画改了它的边界, bring-into-view 偶尔补一次几像素的修正
     *   滚动, 也不藏 —— 否则就是用户 2026-09-10 看到的"文字到达之后瞬间闪一下".
     */
    val isPending: Boolean get() = pending

    /**
     * 方向键连发中 (两次目标变化间隔不到 [TV_NAV_SETTLE_MILLIS]), 直到静默同样时长且卡片停稳.
     * 快速点按不滚动的网格时靠它把文字藏起来; 真正按住的判据是 [TvNavKeyTracker.held].
     */
    val isInBurst: Boolean get() = burst

    /**
     * 文字块此刻该不该藏: 静默闸门判的连发中; 或目标变了还没停稳, 且 (卡片在滚 / 方向键按着且 [这一拍滚过卡片
     * 或系统自动连发已开始]).
     *
     * 后一半是**按住**: 第一格卡片 +310ms 停稳时键还没抬, 只看"在滚"会把停稳前的旧值带着入场动画显回来;
     * 自动连发的第一拍 (+405ms) 又落在静默闸门 300ms 之外, 新目标一到"滚过"重新计、滚动还没起步, 那 40ms
     * 同样漏 —— 两处都是用户 2026-09-10 看到的"长按几百毫秒时文字变一下, 但还是按前那张" (TvHoldProbe 日志
     * 定的). 按住的第一格限定"滚过卡片"是为了不碰直接切换 (轮播按键翻页 / 网格横移): 那里按下即藏会让 A 的
     * 淡出与 B 的滑入撞在一起 (同日"按键的还是快"), 它们按住时由 [TvNavKeyTracker.repeating] 去藏.
     */
    fun isHidden(scrolling: Boolean): Boolean {
        if (burst) return true
        if (!pending) return false
        if (latched || scrolling) return true
        val keys = navKeys ?: return false
        return keys.held && (scrolled || keys.repeating)
    }
}

/**
 * "卡片停稳后的目标": [target] 每次变化都要等 (1) 若处在连发中, 静默 [TV_NAV_SETTLE_MILLIS]; (2) 页面里的卡片
 * 滚动停稳; (3) 方向键抬起 ([TvNavKeyTracker], 按住的真信号) —— 才放行, 中途划过的目标由 `collectLatest`
 * 丢掉. 没装信号的地方只做 (1).
 *
 * 空闲后的第一拍不等静默 (一次深思熟虑的单击不该为连发付延迟), 规则见 [TvNavigationSettle]. 焦点回调
 * 之后 bring-into-view 才在随后的协程里发起滚动, 所以先给它两帧起步 —— 否则这里读到的还是"没在滚",
 * 当场放行就等于没做. 不引起滚动的移动 (网格内横向换卡、行尾) 两帧后照常放行.
 *
 * 热状态的读取全部关在协程里, 调用方 body 不订阅.
 */
@Composable
fun <T> rememberTvScrollSettled(target: () -> T): TvScrollSettled<T> {
    val activity = LocalTvScrollActivity.current
    val navKeys = LocalTvNavKeyTracker.current
    // lambda 每次重组换新实例, 必须经 rememberUpdatedState 再进 snapshotFlow, 否则永久留住首帧值
    val latest = rememberUpdatedState(target)
    // 种子值"不被观察地"读: 直接 target() 会把热状态的读算到调用方的 body 上
    val state = remember { TvScrollSettled(Snapshot.withoutReadObservation { target() }) }
    state.navKeys = navKeys
    LaunchedEffect(activity, navKeys) {
        state.burst = false
        val settle = TvNavigationSettle(TV_NAV_SETTLE_MILLIS)
        snapshotFlow { latest.value.invoke() }.collectLatest { value ->
            state.pending = true
            state.scrolled = false
            state.latched = false
            val changedAt = TimeSource.Monotonic.markNow()
            // 屏幕上还什么都没有 (种子是 null) 时不算连发: 进页面的头两拍是 null -> 首个条目, 两者间隔
            // 远小于静默期, 按连发处理的话内容要凭空晚一个静默期才出现
            val bypass = state.settledValue == null
            if (settle.noteEvent() && !bypass) {
                state.burst = true
                state.latched = true
                delay(TV_NAV_SETTLE_MILLIS)
            }
            if (activity != null) {
                withFrameNanos { }
                withFrameNanos { }
                snapshotFlow { activity.isScrolling }.first { scrolling ->
                    if (scrolling) {
                        state.scrolled = true
                        state.latched = true
                    }
                    !scrolling
                }
            }
            // 按住的第一格: 单击的抬起早在停稳之前, 这里不等; 按住则一直等到松手
            if (navKeys != null && !bypass) {
                snapshotFlow { navKeys.held }.first { held ->
                    if (held && navKeys.repeating) state.latched = true
                    !held
                }
                // 松手之后再看一眼: bring-into-view 偶尔比两帧还晚才起步 (TvHoldProbe 日志抓到过 86ms),
                // 上面那次等待读到的是"没在滚", 不补这一眼文字会在卡片还滑着的时候就出来
                if (activity != null) {
                    snapshotFlow { activity.isScrolling }.first { scrolling ->
                        if (scrolling) state.latched = true
                        !scrolling
                    }
                }
            }
            // 藏过的话, 进场起点不早于直接切换的起点 (TV_SCROLL_HIDDEN_TEXT_ENTER_AT_MILLIS): 滚得近的行 +210ms
            // 就停稳, 不等的话新文字压在旧文字的淡出上 (crossfade), 与轮播按键翻页 / 滚得远的行节奏不一
            if (state.latched) {
                val left = TV_SCROLL_HIDDEN_TEXT_ENTER_AT_MILLIS - changedAt.elapsedNow().inWholeMilliseconds
                if (left > 0) delay(left)
            }
            state.burst = false
            state.settledValue = value
            state.pending = false
        }
    }
    return state
}

/** [rememberTvScrollSettled] 的 provider 形态: 只要值, 不关心连发状态. */
@Composable
fun <T> rememberTvScrollSettledProvider(target: () -> T): () -> T {
    val state = rememberTvScrollSettled(target)
    return remember(state) { { state.value } }
}

/**
 * [rememberTvScrollSettled] + 连发期间、以及"目标变了还没停稳"期间的滚动, 返回 null (调用方据此整块不画).
 *
 * 藏与显的过渡用 [tvScrollHiddenTextTransform] (原地淡出、从右滑入); 没装信号时只在连发期间返回 null.
 *
 * 藏的判据见 [TvScrollSettled.isHidden]: **按着键单独不是藏的条件**, 只有这一拍滚过卡片才算 —— 不滚动的
 * 直接切换 (轮播按键翻页 / 网格横移) 若按下那一刻就 A -> null, 抬起后 null -> B 会被 AnimatedContent 当"从隐藏
 * 态出来"不走先后路径, A 的淡出与 B 的滑入几乎同时跑 (用户 2026-09-10: "按键的还是快"); 它们按住时由连发去藏.
 */
@Composable
fun <T> rememberTvScrollHiddenProvider(target: () -> T?): () -> T? {
    val state = rememberTvScrollSettled(target)
    val scrolling = rememberTvCardsScrollingProvider()
    return remember(state, scrolling) {
        {
            if (state.isHidden(scrolling())) null else state.value
        }
    }
}

/** 本页装了滚动信号 = 「文字块滚动期间隐藏」这套规则正在生效 (手机布局没装). */
@Composable
fun tvScrollHiddenTextEnabled(): Boolean = LocalTvScrollActivity.current != null

/**
 * 遥控器连发的**静默闸门**: 空闲后的第一拍立即放行 (一次深思熟虑的单击不该为连发付延迟); 连发中的拍子
 * (距上一拍不到 [settleMillis]) 等静默满 [settleMillis] 才放行, 被下一拍取消就等于丢掉 —— 所以按住方向键
 * 期间什么都不换, 松手后只换到最后那个. **必须配 `collectLatest`**: 它靠"被取消"丢掉中间目标.
 *
 * 2026-09-10 之前是前沿节流 (连发期间每 300ms 放行一次), 改成静默是为了"按住时 hero 背景停在第一张、
 * 文字藏起来, 松手一起回来" 在不滚动的网格上也成立 (卡片行靠滚动信号本来就是这样); Prime 按住时同样.
 *
 * hero 的展示换挡 (`rememberTvSettledHeroProvider`)、文字块 ([rememberTvScrollSettled]) 与四个 TV 页的
 * 媒体预取共用这一条规则, 是故意的: 错开的话要么预取把带宽花在划过去的卡上, 要么展示已经换到 B 而
 * 预取还停在 A —— 后者正是"停下来还要再等一次网络"的来源.
 */
class TvNavigationSettle(val settleMillis: Long) {
    private var lastEventMark: TimeMark? = null

    /** 记下这一拍, 返回它是否落在连发里 (距上一拍不到 [settleMillis]). */
    fun noteEvent(): Boolean {
        val prev = lastEventMark
        lastEventMark = TimeSource.Monotonic.markNow()
        return prev != null && prev.elapsedNow() < settleMillis.milliseconds
    }


    /**
     * @param bypass 这一拍不必合并, 直接放行 (例如屏幕上还什么都没有: 从无到有没有可合并的对象,
     * 按连发处理的话内容要凭空晚 [settleMillis] 才出现). 仍然记一拍.
     */
    suspend fun awaitTurn(bypass: Boolean = false) {
        val burst = noteEvent()
        if (burst && !bypass) delay(settleMillis)
    }
}

/**
 * 连发静默期 (毫秒): 必须长于长按方向键的连发间隔 (`tvFocusMoveRateLimit` 横向 8 次/秒 = 125ms), 否则
 * 按住期间仍会中途换; 也是停下来之后文字 / 背景图最迟多久跟上 (卡片行另有滚动停稳这一条, 通常更晚).
 */
const val TV_NAV_SETTLE_MILLIS = 300L

/**
 * 滚动期间隐藏的文字块藏 / 显的过渡, **照 Prime Video (Shield, 2026-09-09 录屏逐帧量的)**:
 * 旧文字整块**原地**线性淡出 (所有行同一个 alpha, 不平移、不错开; 简介行逐帧亮度是一条直线), 新文字从右侧约
 * 14dp 处**边滑边淡入**约 200ms, 淡入线性、位移减速. 三个 hero 块用它, 详情页集信息行用不滑的
 * [tvScrollHiddenTextFadeTransform]. 淡出**不能用默认的 FastOutSlowIn**: 前半段掉得太快, 用户看起来是"闪一下就没了".
 *
 * 淡出时长 Prime 量出来是 220ms, 这里拉到 300ms (2026-09-10 用户定): 卡片行的滑入要等卡片停稳, Shield 刚度 260
 * 下停稳在 +310ms, 220 淡完到滑入之间有约 90ms 空屏; Prime 不等停稳所以没这段. 文字到达时间不变 (由停稳决定),
 * 只是让旧文字消失的那一刻正好接上新文字进场.
 *
 * @param sequential 旧内容与新内容**同时在场**的切换 (探索页 hero 轮播自动换页、网格内不滚动的横向换卡:
 * 文字 A 直接变 B, 没经过"藏起来"那一步) 传 true, 进场整体延后一个淡出时长 —— 否则 AnimatedContent 把
 * 淡出和滑入同时跑成交叉淡化, 与卡片行"先藏后显"的先后节奏不一样 (用户 2026-09-09 一眼看出来了);
 * Prime 直接换的时候也是先淡完再进. 从隐藏态 (initialState == null) 出来的传 false, 那时旧内容早没了.
 *
 * Prime 按住方向键时也是这套: 头一两步之后 hero 文字整个不显示, 背景停在一张压暗, 松手停稳后最后那项
 * 再淡入 —— 与本模块"滚动期间隐藏、停稳再显"完全一致. 它的选集页倒是另一回事 (按键瞬间换字再整块
 * 暗一下亮回来), 不值得学.
 *
 * 性能上是免费的 (同日索尼 BRAVIA framestats 交错三轮, 瞬切 / 只平移 / 平移+淡出 的 janky、CPU p90、
 * GPU p90 全部持平): 平移走 placeWithLayer 不重绘, 文字块那点面积的 alpha 层在整屏负载里不值一提.
 *
 * **sizeTransform 一律 null** (三个 transform 都是): 这些 AnimatedContent 的容器高度都由外层钉死 (hero 块
 * weight / 集信息行等高), 默认的尺寸弹簧只会在容器高度跟着 hero 态切换 (按钮块消失, 块高 264 → 240dp) 时
 * 把退场中的文字整块挪 24dp 再挪回来 (2026-09-10 录屏: 下键进卡片区那一帧标题下跳 25dp, 170ms 后跳回才淡出).
 */
fun tvScrollHiddenTextTransform(
    slidePx: Int,
    sequential: Boolean,
    childrenEnter: Boolean = false,
    hiding: Boolean = false,
): ContentTransform {
    val enterDelay = tvHeroTextEnterBaseDelay(sequential)
    val enter = if (childrenEnter) EnterTransition.None else (slideInHorizontally(
        tween(TV_SCROLL_HIDDEN_TEXT_IN_MILLIS, delayMillis = enterDelay, easing = LinearOutSlowInEasing),
    ) { slidePx } + fadeIn(tween(TV_SCROLL_HIDDEN_TEXT_IN_MILLIS, delayMillis = enterDelay, easing = LinearEasing)))
    return ContentTransform(enter, fadeOut(tween(tvScrollHiddenTextOutMillis(hiding), easing = LinearEasing)), sizeTransform = null)
}

/** 直接切换 (sequential) 时新文字的进场起点 (毫秒); 从隐藏态出来的为 0 (起点由 [rememberTvScrollSettled] 保底). 子项错落以它为基准. */
fun tvHeroTextEnterBaseDelay(sequential: Boolean): Int =
    if (sequential) TV_SCROLL_HIDDEN_TEXT_ENTER_AT_MILLIS.toInt() else 0

/** 淡出时长: 藏起来 (目标变 null) 那条路比直接切换长, 见常量. */
private fun tvScrollHiddenTextOutMillis(hiding: Boolean): Int =
    if (hiding) TV_SCROLL_HIDDEN_TEXT_HIDE_OUT_MILLIS else TV_SCROLL_HIDDEN_TEXT_OUT_MILLIS

/** 轮播自动换页时新文字的进场起点 (毫秒) = 那一档的淡出时长. */
fun tvCarouselTextEnterBaseDelay(): Int = TV_CAROUSEL_TEXT_OUT_MILLIS

/** hero 文字分行错落进场是否生效: 视觉效果均衡档起 (见 TvVisualEffectsLevel) + [TvPolishFlags.textStagger]. */
@Composable
fun tvHeroTextStaggerEnabled(): Boolean =
    LocalThemeSettings.current.visualEffects.transitions && TvPolishFlags.textStagger

/**
 * hero 文字块**分行错落进场** (Google TV 首页的味道): 第 [line] 行比上一行晚 [TV_HERO_TEXT_STAGGER_MILLIS] 进,
 * 各自从右滑入 + 淡入; 退场不单独动 (整块跟容器一起淡出). 容器那边要配 `childrenEnter = true` (容器不再整块
 * 进场, 否则叠两遍). [baseDelayMillis] 是容器本来的进场起点 (见 [tvHeroTextEnterBaseDelay]), 错落叠在它之上.
 *
 * 平移走 placeWithLayer 不重绘, 多出来的只是三小块文字各自的 alpha 层; 只在完整视觉效果档开
 * ([tvHeroTextStaggerEnabled]), 低档整块同进. [enabled] 为 false 时原样返回, 什么都不挂.
 */
fun Modifier.tvHeroLineEnter(
    scope: AnimatedVisibilityScope,
    enabled: Boolean,
    baseDelayMillis: Int,
    line: Int,
    slidePx: Int,
    carousel: Boolean = false,
): Modifier {
    if (!enabled) return this
    val delay = baseDelayMillis + line * TV_HERO_TEXT_STAGGER_MILLIS
    val duration = if (carousel) TV_CAROUSEL_TEXT_IN_MILLIS else TV_SCROLL_HIDDEN_TEXT_IN_MILLIS
    return with(scope) {
        animateEnterExit(
            enter = slideInHorizontally(tween(duration, delayMillis = delay, easing = LinearOutSlowInEasing)) { slidePx } +
                    fadeIn(tween(duration, delayMillis = delay, easing = LinearEasing)),
            exit = ExitTransition.None,
        )
    }
}

/** 相邻两行进场的错开量 (毫秒). */
private const val TV_HERO_TEXT_STAGGER_MILLIS = 40

/**
 * [tvScrollHiddenTextTransform] 的**不滑**版本, 给详情页集信息行用: 旧文字整块淡出, 新文字**原地**淡入.
 *
 * Prime 的选集页与它的首页不是一套: 按键那一帧直接换字, 然后在卡片滑动的 ~200ms 里整块暗到不见再亮回来,
 * 标题位置不动、没有横向滑入. "按键瞬间换字"我们做不起 (那正是滚动帧里重排三行 CJK 简介的开销), 所以取
 * 它的观感 —— 暗下去、亮回来 —— 而把换字放到停稳之后 (用户 2026-09-09 定).
 */
fun tvScrollHiddenTextFadeTransform(sequential: Boolean, hiding: Boolean = false): ContentTransform {
    val enterDelay = tvHeroTextEnterBaseDelay(sequential)
    return ContentTransform(
        fadeIn(tween(TV_SCROLL_HIDDEN_TEXT_IN_MILLIS, delayMillis = enterDelay, easing = LinearEasing)),
        fadeOut(tween(tvScrollHiddenTextOutMillis(hiding), easing = LinearEasing)),
        sizeTransform = null,
    )
}

/**
 * 探索页 hero **轮播态** (焦点在 hero 按钮上, 每 6s 自动换页或左右键翻页) 的文字过渡: 动作同
 * [tvScrollHiddenTextTransform] (原地淡出 → 从右滑入, 先后), 但时长放慢到淡出 [TV_CAROUSEL_TEXT_OUT_MILLIS]、
 * 滑入 [TV_CAROUSEL_TEXT_IN_MILLIS]. 轮播没有按键节奏可跟, 用卡片那套 300/200 显得急 (用户 2026-09-10: 改之前是
 * 500ms 交叉淡化, "现在轮播的文字运动似乎更快"); 其余直接切换的地方 (网格横移) 仍用快的那套.
 */
fun tvCarouselTextTransform(slidePx: Int, childrenEnter: Boolean = false): ContentTransform {
    val enter = if (childrenEnter) EnterTransition.None else (slideInHorizontally(
        tween(TV_CAROUSEL_TEXT_IN_MILLIS, delayMillis = TV_CAROUSEL_TEXT_OUT_MILLIS, easing = LinearOutSlowInEasing),
    ) { slidePx } + fadeIn(tween(TV_CAROUSEL_TEXT_IN_MILLIS, delayMillis = TV_CAROUSEL_TEXT_OUT_MILLIS, easing = LinearEasing)))
    return ContentTransform(enter, fadeOut(tween(TV_CAROUSEL_TEXT_OUT_MILLIS, easing = LinearEasing)), sizeTransform = null)
}

/** 轮播态文字淡出 / 滑入时长 (毫秒), 见 [tvCarouselTextTransform]. */
private const val TV_CAROUSEL_TEXT_OUT_MILLIS = 350
private const val TV_CAROUSEL_TEXT_IN_MILLIS = 450

/** [tvScrollHiddenTextTransform] 的位移, 在组合里按当前 density 算一次 (transitionSpec 里拿不到 density). */
@Composable
fun tvScrollHiddenTextSlidePx(): Int = with(LocalDensity.current) { TV_SCROLL_HIDDEN_TEXT_SLIDE_DISTANCE.roundToPx() }

/** 滑入起点距终点的距离: Prime 实测约 28px@1080p ≈ 14dp, 读得出是"滑进来"又不显得在飞. */
private val TV_SCROLL_HIDDEN_TEXT_SLIDE_DISTANCE = 14.dp

/** 淡入+滑入约 200ms (Prime 实测 12 帧), 线性. */
private const val TV_SCROLL_HIDDEN_TEXT_IN_MILLIS = 200

/** 直接切换 (轮播按键翻页 / 网格横移) 的淡出 300ms 线性 (Prime 是 220, 拉长的理由见 [tvScrollHiddenTextTransform]). */
private const val TV_SCROLL_HIDDEN_TEXT_OUT_MILLIS = 300

/**
 * 藏起来 (目标变 null, 卡片行滚动 / 连发) 那条路的淡出 400ms 线性: 新文字要等卡片停稳才进, Shield 实测 (刚度 260)
 * 停稳在按键后 ~330ms, 滚得远的行更晚, 300 淡完到滑入之间仍有空屏 (用户 2026-09-10: 轮播正好, 卡片行还差一点).
 * 拉到 400 让旧文字的尾巴 (alpha < 0.2 肉眼已看不见) 盖住停稳前的那一段; 直接切换不动 —— 那条路进场起点固定,
 * 用户已经认可.
 */
private const val TV_SCROLL_HIDDEN_TEXT_HIDE_OUT_MILLIS = 400

/**
 * 新文字进场的统一起点 (按键后毫秒): 直接切换用作进场延迟 (淡出 300 + 停顿 10); 从隐藏态出来的由
 * [rememberTvScrollSettled] 保底 —— 卡片停稳更晚就跟停稳, 更早 (滚得近的行 +210ms) 就等到这个点, 免得新文字
 * 压在旧文字的淡出上、节奏也和轮播按键翻页对齐 (用户 2026-09-10: 不对齐的话"按键的还是快").
 */
const val TV_SCROLL_HIDDEN_TEXT_ENTER_AT_MILLIS = 310L

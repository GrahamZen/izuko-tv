/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.settings.NetworkTroubleBeacon
import me.him188.ani.app.domain.settings.ProxyTester
import me.him188.ani.app.domain.settings.ServiceConnectionTester
import me.him188.ani.app.domain.settings.ServiceConnectionTesters
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.foundation.launchInBackground
import me.him188.ani.app.ui.foundation.tv.TvCapsuleButton
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_service_check_hint
import me.him188.ani.app.ui.lang.tv_service_check_hint_never
import me.him188.ani.app.ui.lang.tv_service_check_hint_stale
import me.him188.ani.app.ui.lang.tv_service_probe_ani
import me.him188.ani.app.ui.lang.tv_service_probe_bangumi
import me.him188.ani.app.ui.lang.tv_service_probe_bangumi_next
import me.him188.ani.app.ui.lang.tv_service_probe_tmdb
import me.him188.ani.app.ui.lang.tv_service_probe_tmdb_image
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.stringResource
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 一项探测挂了之后, 用户实际会遇到什么.
 *
 * 分档的唯一目的是**不制造假警报**: 五项里有两项 (Bangumi 主站与 Bangumi Next) 在大陆网络下
 * 长期就是连不上的, 而它们不通完全不影响看番 —— 若与"连不上 Animeko 服务器"同色同权重地报红,
 * 那么绝大多数国内用户会看到一行永久的红叉, 于是这一行的信息量归零: 真出问题时没人会注意到它.
 *
 * 所以颜色按本枚举给, 而不是按"成功/失败"给.
 */
enum class TvServiceTier {
    /** 挂了就用不了: 搜索、条目数据、弹幕全走这里. 报错误色. */
    Required,

    /** 挂了掉一块功能, 但番照样能看 (背景图与剧照). 报警示色, 不用错误色. */
    Degraded,

    /** 挂了只掉锦上添花的东西 (条目简介、评论回复关系). **失败也保持次要色**, 不报警. */
    Optional,
}

/** 一项探测: id 对应 [ServiceConnectionTesters] 的常量, 顺序即显示顺序 (按档位从重到轻). */
@Immutable
class TvServiceProbe internal constructor(
    val id: String,
    val tier: TvServiceTier,
)

@Immutable
class TvServiceProbeState internal constructor(
    val probe: TvServiceProbe,
    val result: TvServiceProbeResult,
)

enum class TvServiceProbeResult {
    /** 还没测 / 正在测. 两者不分: 是否在跑由整行末尾那颗指示器表达, 每颗都转会晃眼. */
    Pending,
    Ok,
    Failed,
}

/**
 * 面板里那一行的探测清单.
 *
 * 名字用**受影响的功能**而不是域名 ("背景图" 而不是 "TMDB 接口"): 域名对用户没有意义, 而且
 * 同一个域名分两项探 (接口与图床) 时, 只有按功能命名才能让两行看起来是两件事.
 */
private val TV_SERVICE_PROBES = listOf(
    TvServiceProbe(ServiceConnectionTesters.ID_TMDB, TvServiceTier.Degraded),
    TvServiceProbe(ServiceConnectionTesters.ID_TMDB_IMAGE, TvServiceTier.Degraded),
    TvServiceProbe(ServiceConnectionTesters.ID_BANGUMI, TvServiceTier.Optional),
    TvServiceProbe(ServiceConnectionTesters.ID_BANGUMI_NEXT, TvServiceTier.Optional),
)

/**
 * 超过这个时长的结果视为**可能已过期**: 不自动重测, 只提示用户自己按 (见
 * [TvServiceConnectivityState.resultsExpireAt]).
 */
private val TV_SERVICE_RESULT_TTL: Duration = 5.minutes

/**
 * 故障信标触发的重测之间至少隔这么久, 见 [TvServiceConnectivityState.maybeAutoRun].
 *
 * 与 [TV_SERVICE_RESULT_TTL] 同值但不是同一件事: 那个是"结果多久算旧" (只影响提示色与文案),
 * 这个是"出故障后多久允许自动再测一次" (影响真的发不发请求).
 */
private val TV_SERVICE_TROUBLE_RERUN_MIN_INTERVAL: Duration = 5.minutes

/**
 * 动作面板里「服务连通」那一行的状态.
 *
 * ## 为什么是 Activity 级 ViewModel
 *
 * 面板只在打开的那一瞬间被组合 (`if (showQuickMenu)`), 关掉就整棵子树消失. 结果若跟着组合走,
 * 每次打开都得从零重测一遍 —— 光 TMDB 那项上限就是 15 秒, 用户开面板多数是为了按「回到主界面」,
 * 不该为此每次都付一轮探测. 挂成 ViewModel 之后结果留在进程里, 打开即见.
 *
 * ## 自动只跑一次: 本进程第一次出面板
 *
 * 三档取舍, 最后落在中间那档:
 *
 * - **每次打开都按需重测** (最早的版本: 结果过 [TV_SERVICE_RESULT_TTL] 就自动重跑) —— 撤掉了.
 *   面板的日常用途是按「回到主界面」而不是体检, 每次打开都悄悄发五个请求, 换来的信息多数时候
 *   没人看; 更糟的是**自动跑的那一轮恰好是最冷的一轮**, 最容易误报 (DNS 没缓存、TLS 要新握手、
 *   五项还并发抢连接, 单项封顶一到就判失败). 2026-08-17 实测: 网络明明没问题, 自动那轮报图床
 *   失败, 手动按一下立刻全通.
 * - **一次都不自动跑** —— 也不行. 没跑过时这一条是五颗灰点, 而它不可聚焦、末尾那颗钮又要"从
 *   最后一颗圆钮向右"才够得到: 第一次见到的人根本不知道这些点是等他去按的.
 * - **第一次出面板自动跑一次, 之后只等手动** —— 现在这样. 第一印象是有内容的 (那一下顺带把
 *   "这条会变"演示了一遍), 而后续每次打开都不再偷跑.
 *
 * 另有一个破例: **用户正常浏览时已经撞上失败** (背景图迟迟不出、hero 等满超时) 时, 下次出面板
 * 会再自动跑一轮 —— 那时他打开面板多半就是想知道"是不是网络的问题", 而手上那批结果是坏事发生
 * 之前测的. 判据是 [NetworkTroubleBeacon] 的时刻晚于上一轮起跑, 并压了下限免得长期不通的网络
 * 把它变成"每次开面板都测", 见 [maybeAutoRun].
 *
 * 首次那一轮仍然是最冷的一轮, 因此误报风险没有消失, 只是从"每 5 分钟一次"降到"一进程一次", 并靠
 * 两件事兜着: 图床那项的封顶已放宽到 10 秒 (见 `ServiceConnectionTesters`), 且超时会自己留一行
 * 日志 —— 用户看到红叉后按一下就能复核, 复核结果与日志一起足够判断是真不通还是没跑完.
 *
 * 剩下的结果会旧. 处理办法是**提示而不是代劳**: [resultsExpireAt] 到点后刷新钮换成提示色、
 * 标签行改写成"可能已过期", 按不按由用户决定. 也因此不显示"上次检测时间" —— 已过期/未检测这
 * 两个词就是用户要的全部信息, 具体分钟数不影响他的下一步动作.
 *
 * ## 探测本身
 *
 * 复用 [ProxyTester] (设置页与引导页也各自持一个): 它自带"代理配置变了就重测"与并发编排.
 * 注意它必须有人 collect [ProxyTester.testRunnerLoop] 才会真的跑, 且 collect 的那一刻就会
 * 起第一轮 —— 所以那一次 collect 推迟到用户第一次按下 (见 [refresh]).
 */
class TvServiceConnectivityState : AbstractViewModel(), KoinComponent {
    private val clientProvider: HttpClientProvider by inject()
    private val tmdbImageService: TmdbImageService by inject()

    private val tester = ProxyTester(
        clientProvider = clientProvider,
        flowScope = backgroundScope,
        tmdbImageService = tmdbImageService,
        serviceIds = TV_SERVICE_PROBES.map { it.id }.toSet(),
    )

    var probes: List<TvServiceProbeState> by mutableStateOf(
        TV_SERVICE_PROBES.map { TvServiceProbeState(it, TvServiceProbeResult.Pending) },
    )
        private set

    var running: Boolean by mutableStateOf(false)
        private set

    /**
     * 手上这批结果的**过期时刻** (毫秒); 0 = 本进程还没测出过完整一轮.
     *
     * 过期不再自动重测, 只把刷新钮染成提示色 + 改标签行文字, 由用户自己按, 理由见类文档.
     */
    var resultsExpireAt: Long by mutableLongStateOf(0L)
        private set

    /** 探测器要有人 collect 才会跑, 而第一次 collect 本身就会起一轮 —— 所以推到真要测的那一刻. */
    private var started = false

    /** 上一轮**起跑**的时刻; 用来判断故障信标是这轮之前还是之后亮的. */
    private var lastRunStartedAt: Long = 0L

    /**
     * 面板每次出现都会调用, 但只在这两种情况下真的跑 (见类文档):
     *
     * 1. 本进程从没测过 —— 第一印象不能是五颗看不懂的灰点;
     * 2. 上次那轮之后**又亮过网络故障信标** ([NetworkTroubleBeacon]) —— 用户正常浏览时已经撞上
     *    连不上/等超时了, 此刻他打开面板多半就是想知道是不是网络的问题, 而手上那批结果是坏事
     *    发生**之前**测的, 照旧显示等于答非所问.
     *
     * 第 2 条相当于"把是不是第一次那个判定重新打开"。**但不能真去清 [started]**: 那个标志同时
     * 意味着"探测器的收集器已经挂上", 清掉再走一遍会挂第二份收集器、并发跑两轮. 所以用时间戳
     * 比较来等价实现.
     *
     * 还压了个下限 [TV_SERVICE_TROUBLE_RERUN_MIN_INTERVAL]: 大陆网络下 TMDB 长期不通, 信标会
     * 在每次浏览时被点亮, 没有下限就退化成"每次开面板都测" —— 正是被撤掉的那档行为.
     */
    fun maybeAutoRun() {
        if (!started) {
            refresh()
            return
        }
        if (running) return
        val trouble = NetworkTroubleBeacon.lastTroubleAt
        if (trouble <= lastRunStartedAt) return
        if (currentTimeMillis() - lastRunStartedAt < TV_SERVICE_TROUBLE_RERUN_MIN_INTERVAL.inWholeMilliseconds) {
            return
        }
        refresh()
    }

    /**
     * 按下刷新钮 (或首次自动那一下).
     *
     * 第一次调用才把探测器接上 (那一下即第一轮); 之后每次调用重跑.
     */
    fun refresh() {
        lastRunStartedAt = currentTimeMillis()
        if (started) {
            tester.restartTest()
            return
        }
        started = true
        launchInBackground { tester.testRunnerLoop() }
        launchInBackground {
            tester.testResult.collect { results ->
                if (results.allCompleted()) {
                    resultsExpireAt = currentTimeMillis() + TV_SERVICE_RESULT_TTL.inWholeMilliseconds
                }
                probes = TV_SERVICE_PROBES.map { probe ->
                    TvServiceProbeState(probe, results.findStateById(probe.id).toResult())
                }
            }
        }
        launchInBackground {
            tester.testRunning.collect { running = it }
        }
    }
}

private fun ServiceConnectionTester.TestState?.toResult(): TvServiceProbeResult = when (this) {
    null, ServiceConnectionTester.TestState.Idle, ServiceConnectionTester.TestState.Testing ->
        TvServiceProbeResult.Pending

    is ServiceConnectionTester.TestState.Success -> TvServiceProbeResult.Ok
    // Error 是"探测函数自己抛了", 按上游的说法算 bug; 对用户而言与失败没有区别
    ServiceConnectionTester.TestState.Failed, is ServiceConnectionTester.TestState.Error ->
        TvServiceProbeResult.Failed
}

/**
 * 卡片与圆钮之间那一条: 五项服务各自一颗状态 + 末尾一颗刷新钮 (短按重测, 长按去代理设置).
 *
 * ## 为什么逐项显示而不是合并成一句话
 *
 * 合并的写法 (「网络状态: 良好 · 4/5 正常」) 有两个毛病: 一是要在有限的宽度里从多项失败里挑一项
 * 来说, 挑中的往往不是用户关心的那项 (Bangumi 排在前面, TMDB 就看不见了); 二是"良好"这种词
 * 需要被阅读, 而红绿图标不需要 —— 五颗图标扫一眼就知道结果, 反而比一句话快.
 *
 * 逐项之后不再需要 "N/5 正常" 之类的汇总, 也不需要 "网络良好" 这种复述: 那些都是图标已经说过的话.
 *
 * ## 只有末尾那颗刷新钮吃焦点
 *
 * 五颗状态是**纯展示**, 整条不可聚焦. 状态不该占焦点路径 —— 面板里上下走一遍是找"要按的东西",
 * 而这一条平时只是给人扫一眼的.
 *
 * 心理模型是**"它就是圆钮那排最右边的一颗"**, 面板只有上下两排 (卡片 / 圆钮排). 于是:
 * 从最后一颗圆钮**向右**进来, 向左回去, 向上到卡片, **向右到头就停住**
 * (`right = FocusRequester.Cancel`) —— 不加这条的话, 长按右键会在刷新钮之后继续往右上方
 * "爬"到卡片右端那颗 ✕ 上 (那颗有一部分确实在刷新钮的右边), 表现成"一直按右忽然跳上去了".
 *
 * 这五条改道全是显式写的, 不靠几何搜索 —— 刷新钮在卡片正下方、圆钮右上方, 默认的方向搜索会
 * 把它当成上下方向上的中间站, 而卡片是天天要按的目标 (回去接着看), 不能因为中间多了一条状态
 * 就从一步变成两步.
 *
 * **正在测时只换钮里的字形, 不换钮本身** (换成一个独立的转圈组件会把焦点节点整个换掉 —— 焦点跟着
 * 消失的节点没了, 面板还开着, 方向键就全失效了; 与卡片随会话结束消失是同一类事故).
 *
 * ## 长按 = 去代理设置
 *
 * 这一条只回答"有没有问题", 修得去设置-网络那一页 (挂代理、看单项耗时). 于是**短按重测、长按
 * 直达代理设置** —— 否则用户看到红叉之后要退面板、进侧边栏、翻到设置、再往下找网络那一节.
 *
 * @param onFocused 焦点落到刷新钮 (面板据此写那行固定标签).
 * @param onOpenProxySettings 长按: 关面板 + 跳设置-代理. 由面板实现 (这一条不认识导航).
 * @param refreshFocusRequester 面板拿它做"最后一颗圆钮向右"的落点.
 * @param refreshFocusProperties 面板给刷新钮的改道 (左/下回圆钮, 上回卡片).
 */
@Composable
internal fun TvServiceConnectivityRow(
    state: TvServiceConnectivityState,
    onFocused: () -> Unit,
    onOpenProxySettings: () -> Unit,
    refreshFocusRequester: FocusRequester,
    refreshFocusProperties: FocusProperties.() -> Unit,
    modifier: Modifier = Modifier,
) {
    // 首次出面板、或者上次检测之后用户浏览时又撞上连不上/等超时, 才自动测那一轮 (见 state 的
    // 类文档). 放在这里而不是 state 的 init: 面板不出现就一个请求都不该发
    LaunchedEffect(Unit) { state.maybeAutoRun() }
    // 三态提示: 没测过 / 结果可能过期 / 新鲜. 前两种把刷新钮染成提示色 + 换标签行文字,
    // 因为过期之后不再自动重测了, 得让用户看得出该不该按
    val expireAt = state.resultsExpireAt
    var stale by remember { mutableStateOf(true) }
    LaunchedEffect(expireAt) {
        val remaining = expireAt - currentTimeMillis()
        stale = expireAt == 0L || remaining <= 0
        // 面板一直开着也要在到点那一刻自己变色, 不然只有关掉重开才看得出过期
        if (expireAt != 0L && remaining > 0) {
            delay(remaining)
            stale = true
        }
    }
    // 聚焦时不染提示色: 那时圆钮已经填成主题色实底, 字形要用它的 onPrimary 才看得清,
    // 而"该按我"这件事焦点本身已经说了
    var refreshFocused by remember { mutableStateOf(false) }
    val hint = stringResource(
        when {
            expireAt == 0L -> Lang.tv_service_check_hint_never
            stale -> Lang.tv_service_check_hint_stale
            else -> Lang.tv_service_check_hint
        },
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(TV_SERVICE_ACTION_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TV_SERVICE_CHIP_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (item in state.probes) {
                TvServiceProbeChip(item)
            }
        }
        TvCapsuleButton(
            onClick = state::refresh,
            icon = {
                if (state.running) {
                    CircularProgressIndicator(
                        Modifier.size(TV_SERVICE_SPINNER_SIZE),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = hint,
                        // 提示色只在"该按了"的时候给: 全通且新鲜时这颗跟五颗状态一样安静
                        tint = if (stale && !refreshFocused) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
            },
            modifier = Modifier
                .focusRequester(refreshFocusRequester)
                .focusProperties(refreshFocusProperties)
                // 挂在圆钮外层容器上: 它用 hasFocus 判归属, 且 onPreviewKeyEvent 自上而下,
                // 会先于圆钮内部那个 clickable 拿到事件 —— 正是它要求的"排在 clickable 之前"
                .tvLongPressKey(
                    onLongPress = onOpenProxySettings,
                    onShortPress = state::refresh,
                ),
            onFocusChanged = {
                refreshFocused = it
                if (it) onFocused()
            },
        )
    }
}

@Composable
private fun TvServiceProbeChip(item: TvServiceProbeState) {
    // 失败才染色: 全通的时候整行是一排灰名字 + 绿勾 (安静), 真出事只有那一项亮起来
    val failedColor = when (item.probe.tier) {
        TvServiceTier.Required -> MaterialTheme.colorScheme.error
        TvServiceTier.Degraded -> MaterialTheme.colorScheme.tertiary
        // 可选项失败不报警, 见 TvServiceTier.Optional
        TvServiceTier.Optional -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val textColor = when (item.result) {
        TvServiceProbeResult.Failed -> failedColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.result) {
            TvServiceProbeResult.Ok -> Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                Modifier.size(TV_SERVICE_GLYPH_SIZE),
                tint = TvServiceOkColor,
            )

            TvServiceProbeResult.Failed -> Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                Modifier.size(TV_SERVICE_GLYPH_SIZE),
                tint = failedColor,
            )

            // 没结果画一颗小点而不是留空: 留空会让这一行在测之前看起来是没画完
            TvServiceProbeResult.Pending -> Box(
                Modifier.size(TV_SERVICE_GLYPH_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
        }
        Text(
            probeLabel(item.probe.id),
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun probeLabel(id: String): String = when (id) {
    // api.bgm.tv: fork 里条目数据已经走 Animeko 服务器, 这个域名只剩"补条目简介"与登录
    ServiceConnectionTesters.ID_BANGUMI -> stringResource(Lang.tv_service_probe_bangumi)
    ServiceConnectionTesters.ID_BANGUMI_NEXT -> stringResource(Lang.tv_service_probe_bangumi_next)
    ServiceConnectionTesters.ID_TMDB -> stringResource(Lang.tv_service_probe_tmdb)
    ServiceConnectionTesters.ID_TMDB_IMAGE -> stringResource(Lang.tv_service_probe_tmdb_image)
    else -> id
}

/**
 * 通的那颗勾用**固定的绿**而不是主题色: 主题色是可换的 (设置里能改), 换成暖色系之后"通"和"挂"
 * 就分不出来了. 深浅两套主题下这个绿都够看, 而它只用在 14dp 的小图标上, 不参与配色.
 */
private val TvServiceOkColor = Color(0xFF43A047)

private val TV_SERVICE_GLYPH_SIZE = 14.dp

/** 刷新钮里那圈转圈: 比钮里的刷新字形 (20dp) 小一点才不显得撑满. */
private val TV_SERVICE_SPINNER_SIZE = 16.dp

/**
 * 五项挤在 412dp (面板内宽) 的一行里. 中文标签合计约 300dp 富余不少, 但英文
 * ("Synopsis"/"Comments") 会顶到边 —— 间距按最长的那套语言定, 别按中文定.
 */
private val TV_SERVICE_CHIP_SPACING = 12.dp

/** 状态那一组与末尾刷新钮之间: 比项与项之间宽一点, 那颗是动作不是状态. */
private val TV_SERVICE_ACTION_SPACING = 12.dp

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import androidx.compose.runtime.mutableStateOf

/**
 * 开发时临时调试用的开关与数值: 在 [DevSwitches] 里声明, 只在 debug 包 Web 控制台的「设置 → 调试」里列出来,
 * 网页上改了立即生效, 不用去电视设置页一层层找.
 *
 * 用的地方读 [value]. 它是 Compose 的 State: Composable 里读会跟着重组, 别处读拿到当前值. 值默认只在内存里, 应用重启回到默认;
 * 声明成 [persistent] 的存在设备上 (见 [DevSwitchStore]), 重启后还在 —— 进程启动时就要生效的 (冷启动、进页对比) 用它.
 * 正式包的网页没有调试区、接口也不收, 也不读存下的值, 读到的永远是默认值 (性能对比包用 `-Pani.devConsole=true` 编, 也有调试区).
 * 用完就删; 要长期留的做成正式设置.
 */
sealed class DevSwitch<T>(
    /** 网页与接口里的名字, [DevSwitches] 里唯一. */
    val key: String,
    val title: String,
    val default: T,
    val description: String?,
    /** 改了存到设备上, 重启后还在 (见 [DevSwitches.attachStore]). */
    val persistent: Boolean,
) {
    private val state = mutableStateOf(default)

    val value: T get() = state.value

    /** 网页提交的原文转成值, 不合法返回 null. */
    protected abstract fun parse(raw: String): T?

    /** 按网页提交的原文改值; 不合法时不改, 返回 false. [persistent] 的同时存下. */
    fun set(raw: String): Boolean {
        state.value = parse(raw) ?: return false
        if (persistent) DevSwitches.store?.save(key, raw)
        return true
    }

    /** 读回存下的原文, 不再存一遍. */
    internal fun restore(raw: String) {
        parse(raw)?.let { state.value = it }
    }
}

/** [DevSwitch.persistent] 开关存在哪 (Android 上是一份 SharedPreferences). 只有带调试区的包才接上. */
interface DevSwitchStore {
    fun load(key: String): String?
    fun save(key: String, raw: String)
}

/** 开关. 网页提交 "1" / "true" 为开, 其余为关. */
class ToggleSwitch(
    key: String,
    title: String,
    default: Boolean = false,
    description: String? = null,
    persistent: Boolean = false,
) : DevSwitch<Boolean>(key, title, default, description, persistent) {
    override fun parse(raw: String): Boolean = raw == "1" || raw.equals("true", ignoreCase = true)
}

/** 数值 (距离、时长、倍数等), 限定在 [min] 到 [max] 之间. */
class NumberSwitch(
    key: String,
    title: String,
    default: Float,
    val min: Float,
    val max: Float,
    description: String? = null,
    persistent: Boolean = false,
) : DevSwitch<Float>(key, title, default, description, persistent) {
    override fun parse(raw: String): Float? = raw.trim().toFloatOrNull()?.takeIf { it in min..max }
}

/** 几选一. */
class ChoiceSwitch(
    key: String,
    title: String,
    val choices: List<String>,
    default: String = choices.first(),
    description: String? = null,
    persistent: Boolean = false,
) : DevSwitch<String>(key, title, default, description, persistent) {
    override fun parse(raw: String): String? = raw.takeIf { it in choices }
}

/**
 * 调试开关登记处: 在本对象最下面声明, 例如
 * ```
 * val heroBlur = toggle("heroBlur", "hero 模糊背景", default = true)
 * val wallEdge = number("wallEdge", "海报墙左右边距 (dp)", default = 48f, range = 0f..160f)
 * ```
 * 用的地方读 `DevSwitches.heroBlur.value`. 要声明在本对象里: 写在别处的要等那段代码第一次被用到才登记, 网页上看不到.
 */
object DevSwitches {
    // 下面的声明会往里加, 必须先初始化
    private val list = mutableListOf<DevSwitch<*>>()

    /** 声明过的全部开关, 按声明顺序. */
    val all: List<DevSwitch<*>> get() = list

    /** 持久开关存在哪; 没接上 (正式包) 时持久开关也只在内存里. */
    var store: DevSwitchStore? = null
        private set

    /** 接上设备存储, 读回各持久开关存下的值. 进程启动时调一次, 要赶在读开关的代码之前. */
    fun attachStore(store: DevSwitchStore) {
        this.store = store
        for (switch in list) {
            if (switch.persistent) store.load(switch.key)?.let(switch::restore)
        }
    }

    fun toggle(
        key: String,
        title: String,
        default: Boolean = false,
        description: String? = null,
        persistent: Boolean = false,
    ): ToggleSwitch = add(ToggleSwitch(key, title, default, description, persistent))

    fun number(
        key: String,
        title: String,
        default: Float,
        range: ClosedFloatingPointRange<Float>,
        description: String? = null,
        persistent: Boolean = false,
    ): NumberSwitch = add(NumberSwitch(key, title, default, range.start, range.endInclusive, description, persistent))

    fun choice(
        key: String,
        title: String,
        choices: List<String>,
        default: String = choices.first(),
        description: String? = null,
        persistent: Boolean = false,
    ): ChoiceSwitch = add(ChoiceSwitch(key, title, choices, default, description, persistent))

    private fun <S : DevSwitch<*>> add(switch: S): S {
        require(list.none { it.key == switch.key }) { "DevSwitch key 重复: ${switch.key}" }
        list += switch
        return switch
    }

    // ---------------- 调试开关在下面声明, 用完删掉 ----------------

    /** 故障工单服务连哪个 (见 FeedbackTicketService). */
    val ticketService = choice(
        "ticketService",
        "故障工单服务",
        listOf("正式", "本机 127.0.0.1:8787"),
        description = "本机: 电脑上在 tools/ticket-worker 跑 wrangler dev --env dev (假 GitHub), 再 adb reverse tcp:8787 tcp:8787",
    )

    // 开播与搜索的可调项; 默认值是真机上量过选定的取值. 存在设备上, 冷启动也生效

    /** 记住的源 (自动选源的记忆源阶段只等它) 在同时查的名额里排到最前 */
    val searchMemoryFirst = toggle("searchMemoryFirst", "开播: 记忆源插队", persistent = true)

    /** 开查时这部番记住的源排在开查先后的最前 (先拿搜索线程), 限流也排它到队首 (见 SourceStartOrder) */
    val searchRememberedFirst = toggle("searchRememberedFirst", "开播: 记住的源开查就排最前 (先拿搜索线程)", default = true, persistent = true)

    /** 记忆源阶段最多等几秒, 到了就放开别的源; 0 为一直等 */
    val searchMemoryWaitCap = number("searchMemoryWaitCap", "开播: 记忆源最多等几秒 (0 = 一直等)", default = 0f, range = 0f..60f, persistent = true)

    /** 固定分享合集的文件夹索引多少小时内直接用, 过了只在对上的文件夹还读得出这一部时用 (见 DriveShareSearchEngine) */
    val driveShareIndexTtlHours = number("driveShareIndexTtlHours", "开播: 网盘合集索引几小时内直接用", default = 6f, range = 0f..48f, persistent = true)

    /** 固定分享合集的索引过期或没有时, 在分享里按番名搜文件夹, 不在开播时重列; 关时照旧重列 (见 DriveShareSearchEngine) */
    val driveShareFolderSearch = toggle("driveShareFolderSearch", "开播: 网盘合集索引过期时按番名搜文件夹 (关 = 重列)", default = true, persistent = true)

    /** 数据源查询用低优先级线程 (见 SearchDispatchers), 重启后生效 */
    val searchLowPriority = toggle("searchLowPriority", "开播: 数据源查询用低优先级线程 (重启生效)", default = true, persistent = true)

    /** 各源的查询用自己的通道, 同一组线程先给开查先后里靠前的源 (见 SearchDispatchers.forSource) */
    val searchPrioritized = toggle("searchPrioritized", "开播: 搜索线程按源的先后取任务", default = true, persistent = true)

    /** 名额空着时一批源同时来查, 先收齐这么多毫秒再按优先级发名额 (见 MediaSourceSearchLimiter); 0 为来一个发一个 */
    val searchStartWindowMs = number("searchStartWindowMs", "开播: 先收齐一批再按优先级发名额 (毫秒, 0 = 先到先得)", default = 150f, range = 0f..1000f, persistent = true)

    /** 在线源的播放线索记着条目页时, 下一集直接打开这一页找, 不等这个源整轮搜完 (见 WebSourceContinuation) */
    val lineageOpenPage = toggle("lineageOpenPage", "开播: 网页源线索直接开条目页", default = true, persistent = true)

    /** 自动选定资源时就暂停还没查完的数据源, 不等视频播起来 (见 PauseMediaFetchWhilePlayingExtension) */
    val pauseSearchOnSelect = toggle("pauseSearchOnSelect", "开播: 选定资源就暂停其他搜索", default = true, persistent = true)

    /** 网页抓取源一个关键词搜到几个条目页时同时开两页 (同规则源), 关时一页页开 (见 SelectorMediaSource) */
    val selectorPagesConcurrent = toggle("selectorPagesConcurrent", "开播: 网页抓取源同时开两页", default = true, persistent = true)

    /** 播放线索的数据源还在查时, 它已经交出的里有线索上的资源就选, 关时等它查完 (见 PlaybackLineageSelection) */
    val lineageWorkingResults = toggle("lineageWorkingResults", "开播: 线索源边查边找", default = true, persistent = true)

    /** 各源开查先后与排队按选源时它能到的最好层级 (线路层级、画像降级), 关时按源自身的层级 (见 SourceStartOrder.startTierOf) */
    val searchStartByBestTier = toggle("searchStartByBestTier", "开播: 开查先后按线路最好层级", default = true, persistent = true)

    /** 播放器打开视频期间读取出错只重试一次, 关时按 Media3 默认重试 3 次 (见 OpeningLoadErrorPolicy) */
    val openFailFast = toggle("openFailFast", "开播: 打开视频出错少重试 (坏链接早换源)", default = true, persistent = true)

    /**
     * 开播诊断: 记开播时间线 (控制台 `api/debug/timeline`, 见 PlayTimeline)、自动选源每一步在等什么、共用客户端与播放请求的各段耗时、
     * 每个媒体出第一帧 (见 PlaybackPerfProbe);
     * 主线程卡住超过 4 秒时把栈打进日志 (重启后生效). 关着时这些都不记.
     */
    val playDiagnostics = toggle("playDiagnostics", "开播诊断: 时间线、选源追踪、请求耗时与主线程卡顿 (卡顿监视重启生效)", persistent = true)
}

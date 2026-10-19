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

    /** 海报墙 / 详情页横排换成 Compose 版 (iOS 上画的那一套, 见 ui-tv 的 foundation/tv/wall), 在安卓上对照用. */
    val tvComposeWalls = toggle(
        "tvComposeWalls",
        "海报墙用 Compose 版 (iOS 那套)",
        description = "探索 / 追番 / 搜索 / 新番时间表的海报墙, 详情页的关联作品与演职人员行; 切换后回到页面重进生效",
    )

    /** 触屏上 (平板 / iPad) Compose 版海报墙的热门轮播与 hero 态改回电视的画法, 对比用 (触屏上默认是平板版画法, 见 TvPadHeroLayer). */
    val tvHeroOnTouch = toggle(
        "tvHeroOnTouch",
        "触屏上轮播与 hero 用电视的样子",
        description = "要先打开「海报墙用 Compose 版」; 触屏上默认是平板版画法 (模糊底铺满、横版图羽化、文字在左下)",
    )

    /** 电视包的播放页换成上游的共享版 (iOS 上用的那一版, 见 InstallTvTouchPageVariants), 在安卓平板上对照用. */
    val upstreamPlayer = toggle(
        "upstreamPlayer",
        "播放器用上游版 (iOS 那套)",
        description = "播放页换成手机 / 平板的共享版播放器; 下次进播放页生效",
    )
}

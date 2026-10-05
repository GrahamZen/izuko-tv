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
 * 用的地方读 [value]. 它是 Compose 的 State: Composable 里读会跟着重组, 别处读拿到当前值. 值只在内存里, 应用重启回到默认;
 * release 包的网页没有调试区、接口也不收, 读到的永远是默认值. 用完就删; 要长期留的做成正式设置.
 */
sealed class DevSwitch<T>(
    /** 网页与接口里的名字, [DevSwitches] 里唯一. */
    val key: String,
    val title: String,
    val default: T,
    val description: String?,
) {
    private val state = mutableStateOf(default)

    val value: T get() = state.value

    /** 网页提交的原文转成值, 不合法返回 null. */
    protected abstract fun parse(raw: String): T?

    /** 按网页提交的原文改值; 不合法时不改, 返回 false. */
    fun set(raw: String): Boolean {
        state.value = parse(raw) ?: return false
        return true
    }
}

/** 开关. 网页提交 "1" / "true" 为开, 其余为关. */
class ToggleSwitch(
    key: String,
    title: String,
    default: Boolean = false,
    description: String? = null,
) : DevSwitch<Boolean>(key, title, default, description) {
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
) : DevSwitch<Float>(key, title, default, description) {
    override fun parse(raw: String): Float? = raw.trim().toFloatOrNull()?.takeIf { it in min..max }
}

/** 几选一. */
class ChoiceSwitch(
    key: String,
    title: String,
    val choices: List<String>,
    default: String = choices.first(),
    description: String? = null,
) : DevSwitch<String>(key, title, default, description) {
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

    fun toggle(key: String, title: String, default: Boolean = false, description: String? = null): ToggleSwitch =
        add(ToggleSwitch(key, title, default, description))

    fun number(
        key: String,
        title: String,
        default: Float,
        range: ClosedFloatingPointRange<Float>,
        description: String? = null,
    ): NumberSwitch = add(NumberSwitch(key, title, default, range.start, range.endInclusive, description))

    fun choice(
        key: String,
        title: String,
        choices: List<String>,
        default: String = choices.first(),
        description: String? = null,
    ): ChoiceSwitch = add(ChoiceSwitch(key, title, choices, default, description))

    private fun <S : DevSwitch<*>> add(switch: S): S {
        require(list.none { it.key == switch.key }) { "DevSwitch key 重复: ${switch.key}" }
        list += switch
        return switch
    }

    // ---------------- 调试开关在下面声明, 用完删掉 ----------------
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.platform.ChoiceSwitch
import me.him188.ani.app.platform.DevSwitch
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.app.platform.NumberSwitch
import me.him188.ani.app.platform.ToggleSwitch
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * Web 控制台「设置 → 调试」(只在 debug 包里有, 见 [enabled]): 开发时临时要调的东西放这里, 不用去电视设置页一层层找.
 * 两种来源, 网页上与通用设置项同一套卡片:
 * - [DevSwitches] 里声明的调试开关 (代码里临时的开关 / 数值, 只在内存里), key 加 `dev.` 前缀;
 * - [RemoteSettingsCatalog.debugItems] 里登记的已有设置项 (存进设置, 写法同正式清单).
 */
internal object RemoteDebugSettings {
    private val logger = logger<RemoteDebugSettings>()
    private const val SWITCH_PREFIX = "dev."

    /** debug 包才有调试区: release 包的网页没有这一组, 接口也不收. */
    val enabled: Boolean get() = currentAniBuildConfig.isDebug

    /** 给网页的清单: 调试开关在前, 登记的设置项在后. */
    suspend fun describe(repository: SettingsRepository): JsonArray =
        JsonArray(DevSwitches.all.map(::describe) + RemoteGenericSettings.describe(repository, RemoteSettingsCatalog.debugItems))

    fun describe(switch: DevSwitch<*>): JsonObject = buildJsonObject {
        put("key", SWITCH_PREFIX + switch.key)
        put("title", switch.title)
        switch.description?.let { put("description", it) }
        when (switch) {
            is ToggleSwitch -> {
                put("editor", "toggle")
                put("value", switch.value)
            }

            is NumberSwitch -> {
                put("editor", "number")
                put("value", switch.value)
                put("min", switch.min)
                put("max", switch.max)
                put("step", "any")
            }

            is ChoiceSwitch -> {
                put("editor", "choice")
                put("value", switch.value)
                putJsonArray("choices") {
                    for (choice in switch.choices) addJsonObject {
                        put("value", choice)
                        put("label", choice)
                    }
                }
            }
        }
    }

    /** 网页改了一项: `dev.` 开头的是调试开关, 其余在 [RemoteSettingsCatalog.debugItems] 里找. */
    fun set(repository: SettingsRepository, key: String, raw: String): JsonObject {
        if (!enabled) return result(false, tr("只有 debug 包能改调试项"))
        if (!key.startsWith(SWITCH_PREFIX)) return RemoteGenericSettings.set(repository, key, raw, RemoteSettingsCatalog.debugItems)
        return setSwitch(DevSwitches.all, key.removePrefix(SWITCH_PREFIX), raw)
    }

    fun setSwitch(switches: List<DevSwitch<*>>, key: String, raw: String): JsonObject {
        val switch = switches.firstOrNull { it.key == key } ?: return result(false, tr("没有这一项设置：{0}", key))
        if (!switch.set(raw)) return result(false, tr("格式不对：{0}", raw))
        logger.info { "Dev switch ${switch.key} = ${switch.value}" }
        return result(true, tr("已保存「{0}」", switch.title))
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}

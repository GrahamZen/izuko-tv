/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.DanmakuSettings
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.episode.DanmakuOriginConfig
import me.him188.ani.app.domain.episode.EpisodeDanmakuLoader
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.Imported

/**
 * 播放器里各弹幕源的开关记在设置里 ([DanmakuSettings.disabledServices]), 和弹幕总开关一样换番、重启后照旧:
 * 关掉一个重复的源 (比如和弹弹 play 取到同一份弹幕的弹幕服务器) 以后, 看别的番也是关着的.
 *
 * - 时间校准只对这一集, 不记.
 * - 导入的弹幕 ([DanmakuServiceId.Imported]) 不记: 那是为正在播放的这一集特意导入的, 每次都显示.
 * - 设置变了 (在另一个播放页里改的) 也套到这个播放页上.
 */
fun EpisodeDanmakuLoader.rememberSourceSwitches(settings: Settings<DanmakuSettings>, scope: CoroutineScope) {
    syncDanmakuSourceSwitches(configFlow, ::setEnabled, settings, scope)
}

internal fun syncDanmakuSourceSwitches(
    config: StateFlow<Map<DanmakuServiceId, DanmakuOriginConfig>>,
    setEnabled: (DanmakuServiceId, Boolean) -> Unit,
    settings: Settings<DanmakuSettings>,
    scope: CoroutineScope,
) {
    scope.launch {
        settings.flow.map { it.disabledServices }.distinctUntilChanged().collect { disabled ->
            for ((id, enabled) in switchesToApply(disabled, config.value)) setEnabled(id, enabled)
        }
    }
    scope.launch {
        // 只记这一次变了的开关: 整张表写回去会把别的播放页刚改过的又改回来
        var previous = emptyMap<DanmakuServiceId, DanmakuOriginConfig>()
        config.collect { current ->
            val changes = switchChanges(previous, current)
            previous = current
            if (changes.isEmpty()) return@collect
            // 从设置套过来的那几下, 设置里本来就是这样, 不用再写
            val stored = settings.flow.first().disabledServices
            if (stored.withSwitches(changes) == stored) return@collect
            settings.update { copy(disabledServices = disabledServices.withSwitches(changes)) }
        }
    }
}

/** 设置里的开关和播放页当前的不一样的那些: 设置里关着的关掉, 播放页关着而设置里已经打开的打开 */
internal fun switchesToApply(
    disabled: Set<String>,
    config: Map<DanmakuServiceId, DanmakuOriginConfig>,
): List<Pair<DanmakuServiceId, Boolean>> = buildList {
    for (value in disabled) {
        val id = DanmakuServiceId(value)
        if (id.isRemembered && config[id]?.enabled != false) add(id to false)
    }
    for ((id, origin) in config) {
        if (id.isRemembered && !origin.enabled && id.value !in disabled) add(id to true)
    }
}

/** 两次之间开或关了的源; 没出现过的源当作开着 */
internal fun switchChanges(
    previous: Map<DanmakuServiceId, DanmakuOriginConfig>,
    current: Map<DanmakuServiceId, DanmakuOriginConfig>,
): Map<DanmakuServiceId, Boolean> = current
    .filter { (id, origin) -> id.isRemembered && (previous[id]?.enabled ?: true) != origin.enabled }
    .mapValues { it.value.enabled }

internal fun Set<String>.withSwitches(changes: Map<DanmakuServiceId, Boolean>): Set<String> {
    val result = toMutableSet()
    for ((id, enabled) in changes) {
        if (enabled) result.remove(id.value) else result.add(id.value)
    }
    return result
}

private val DanmakuServiceId.isRemembered: Boolean get() = this != DanmakuServiceId.Imported

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

/**
 * 把用户排过的顺序对齐到当前版本的全集 [defaults]: 不认识的条目丢掉, 缺的按**它在默认顺序里紧挨着的
 * 前一个条目**之后插回去.
 *
 * 后一条是关键: 新版本加进来的东西必须落在它设计时该在的位置 (比如新的弹幕类按钮要挨着弹幕开关),
 * 一律追到末尾会让它掉进不相干的一组里, 看着像个 bug.
 *
 * [saved] 为空 = 用户没排过, 直接给默认顺序.
 *
 * 播放器控制层 ([TvPlayerChromeLayout]) 与追番页的分类标签 ([ThemeSettings.tvCollectionTabOrder])
 * 共用这一份: 两处都是"用户排过的顺序 + 版本升级后条目集合会变".
 */
fun <T> resolveSavedOrder(saved: List<T>, defaults: List<T>): List<T> {
    if (saved.isEmpty()) return defaults
    val result = saved.distinct().filterTo(mutableListOf()) { it in defaults }
    if (result.size == defaults.size) return result
    for ((index, item) in defaults.withIndex()) {
        if (item in result) continue
        // 默认顺序里它前面最近的、已经落位的那个 —— 插到它后面
        var anchor = -1
        for (i in index - 1 downTo 0) {
            val at = result.indexOf(defaults[i])
            if (at >= 0) {
                anchor = at
                break
            }
        }
        result.add(anchor + 1, item)
    }
    return result
}

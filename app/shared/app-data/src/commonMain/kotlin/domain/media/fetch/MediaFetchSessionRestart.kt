/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

/**
 * 重新查询各数据源 (选源面板里的「刷新」、改了搜索条件). 禁用的数据源 (在数据源管理里关掉的, 这一页也没单独点开过,
 * 还停在 [MediaSourceFetchState.Disabled]) 只在 [includeDisabled] 时一并查; 单独点开过的照常重查.
 * 播放页里 [includeDisabled] 跟着「完整搜索」走: 开着 (或设置为「始终」) 才带上禁用的.
 */
fun MediaFetchSession.restartSources(includeDisabled: Boolean) {
    for (result in mediaSourceResults) {
        if (!includeDisabled && result.state.value is MediaSourceFetchState.Disabled) continue
        result.restart()
    }
}

/** 让还停在 [MediaSourceFetchState.Disabled] 的数据源开始查 (开着「完整搜索」时重新搜索, 见 [restartSources]). */
fun MediaFetchSession.startDisabledSources() {
    for (result in mediaSourceResults) {
        if (result.state.value is MediaSourceFetchState.Disabled) result.restart()
    }
}

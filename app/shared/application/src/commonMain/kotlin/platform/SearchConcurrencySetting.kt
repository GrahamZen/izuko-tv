/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.fetch.SearchConcurrency
import me.him188.ani.app.domain.settings.SettingAdjustment
import me.him188.ani.app.domain.settings.SettingAdjustments

/**
 * 设置「同时搜索的数据源」与 [SearchConcurrency] 之间: 设置的值交给它当上限; 它自动往下调时把设置写成新值并告知用户.
 */
class SearchConcurrencySetting(
    private val settings: SettingsRepository,
    private val adjustments: SettingAdjustments,
    private val concurrency: SearchConcurrency = SearchConcurrency.Default,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.mediaSelectorSettings.flow.map { it.searchConcurrency }.distinctUntilChanged().collect {
                concurrency.onSetting(it)
            }
        }
        scope.launch {
            concurrency.adjustments.collect { adjustment ->
                // 只往下调: 用户自己调得更小的不动
                settings.mediaSelectorSettings.update {
                    if (searchConcurrency in 1..adjustment.limit) this else copy(searchConcurrency = adjustment.limit)
                }
                adjustments.notify(SettingAdjustment.SearchConcurrencyReduced(adjustment.limit, adjustment.reason))
            }
        }
    }
}

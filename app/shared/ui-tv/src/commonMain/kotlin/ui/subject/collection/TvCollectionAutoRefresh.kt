/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.koin.mp.KoinPlatform

/**
 * 收藏页进页 (从详情页返回时本页也是新组合的) 与切到某一类时, 这一类的列表离上次从服务端刷新已经过了几分钟就刷新一次
 * (见 [SubjectCollectionRepository.claimCollectionListRefresh]): 在网页或别的设备上改的收藏几分钟内就能同步过来,
 * 不必等重启或手动「刷新本页」. 列表没变时一次刷新只发一个请求.
 *
 * 先等分页器起来 (首次进页由它自己判断要不要刷新), 免得这里先把这次刷新占了、分页器又因此跳过.
 */
@Composable
internal fun TvCollectionAutoRefresh(type: UnifiedCollectionType, items: LazyPagingItems<*>) {
    val repository = remember { KoinPlatform.getKoin().get<SubjectCollectionRepository>() }
    LaunchedEffect(type, items) {
        snapshotFlow { items.loadState.mediator }.filterNotNull().first()
        if (repository.claimCollectionListRefresh(type)) items.refresh()
    }
}

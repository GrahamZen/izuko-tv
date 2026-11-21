/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.app.data.models.schedule.AnimeSeason
import me.him188.ani.app.domain.search.RatingRange
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.app.ui.exploration.search.SubjectPreviewItemInfo

/**
 * 手机网页上提交的一次搜索: 关键词 + 与电视筛选弹窗同一套的筛选项 (排序 / 最低评分 / 标签).
 */
class RemoteSearchSubmission(
    val keywords: String,
    val sort: SearchSort,
    val minRating: Int?,
    val tags: List<String>,
    val year: Int?,
    val season: AnimeSeason?,
) {
    /** 套在电视当前查询上, 与电视筛选弹窗的确认逻辑一致. */
    fun applyTo(base: SubjectSearchQuery): SubjectSearchQuery = base.copy(
        keywords = keywords,
        tags = tags.ifEmpty { null },
        sort = sort,
        rating = minRating?.let { RatingRange(it, null) },
        year = year,
        // 季度从属年份 (同电视弹窗与上游 withYearFilter): 没有年份的季度是无效条件
        season = year?.let { season },
    )
}

/** 电视搜索页结果面板此刻的样子: 查询、已加载的条目与分页状态. */
internal class RemoteSearchResultsSnapshot(
    val query: SubjectSearchQuery,
    val items: List<SubjectPreviewItemInfo>,
    /** 首页加载 / 刷新中. */
    val refreshing: Boolean,
    /** 正在加载下一页. */
    val appending: Boolean,
    val endReached: Boolean,
    val error: String?,
)

/** 电视搜索页结果面板在组合里时登记 (见 [TvRemoteControl.registerSearchResults]). */
internal interface RemoteSearchResultsSource {
    /** HTTP 线程上调用: 只读快照状态. */
    fun snapshot(): RemoteSearchResultsSnapshot

    /** 让电视的列表加载下一页 (实现方自己投到主线程). */
    fun loadMore()
}

/**
 * Web 控制台 (手机扫码打开的网页) 在页面里露出来的那一面: 地址与连接状态 (画二维码)、搜索页接手机提交的查询、把结果列给网页、
 * 启动时的二维码弹窗. 安卓上是那个局域网服务 (见安卓的实现); 没有控制台的端给空的 (地址恒为 null, 什么都不发).
 */
expect object TvRemoteControl {
    /** 控制台的地址 (二维码的内容); 服务没起来 / 没连局域网时为 null. */
    val url: StateFlow<String?>

    /** 有手机连着. */
    val phoneConnected: StateFlow<Boolean>

    /** 上次记下的地址的主机 (换了网络时对比用). */
    val knownHost: StateFlow<String?>

    /** 地址跟上次记下的不一样 (换了网络): 旧的二维码失效. */
    val hostChanged: StateFlow<Boolean>

    /** 启动时那个二维码弹窗开着没有. */
    val dialogVisible: StateFlow<Boolean>

    fun dismissDialog()

    /** 手机上点了跳转 (电视要关掉手头的弹窗 / 调整窗口). */
    internal val remoteNavigations: SharedFlow<Unit>

    /** 搜索页给的当前查询 (网页搜索表单的初值); 不在搜索页时为 null. */
    var currentQueryProvider: (() -> SubjectSearchQuery)?

    /** 搜索页给的可选年份. */
    var currentYearsProvider: (() -> List<Int>)?

    /** 手机上提交的搜索. */
    val submissions: SharedFlow<RemoteSearchSubmission>

    /** 搜索页在组合里时按住服务 (手机输入要它), 离开时放开. */
    fun acquire()

    fun release()

    /** 重读一遍局域网地址 (换过网络). */
    fun refreshAddress()

    /** 进搜索页之前手机已经提交的那一次 (取走即清). */
    fun takePending(): RemoteSearchSubmission?

    internal fun registerSearchResults(source: RemoteSearchResultsSource)

    internal fun unregisterSearchResults(source: RemoteSearchResultsSource)

    /** 换一个端口 / 地址重起服务 (旧二维码作废). */
    fun resetAddress()

    /** 启动时弹二维码 (设置里开着时); [onHomePage] = 此刻还在首页 (拿到地址前用户可能已经走开). */
    fun showDialogOnLaunch(onHomePage: () -> Boolean = { true })

    /** 「启动时不再显示」. */
    fun setShowOnLaunch(enabled: Boolean)

    /** 本机开着 VPN 没有 (二维码下面的排障提示用). */
    internal fun tvVpnActive(): Boolean
}


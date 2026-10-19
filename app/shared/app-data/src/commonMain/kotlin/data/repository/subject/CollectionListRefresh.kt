/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CancellationException
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger("CollectionListRefresh")

/**
 * 收藏列表的一页, 已经写进库里.
 *
 * @param subjects 这一页的条目, 按服务端的顺序 (收藏更新时间降序)
 * @param total 服务端报的这一类的总条数
 * @param changed 这一页里有没有库里原来没有的条目, 或收藏类型、收藏更新时间变了的
 */
internal class CollectionListPage(
    val subjects: List<SubjectCollectionEntity>,
    val total: Int,
    val changed: Boolean,
)

/**
 * @param inSync 本地这一类是否已与服务端一致 (一致时列表往下翻不必再请求); 补齐中途失败时为 `false`
 * @param changed 本地这一类有没有因此变了 (多了、少了条目, 或收藏状态变了); 没变就不必重取各类的收藏计数
 */
internal class CollectionListSync(val inSync: Boolean, val changed: Boolean)

/**
 * 收藏页某一类收藏 (想看、在看…) 的刷新: 取第一页写库, 本地这一类与服务端条数对得上就到此为止, 列表其余的行照用库里的;
 * 对不上 (本地还没取全, 或有条目在别处改了收藏状态、取消了收藏) 才把这一类整个翻一遍补齐, 再把本地多出来的交给 [removeLeftType].
 *
 * 不整类删了再写: 分集对条目是级联删除, 删一次这一类所有条目的分集、播出周期与关联的取数时间都没了,
 * 之后进每一部都要重取. 收藏状态一变, 更新时间就跟着变, 条目会排到第一页, 所以条数对得上时第一页之后的行不必再核对.
 *
 * @param fetchAndSave 取服务端一页并写库
 * @param reconcileFirstPage 纠正第一页覆盖的时间窗口内本地多出来的条目
 * @param localIds 本地标着这一类的全部条目
 */
internal suspend fun syncCollectionList(
    firstPageSize: Int,
    fullSyncPageSize: Int,
    fetchAndSave: suspend (offset: Int, limit: Int) -> CollectionListPage,
    reconcileFirstPage: suspend (CollectionListPage) -> Unit,
    localIds: suspend () -> Collection<Int>,
    removeLeftType: suspend (subjectIds: List<Int>) -> Unit,
): CollectionListSync {
    val localBefore = localIds().toSet()
    val first = fetchAndSave(0, firstPageSize)
    var pagesChanged = first.changed
    reconcileFirstPage(first)
    suspend fun result(inSync: Boolean) = CollectionListSync(inSync, pagesChanged || localIds().toSet() != localBefore)

    if (localIds().size == first.total) return result(inSync = true)

    val serverIds = first.subjects.mapTo(HashSet()) { it.subjectId }
    var offset = first.subjects.size
    try {
        while (offset < first.total) {
            val page = fetchAndSave(offset, fullSyncPageSize)
            if (page.subjects.isEmpty()) break
            pagesChanged = pagesChanged || page.changed
            page.subjects.mapTo(serverIds) { it.subjectId }
            offset += page.subjects.size
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 第一页已经写好, 刷新算成功; 没翻完就不知道本地哪些是多出来的, 这次不删
        logger.warn { "Collection list full sync stopped at offset $offset of ${first.total}: $e" }
        return result(inSync = false)
    }
    val extra = localIds().filter { it !in serverIds }
    if (extra.isNotEmpty()) removeLeftType(extra)
    return result(inSync = true)
}

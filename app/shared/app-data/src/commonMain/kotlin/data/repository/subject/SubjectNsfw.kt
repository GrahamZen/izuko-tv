/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import androidx.datastore.core.DataStore
import androidx.paging.PagingData
import androidx.paging.filter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.NsfwMode
import kotlin.jvm.JvmName
import kotlin.time.Duration.Companion.seconds

/**
 * 哪些作品是 NSFW, 按条目 id 记 (只记是的). 数据经过的几处转换顺手登记 ([record]): 条目详情与收藏库、搜索结果、热门、推荐、关联条目、
 * 人物作品、播放器里的相关推荐、新番时间表 —— 各页面的数据模型因此不必各带一个标记, 只要有条目 id 就能问 [modeOf].
 * 没登记过的当作不是 (照常显示). 登记表落盘, 启动时读回并从收藏库补齐 (见 [attach]).
 *
 * 设置 (UISettings.searchSettings.nsfwMode) 管所有页面: 隐藏 = 列表里去掉, 模糊 = 封面 / 背景图打码, 显示 = 不处理.
 * 界面里按 ui-foundation 的 NsfwPolicy 用; 不在界面里的 (系统主屏频道、屏保、Web 控制台) 直接问 [modeOf].
 */
object SubjectNsfw {
    private val _ids = MutableStateFlow<Set<Int>>(emptySet())

    /** 登记过是 NSFW 的条目 id. */
    val ids: StateFlow<Set<Int>> = _ids.asStateFlow()

    private val _mode = MutableStateFlow(NsfwMode.BLUR)

    /** 设置里选的处理方式 (挂上设置之前按默认的模糊). */
    val mode: StateFlow<NsfwMode> = _mode.asStateFlow()

    private var attached = false

    /**
     * 挂上落盘的 [store] 与设置 [modeFlow]: 读回落盘的、并上 [seed] (收藏库里记着是 NSFW 的), 之后登记表一变就写回 (攒一会儿再写).
     * 只挂一次 (进程启动时).
     */
    @OptIn(FlowPreview::class)
    fun attach(
        scope: CoroutineScope,
        store: DataStore<List<Int>>,
        modeFlow: Flow<NsfwMode>,
        seed: suspend () -> Collection<Int>,
    ) {
        if (attached) return
        attached = true
        scope.launch { modeFlow.collect { _mode.value = it } }
        scope.launch {
            val saved = store.data.first()
            val seeded = try {
                seed()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            _ids.update { it + saved + seeded }
            _ids.debounce(PERSIST_DEBOUNCE).distinctUntilChanged().collect { ids ->
                store.updateData { ids.sorted() }
            }
        }
    }

    /** 登记 [subjectId] 是不是 NSFW (数据里带着这个标记的地方调; 标记变了也跟着改). */
    fun record(subjectId: Int, nsfw: Boolean) {
        if (nsfw == (subjectId in _ids.value)) return
        _ids.update { if (nsfw) it + subjectId else it - subjectId }
    }

    /** 当前设置下 [subjectId] 怎么显示: 登记过是 NSFW 的按设置, 否则照常. */
    fun modeOf(subjectId: Int): NsfwMode = if (subjectId in _ids.value) _mode.value else NsfwMode.DISPLAY

    private val PERSIST_DEBOUNCE = 2.seconds
}

/**
 * 分页列表去掉设置为隐藏的 NSFW 作品 (见 [SubjectNsfw]). 每条在分页取到时按当时的登记表判; 设置在「隐藏」与别的档之间切换时重新收集上游
 * (取回第一页重过), 所以上游要能重收 —— `Pager.flow` 或 cachedIn 过的. 不与设置流 combine: 那样会把同一份 PagingData 再交给下游收一次,
 * 报 "Attempt to collect twice from pageEventFlow".
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T : Any> Flow<PagingData<T>>.withoutHiddenNsfw(subjectIdOf: (T) -> Int): Flow<PagingData<T>> {
    val upstream = this
    return SubjectNsfw.mode.map { it == NsfwMode.HIDE }.distinctUntilChanged().flatMapLatest { hide ->
        if (!hide) upstream else upstream.map { data -> data.filter { SubjectNsfw.modeOf(subjectIdOf(it)) != NsfwMode.HIDE } }
    }
}

/** 列表去掉设置为隐藏的 NSFW 作品 (见 [SubjectNsfw]); 设置或登记表一变重过. */
@JvmName("withoutHiddenNsfwList")
fun <T> Flow<List<T>>.withoutHiddenNsfw(subjectIdOf: (T) -> Int): Flow<List<T>> =
    combine(SubjectNsfw.mode, SubjectNsfw.ids, this) { mode, ids, list ->
        if (mode != NsfwMode.HIDE) list else list.filterNot { subjectIdOf(it) in ids }
    }

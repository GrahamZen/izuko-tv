/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.preference.NoticeSoundKind
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.navigation.BrowserNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.time.Duration.Companion.milliseconds

/** 写之前要先问的一句, 等页面弹窗回话. */
@Immutable
class TvSettingsPendingConfirm(
    val confirm: TvSettingsConfirm,
    val onConfirm: () -> Unit,
)

/** 页面给状态的东西 (组合里才拿得到的): 试听提示音、弹提示、打开链接. */
class TvSettingsHost(
    val playNoticeSound: (NoticeSoundKind) -> Unit,
    val toast: (String) -> Unit,
    val browser: BrowserNavigator,
)

/**
 * 电视设置页的状态: 收各份值 (设置、系统值、实时值), 按 [TvSettingsNav] 建出整页 ([content], 字已换好), 把页面上的操作写回设置.
 *
 * 文案模板按需读 (第一次建页时缺哪些读哪些, 读完再建), 页面模型本身是纯函数 (见 [buildTvSettingsPage]).
 * 写设置走一个不随页面取消的作用域, 串行写 —— 连按两下开关不丢一次, 刚改完就退出页面也写得进去.
 * 实时值 ([TvSettingKey.Live]) 各收各的, 哪个先到先用 (一个慢的不拖住整页).
 */
class TvSettingsState(
    private val catalog: List<TvSettingsCategory>,
    private val repository: SettingsRepository,
    private val deps: TvSettingsDeps,
    initialCategory: String?,
    private val scope: CoroutineScope,
    /** 一直要读的值 (分类的显示条件、说明栏二维码用到的, 如调试、Web 控制台地址). */
    extraKeys: List<TvSettingKey<*>> = emptyList(),
    private val loadTemplate: suspend (StringResource) -> String = { getString(it) },
) {
    val nav = MutableStateFlow(TvSettingsNav(categoryId = initialCategory))

    /** 盖在上面的整页或对话框; null = 没有. */
    val overlay = MutableStateFlow<TvSettingsOverlay?>(null)

    val pendingConfirm = MutableStateFlow<TvSettingsPendingConfirm?>(null)

    private val allKeys: List<TvSettingKey<*>> =
        (catalog.flatMap { it.items.orEmpty() }.flatMap { it.flatten() }.flatMap { it.keys } + extraKeys).distinct()

    private val storedKeys = allKeys.filterIsInstance<TvSettingKey.Stored<*>>()
    private val platformKeys = allKeys.filterIsInstance<TvSettingKey.Platform<*>>()
    private val liveKeys = allKeys.filterIsInstance<TvSettingKey.Live<*>>()

    private val platformValues = MutableStateFlow<Map<TvSettingKey<*>, Any>>(emptyMap())
    private val liveValues = MutableStateFlow<Map<TvSettingKey<*>, Any>>(emptyMap())
    private val templates = MutableStateFlow<Map<StringResource, String>>(emptyMap())
    private val loadingTemplates = HashSet<StringResource>()

    private var env: TvSettingsEnv? = null

    /** 焦点所在的中栏行 (在左栏 / 进了一层时为 null) 与它的焦点任务 (见 [TvSettingItem.whileFocused]). */
    private var focusedRowId: String? = null
    private var focusJob: Job? = null

    private val storedValues = if (storedKeys.isEmpty()) {
        flowOf(emptyMap())
    } else {
        combine(storedKeys.map { key -> key.settings(repository).flow.map { key to (it as Any) } }) { pairs -> pairs.toMap() }
    }

    val values: StateFlow<TvSettingsValues> = combine(storedValues, platformValues, liveValues) { stored, platform, live ->
        TvSettingsValues(stored + platform + live)
    }.stateIn(scope, SharingStarted.Eagerly, TvSettingsValues(emptyMap()))

    /** 建好的整页; 文案还没读齐时为 null. */
    val content: StateFlow<TvSettingsContent?> = combine(values, nav, templates) { values, nav, templates ->
        val resolver = TvTextResolver { templates[it] }
        val built = buildTvSettingsPage(catalog, values, nav, resolver)
        if (resolver.missing.isEmpty()) built else {
            requestTemplates(resolver.missing)
            null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        for (key in liveKeys) {
            scope.launch {
                runCatching {
                    key.flow(deps).collect { value -> liveValues.update { it + (key to value) } }
                }.onFailure { e -> logger.error(e) { "Failed to collect live setting ${key.name}" } }
            }
        }
    }

    /** 页面给的东西到了: 读一遍系统值 (语言、刷新率). */
    fun attach(host: TvSettingsHost) {
        env = TvSettingsEnv(
            context = deps.context,
            deps = deps,
            repository = repository,
            playNoticeSound = host.playNoticeSound,
            toast = host.toast,
            browser = host.browser,
            show = { overlay.value = it },
            ask = { confirm, onConfirm ->
                pendingConfirm.value = TvSettingsPendingConfirm(confirm) { apply(listOf(TvSettingsEdit.Run(onConfirm))) }
            },
            restartFocusTask = { startFocusTask(focusedRowId, dwell = false) },
        )
        reloadPlatform()
        startFocusTask(focusedRowId, dwell = true)
    }

    private fun reloadPlatform() {
        platformValues.value = platformKeys.associateWith { key ->
            runCatching { key.load(deps) }.getOrElse { e ->
                logger.error(e) { "Failed to load platform setting ${key.name}" }
                null
            }
        }.filterValues { it != null }.mapValues { it.value!! }
    }

    private fun requestTemplates(resources: Set<StringResource>) {
        val toLoad = resources.filter { it !in loadingTemplates }
        if (toLoad.isEmpty()) return
        loadingTemplates += toLoad
        scope.launch {
            val loaded = toLoad.associateWith { res ->
                runCatching { loadTemplate(res) }.getOrElse { "" }
            }
            templates.update { it + loaded }
        }
    }

    // ---- 页面上的操作 ----

    /** 焦点换到中栏的 [rowId] (null = 不在中栏): 上一行的焦点任务取消, 这一行有的话停一会儿再起. */
    fun onRowFocused(rowId: String?) {
        if (rowId == focusedRowId) return
        focusedRowId = rowId
        startFocusTask(rowId, dwell = true)
    }

    private fun startFocusTask(rowId: String?, dwell: Boolean) {
        focusJob?.cancel()
        focusJob = null
        val env = env ?: return
        val task = rowId?.let { focusTaskOf(it) } ?: return
        focusJob = scope.launch {
            // 停一会儿再起: 一路按下键划过去的行不起服务、不去要二维码
            if (dwell) delay(FOCUS_DWELL)
            runCatching { task(env) }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.error(e) { "Focus task of $rowId failed" }
            }
        }
    }

    private fun focusTaskOf(rowId: String): (suspend (TvSettingsEnv) -> Unit)? {
        TvSettingsRowIds.parseEntry(rowId)?.let { (itemId, key) ->
            val item = findTvSettingItem(catalog, itemId) as? TvSettingItem.Entries<*> ?: return null
            return item.focusTask(values.value, key)
        }
        return findTvSettingItem(catalog, rowId)?.whileFocused
    }

    /** 左栏焦点换到 [categoryId] (中栏跟着换). */
    fun onRailFocused(categoryId: String) {
        nav.update { if (it.categoryId == categoryId && it.drill.isEmpty()) it else TvSettingsNav(categoryId = categoryId) }
    }

    /** 在中栏按了返回 / 左键: 进了某一项时退出一层, 返回 true; 否则 false (焦点回左栏). */
    fun backInRight(): Boolean {
        if (nav.value.drill.isEmpty()) return false
        nav.update { it.copy(drill = it.drill.dropLast(1)) }
        return true
    }

    /** 中栏 (或没有新样式的分类那一行) 按了确定. */
    fun onRowClicked(rowId: String) {
        TvSettingsRowIds.parseLegacy(rowId)?.let { id ->
            SettingsTab.entries.firstOrNull { it.name == id }?.let { overlay.value = TvSettingsOverlay.Legacy(it) }
            return
        }
        val values = values.value
        TvSettingsRowIds.parseOption(rowId)?.let { (itemId, index) ->
            val item = findTvSettingItem(catalog, itemId) as? TvSettingItem.Choice<*> ?: return
            val edits = item.edits(values, index)
            val confirm = item.confirmFor(values, index)
            val commit = {
                apply(edits)
                nav.update { if (it.drill.lastOrNull() == itemId) it.copy(drill = it.drill.dropLast(1)) else it }
            }
            if (confirm != null) pendingConfirm.value = TvSettingsPendingConfirm(confirm, commit) else commit()
            return
        }
        TvSettingsRowIds.parseSorted(rowId)?.let { (itemId, key) ->
            val item = findTvSettingItem(catalog, itemId) as? TvSettingItem.Sorter ?: return
            apply(item.toggleEdits(values, key))
            return
        }
        TvSettingsRowIds.parseEntry(rowId)?.let { (itemId, key) ->
            val item = findTvSettingItem(catalog, itemId) as? TvSettingItem.Entries<*> ?: return
            val edits = item.clickEdits(values, key)
            val confirm = item.confirmFor(values, key)
            if (confirm != null) pendingConfirm.value = TvSettingsPendingConfirm(confirm) { apply(edits) } else apply(edits)
            return
        }
        when (val item = findTvSettingItem(catalog, rowId)) {
            is TvSettingItem.Toggle -> {
                val target = !item.read(values)
                val commit = { apply(item.write(target)) }
                val confirm = item.confirm?.invoke(values, target)
                if (confirm != null) pendingConfirm.value = TvSettingsPendingConfirm(confirm, commit) else commit()
            }

            is TvSettingItem.Choice<*>, is TvSettingItem.Sorter -> nav.update { it.copy(drill = it.drill + item.id) }
            is TvSettingItem.Group -> {
                env?.let { e -> item.onOpen?.invoke(e) }
                nav.update { it.copy(drill = it.drill + item.id) }
            }

            is TvSettingItem.Action -> {
                val edits = item.edits(values)
                if (edits.isEmpty()) return
                val commit = { apply(edits) }
                if (item.confirm != null) pendingConfirm.value = TvSettingsPendingConfirm(item.confirm, commit) else commit()
            }

            is TvSettingItem.Entries<*>, is TvSettingItem.Header, null -> {}
        }
    }

    /** 挪完顺序放下了: [rowId] 是挪的那一行, [order] 是它那一组 (同 [TvSettingsRow.moveGroup]) 现在的先后. */
    fun onRowsReordered(rowId: String, order: List<String>) {
        val values = values.value
        TvSettingsRowIds.parseSorted(rowId)?.let { (itemId, _) ->
            val item = findTvSettingItem(catalog, itemId) as? TvSettingItem.Sorter ?: return
            apply(item.reorderEdits(values, order.mapNotNull { TvSettingsRowIds.parseSorted(it)?.second }))
            return
        }
        TvSettingsRowIds.parseEntry(rowId)?.let { (itemId, _) ->
            val item = findTvSettingItem(catalog, itemId) as? TvSettingItem.Entries<*> ?: return
            val move = item.move ?: return
            apply(move(order.mapNotNull { TvSettingsRowIds.parseEntry(it)?.second }))
        }
    }

    fun confirmPending() {
        val pending = pendingConfirm.value ?: return
        pendingConfirm.value = null
        pending.onConfirm()
    }

    fun dismissPending() {
        pendingConfirm.value = null
    }

    fun dismissOverlay() {
        overlay.value = null
    }

    private fun apply(edits: List<TvSettingsEdit>) {
        if (edits.isEmpty()) return
        tvSettingsWriteScope.launch {
            writeLock.withLock {
                for (edit in edits) {
                    runCatching {
                        when (edit) {
                            is TvSettingsEdit.Update<*> -> edit.applyTo(repository)
                            is TvSettingsEdit.Run -> {
                                val env = env ?: return@runCatching
                                edit.block(env)
                                reloadPlatform()
                            }
                        }
                    }.onFailure { e -> logger.error(e) { "Failed to apply TV setting edit" } }
                }
            }
        }
    }

    private companion object {
        private val logger = logger<TvSettingsState>()
        private val writeLock = Mutex()

        /** 焦点在一行上停多久才起它的焦点任务. */
        val FOCUS_DWELL = 400.milliseconds
    }
}

/** 写设置用的作用域: 不随页面取消 (改完马上退出页面也要写进去; 对话框里按了确定、对话框关掉后也要写完). */
internal val tvSettingsWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private suspend fun <S : Any> TvSettingsEdit.Update<S>.applyTo(repository: SettingsRepository) {
    key.settings(repository).update { transform(this) }
}

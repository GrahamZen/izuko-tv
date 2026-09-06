/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.preference.NoticeSoundKind
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.navigation.BrowserNavigator
import me.him188.ani.app.navigation.SettingsTab
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/**
 * 电视设置页的清单: 分类 ([TvSettingsCategory]) 下面一条条设置 ([TvSettingItem]), 写成数据而不是界面. 页面 (原生三栏, 见 TvSettingsView)
 * 按它建出行; 加一项设置 = 在 [tvSettingsCatalog] 里加一行 (见 TvSettingsEntries*.kt).
 *
 * 一项设置只说三件事: 读 (从哪份设置里取当前值)、写 (改成什么)、给人看的字 (标题 / 说明 / 选项名). 控件按类型定:
 * 开关 ([TvSettingItem.Toggle]), 单选 ([TvSettingItem.Choice], 数值也是 —— 一档一个选项), 动作 ([TvSettingItem.Action]),
 * 一组随数据变的行 ([TvSettingItem.Entries], 如数据源列表), 排序 ([TvSettingItem.Sorter]), 进一层的一组设置项 ([TvSettingItem.Group]).
 */
class TvSettingsCategory(
    val tab: SettingsTab,
    val title: TvText,
    /** 左栏分组的小标题 (这一组的第一项才有). */
    val section: TvText?,
    /** null = 这一类没有新样式: 右栏只有一行, 确定打开原来的设置页. */
    val items: List<TvSettingItem>?,
    val visible: (TvSettingsValues) -> Boolean = { true },
) {
    val id: String get() = tab.name
}

/**
 * 一份值的来源: 设置仓库里的一份 ([Stored]), 要从系统现读的 ([Platform], 如应用语言、屏幕支持的刷新率),
 * 或页面开着时一直在变的 ([Live], 如账号、数据源列表、检查更新的进度).
 */
sealed class TvSettingKey<S : Any>(val name: String) {
    class Stored<S : Any>(name: String, val settings: (SettingsRepository) -> Settings<S>) : TvSettingKey<S>(name)

    /** 打开页面时读一次, 每次做完一件事 ([TvSettingsEdit.Run]) 后重读. */
    class Platform<S : Any>(name: String, val load: (TvSettingsDeps) -> S) : TvSettingKey<S>(name)

    class Live<S : Any>(name: String, val flow: (TvSettingsDeps) -> Flow<S>) : TvSettingKey<S>(name)

    override fun toString(): String = "TvSettingKey($name)"
}

/** 各份值此刻的样子. 还没读到的为 null —— 用到它的设置项先不显示. */
class TvSettingsValues(private val values: Map<TvSettingKey<*>, Any>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <S : Any> get(key: TvSettingKey<S>): S? = values[key] as S?

    fun has(key: TvSettingKey<*>): Boolean = key in values
}

/** 写设置: 改一份设置 ([Update]), 或做一件事 ([Run], 如改应用语言、试听提示音、问一句再做). */
sealed interface TvSettingsEdit {
    class Update<S : Any>(val key: TvSettingKey.Stored<S>, val transform: (S) -> S) : TvSettingsEdit

    class Run(val block: suspend (TvSettingsEnv) -> Unit) : TvSettingsEdit
}

/** [TvSettingsEdit.Run] 用得到的东西. 都在主线程调. */
class TvSettingsEnv(
    val context: Context,
    val deps: TvSettingsDeps,
    val repository: SettingsRepository,
    val playNoticeSound: (NoticeSoundKind) -> Unit,
    val toast: (String) -> Unit,
    /** 打开链接 (电视上没有浏览器时弹二维码). */
    val browser: BrowserNavigator,
    /** 弹出对话框 / 盖一整页 (见 [TvSettingsOverlay]). */
    val show: (TvSettingsOverlay) -> Unit,
    /** 先问一句, 确认了再做 [onConfirm]. */
    val ask: (confirm: TvSettingsConfirm, onConfirm: suspend (TvSettingsEnv) -> Unit) -> Unit,
    /** 焦点所在那一行的焦点任务 (见 [TvSettingItem.whileFocused]) 重新跑一遍 (如换一张过期的登录二维码). */
    val restartFocusTask: () -> Unit,
)

/**
 * 说明栏里画的二维码: [content] 为 null 时还没有码 (只写 [status], 如正在准备、没连局域网); [caption] 写在码下面
 * (多是地址本身, 扫不了时能手敲), [status] 写在码上面 (如已确认、已过期).
 */
data class TvQr(val content: String?, val caption: TvText? = null, val status: TvText? = null)

/**
 * 说明栏标题上面画的图 (如开发者头像、鸣谢里各家的标志). [round] = 裁成圆形; false 时整张原样画 (TMDB 的品牌条款要求标志不改形状).
 */
data class TvImage(val res: DrawableResource, val round: Boolean = true)

/** 写之前先问一句 (默认焦点在「取消」上). [destructive] = 确认按钮用红字. */
class TvSettingsConfirm(
    val title: TvText?,
    val text: TvText,
    val confirmLabel: TvText,
    val destructive: Boolean = false,
)

/** 盖在设置页上面的东西. 整页 ([Legacy]) 盖着时原生页藏起来, 返回键先关它; 对话框 ([Dialog]) 自己是窗口. */
sealed interface TvSettingsOverlay {
    /** 原来的设置页那一类. */
    data class Legacy(val tab: SettingsTab) : TvSettingsOverlay

    class Dialog(val content: @Composable (dismiss: () -> Unit) -> Unit) : TvSettingsOverlay
}

sealed class TvSettingItem(
    val id: String,
    val keys: List<TvSettingKey<*>>,
    val visible: (TvSettingsValues) -> Boolean,
    /** 焦点在这一行时说明栏画的二维码 (手机扫了去做这一项: 打开网页、下载日志、登录网盘). */
    val qr: ((TvSettingsValues) -> TvQr?)? = null,
    /**
     * 焦点停在这一行时跑的事 (停一会儿才开始, 划过去不跑; 焦点离开就取消), 如起「扫码传到手机」的服务: 跑出来的东西放进
     * [TvSettingsDeps] 里, 经实时值回到 [qr].
     */
    val whileFocused: (suspend (TvSettingsEnv) -> Unit)? = null,
) {
    /** 组标题 (不可聚焦). */
    class Header(id: String, val title: TvText, visible: (TvSettingsValues) -> Boolean) : TvSettingItem(id, emptyList(), visible)

    /** 开关: 确定直接切换, 行尾写「开 / 关」. [confirm] 给了的那一边先问一句. */
    class Toggle(
        id: String,
        keys: List<TvSettingKey<*>>,
        val title: TvText,
        val description: (TvSettingsValues) -> TvText?,
        val read: (TvSettingsValues) -> Boolean,
        val write: (Boolean) -> List<TvSettingsEdit>,
        /** 切到那一边 (第二个参数) 之前要问的一句; null = 直接切. */
        val confirm: ((TvSettingsValues, Boolean) -> TvSettingsConfirm?)?,
        visible: (TvSettingsValues) -> Boolean,
        qr: ((TvSettingsValues) -> TvQr?)? = null,
    ) : TvSettingItem(id, keys, visible, qr)

    /**
     * 单选 (数值也是, 一档一个选项): 行尾写当前值, 确定进一层列出选项, 当前值打勾、焦点落在它上面, 选完写入并退回.
     * 当前值不在选项里 ([read] 给 null 或对不上) 时不打勾.
     */
    class Choice<V>(
        id: String,
        keys: List<TvSettingKey<*>>,
        val title: TvText,
        val description: (TvSettingsValues) -> TvText?,
        private val read: (TvSettingsValues) -> V?,
        private val options: (TvSettingsValues) -> List<V>,
        private val label: (V) -> TvText,
        private val write: (V) -> List<TvSettingsEdit>,
        private val confirm: ((V) -> TvSettingsConfirm?)?,
        /** 选项前面画的色块 (ARGB); null = 不画. */
        private val swatch: ((V) -> Int?)?,
        /** 焦点在某个选项上时说明栏写的 (这一档具体做什么); null = 写这一项的说明. */
        private val optionDescription: ((V) -> TvText?)?,
        visible: (TvSettingsValues) -> Boolean,
        qr: ((TvSettingsValues) -> TvQr?)? = null,
    ) : TvSettingItem(id, keys, visible, qr) {
        fun optionLabels(values: TvSettingsValues): List<TvText> = options(values).map(label)

        fun optionSwatches(values: TvSettingsValues): List<Int?> = options(values).map { v -> swatch?.invoke(v) }

        fun optionDescriptions(values: TvSettingsValues): List<TvText?> = options(values).map { v -> optionDescription?.invoke(v) }

        /** 当前值在选项里的下标; -1 = 不在里面. */
        fun currentIndex(values: TvSettingsValues): Int {
            val current = read(values) ?: return -1
            return options(values).indexOf(current)
        }

        fun currentLabel(values: TvSettingsValues): TvText? = read(values)?.let(label)

        /** 选第 [index] 个: 要先问一句就给出问话, 写法见 [edits]. 选的就是当前值时什么都不写. */
        fun confirmFor(values: TvSettingsValues, index: Int): TvSettingsConfirm? {
            val v = options(values).getOrNull(index) ?: return null
            if (v == read(values)) return null
            return confirm?.invoke(v)
        }

        fun edits(values: TvSettingsValues, index: Int): List<TvSettingsEdit> {
            val v = options(values).getOrNull(index) ?: return emptyList()
            if (v == read(values)) return emptyList()
            return write(v)
        }
    }

    /**
     * 动作 (如清理图片缓存、检查更新): 确定执行 ([edits] 按此刻的值给), [confirm] 非空时先问. [value] 是行尾的字 (如占用多少、检查结果),
     * [chevron] = 行尾画 › (打开一页或一个对话框). [edits] 给空表示这一行只是显示 (如版本号、昵称).
     */
    class Action(
        id: String,
        keys: List<TvSettingKey<*>>,
        val title: TvText,
        val description: (TvSettingsValues) -> TvText?,
        val value: (TvSettingsValues) -> TvText?,
        val confirm: TvSettingsConfirm?,
        val chevron: Boolean,
        val edits: (TvSettingsValues) -> List<TvSettingsEdit>,
        visible: (TvSettingsValues) -> Boolean,
        qr: ((TvSettingsValues) -> TvQr?)? = null,
        whileFocused: (suspend (TvSettingsEnv) -> Unit)? = null,
        /** 焦点在这一行时说明栏画的图. */
        val image: TvImage? = null,
    ) : TvSettingItem(id, keys, visible, qr, whileFocused)

    /**
     * 一组设置项 (如开发者名单、鸣谢): 行尾画 ›, 确定进一层列出 [items] (同分类里的项, 也可以再套一组), 返回退回这一行.
     * [onOpen] 是进去时做的事 (如开始读开源库列表), 不等它做完就进.
     */
    class Group(
        id: String,
        val title: TvText,
        val description: (TvSettingsValues) -> TvText?,
        val value: (TvSettingsValues) -> TvText?,
        val items: List<TvSettingItem>,
        val onOpen: ((TvSettingsEnv) -> Unit)?,
        visible: (TvSettingsValues) -> Boolean,
        whileFocused: (suspend (TvSettingsEnv) -> Unit)? = null,
    ) : TvSettingItem(id, emptyList(), visible, whileFocused = whileFocused)

    /**
     * 一组随数据变的行 (如数据源、订阅、弹幕过滤规则): [entries] 给出现在有哪些, 每一行按 [key] 认 (行 id 用它, 焦点跟着它走).
     * 确定做 [click] (要先问的给 [confirm]); [move] 非空时可以长按确定挪顺序, 放下时按新顺序 (全部 key) 写回.
     */
    class Entries<T>(
        id: String,
        keys: List<TvSettingKey<*>>,
        private val entries: (TvSettingsValues) -> List<T>,
        private val key: (T) -> String,
        private val title: (T) -> TvText,
        private val value: (TvSettingsValues, T) -> TvText?,
        private val description: (TvSettingsValues, T) -> TvText?,
        private val confirm: ((T) -> TvSettingsConfirm?)?,
        private val click: (T) -> List<TvSettingsEdit>,
        val move: ((List<String>) -> List<TvSettingsEdit>)?,
        visible: (TvSettingsValues) -> Boolean,
        /** 焦点在某一行时说明栏画的二维码 (见 [TvSettingItem.qr]). */
        private val entryQr: ((TvSettingsValues, T) -> TvQr?)? = null,
        /** 焦点停在某一行时跑的事 (见 [TvSettingItem.whileFocused]); 给 null 的行不跑. */
        private val entryWhileFocused: ((T) -> (suspend (TvSettingsEnv) -> Unit)?)? = null,
        /** 焦点在某一行时说明栏画的图. */
        private val entryImage: ((T) -> TvImage?)? = null,
    ) : TvSettingItem(id, keys, visible) {
        fun rows(values: TvSettingsValues): List<TvSettingsEntry> = entries(values).map { e ->
            TvSettingsEntry(key(e), title(e), value(values, e), description(values, e), entryQr?.invoke(values, e), entryImage?.invoke(e))
        }

        fun focusTask(values: TvSettingsValues, entryKey: String): (suspend (TvSettingsEnv) -> Unit)? =
            find(values, entryKey)?.let { entryWhileFocused?.invoke(it) }

        fun confirmFor(values: TvSettingsValues, entryKey: String): TvSettingsConfirm? =
            find(values, entryKey)?.let { confirm?.invoke(it) }

        fun clickEdits(values: TvSettingsValues, entryKey: String): List<TvSettingsEdit> =
            find(values, entryKey)?.let(click).orEmpty()

        private fun find(values: TvSettingsValues, entryKey: String): T? = entries(values).firstOrNull { key(it) == entryKey }
    }

    /**
     * 排序 (如字幕语言、分辨率的优先顺序): 行尾写选中的几项, 确定进一层列出全部; 确定勾选 / 取消, 长按确定挪顺序.
     * [read] 给全部选项 (key 与是否选中) 的当前顺序, [write] 按新的顺序与勾选写回.
     */
    class Sorter(
        id: String,
        keys: List<TvSettingKey<*>>,
        val title: TvText,
        val description: (TvSettingsValues) -> TvText?,
        private val read: (TvSettingsValues) -> List<Pair<String, Boolean>>,
        private val label: (String) -> TvText,
        private val summary: (List<Pair<String, Boolean>>) -> TvText,
        private val write: (List<Pair<String, Boolean>>) -> List<TvSettingsEdit>,
        visible: (TvSettingsValues) -> Boolean,
    ) : TvSettingItem(id, keys, visible) {
        fun options(values: TvSettingsValues): List<Pair<String, Boolean>> = read(values)

        fun labelOf(key: String): TvText = label(key)

        fun summary(values: TvSettingsValues): TvText = summary(read(values))

        fun toggleEdits(values: TvSettingsValues, key: String): List<TvSettingsEdit> =
            write(read(values).map { (k, selected) -> k to (if (k == key) !selected else selected) })

        fun reorderEdits(values: TvSettingsValues, order: List<String>): List<TvSettingsEdit> {
            val current = read(values).toMap()
            if (order.toSet() != current.keys) return emptyList()
            return write(order.map { it to current.getValue(it) })
        }
    }
}

/** [TvSettingItem.Entries] 里的一行. */
data class TvSettingsEntry(
    val key: String,
    val title: TvText,
    val value: TvText?,
    val description: TvText?,
    val qr: TvQr? = null,
    val image: TvImage? = null,
)

/** 这一项与 (是一组时) 它里面的全部项, 一层层展开. */
fun TvSettingItem.flatten(): Sequence<TvSettingItem> =
    if (this is TvSettingItem.Group) sequenceOf(this) + items.asSequence().flatMap { it.flatten() } else sequenceOf(this)

// ---- 写清单用的 DSL ----

@DslMarker
annotation class TvSettingsDsl

@TvSettingsDsl
class TvSettingsCatalogBuilder {
    private val categories = ArrayList<TvSettingsCategory>()
    private var pendingSection: TvText? = null

    /** 左栏新起一组: 下一个分类上面写这个小标题. */
    fun section(title: StringResource) {
        pendingSection = tvText(title)
    }

    fun category(
        tab: SettingsTab,
        title: StringResource,
        visible: (TvSettingsValues) -> Boolean = { true },
        items: TvSettingsItemsBuilder.() -> Unit,
    ) {
        val builder = TvSettingsItemsBuilder(tab.name).apply(items)
        add(TvSettingsCategory(tab, tvText(title), takeSection(), builder.build(), visible))
    }

    /** 没有新样式的分类: 打开原来的设置页. */
    fun legacy(tab: SettingsTab, title: StringResource, visible: (TvSettingsValues) -> Boolean = { true }) {
        add(TvSettingsCategory(tab, tvText(title), takeSection(), null, visible))
    }

    private fun takeSection(): TvText? = pendingSection.also { pendingSection = null }

    private fun add(category: TvSettingsCategory) {
        require(categories.none { it.tab == category.tab }) { "duplicate category ${category.tab}" }
        categories += category
    }

    fun build(): List<TvSettingsCategory> = categories.toList()
}

fun tvSettingsCatalog(block: TvSettingsCatalogBuilder.() -> Unit): List<TvSettingsCategory> =
    TvSettingsCatalogBuilder().apply(block).build()

/**
 * 一个分类 (或一组) 里的设置项. 项的 id 按声明顺序编号 (`分类.序号`, 组里的是 `组的 id.序号`), 清单里条目的先后不变就稳定 ——
 * 页面按它记焦点停在哪.
 */
@TvSettingsDsl
class TvSettingsItemsBuilder(private val prefix: String) {
    private val items = ArrayList<TvSettingItem>()

    private fun nextId(): String = "$prefix.${items.size}"

    fun header(title: StringResource, visible: (TvSettingsValues) -> Boolean = { true }) {
        items += TvSettingItem.Header(nextId(), tvText(title), visible)
    }

    fun <S : Any> toggle(
        key: TvSettingKey.Stored<S>,
        title: StringResource,
        description: StringResource? = null,
        visible: (S) -> Boolean = { true },
        confirm: ((Boolean) -> TvSettingsConfirm?)? = null,
        after: ((Boolean) -> TvSettingsEdit)? = null,
        qr: ((TvSettingsValues) -> TvQr?)? = null,
        read: (S) -> Boolean,
        write: S.(Boolean) -> S,
    ) {
        items += TvSettingItem.Toggle(
            id = nextId(),
            keys = listOf(key),
            title = tvText(title),
            description = staticText(description),
            read = { values -> values[key]?.let(read) ?: false },
            write = { checked -> listOfNotNull(TvSettingsEdit.Update(key) { it.write(checked) }, after?.invoke(checked)) },
            confirm = confirm?.let { c -> { _, on -> c(on) } },
            visible = { values -> values[key]?.let(visible) ?: false },
            qr = qr,
        )
    }

    /** 跨几份值的开关 (如读的是实时值、写法是做一件事). [keys] 里的值都读到了才显示. */
    fun toggleOf(
        keys: List<TvSettingKey<*>>,
        title: StringResource,
        description: ((TvSettingsValues) -> TvText?)? = null,
        visible: (TvSettingsValues) -> Boolean = { true },
        confirm: ((TvSettingsValues, Boolean) -> TvSettingsConfirm?)? = null,
        read: (TvSettingsValues) -> Boolean,
        write: (Boolean) -> List<TvSettingsEdit>,
    ) {
        items += TvSettingItem.Toggle(
            id = nextId(),
            keys = keys,
            title = tvText(title),
            description = description ?: { null },
            read = read,
            write = write,
            confirm = confirm,
            visible = { values -> keys.all { values.has(it) } && visible(values) },
        )
    }

    /**
     * 单选 / 数值. [options] 是要列出的值 (按设置算, 如「播放键长按」不给「不做任何事」), [label] 是每个值给人看的字,
     * [read] 取当前值, [write] 把选中的值写回. [confirm] 给了的值写之前先问; [after] 写完再做的事 (如试听提示音).
     */
    fun <S : Any, V> choice(
        key: TvSettingKey.Stored<S>,
        title: StringResource,
        description: StringResource? = null,
        options: (S) -> List<V>,
        label: (V) -> TvText,
        read: (S) -> V?,
        write: S.(V) -> S,
        visible: (S) -> Boolean = { true },
        confirm: ((V) -> TvSettingsConfirm?)? = null,
        swatch: ((V) -> Int?)? = null,
        optionDescription: ((V) -> TvText?)? = null,
        after: ((V) -> TvSettingsEdit)? = null,
    ) {
        items += TvSettingItem.Choice(
            id = nextId(),
            keys = listOf(key),
            title = tvText(title),
            description = staticText(description),
            read = { values -> values[key]?.let(read) },
            options = { values -> values[key]?.let(options).orEmpty() },
            label = label,
            write = { v -> listOfNotNull(TvSettingsEdit.Update(key) { it.write(v) }, after?.invoke(v)) },
            confirm = confirm,
            swatch = swatch,
            optionDescription = optionDescription,
            visible = { values -> values[key]?.let(visible) ?: false },
        )
    }

    /**
     * 跨几份值的单选: 当前值、选项、写法都按全部的值算 (如屏幕刷新率: 选项从系统读, 选中的存在播放器设置里).
     * [keys] 里的值都读到了才显示.
     */
    fun <V> choiceOf(
        keys: List<TvSettingKey<*>>,
        title: StringResource,
        description: StringResource? = null,
        options: (TvSettingsValues) -> List<V>,
        label: (V) -> TvText,
        read: (TvSettingsValues) -> V?,
        write: (V) -> List<TvSettingsEdit>,
        visible: (TvSettingsValues) -> Boolean = { true },
        confirm: ((V) -> TvSettingsConfirm?)? = null,
        optionDescription: ((V) -> TvText?)? = null,
        dynamicDescription: ((TvSettingsValues) -> TvText?)? = null,
        qr: ((TvSettingsValues) -> TvQr?)? = null,
    ) {
        items += TvSettingItem.Choice(
            id = nextId(),
            keys = keys,
            title = tvText(title),
            description = dynamicDescription ?: staticText(description),
            read = read,
            options = options,
            label = label,
            write = write,
            confirm = confirm,
            swatch = null,
            optionDescription = optionDescription,
            visible = { values -> keys.all { values.has(it) } && visible(values) },
            qr = qr,
        )
    }

    /**
     * 动作 / 只显示的一行. [value] 是行尾的字, [details] 给了时说明栏写它 (按此刻的值, 如新版本的更新内容), 否则写 [description].
     * [keys] 里的值都读到了才显示.
     */
    fun action(
        title: StringResource,
        description: StringResource? = null,
        keys: List<TvSettingKey<*>> = emptyList(),
        value: ((TvSettingsValues) -> TvText?)? = null,
        details: ((TvSettingsValues) -> TvText?)? = null,
        confirm: TvSettingsConfirm? = null,
        chevron: Boolean = false,
        visible: (TvSettingsValues) -> Boolean = { true },
        qr: ((TvSettingsValues) -> TvQr?)? = null,
        whileFocused: (suspend (TvSettingsEnv) -> Unit)? = null,
        image: TvImage? = null,
        edits: (TvSettingsValues) -> List<TvSettingsEdit>,
    ) {
        val static = staticText(description)
        items += TvSettingItem.Action(
            id = nextId(),
            keys = keys,
            title = tvText(title),
            description = { values -> details?.invoke(values) ?: static(values) },
            value = value ?: { null },
            confirm = confirm,
            chevron = chevron,
            edits = edits,
            visible = { values -> keys.all { values.has(it) } && visible(values) },
            qr = qr,
            whileFocused = whileFocused,
            image = image,
        )
    }

    /** 做一件事的动作 (见 [action]). */
    fun run(
        title: StringResource,
        description: StringResource? = null,
        keys: List<TvSettingKey<*>> = emptyList(),
        value: ((TvSettingsValues) -> TvText?)? = null,
        confirm: TvSettingsConfirm? = null,
        chevron: Boolean = false,
        visible: (TvSettingsValues) -> Boolean = { true },
        qr: ((TvSettingsValues) -> TvQr?)? = null,
        block: suspend (TvSettingsEnv) -> Unit,
    ) = action(title, description, keys, value, null, confirm, chevron, visible, qr) { listOf(TvSettingsEdit.Run(block)) }

    /** 进一层的一组设置项 (见 [TvSettingItem.Group]), 里面的项照分类里的写法写. */
    fun group(
        title: StringResource,
        description: StringResource? = null,
        value: ((TvSettingsValues) -> TvText?)? = null,
        visible: (TvSettingsValues) -> Boolean = { true },
        whileFocused: (suspend (TvSettingsEnv) -> Unit)? = null,
        onOpen: ((TvSettingsEnv) -> Unit)? = null,
        items: TvSettingsItemsBuilder.() -> Unit,
    ) {
        val id = nextId()
        this.items += TvSettingItem.Group(
            id = id,
            title = tvText(title),
            description = staticText(description),
            value = value ?: { null },
            items = TvSettingsItemsBuilder(id).apply(items).build(),
            onOpen = onOpen,
            visible = visible,
            whileFocused = whileFocused,
        )
    }

    /** 打开原来的设置页 [tab] 那一类. */
    fun legacyLink(tab: SettingsTab, title: StringResource, description: StringResource? = null) =
        run(title, description, chevron = true) { env -> env.show(TvSettingsOverlay.Legacy(tab)) }

    /** 一组随数据变的行 (见 [TvSettingItem.Entries]). [keys] 里的值都读到了才显示. */
    fun <T> entries(
        keys: List<TvSettingKey<*>>,
        entries: (TvSettingsValues) -> List<T>,
        key: (T) -> String,
        title: (T) -> TvText,
        value: (TvSettingsValues, T) -> TvText? = { _, _ -> null },
        description: (TvSettingsValues, T) -> TvText? = { _, _ -> null },
        confirm: ((T) -> TvSettingsConfirm?)? = null,
        move: ((List<String>) -> List<TvSettingsEdit>)? = null,
        visible: (TvSettingsValues) -> Boolean = { true },
        qr: ((TvSettingsValues, T) -> TvQr?)? = null,
        whileFocused: ((T) -> (suspend (TvSettingsEnv) -> Unit)?)? = null,
        image: ((T) -> TvImage?)? = null,
        click: (T) -> List<TvSettingsEdit>,
    ) {
        items += TvSettingItem.Entries(
            id = nextId(),
            keys = keys,
            entries = entries,
            key = key,
            title = title,
            value = value,
            description = description,
            confirm = confirm,
            click = click,
            move = move,
            visible = { values -> keys.all { values.has(it) } && visible(values) },
            entryQr = qr,
            entryWhileFocused = whileFocused,
            entryImage = image,
        )
    }

    /** 排序 (见 [TvSettingItem.Sorter]). */
    fun <S : Any> sorter(
        key: TvSettingKey.Stored<S>,
        title: StringResource,
        description: StringResource? = null,
        read: (S) -> List<Pair<String, Boolean>>,
        label: (String) -> TvText,
        summary: (List<Pair<String, Boolean>>) -> TvText,
        write: S.(List<Pair<String, Boolean>>) -> S,
    ) {
        items += TvSettingItem.Sorter(
            id = nextId(),
            keys = listOf(key),
            title = tvText(title),
            description = staticText(description),
            read = { values -> values[key]?.let(read).orEmpty() },
            label = label,
            summary = summary,
            write = { list -> listOf(TvSettingsEdit.Update(key) { it.write(list) }) },
            visible = { values -> values.has(key) },
        )
    }

    private fun staticText(res: StringResource?): (TvSettingsValues) -> TvText? {
        val text = res?.let { tvText(it) }
        return { text }
    }

    fun build(): List<TvSettingItem> = items.toList()
}

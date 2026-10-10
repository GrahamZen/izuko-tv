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
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_settings_legacy_hint
import me.him188.ani.app.ui.lang.tv_settings_legacy_open
import me.him188.ani.app.ui.lang.tv_settings_move_hint
import me.him188.ani.app.ui.lang.tv_settings_off
import me.him188.ani.app.ui.lang.tv_settings_on
import me.him188.ani.app.ui.lang.tv_settings_sorter_hint

/**
 * 停在哪: 左栏选中的分类 ([categoryId], 即 [TvSettingsCategory.id]), 中栏一层层进了哪些项 ([drill], 从外到里的设置项 id:
 * 前面的是组, 最后一个可以是组、单选或排序). 空 = 分类本身.
 */
data class TvSettingsNav(val categoryId: String? = null, val drill: List<String> = emptyList())

enum class TvSettingsRowKind {
    /** 左栏的分类. */
    Category,

    /** 左栏的分组小标题 / 中栏的组标题 (不可聚焦). */
    Header,

    /** 中栏的设置项. */
    Item,

    /** 进一层后的选项. */
    Option,
}

/**
 * 一行. [value] 是行尾的字 (当前值 / 开关), [chevron] = 行尾画 › (确定进一层或去别处), [checked] = 选项是当前值 (行尾打勾),
 * [swatch] = 字前面画的色块 (ARGB, 调色板). [moveGroup] 非空 = 可以长按确定挪顺序, 只在同一组的相邻行之间挪.
 */
@Immutable
data class TvSettingsRow(
    val id: String,
    val kind: TvSettingsRowKind,
    val title: String,
    val value: String = "",
    val chevron: Boolean = false,
    val checked: Boolean = false,
    val swatch: Int? = null,
    val moveGroup: String? = null,
) {
    val focusable: Boolean get() = kind != TvSettingsRowKind.Header
}

/** 中栏的一屏. [key] 变了就是换了一屏 (视图据此复位滚动与焦点); [title] 是进一层后顶上的小标题; [focusId] = 进来时落在哪一行. */
@Immutable
data class TvSettingsScreen(
    val key: String,
    val title: String?,
    val rows: List<TvSettingsRow>,
    val focusId: String?,
)

/** 说明栏: 焦点所在那一行的标题与说明, 有的还画一个二维码 ([qr]), 标题上面画一张图 ([image]). */
@Immutable
data class TvSettingsInfo(val title: String, val body: String, val qr: TvSettingsQr? = null, val image: TvImage? = null)

/** 说明栏的二维码 (字已换好, 见 [TvQr]): [content] 为 null 时不画码, 只写 [status]. */
@Immutable
data class TvSettingsQr(val content: String?, val caption: String, val status: String)

@Immutable
data class TvSettingsContent(
    val rail: List<TvSettingsRow>,
    /** 左栏选中的分类那一行的 id. */
    val railKey: String?,
    val right: TvSettingsScreen,
    /** 按行 id 查说明 (中栏各行、左栏还没换成新样式的分类). */
    val info: Map<String, TvSettingsInfo>,
    /** 挪顺序时说明栏写的. */
    val moveHint: String = "",
)

object TvSettingsRowIds {
    fun category(id: String) = "cat:$id"
    fun legacy(id: String) = "legacy:$id"
    fun option(itemId: String, index: Int) = "opt:$itemId:$index"

    /** [TvSettingItem.Entries] 里的一行 ([key] 见 [TvSettingsEntry.key]). */
    fun entry(itemId: String, key: String) = "ent:$itemId:$key"

    /** [TvSettingItem.Sorter] 进一层后的一个选项. */
    fun sorted(itemId: String, key: String) = "srt:$itemId:$key"

    /** 选项行 id 拆回 (设置项 id, 下标); 不是选项行为 null. */
    fun parseOption(rowId: String): Pair<String, Int>? {
        val (itemId, rest) = split(rowId, "opt:") ?: return null
        return itemId to (rest.toIntOrNull() ?: return null)
    }

    /** 拆回 (设置项 id, 行的 key). */
    fun parseEntry(rowId: String): Pair<String, String>? = split(rowId, "ent:")

    fun parseSorted(rowId: String): Pair<String, String>? = split(rowId, "srt:")

    fun parseCategory(rowId: String): String? = rowId.takeIf { it.startsWith("cat:") }?.removePrefix("cat:")
    fun parseLegacy(rowId: String): String? = rowId.takeIf { it.startsWith("legacy:") }?.removePrefix("legacy:")

    /** 设置项 id 里没有冒号 (`分类.序号`), 第一个冒号后面整段都是 key (key 里可以有冒号). */
    private fun split(rowId: String, prefix: String): Pair<String, String>? {
        if (!rowId.startsWith(prefix)) return null
        val rest = rowId.removePrefix(prefix)
        val sep = rest.indexOf(':')
        if (sep < 0) return null
        return rest.substring(0, sep) to rest.substring(sep + 1)
    }
}

/** 左栏选中的分类: [TvSettingsNav.categoryId] 指的那一类还在 (看得见) 就是它, 否则第一个有设置项的分类. */
fun selectedTvSettingsCategory(catalog: List<TvSettingsCategory>, values: TvSettingsValues, nav: TvSettingsNav): TvSettingsCategory? {
    val visible = catalog.filter { it.visible(values) }
    return visible.firstOrNull { it.id == nav.categoryId } ?: visible.firstOrNull { it.items != null } ?: visible.firstOrNull()
}

/** 清单里 id 为 [id] 的设置项 (组里的也找). */
fun findTvSettingItem(catalog: List<TvSettingsCategory>, id: String): TvSettingItem? =
    catalog.asSequence().flatMap { it.items.orEmpty() }.flatMap { it.flatten() }.firstOrNull { it.id == id }

/** 设置项要的那几份值都读到了、且这时该显示. */
fun TvSettingItem.shown(values: TvSettingsValues): Boolean = keys.all { values.has(it) } && visible(values)

/**
 * 按清单与此刻的值建出整页: 左栏 (分组小标题 + 分类), 中栏 (分类下的设置项, 或进了某一项后的选项), 说明栏的内容.
 * 纯函数: 字由 [resolver] 给, 缺的模板它会记下来 (见 [TvTextResolver]).
 */
fun buildTvSettingsPage(
    catalog: List<TvSettingsCategory>,
    values: TvSettingsValues,
    nav: TvSettingsNav,
    resolver: TvTextResolver,
): TvSettingsContent {
    val selected = selectedTvSettingsCategory(catalog, values, nav)
    val info = HashMap<String, TvSettingsInfo>()

    val rail = buildList {
        catalog.filter { it.visible(values) }.forEachIndexed { index, category ->
            category.section?.let { add(TvSettingsRow("sec:$index", TvSettingsRowKind.Header, resolver.resolve(it))) }
            add(TvSettingsRow(TvSettingsRowIds.category(category.id), TvSettingsRowKind.Category, resolver.resolve(category.title)))
            if (category.items == null) {
                info[TvSettingsRowIds.category(category.id)] =
                    TvSettingsInfo(resolver.resolve(category.title), resolver.resolve(tvText(Lang.tv_settings_legacy_hint)))
            }
        }
    }

    val right = when {
        selected == null -> TvSettingsScreen("empty", null, emptyList(), null)
        selected.items == null -> {
            val rowId = TvSettingsRowIds.legacy(selected.id)
            info[rowId] = TvSettingsInfo(resolver.resolve(selected.title), resolver.resolve(tvText(Lang.tv_settings_legacy_hint)))
            TvSettingsScreen(
                key = TvSettingsRowIds.category(selected.id),
                title = null,
                rows = listOf(TvSettingsRow(rowId, TvSettingsRowKind.Item, resolver.resolve(tvText(Lang.tv_settings_legacy_open)), chevron = true)),
                focusId = rowId,
            )
        }

        else -> {
            // 顺着 drill 一层层往里走: 走不通 (那一项没了 / 这时不显示) 就停在走得到的那一层
            var key = TvSettingsRowIds.category(selected.id)
            var items: List<TvSettingItem> = selected.items
            var group: TvSettingItem.Group? = null
            var leaf: TvSettingItem? = null
            for (id in nav.drill) {
                val item = items.firstOrNull { it.id == id }?.takeIf { it.shown(values) } ?: break
                if (item is TvSettingItem.Group) {
                    key += "/" + item.id
                    items = item.items
                    group = item
                } else {
                    if (item is TvSettingItem.Choice<*> || item is TvSettingItem.Sorter) leaf = item
                    break
                }
            }
            when (leaf) {
                is TvSettingItem.Choice<*> -> optionsScreen(key, leaf, values, resolver, info)
                is TvSettingItem.Sorter -> sorterScreen(key, leaf, values, resolver, info)
                else -> itemsScreen(key, group?.title?.let(resolver::resolve), items, values, resolver, info)
            }
        }
    }

    return TvSettingsContent(
        rail = rail,
        railKey = selected?.let { TvSettingsRowIds.category(it.id) },
        right = right,
        info = info,
        moveHint = resolver.resolve(tvText(Lang.tv_settings_move_hint)),
    )
}

/** 一列设置项 (分类本身, 或进了一组: [title] 是组名). */
private fun itemsScreen(
    key: String,
    title: String?,
    items: List<TvSettingItem>,
    values: TvSettingsValues,
    resolver: TvTextResolver,
    info: MutableMap<String, TvSettingsInfo>,
): TvSettingsScreen {
    // 先按项出行, 再放组标题: 组标题下面一行都没有 (项都不显示、列表是空的) 就不出
    val groups = items.filter { it.shown(values) }.map { item -> item to itemRows(item, values, resolver, info) }
    val rows = ArrayList<TvSettingsRow>()
    groups.forEachIndexed { index, (item, itemRows) ->
        if (item is TvSettingItem.Header) {
            val hasContent = groups.drop(index + 1).takeWhile { it.first !is TvSettingItem.Header }.any { it.second.isNotEmpty() }
            if (hasContent) rows += TvSettingsRow(item.id, TvSettingsRowKind.Header, resolver.resolve(item.title))
        } else {
            rows += itemRows
        }
    }
    return TvSettingsScreen(
        key = key,
        title = title,
        rows = rows,
        focusId = rows.firstOrNull { it.focusable }?.id,
    )
}

/** 一项在中栏出的行 (组标题另放, 这里不出). */
private fun itemRows(
    item: TvSettingItem,
    values: TvSettingsValues,
    resolver: TvTextResolver,
    info: MutableMap<String, TvSettingsInfo>,
): List<TvSettingsRow> = when (item) {
    is TvSettingItem.Header -> emptyList()

    is TvSettingItem.Toggle -> {
        val on = item.read(values)
        info[item.id] = itemInfo(item.title, item.description(values), resolver, item.qr?.invoke(values))
        listOf(
            TvSettingsRow(
                item.id, TvSettingsRowKind.Item, resolver.resolve(item.title),
                value = resolver.resolve(tvOnOff(on)),
            ),
        )
    }

    is TvSettingItem.Choice<*> -> {
        info[item.id] = itemInfo(item.title, item.description(values), resolver, item.qr?.invoke(values))
        listOf(
            TvSettingsRow(
                item.id, TvSettingsRowKind.Item, resolver.resolve(item.title),
                value = item.currentLabel(values)?.let { resolver.resolve(it) }.orEmpty(),
                chevron = true,
            ),
        )
    }

    is TvSettingItem.Action -> {
        info[item.id] = itemInfo(item.title, item.description(values), resolver, item.qr?.invoke(values), item.image)
        listOf(
            TvSettingsRow(
                item.id, TvSettingsRowKind.Item, resolver.resolve(item.title),
                value = item.value(values)?.let { resolver.resolve(it) }.orEmpty(),
                chevron = item.chevron,
            ),
        )
    }

    is TvSettingItem.Entries<*> -> {
        val movable = item.move != null
        item.rows(values).map { entry ->
            val id = TvSettingsRowIds.entry(item.id, entry.key)
            info[id] = itemInfo(entry.title, entry.description, resolver, entry.qr, entry.image)
            TvSettingsRow(
                id, TvSettingsRowKind.Item, resolver.resolve(entry.title),
                value = entry.value?.let { resolver.resolve(it) }.orEmpty(),
                moveGroup = if (movable) item.id else null,
            )
        }
    }

    is TvSettingItem.Sorter -> {
        info[item.id] = itemInfo(item.title, item.description(values), resolver, item.qr?.invoke(values))
        listOf(
            TvSettingsRow(
                item.id, TvSettingsRowKind.Item, resolver.resolve(item.title),
                value = resolver.resolve(item.summary(values)),
                chevron = true,
            ),
        )
    }

    is TvSettingItem.Group -> {
        info[item.id] = itemInfo(item.title, item.description(values), resolver)
        listOf(
            TvSettingsRow(
                item.id, TvSettingsRowKind.Item, resolver.resolve(item.title),
                value = item.value(values)?.let { resolver.resolve(it) }.orEmpty(),
                chevron = true,
            ),
        )
    }
}

/** 进了单选: [parentKey] 是它所在那一屏的 key. */
private fun optionsScreen(
    parentKey: String,
    item: TvSettingItem.Choice<*>,
    values: TvSettingsValues,
    resolver: TvTextResolver,
    info: MutableMap<String, TvSettingsInfo>,
): TvSettingsScreen {
    val current = item.currentIndex(values)
    val swatches = item.optionSwatches(values)
    val descriptions = item.optionDescriptions(values)
    val itemDescription = item.description(values)
    val rows = item.optionLabels(values).mapIndexed { index, label ->
        val id = TvSettingsRowIds.option(item.id, index)
        info[id] = itemInfo(label.takeIf { descriptions[index] != null } ?: item.title, descriptions[index] ?: itemDescription, resolver)
        TvSettingsRow(
            id, TvSettingsRowKind.Option, resolver.resolve(label),
            checked = index == current,
            swatch = swatches.getOrNull(index),
        )
    }
    return TvSettingsScreen(
        key = parentKey + "/" + item.id,
        title = resolver.resolve(item.title),
        rows = rows,
        focusId = rows.getOrNull(current)?.id ?: rows.firstOrNull()?.id,
    )
}

/** 排序进一层: 全部选项按当前顺序, 选中的打勾; 都可以长按挪. */
private fun sorterScreen(
    parentKey: String,
    item: TvSettingItem.Sorter,
    values: TvSettingsValues,
    resolver: TvTextResolver,
    info: MutableMap<String, TvSettingsInfo>,
): TvSettingsScreen {
    val hint = tvText(Lang.tv_settings_sorter_hint)
    val rows = item.options(values).map { (key, selected) ->
        val id = TvSettingsRowIds.sorted(item.id, key)
        info[id] = itemInfo(item.title, hint, resolver)
        TvSettingsRow(
            id, TvSettingsRowKind.Option, resolver.resolve(item.labelOf(key)),
            checked = selected,
            moveGroup = item.id,
        )
    }
    return TvSettingsScreen(
        key = parentKey + "/" + item.id,
        title = resolver.resolve(item.title),
        rows = rows,
        focusId = rows.firstOrNull()?.id,
    )
}

internal fun tvOnOff(on: Boolean): TvText = tvText(if (on) Lang.tv_settings_on else Lang.tv_settings_off)

private fun itemInfo(title: TvText, description: TvText?, resolver: TvTextResolver, qr: TvQr? = null, image: TvImage? = null) =
    TvSettingsInfo(
        resolver.resolve(title),
        description?.let { resolver.resolve(it) }.orEmpty(),
        qr?.let { TvSettingsQr(it.content, it.caption?.let(resolver::resolve).orEmpty(), it.status?.let(resolver::resolve).orEmpty()) },
        image,
    )

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.remote_web_lang
import org.jetbrains.compose.resources.getString

/**
 * Web 控制台跟着 app 内的语言走 (简体 / 香港繁体 / 台湾繁体 / 英文).
 *
 * 文案照旧直接写简体中文, 简体原文就是查表的键 (gettext 式): 网页脚本里写 `T('全选')` / `T('已选 {0} 项', n)`,
 * 服务端写 `tr("…")`; 译文在 [REMOTE_I18N_TABLE]. 表里没有的原样显示简体 —— 新加的文案不会坏, 只是暂时没翻译.
 * 网页的静态 HTML (标签栏、面板标题等) 由 LANG_SCRIPT 在页面加载时按同一张表逐个文本节点替换.
 *
 * 当前语言用 `Lang.remote_web_lang` 这条资源判断 (各语言 strings.xml 里写的是语言标记): 与 app 自己的文案同一套资源解析,
 * 不会出现 app 是英文、网页却按系统语言显示中文的情况. 每个请求进来时刷新一次; 网页的提示轮询带上它, 变了就整页重载.
 */
internal enum class RemoteLang(val tag: String) {
    ZH_CN("zh-CN"),
    ZH_HK("zh-HK"),
    ZH_TW("zh-TW"),
    EN("en"),
}

internal object RemoteI18n {
    @Volatile
    var lang: RemoteLang = RemoteLang.ZH_CN
        private set

    fun refresh() {
        val tag = runCatching { runBlocking { getString(Lang.remote_web_lang) } }.getOrNull()
        lang = RemoteLang.entries.firstOrNull { it.tag == tag } ?: RemoteLang.ZH_CN
    }

    private val tables: Map<RemoteLang, Map<String, String>> by lazy {
        val rows = REMOTE_I18N_TABLE
        mapOf(
            RemoteLang.EN to rows.associate { it.zh to it.en },
            RemoteLang.ZH_HK to rows.associate { it.zh to it.hk },
            RemoteLang.ZH_TW to rows.associate { it.zh to it.tw },
        )
    }

    fun table(l: RemoteLang = lang): Map<String, String> = tables[l].orEmpty()

    /** 放进网页 <head> 的译文 (当前语言整张表, 简体为空表), 由 LANG_SCRIPT 的 T() 使用 */
    fun pageScript(): String {
        val l = lang
        val json = JsonObject(table(l).mapValues { JsonPrimitive(it.value) }).toString()
        return "var I18N = " + json.replace("</", "<\\/") + ";\nvar LANG = '" + l.tag + "';"
    }
}

/** 服务端回给网页的文案: 简体原文查当前语言的译文, `{0}` `{1}` … 依次换成参数. */
internal fun tr(zh: String, vararg args: Any?): String {
    var s = RemoteI18n.table()[zh] ?: zh
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}

internal class RemoteText(val zh: String, val en: String, val hk: String, val tw: String)

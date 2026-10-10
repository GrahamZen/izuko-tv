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
import org.jetbrains.compose.resources.StringResource

/**
 * 设置清单里的一段文字: 文案资源 (可带参数) 或现成的字. 清单与页面模型只认它, 换成字的那一步在 [TvTextResolver] —— 模型是纯函数,
 * 不碰挂起的资源读取.
 */
@Immutable
sealed interface TvText {
    /** 文案资源; [args] 按位置填进 `%1$s` / `%1$d`, 可以是字、数字或另一段 [TvText]. */
    data class Res(val res: StringResource, val args: List<Any> = emptyList()) : TvText

    data class Plain(val text: String) : TvText
}

fun tvText(res: StringResource, vararg args: Any): TvText = TvText.Res(res, args.toList())

fun tvText(text: String): TvText = TvText.Plain(text)

/**
 * 把 [TvText] 换成字. 文案模板由 [template] 给 (页面状态里是读过的资源缓存); 没有的记进 [missing] 并先给空字 ——
 * 调用方读完缺的那些再建一次 (见 TvSettingsState).
 */
class TvTextResolver(private val template: (StringResource) -> String?) {
    val missing: MutableSet<StringResource> = LinkedHashSet()

    fun resolve(text: TvText): String = when (text) {
        is TvText.Plain -> text.text
        is TvText.Res -> {
            val t = template(text.res)
            if (t == null) {
                missing += text.res
                ""
            } else {
                formatTvTemplate(unescapeQuotes(t), text.args.map { arg -> if (arg is TvText) resolve(arg) else arg.toString() })
            }
        }
    }
}

/** 按位置 (`%1$s` / `%2$d`) 或顺序 (`%s` / `%d`) 填参数, `%%` 是百分号; 同 Compose 资源的格式. */
internal fun formatTvTemplate(template: String, args: List<String>): String {
    if (args.isEmpty() && '%' !in template) return template
    var next = 0
    return PLACEHOLDER.replace(template) { match ->
        val index = match.groupValues[1]
        when {
            match.value == "%%" -> "%"
            index.isNotEmpty() -> args.getOrNull(index.toInt() - 1) ?: match.value
            else -> args.getOrNull(next++) ?: match.value
        }
    }
}

private val PLACEHOLDER = Regex("""%%|%(?:(\d+)\$)?[sd]""")

/**
 * 文案里安卓资源写法的引号转义 (`\'` / `\"`, 上游的英文文案有) 换回引号: Compose 资源只处理 `\n` / `\t` / `\u`, 这两种原样留着.
 */
internal fun unescapeQuotes(template: String): String =
    if ('\\' !in template) template else template.replace("\\'", "'").replace("\\\"", "\"")

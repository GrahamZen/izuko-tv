/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.xml

import kotlinx.io.Source
import kotlinx.io.asInputStream
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

actual object Xml {
    actual fun parse(string: String, baseUrl: String): org.jsoup.nodes.Document =
        Jsoup.parse(string, baseUrl, Parser.xmlParser())

    actual fun parse(source: Source, baseUrl: String): Document =
        Jsoup.parse(source.asInputStream(), "UTF-8", baseUrl, Parser.xmlParser())

    actual fun parse(string: String): Document {
        return Jsoup.parse(string, Parser.xmlParser())
    }

    actual fun parse(source: Source): Document {
        return Jsoup.parse(source.asInputStream(), "UTF-8", "", Parser.xmlParser())
    }
}

actual object QueryParser {
    /**
     * 直接静态调用 [org.jsoup.select.QueryParser.parse] 是否已经被 ART 拒绝. 见 [parseSelector].
     */
    @Volatile
    private var directCallDenied = false

    private val reflectiveParse: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            Class.forName("org.jsoup.select.QueryParser")
                .getDeclaredMethod("parse", String::class.java)
                .apply { isAccessible = true }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 有的电视 ROM 往 BOOTCLASSPATH 里塞了另一份 jsoup. 类加载双亲优先, APK 里的 `org.jsoup` 会被整个遮蔽,
     * 而那一份的 `QueryParser` 不是 public, 于是这行直接调用在设备上抛 `IllegalAccessError`:
     * ```
     * Illegal class access ('me.him188.ani.utils.xml.QueryParser' attempting to access
     * 'org.jsoup.select.QueryParser') in attempt to invoke static method ...parse(java.lang.String)
     * ```
     * 结果是所有 selector (在线) 数据源一律 "upstream error" 全挂, 只剩 BT 源 (issue #12).
     * 类不可见但方法还在 (ART 是先解析到方法再做类访问检查的), 所以退回反射调用.
     */
    @Throws(IllegalStateException::class)
    actual fun parseSelector(selector: String): Evaluator {
        if (!directCallDenied) {
            try {
                return org.jsoup.select.QueryParser.parse(selector)
            } catch (e: LinkageError) { // IllegalAccessError 及一切链接期问题
                directCallDenied = true
            }
        }
        return parseSelectorReflectively(selector)
    }

    internal fun parseSelectorReflectively(selector: String): Evaluator {
        val method = reflectiveParse
            ?: throw IllegalStateException("jsoup QueryParser.parse(String) is unavailable on this device")
        val result = try {
            method.invoke(null, selector)
        } catch (e: InvocationTargetException) {
            // 选择器写错时 jsoup 抛的是 SelectorParseException (IllegalStateException 的子类),
            // 必须还原出来, 否则 parseSelectorOrNull 接不住.
            throw e.targetException
        } catch (e: Exception) {
            throw IllegalStateException("Failed to invoke jsoup QueryParser.parse(String)", e)
        }
        return result as? Evaluator
            ?: throw IllegalStateException("jsoup QueryParser.parse(String) returned ${result?.javaClass?.name}")
    }
}

actual object Html {
    actual fun parse(string: String): Document {
        return Jsoup.parse(string)
    }

    actual fun parse(string: String, baseUrl: String): Document {
        return Jsoup.parse(string, baseUrl)
    }

    actual fun parse(source: Source): Document {
        return Jsoup.parse(source.asInputStream(), "UTF-8", "")
    }

    actual fun parse(source: Source, baseUrl: String): Document {
        return Jsoup.parse(source.asInputStream(), "UTF-8", baseUrl)
    }
}
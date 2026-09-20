/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.ContentConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.decode
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.streams.asInput
import me.him188.ani.shaded.jsoup.Jsoup
import me.him188.ani.shaded.jsoup.nodes.Document

actual fun getPlatformKtorEngine(): HttpClientEngineFactory<*> = OkHttp

suspend inline fun HttpResponse.bodyAsDocument(): Document = body()

internal actual fun getXmlConverter(): ContentConverter = XmlConverter

private object XmlConverter : ContentConverter {
    override suspend fun serialize(
        contentType: ContentType,
        charset: io.ktor.utils.io.charsets.Charset,
        typeInfo: TypeInfo,
        value: Any?
    ): OutgoingContent? = null

    override suspend fun deserialize(
        charset: io.ktor.utils.io.charsets.Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel
    ): Any? {
        if (typeInfo.type.qualifiedName != Document::class.qualifiedName) return null
        content.awaitContent()
        val decoder = Charsets.UTF_8.newDecoder()
        val string = decoder.decode(content.toInputStream().asInput())
        return Jsoup.parse(string, charset.name())
    }
}

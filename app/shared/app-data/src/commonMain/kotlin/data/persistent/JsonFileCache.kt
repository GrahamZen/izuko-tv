/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 存在一个 JSON 小文件里的缓存. 丢了只是多发几个请求, 所以读写出错一律当没有 (记一行日志), 调用方照常回源.
 *
 * - 写先写临时文件再原子挪过去, 写到一半进程被杀也不会留下半截;
 * - 读不出来 (文件坏了、旧版本写的字段对不上) 就删掉它, 当没有.
 *
 * 每份数据单独一个文件: 写一次只重写自己那份. 几样东西挤在同一个大 DataStore 里的话, 改哪一样都要整份重写
 * (时间表缓存近 300 KB, 每写一次零点几秒).
 */
internal class JsonFileCache<T>(
    private val file: SystemPath,
    private val serializer: KSerializer<T>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    suspend fun read(): T? = withContext(ioDispatcher) {
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString(serializer, file.readBytes().decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${file.name}, discarding it" }
            runCatching { file.delete() }
            null
        }
    }

    suspend fun write(value: T): Unit = withContext(ioDispatcher) {
        try {
            val temp = file.resolveSibling(file.name + ".tmp")
            temp.writeBytes(json.encodeToString(serializer, value).encodeToByteArray())
            temp.moveTo(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to write ${file.name}" }
        }
    }

    private companion object {
        private val logger = logger("JsonFileCache")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

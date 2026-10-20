/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.him188.ani.app.ui.lang.Res
import me.him188.ani.app.ui.lang.allStringResources
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.rememberResourceEnvironment
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 启动后空闲时把全部文案读进 Compose 资源的缓存.
 *
 * `stringResource` 第一次读某条文案时, 主线程 `runBlocking` 等资源库在 `Dispatchers.Default` 上的读取任务; 后台搜索、选源把 Default
 * 占满的时候要排几百毫秒到两秒 —— 唤出控制层、开面板、进播放页的那一下就卡在这里. 读过的从缓存里拿, 不再排队.
 * 用组合里的资源环境读 (应用内语言与系统不同时, 缓存的是界面要用的那一份). 同时只读 [PARALLELISM] 条, 不把 Default 占满.
 *
 * 全部文案里有只在部分语言里有的 (默认语言那份已删、繁简中文里还留着): 这个环境下读不到, 跳过 —— 界面不会用它, 读它会抛异常.
 */
@Composable
fun PreloadStringResourcesWhenIdle() {
    val environment = rememberResourceEnvironment()
    LaunchedEffect(environment) {
        delay(START_DELAY)
        val mark = TimeSource.Monotonic.markNow()
        val all = Res.allStringResources.values.toList()
        val missing = withContext(Dispatchers.IO) {
            val permits = Semaphore(PARALLELISM)
            all.map { resource ->
                async {
                    permits.withPermit {
                        try {
                            getString(environment, resource)
                            false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: IllegalStateException) {
                            true
                        }
                    }
                }
            }.awaitAll().count { it }
        }
        logger.info { "Preloaded ${all.size} strings ($missing missing here) in ${mark.elapsedNow().inWholeMilliseconds} ms" }
    }
}

private val logger = logger("TvStringResourcePreload")

/** 等启动这一阵过去 (首屏封面、推荐) 再开始. */
private val START_DELAY = 4.seconds

private const val PARALLELISM = 2

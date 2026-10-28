/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * 挂起读文案 ([getString]), 但不在主线程上等资源库的锁.
 *
 * 资源库每读一条文案 (含命中缓存) 都要拿同一把公平的协程锁. 界面上的 `stringResource` 第一次读某条文案时, 主线程在 `runBlocking`
 * 里同步等这把锁; 锁要是先排给了一个跑在主线程上的协程, 那个协程要等主线程空出来才能拿锁、放锁, 主线程又在等它, 两边永远互等,
 * 界面卡死 (冷启动后马上进播放页, 文案预读还没读完时撞上过). 所以挂起读文案在 [Dispatchers.Default] 上等锁, 读完回到调用方.
 *
 * 不给 [formatArgs] 时同资源库不带参数的 [getString]: 原样返回, 不替换 `%1$s` 这类占位 (带参数的那个会拿空参数去替换, 模板文案直接抛异常).
 */
suspend fun getStringOffMain(resource: StringResource, vararg formatArgs: Any): String =
    withContext(Dispatchers.Default) {
        if (formatArgs.isEmpty()) getString(resource) else getString(resource, *formatArgs)
    }

/** 同 [getStringOffMain], 按 [environment] (界面的语言等) 读. */
suspend fun getStringOffMain(environment: ResourceEnvironment, resource: StringResource, vararg formatArgs: Any): String =
    withContext(Dispatchers.Default) {
        if (formatArgs.isEmpty()) getString(environment, resource) else getString(environment, resource, *formatArgs)
    }

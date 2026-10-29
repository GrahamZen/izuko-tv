/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放器正在打开一个媒体: 从交给播放器到读出音视频轨 (`setMediaData` 返回). 新的打开顶掉旧的时, 旧的结束不清掉新的.
 * 加载线程上读.
 */
internal class OpeningPhase {
    private val nextId = AtomicLong()
    private val current = AtomicLong()

    val isOpening: Boolean get() = current.get() != 0L

    inline fun <T> during(block: () -> T): T {
        val id = begin()
        try {
            return block()
        } finally {
            end(id)
        }
    }

    @PublishedApi
    internal fun begin(): Long = nextId.incrementAndGet().also { current.set(it) }

    @PublishedApi
    internal fun end(id: Long) {
        current.compareAndSet(id, 0L)
    }
}

/**
 * 按打开阶段换重试次数的加载出错策略: 打开期间 ([isOpening]) 出错只重试一次 (第一次重试照 Media3 默认马上发),
 * 第二次失败就报打开失败, 坏链接约一秒换下一个源; 重试也不会好的错 ([isDefinitive]: 401 / 403 / 404 / 410、域名解析不出)
 * 打开期间不重试, 当场报失败. 打开之后用 Media3 默认的 (重试 3 次、逐次多等), 播放中网络抖一下不至于报错.
 *
 * 重试间隔一律用 Media3 默认的, 不能改成 0: 加载器 (Loader) 只在等下一次重试的间隙里留着错误, 播放器在这时查到重试次数超了才报错;
 * 间隔为 0 时当场清掉错误再发, 错误永远报不出来, 坏链接被一直重试下去.
 *
 * 播放器拦截后重建的媒体 (LibassMediaSourcePipeline) 用它; 每次加载出错时现问, 打开结束后不用重建媒体.
 */
@OptIn(UnstableApi::class)
internal class OpeningLoadErrorPolicy(
    private val isOpening: () -> Boolean,
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
) : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = delegate.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)

    /** [C.TIME_UNSET] 让加载器把这次错误当作致命的, 不再重试 (与间隔 0 不同, 见上) */
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isOpening() && isDefinitive(loadErrorInfo.exception)) C.TIME_UNSET
        else delegate.getRetryDelayMsFor(loadErrorInfo)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (isOpening()) OPENING_RETRY_COUNT else delegate.getMinimumLoadableRetryCount(dataType)

    companion object {
        const val OPENING_RETRY_COUNT = 1

        /** 链接没了、没权限、地区屏蔽: 同一个地址再请求一次结果一样 */
        private val DEFINITIVE_RESPONSE_CODES = setOf(401, 403, 404, 410)

        /** 重试也不会好的加载错误: 服务器回 [DEFINITIVE_RESPONSE_CODES], 或域名解析不出 */
        fun isDefinitive(error: IOException): Boolean =
            error is HttpDataSource.InvalidResponseCodeException && error.responseCode in DEFINITIVE_RESPONSE_CODES ||
                    generateSequence<Throwable>(error) { it.cause }.any { it is UnknownHostException }
    }
}

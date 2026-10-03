/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.media.AssHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.BufferedInputStream
import java.lang.reflect.Field
import kotlin.time.TimeSource

/**
 * 一个媒体被 [FontDeferringMkvExtractor] 跳过的字体附件: 出了第一帧之后再从 [uri] 单独读那一段, 交给 libass.
 *
 * 读完之前字幕用系统字体画. libass 会收下后加的字体, 但已经用替代字体画过的字体族会一直沿用替代字体
 * (渲染器按字体族缓存选好的字体, 只在建渲染器时清空), 所以读完后换一个新渲染器 ([reloadRender]).
 *
 * @param dataSourceFactory 与播放用的相同 (同样的请求头与并发连接)
 */
@AndroidxOptIn(UnstableApi::class)
internal class DeferredMkvFonts(
    private val uri: Uri,
    private val dataSourceFactory: DataSource.Factory,
    private val assHandler: AssHandler,
    scope: CoroutineScope,
) : MkvFontSink {
    private val attachments = MutableStateFlow<Pair<Long, Long>?>(null)
    private val firstFrameRendered = MutableStateFlow(false)

    private val job = scope.launch {
        val (position, length) = combine(attachments.filterNotNull(), firstFrameRendered) { region, rendered ->
            region.takeIf { rendered }
        }.filterNotNull().first()
        load(position, length)
    }

    override fun addFont(name: String, data: ByteArray) = assHandler.addFont(name, data)

    override fun deferAttachments(position: Long, length: Long) {
        // 提取器重建 (如出错重试) 会再报一次同一段
        if (attachments.compareAndSet(null, position to length)) {
            logger.info { "Deferred ${length / 1024} KiB of mkv attachments at $position" }
        }
    }

    fun onFirstFrameRendered() {
        firstFrameRendered.value = true
    }

    fun cancel() = job.cancel()

    private suspend fun load(position: Long, length: Long) {
        val started = TimeSource.Monotonic.markNow()
        var fonts = 0
        try {
            runInterruptible(Dispatchers.IO) {
                val dataSpec = DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()
                DataSourceInputStream(dataSourceFactory.createDataSource(), dataSpec).use { stream ->
                    MkvAttachments.readFonts(BufferedInputStream(stream, 64 * 1024), length) { name, data ->
                        assHandler.addFont(name, data)
                        fonts++
                    }
                }
            }
            logger.info { "Loaded $fonts deferred fonts (${length / 1024} KiB) in ${started.elapsedNow()}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Deferred fonts failed after $fonts fonts in ${started.elapsedNow()}: $e" }
        }
        currentCoroutineContext().ensureActive()
        if (fonts > 0) withContext(Dispatchers.Main) { reloadRender() }
    }

    /** 换一个新渲染器, 设置照 AssHandler 建渲染器时的做法; 帧尺寸由字幕视图发现渲染器换了时补上. */
    private fun reloadRender() {
        val renderField = RENDER_FIELD ?: return
        val old = assHandler.render ?: return // 还没建: 建的时候会带上全部字体
        val ass = assHandler.ass
        if (ass.released) return
        val fresh = ass.createRender()
        assHandler.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { fresh.setStorageSize(it.width, it.height) }
        fresh.setCacheLimit(assHandler.config.glyphSize, assHandler.config.cacheSize)
        assHandler.track?.let(fresh::setTrack)
        renderField.set(assHandler, fresh)
        assHandler.renderCallback?.invoke(fresh)
        old.release()
        logger.info { "Reloaded libass renderer with deferred fonts" }
    }

    private companion object {
        private val logger = logger<DeferredMkvFonts>()

        /**
         * AssHandler 没有换渲染器的接口, 只能写它的字段 (混淆规则保留了字段名, 见 proguard-rules.pro).
         * 库改了取不到时不换: 已用替代字体画过的字体族继续用替代字体.
         */
        private val RENDER_FIELD: Field? = runCatching {
            AssHandler::class.java.getDeclaredField("render")
                .takeIf { it.type == AssRender::class.java }
                ?.apply { isAccessible = true }
        }.getOrNull().also { if (it == null) logger.warn { "AssHandler.render not found; deferred fonts won't reload the renderer" } }
    }
}

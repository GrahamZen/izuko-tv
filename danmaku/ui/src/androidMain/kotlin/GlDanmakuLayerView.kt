/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * [DanmakuLayer] 在 Android 上的那一层: 系统合成里在视频之上、窗口之下 ([setZOrderMediaOverlay]).
 *
 * 每条弹幕的图第一次出现时传进一张图集纹理 ([DanmakuAtlasAllocator]), 之后每帧把在屏的弹幕拼成一组顶点一次画完.
 * 有滚动弹幕时由自己线程上的 Choreographer 每个 vsync 请求一帧, 不用连续模式: 连续模式下 GL 线程大半时间卡在等下一块缓冲上,
 * 同进程里界面的渲染线程会被它拖住 (Shield 上界面掉到每秒十几帧).
 */
internal class GlDanmakuLayerView(context: Context) : GLSurfaceView(context) {
    private val pacingStats = PacingStats()
    private val pacerTiming = PacerTiming()
    private val renderer = Renderer(pacingStats, pacerTiming)

    private val pacerThread = HandlerThread("DanmakuPacer", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val pacer = Handler(pacerThread.looper)
    private var pacerChoreographer: Choreographer? = null

    @Volatile
    private var pacing = false
    private val pace = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pacing) {
                pacerTiming.onPacingStopped()
                return
            }
            pacerTiming.onPace(System.nanoTime())
            requestRender()
            pacerChoreographer?.postFrameCallback(this)
        }
    }

    /** 最近发布的一份, 截图时用. */
    val latestFrame: DanmakuLayerFrame? get() = renderer.frame

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        pacer.post { pacerChoreographer = Choreographer.getInstance() }
    }

    /** 界面线程上调用: 交一份新的弹幕. 有滚动弹幕时开始按 vsync 出帧, 否则画这一份就停. */
    fun publish(frame: DanmakuLayerFrame) {
        val previous = renderer.frame
        renderer.frame = frame
        if (frame.moving) {
            if (!pacing) {
                if (stoppedAtNanos != 0L) pacingStats.record(stopReason, System.nanoTime() - stoppedAtNanos)
                pacing = true
                pacer.post { pacerChoreographer?.postFrameCallback(pace) }
            }
        } else {
            val wasPacing = pacing
            pacing = false
            if (wasPacing) {
                stoppedAtNanos = System.nanoTime()
                stopReason = PacingStats.reasonOf(frame)
            }
            // 刚停下时补画一帧, 停在最后的位置
            if (wasPacing || previous == null || !frame.stillSameAs(previous)) requestRender()
        }
    }

    // 只在界面线程上读写
    private var stoppedAtNanos = 0L
    private var stopReason = 0

    fun release() {
        pacing = false
        pacerThread.quitSafely()
        onPause()
    }

    private class Renderer(pacingStats: PacingStats, private val pacerTiming: PacerTiming) : GLSurfaceView.Renderer {
        @Volatile
        var frame: DanmakuLayerFrame? = null

        private var program = 0
        private var aPos = 0
        private var aUv = 0
        private var uViewport = 0
        private var uAlpha = 0
        private var texture = 0
        private var atlas = DanmakuAtlasAllocator<Bitmap>(1, 1)
        private val clock = DanmakuRenderClock()
        private val stats = RenderStats(pacingStats, pacerTiming)

        private val vertices: FloatBuffer =
            ByteBuffer.allocateDirect(MAX_QUADS * 16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val indices: ShortBuffer =
            ByteBuffer.allocateDirect(MAX_QUADS * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                for (i in 0 until MAX_QUADS) {
                    val v = i * 4
                    put(v.toShort()); put((v + 1).toShort()); put((v + 2).toShort())
                    put((v + 2).toShort()); put((v + 1).toShort()); put((v + 3).toShort())
                }
                position(0)
            }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VERTEX_SHADER, FRAGMENT_SHADER)
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uViewport = GLES20.glGetUniformLocation(program, "uViewport")
            uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")

            // 新的 EGL 上下文: 旧纹理随旧上下文没了, 等 onSurfaceChanged 按尺寸重建
            texture = 0
            atlas = DanmakuAtlasAllocator(1, 1)

            GLES20.glEnable(GLES20.GL_BLEND)
            // 位图是预乘 alpha 的
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)

            val maxSize = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            val atlasWidth = danmakuAtlasWidth(width, maxSize)
            val atlasHeight = atlasWidth / 2
            if (texture != 0 && atlas.width == atlasWidth && atlas.height == atlasHeight) return
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            atlas = DanmakuAtlasAllocator(atlasWidth, atlasHeight)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, atlas.width, atlas.height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
        }

        override fun onDrawFrame(gl: GL10?) {
            val start = System.nanoTime()
            pacerTiming.onDraw(start)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (texture == 0) return
            val f = frame ?: return
            if (f.alpha <= 0f || f.hostWidth <= 0 || f.hostHeight <= 0) return
            val elapsed = clock.elapsedAt(f.elapsedAt(start), f.paused, start)
            stats.onFrame(start, f.moving, clock.lastErrorNanos)
            val clearsBefore = atlas.clears
            atlas.beginFrame()
            var quads = buildQuads(f, elapsed)
            // 中途整张清空过图集: 前面拼好的指向被覆盖的位置, 整帧重拼
            if (quads < 0) quads = buildQuads(f, elapsed).coerceAtLeast(0)
            stats.onDrawn(System.nanoTime() - start, atlas.clears - clearsBefore, clock.snaps)
            if (quads == 0) return

            GLES20.glUseProgram(program)
            GLES20.glUniform2f(uViewport, f.hostWidth.toFloat(), f.hostHeight.toFloat())
            GLES20.glUniform1f(uAlpha, f.alpha.coerceIn(0f, 1f))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            vertices.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(aPos)
            vertices.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(aUv)
            indices.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, quads * 6, GLES20.GL_UNSIGNED_SHORT, indices)
        }

        /** 拼这一帧的顶点, 返回四边形个数; 中途整张清空过图集时返回 -1. */
        private fun buildQuads(f: DanmakuLayerFrame, elapsed: Long): Int {
            vertices.clear()
            var quads = 0
            for (item in f.items) {
                if (quads >= MAX_QUADS) break
                val bitmap = item.bitmap
                val left = f.xOf(item, elapsed) + item.offsetX
                if (left > f.hostWidth || left + bitmap.width < 0) continue
                val slot = atlas.get(bitmap) ?: run {
                    val allocation = atlas.allocate(bitmap, bitmap.width, bitmap.height) ?: return@run null
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, allocation.slot.x, allocation.slot.y, bitmap)
                    stats.uploads++
                    if (allocation.cleared && quads > 0) return -1
                    allocation.slot
                } ?: continue
                val top = item.y + item.offsetY
                val right = left + bitmap.width
                val bottom = top + bitmap.height
                val u0 = slot.x.toFloat() / atlas.width
                val v0 = slot.y.toFloat() / atlas.height
                val u1 = (slot.x + slot.width).toFloat() / atlas.width
                val v1 = (slot.y + slot.height).toFloat() / atlas.height
                vertices.put(left).put(top).put(u0).put(v0)
                vertices.put(right).put(top).put(u1).put(v0)
                vertices.put(left).put(bottom).put(u0).put(v1)
                vertices.put(right).put(bottom).put(u1).put(v1)
                quads++
            }
            return quads
        }

        private fun link(vertexSource: String, fragmentSource: String): Int {
            fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertexSource))
                GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
                GLES20.glLinkProgram(it)
            }
        }
    }

    private companion object {
        const val MAX_QUADS = 1024

        // 坐标按界面像素给, 在这里换成裁剪空间 (y 朝下)
        const val VERTEX_SHADER = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform vec2 uViewport;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPos.x / uViewport.x * 2.0 - 1.0, 1.0 - aPos.y / uViewport.y * 2.0, 0.0, 1.0);
                vUv = aUv;
            }
        """

        // 图集有 4096 宽, mediump 的纹理坐标只有约 1/1024 的精度, 会采到旁边几个像素
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying highp vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            void main() {
                gl_FragColor = texture2D(uTex, vUv) * uAlpha;
            }
        """
    }
}

/**
 * 渲染线程上的统计, 查「弹幕顿一下」是哪一种: 渲染线程没赶上 vsync (间隔长)、一帧里传了很多图 (图集满了整张重传)、
 * 还是界面时钟落后 / 跳变 (见 [DanmakuRenderClock]). 每 10 秒有异常时打一行日志. 只在渲染线程上用.
 */
private class RenderStats(private val pacingStats: PacingStats, private val pacerTiming: PacerTiming) {
    private val logger = logger<GlDanmakuLayerView>()

    var uploads = 0
    private var frames = 0
    private var slowDraws = 0
    private var maxDrawNanos = 0L
    private var gaps = 0
    private var maxGapNanos = 0L
    private var clears = 0
    private var snaps = 0
    private var maxLagNanos = 0L
    private var lastFrameNanos = 0L
    private var lastMoving = false
    private var windowStart = 0L

    fun onFrame(nowNanos: Long, moving: Boolean, clockErrorNanos: Long) {
        if (windowStart == 0L) windowStart = nowNanos
        frames++
        if (moving && lastMoving) {
            val gap = nowNanos - lastFrameNanos
            if (gap > GAP_NANOS) gaps++
            maxGapNanos = maxOf(maxGapNanos, gap)
        }
        // 负数: 界面时钟落在渲染时钟后面 (直接照它画就会往回跳)
        maxLagNanos = maxOf(maxLagNanos, -clockErrorNanos)
        lastFrameNanos = nowNanos
        lastMoving = moving
    }

    fun onDrawn(drawNanos: Long, atlasClears: Int, totalSnaps: Int) {
        if (drawNanos > SLOW_DRAW_NANOS) slowDraws++
        maxDrawNanos = maxOf(maxDrawNanos, drawNanos)
        clears += atlasClears
        val now = lastFrameNanos
        if (now - windowStart < WINDOW_NANOS) return
        val newSnaps = totalSnaps - snaps
        val pacing = pacingStats.drain()
        val timing = pacerTiming.drain()
        if (slowDraws > 0 || gaps > 0 || clears > 0 || newSnaps > 0 || maxLagNanos > LAG_REPORT_NANOS || pacing != null) {
            logger.info {
                "GL danmaku ${(now - windowStart) / 1_000_000}ms: frames=$frames, missedVsync=$gaps (max gap ${maxGapNanos / 1_000_000}ms), " +
                    "slowDraws=$slowDraws (max ${maxDrawNanos / 1_000_000}ms), uploads=$uploads, atlasClears=$clears, " +
                    "clockSnaps=$newSnaps, uiClockMaxLag=${maxLagNanos / 1_000_000}ms, pacingStops=${pacing ?: "none"}, $timing"
            }
        }
        snaps = totalSnaps
        frames = 0; slowDraws = 0; maxDrawNanos = 0; gaps = 0; maxGapNanos = 0; uploads = 0; clears = 0; maxLagNanos = 0
        windowStart = now
    }

    private companion object {
        const val WINDOW_NANOS = 10_000_000_000L
        const val GAP_NANOS = 25_000_000L
        const val SLOW_DRAW_NANOS = 8_000_000L
        const val LAG_REPORT_NANOS = 50_000_000L
    }
}

/**
 * 停过几次逐帧出帧、各停了多久, 按原因分 ([reasonOf]): 停着的时候渲染线程只在内容变了才画一帧.
 * 界面线程写 (停下与恢复都在 [GlDanmakuLayerView.publish]), 渲染线程每 10 秒取一次 ([drain]).
 */
private class PacingStats {
    private val counts = IntArray(REASONS.size)
    private val maxNanos = LongArray(REASONS.size)

    @Synchronized
    fun record(reason: Int, stoppedNanos: Long) {
        counts[reason]++
        maxNanos[reason] = maxOf(maxNanos[reason], stoppedNanos)
    }

    /** 这段时间停过的, 形如 `noFloating 3 (max 120ms)`; 没停过为 null. 取完清零. */
    @Synchronized
    fun drain(): String? {
        if (counts.all { it == 0 }) return null
        val text = REASONS.indices.filter { counts[it] > 0 }
            .joinToString(", ") { "${REASONS[it]} ${counts[it]} (max ${maxNanos[it] / 1_000_000}ms)" }
        counts.fill(0)
        maxNanos.fill(0)
        return text
    }

    companion object {
        private val REASONS = arrayOf("paused", "hidden", "noFloating")

        fun reasonOf(frame: DanmakuLayerFrame): Int = when {
            frame.paused -> 0
            frame.alpha <= 0f -> 1
            else -> 2
        }
    }
}

/**
 * 逐帧出帧时的两段耗时, 分清弹幕层没出帧是 vsync 回调来晚了 ([onPace] 之间的间隔), 还是渲染线程迟迟没开始画
 * (请求画到 [onDraw] 之间: 多半卡在上一帧交换缓冲上).
 */
private class PacerTiming {
    @Volatile
    private var lastRequestNanos = 0L
    private var lastPaceNanos = 0L // 只在 pacer 线程上
    private val maxPaceGapNanos = AtomicLong()
    private val maxLatencyNanos = AtomicLong()

    /** pacer 线程: 这一帧请求画了. */
    fun onPace(nowNanos: Long) {
        if (lastPaceNanos != 0L) maxPaceGapNanos.accumulateAndGet(nowNanos - lastPaceNanos, ::maxOf)
        lastPaceNanos = nowNanos
        lastRequestNanos = nowNanos
    }

    /** pacer 线程: 停了, 下次开始不算间隔. */
    fun onPacingStopped() {
        lastPaceNanos = 0L
    }

    /** 渲染线程: 开始画. */
    fun onDraw(startNanos: Long) {
        val requested = lastRequestNanos
        if (requested != 0L) maxLatencyNanos.accumulateAndGet(startNanos - requested, ::maxOf)
    }

    fun drain(): String =
        "pacerMaxGap=${maxPaceGapNanos.getAndSet(0) / 1_000_000}ms, drawMaxLatency=${maxLatencyNanos.getAndSet(0) / 1_000_000}ms"
}

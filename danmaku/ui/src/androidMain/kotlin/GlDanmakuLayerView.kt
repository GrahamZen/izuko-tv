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
import me.him188.ani.utils.logging.warn
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
 * 字号、显示区域调大后一帧放不下时换更大的图集 (见 [grownDanmakuAtlasSize]); 到顶了还放不下的, 以及比最大的图集还宽的,
 * 各用一张自己的纹理画.
 * 有滚动弹幕时由自己线程上的 Choreographer 每个 vsync 请求一帧, 不用连续模式: 连续模式下 GL 线程大半时间卡在等下一块缓冲上,
 * 同进程里界面的渲染线程会被它拖住 (Shield 上界面掉到每秒十几帧).
 *
 * 这台设备画不了时 (片元着色器不支持 highp、着色器编不过、开不出图集纹理) 在界面线程上调 [onUnavailable], 由调用方换别的画法.
 *
 * @param fragmentShader 测试用, 换掉片元着色器.
 */
internal class GlDanmakuLayerView(
    context: Context,
    private val onUnavailable: () -> Unit = {},
    fragmentShader: String? = null,
) : GLSurfaceView(context) {
    private val pacingStats = PacingStats()
    private val pacerTiming = PacerTiming()
    private val renderer = Renderer(pacingStats, pacerTiming, fragmentShader ?: FRAGMENT_SHADER) { post(onUnavailable) }

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

    private class Renderer(
        pacingStats: PacingStats,
        private val pacerTiming: PacerTiming,
        private val fragmentShader: String,
        private val reportUnavailable: () -> Unit,
    ) : GLSurfaceView.Renderer {
        @Volatile
        var frame: DanmakuLayerFrame? = null

        private val logger = logger<GlDanmakuLayerView>()

        private var program = 0
        private var aPos = 0
        private var aUv = 0
        private var uViewport = 0
        private var uAlpha = 0
        private var texture = 0
        private var atlas = DanmakuAtlasAllocator<Bitmap>(1, 1)
        private var atlasLimit = 0
        private var atlasGrowthStopped = false
        private var lastCompactNanos = 0L

        // 最近一次放不下的那张图的尺寸, 换图集时至少要装得下它
        private var overflowWidth = 0
        private var overflowHeight = 0

        // 不进图集、各用一张自己纹理画的 (这一帧的), 与它们的纹理 (连着两帧没画就删)
        private val separateItems = ArrayList<DanmakuLayerItem>()
        private val separateTextures = HashMap<Bitmap, SeparateTexture>()
        private var maxTextureSize = 0
        private var frameIndex = 0L
        private val clock = DanmakuRenderClock()
        private val stats = RenderStats(pacingStats, pacerTiming)

        private val vertices: FloatBuffer =
            ByteBuffer.allocateDirect(MAX_QUADS * 16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val separateVertices: FloatBuffer =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
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
            // 新的 EGL 上下文: 旧纹理随旧上下文没了, 等 onSurfaceChanged 按尺寸重建
            texture = 0
            atlas = DanmakuAtlasAllocator(1, 1)
            atlasGrowthStopped = false
            separateTextures.clear()

            // 图集有 2048~4096 宽, 纹理坐标要 highp 才采得准; 不支持的 (Mali-400/450 等) 不用这一层
            val highp = fragmentHighpSupported()
            program = if (highp) link(VERTEX_SHADER, fragmentShader) else 0
            if (program == 0) {
                logger.warn { "GL danmaku unavailable: ${if (highp) "shader failed" else "no highp in fragment shaders"}" }
                reportUnavailable()
                return
            }
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uViewport = GLES20.glGetUniformLocation(program, "uViewport")
            uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")

            GLES20.glEnable(GLES20.GL_BLEND)
            // 位图是预乘 alpha 的
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            if (program == 0) return

            val maxSize = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            maxTextureSize = maxSize
            atlasLimit = minOf(maxSize, ATLAS_SIZE_LIMIT)
            val atlasWidth = danmakuAtlasWidth(width, maxSize)
            val atlasHeight = atlasWidth / 2
            // 换过更大的就留着
            if (texture != 0 && atlas.width >= atlasWidth && atlas.height >= atlasHeight) return
            if (!createAtlas(atlasWidth, atlasHeight)) {
                logger.warn { "GL danmaku unavailable: cannot allocate a ${atlasWidth}x$atlasHeight atlas" }
                reportUnavailable()
            }
        }

        /** 换一张 [width]×[height] 的空图集, 原来的图都要重传. 开不出来 (显存不够) 时为 false, 原来那张照用. */
        private fun createAtlas(width: Int, height: Int): Boolean {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) Unit
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            setTextureParameters()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                return false
            }
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = ids[0]
            atlas = DanmakuAtlasAllocator(width, height)
            atlas.beginFrame()
            return true
        }

        private fun setTextureParameters() {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        /** 换一张至少装得下 [overflowWidth]×[overflowHeight] 的更大的图集. 到顶或开不出来为 false. */
        private fun growAtlas(): Boolean {
            if (atlasGrowthStopped) return false
            val (width, height) = grownDanmakuAtlasSize(atlas.width, atlas.height, overflowWidth, overflowHeight, atlasLimit)
                ?: return false
            if (!createAtlas(width, height)) {
                atlasGrowthStopped = true
                logger.warn { "GL danmaku atlas cannot grow to ${width}x$height, staying at ${atlas.width}x${atlas.height}" }
                return false
            }
            logger.info { "GL danmaku atlas grown to ${width}x$height" }
            return true
        }

        override fun onDrawFrame(gl: GL10?) {
            val start = System.nanoTime()
            pacerTiming.onDraw(start)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0 || texture == 0) return
            val f = frame ?: return
            if (f.alpha <= 0f || f.hostWidth <= 0 || f.hostHeight <= 0) return
            val elapsed = clock.elapsedAt(f.elapsedAt(start), f.paused, start)
            stats.onFrame(start, f.moving, clock.lastErrorNanos)
            frameIndex++
            val quads = buildFrame(f, elapsed, start)
            // 只算自己的活 (拼顶点、传图): 之后的绘制调用会等系统给缓冲区, 那段算在 vsync 里
            stats.onDrawn(System.nanoTime() - start, clock.snaps, atlas.width, atlas.height)
            if (quads > 0 || separateItems.isNotEmpty()) {
                GLES20.glUseProgram(program)
                GLES20.glUniform2f(uViewport, f.hostWidth.toFloat(), f.hostHeight.toFloat())
                GLES20.glUniform1f(uAlpha, f.alpha.coerceIn(0f, 1f))
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                if (quads > 0) draw(texture, vertices, quads)
                for (item in separateItems) drawSeparately(f, item, elapsed)
            }
            releaseUnusedSeparateTextures()
        }

        private fun draw(texture: Int, buffer: FloatBuffer, quads: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            buffer.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(aPos)
            buffer.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(aUv)
            indices.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, quads * 6, GLES20.GL_UNSIGNED_SHORT, indices)
        }

        /** 用 [item] 自己的纹理画它 (第一次画时传). */
        private fun drawSeparately(f: DanmakuLayerFrame, item: DanmakuLayerItem, elapsed: Long) {
            val bitmap = item.bitmap
            val texture = separateTextures.getOrPut(bitmap) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                setTextureParameters()
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                stats.uploads++
                SeparateTexture(ids[0])
            }
            texture.lastUsedFrame = frameIndex
            separateVertices.clear()
            separateVertices.putQuad(item, f.xOf(item, elapsed) + item.offsetX, 0, 0, bitmap.width, bitmap.height)
            draw(texture.id, separateVertices, 1)
        }

        private fun releaseUnusedSeparateTextures() {
            val iterator = separateTextures.values.iterator()
            while (iterator.hasNext()) {
                val texture = iterator.next()
                if (texture.lastUsedFrame >= frameIndex - 1) continue
                GLES20.glDeleteTextures(1, intArrayOf(texture.id), 0)
                iterator.remove()
            }
        }

        /**
         * 拼这一帧, 返回图集里的四边形个数, 不进图集的放进 [separateItems]. 图集放不下时先整理 (清空后整帧重传:
         * 多半是滚出去的弹幕还占着位置), 还放不下就换大的, 到顶了放不下的各用一张自己的纹理.
         * 整理、换图集后前面拼好的指向的位置都作废, 所以每一步都整帧重拼.
         */
        private fun buildFrame(f: DanmakuLayerFrame, elapsed: Long, nowNanos: Long): Int {
            atlas.beginFrame()
            var quads = buildQuads(f, elapsed, separateTheRest = false)
            if (quads >= 0) return quads
            val visible = f.items.count { isDrawable(f, it, elapsed) }
            if (atlas.size - visible >= visible / 2 && nowNanos - lastCompactNanos > COMPACT_INTERVAL_NANOS) {
                lastCompactNanos = nowNanos
                atlas.clear()
                stats.clears++
                quads = buildQuads(f, elapsed, separateTheRest = false)
                if (quads >= 0) return quads
            }
            while (growAtlas()) {
                quads = buildQuads(f, elapsed, separateTheRest = false)
                if (quads >= 0) return quads
            }
            var area = 0L
            for (item in f.items) if (isDrawable(f, item, elapsed)) area += item.bitmap.width.toLong() * item.bitmap.height
            stats.onOverflow(visible, area, atlas.size)
            return buildQuads(f, elapsed, separateTheRest = true)
        }

        /** 在屏上, 且不比显卡能开的最大纹理还大. */
        private fun isDrawable(f: DanmakuLayerFrame, item: DanmakuLayerItem, elapsed: Long): Boolean {
            val left = f.xOf(item, elapsed) + item.offsetX
            return left <= f.hostWidth && left + item.bitmap.width >= 0 &&
                item.bitmap.width <= maxTextureSize && item.bitmap.height <= maxTextureSize
        }

        /**
         * 拼这一帧图集里的顶点, 返回四边形个数. 比最大的图集还大的放进 [separateItems]; 图集放不下的:
         * [separateTheRest] 时也放进 [separateItems] 接着拼, 否则记下它的尺寸返回 -1.
         */
        private fun buildQuads(f: DanmakuLayerFrame, elapsed: Long, separateTheRest: Boolean): Int {
            vertices.clear()
            separateItems.clear()
            var quads = 0
            for (item in f.items) {
                if (quads >= MAX_QUADS) break
                if (!isDrawable(f, item, elapsed)) continue
                val bitmap = item.bitmap
                if (bitmap.width > atlasLimit || bitmap.height > atlasLimit) {
                    separateItems += item
                    continue
                }
                var slot = atlas.get(bitmap)
                if (slot == null) {
                    slot = atlas.allocate(bitmap, bitmap.width, bitmap.height)
                    if (slot == null) {
                        if (!separateTheRest) {
                            overflowWidth = bitmap.width
                            overflowHeight = bitmap.height
                            return -1
                        }
                        separateItems += item
                        continue
                    }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, slot.x, slot.y, bitmap)
                    stats.uploads++
                }
                vertices.putQuad(item, f.xOf(item, elapsed) + item.offsetX, slot.x, slot.y, atlas.width, atlas.height)
                quads++
            }
            stats.separate += separateItems.size
            return quads
        }

        /**
         * 拼 [item] 的四边形: 左边缘在 [left], 图在纹理 ([textureWidth]×[textureHeight]) 的 ([x], [y]) 处.
         *
         * 四边各往里缩半个像素: 线性采样只落在这张图自己的像素上. 图集里图与图之间的空隙
         * (以及腾出来重用的行) 留着旧图的像素, 采到会在弹幕边上出一条点状的竖线.
         * 图四周留着透明边 (见 [cropDanmakuImage]), 缩掉的半像素看不出来.
         */
        private fun FloatBuffer.putQuad(item: DanmakuLayerItem, left: Float, x: Int, y: Int, textureWidth: Int, textureHeight: Int) {
            val bitmap = item.bitmap
            val top = item.y + item.offsetY + HALF_TEXEL
            val bottom = item.y + item.offsetY + bitmap.height - HALF_TEXEL
            val right = left + bitmap.width - HALF_TEXEL
            val quadLeft = left + HALF_TEXEL
            val u0 = (x + HALF_TEXEL) / textureWidth
            val v0 = (y + HALF_TEXEL) / textureHeight
            val u1 = (x + bitmap.width - HALF_TEXEL) / textureWidth
            val v1 = (y + bitmap.height - HALF_TEXEL) / textureHeight
            put(quadLeft).put(top).put(u0).put(v0)
            put(right).put(top).put(u1).put(v0)
            put(quadLeft).put(bottom).put(u0).put(v1)
            put(right).put(bottom).put(u1).put(v1)
        }

        private fun fragmentHighpSupported(): Boolean {
            val range = IntArray(2)
            val precision = IntArray(1)
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
            return precision[0] > 0
        }

        /** 编译、链接着色器; 失败时打出编译器的说明, 返回 0. */
        private fun link(vertexSource: String, fragmentSource: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1).also { GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, it, 0) }[0]
                if (status != 0) return shader
                logger.warn { "GL danmaku shader does not compile: ${GLES20.glGetShaderInfoLog(shader)}" }
                GLES20.glDeleteShader(shader)
                return 0
            }
            val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (vertex == 0 || fragment == 0) return 0
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val status = IntArray(1).also { GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, it, 0) }[0]
            if (status != 0) return program
            logger.warn { "GL danmaku program does not link: ${GLES20.glGetProgramInfoLog(program)}" }
            GLES20.glDeleteProgram(program)
            return 0
        }
    }

    private class SeparateTexture(val id: Int) {
        var lastUsedFrame = 0L
    }

    private companion object {
        const val MAX_QUADS = 1024
        const val HALF_TEXEL = 0.5f

        /** 图集最大的边长: 4096×4096 已占 64 MiB 显存. */
        const val ATLAS_SIZE_LIMIT = 4096

        /** 图集放不下时整理 (清空重传) 至少隔这么久, 免得一直放不下时每帧整张重传. */
        const val COMPACT_INTERVAL_NANOS = 1_000_000_000L

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

        // 图集有 2048~4096 宽, mediump 的纹理坐标只有约 1/1024 的精度, 会采到旁边几个像素
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
    var clears = 0
    var separate = 0
    private var overflowVisible = 0
    private var overflowArea = 0L
    private var overflowKeys = 0
    private var frames = 0
    private var slowDraws = 0
    private var maxDrawNanos = 0L
    private var gaps = 0
    private var maxGapNanos = 0L
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

    /** 图集到顶还放不下的一帧 (多出来的各用自己的纹理画): 在屏几张、总面积、图集里存着几张. 记这段时间里最多的. */
    fun onOverflow(visible: Int, area: Long, keys: Int) {
        overflowVisible = maxOf(overflowVisible, visible)
        overflowArea = maxOf(overflowArea, area)
        overflowKeys = maxOf(overflowKeys, keys)
    }

    fun onDrawn(drawNanos: Long, totalSnaps: Int, atlasWidth: Int, atlasHeight: Int) {
        if (drawNanos > SLOW_DRAW_NANOS) slowDraws++
        maxDrawNanos = maxOf(maxDrawNanos, drawNanos)
        val now = lastFrameNanos
        if (now - windowStart < WINDOW_NANOS) return
        val newSnaps = totalSnaps - snaps
        val pacing = pacingStats.drain()
        val timing = pacerTiming.drain()
        if (slowDraws > 0 || gaps > 0 || clears > 0 || separate > 0 || newSnaps > 0 || maxLagNanos > LAG_REPORT_NANOS || pacing != null) {
            logger.info {
                "GL danmaku ${(now - windowStart) / 1_000_000}ms: frames=$frames, missedVsync=$gaps (max gap ${maxGapNanos / 1_000_000}ms), " +
                    "slowDraws=$slowDraws (max ${maxDrawNanos / 1_000_000}ms), uploads=$uploads, atlas=${atlasWidth}x$atlasHeight, " +
                    "atlasClears=$clears, separate=$separate${if (overflowVisible > 0) " (atlas full: max visible $overflowVisible, ${overflowArea / 1000}k px, keys $overflowKeys)" else ""}, " +
                    "clockSnaps=$newSnaps, uiClockMaxLag=${maxLagNanos / 1_000_000}ms, pacingStops=${pacing ?: "none"}, $timing"
            }
        }
        snaps = totalSnaps
        frames = 0; slowDraws = 0; maxDrawNanos = 0; gaps = 0; maxGapNanos = 0; uploads = 0; clears = 0; separate = 0; maxLagNanos = 0
        overflowVisible = 0; overflowArea = 0; overflowKeys = 0
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

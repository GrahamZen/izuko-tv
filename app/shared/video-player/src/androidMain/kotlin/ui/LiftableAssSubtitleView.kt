/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.AssTex
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.media.AssHandler
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 画 libass 字幕的一层 GL 视图, 做法同 libass-android 的 `AssSubtitleTextureView`: 自己的 GL 线程, 视频时间每推进一次
 * 就让 libass 直接出纹理 (不支持 `GL_EXT_unpack_subimage` 的机器退回位图上传), 每张小图按它的矩形设 viewport 画一个四边形,
 * 纹理的 alpha 乘上 libass 给的颜色.
 *
 * 多出来的是 [bottomLift]: 不为 0 时, 落在画面下半部分的小图往上挪这么多像素, 让开画面底部的播放器控件;
 * 上半部分的 (标题、注释、顶部的歌词) 留在原处.
 */
@SuppressLint("ViewConstructor")
@OptIn(UnstableApi::class)
internal class LiftableAssSubtitleView(
    context: Context,
    private val assHandler: AssHandler,
) : TextureView(context), TextureView.SurfaceTextureListener {
    /** 播放线程经 [AssHandler.videoTimeCallback] 读它. */
    @Volatile
    private var renderThread: RenderThread? = null

    /** 下半部分的字幕往上挪多少像素, 0 = 在原位. 主线程写, GL 线程读. */
    @Volatile
    var bottomLift: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            renderThread?.redraw()
        }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        assHandler.videoTimeCallback = { timeUs -> renderThread?.requestRender(timeUs) }
    }

    override fun onDetachedFromWindow() {
        assHandler.videoTimeCallback = null
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread = RenderThread(surface, width, height, AssGlRenderer(assHandler) { bottomLift }).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.onSurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.release()
        renderThread = null
        // SurfaceTexture 由 GL 线程拆掉 EGL 之后再释放, 不让系统抢在前面释放
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    /**
     * GL 线程: 建 EGL、按请求渲染、交换缓冲. 新的渲染请求顶掉还没处理的旧请求.
     */
    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
        private val renderer: AssGlRenderer,
    ) : HandlerThread("AssLiftRender"), Handler.Callback {
        private lateinit var handler: Handler
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        /** 最近一次画的视频时间 (微秒), 尺寸变了或挪动距离变了时照它重画; 还没画过为 -1. */
        private var lastTimeUs = -1L

        override fun start() {
            super.start()
            handler = Handler(looper, this)
            handler.sendEmptyMessage(MSG_INIT)
        }

        fun requestRender(timeUs: Long) {
            handler.removeMessages(MSG_DRAW)
            handler.obtainMessage(MSG_DRAW, timeUs).sendToTarget()
        }

        fun redraw() {
            handler.removeMessages(MSG_REDRAW)
            handler.sendEmptyMessage(MSG_REDRAW)
        }

        fun onSurfaceSizeChanged(width: Int, height: Int) {
            handler.obtainMessage(MSG_SIZE, width, height).sendToTarget()
        }

        fun release() {
            handler.sendEmptyMessage(MSG_RELEASE)
        }

        override fun handleMessage(msg: Message): Boolean {
            try {
                when (msg.what) {
                    MSG_INIT -> init()
                    MSG_DRAW -> draw(msg.obj as Long, force = false)
                    MSG_REDRAW -> if (lastTimeUs >= 0) draw(lastTimeUs, force = true)
                    MSG_SIZE -> sizeChanged(msg.arg1, msg.arg2)
                    MSG_RELEASE -> {
                        releaseGl()
                        quitSafely()
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "ASS subtitle GL thread failed" }
                releaseGl()
                quitSafely()
            }
            return true
        }

        private fun init() {
            display = GlUtil.getDefaultEglDisplay()
            context = GlUtil.createEglContext(display)
            eglSurface = GlUtil.createEglSurface(display, surfaceTexture, C.COLOR_TRANSFER_SDR, false)
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            renderer.onSurfaceCreated()
            sizeChanged(width, height)
        }

        private fun sizeChanged(width: Int, height: Int) {
            this.width = width
            this.height = height
            renderer.onSurfaceChanged(width, height)
            if (display == EGL14.EGL_NO_DISPLAY) return
            GlUtil.clearFocusedBuffers()
            EGL14.eglSwapBuffers(display, eglSurface)
            if (lastTimeUs >= 0) draw(lastTimeUs, force = true)
        }

        private fun draw(timeUs: Long, force: Boolean) {
            if (display == EGL14.EGL_NO_DISPLAY) return
            lastTimeUs = timeUs
            if (renderer.onDrawFrame(timeUs, force)) EGL14.eglSwapBuffers(display, eglSurface)
        }

        private fun releaseGl() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                renderer.onSurfaceDestroyed()
                GlUtil.destroyEglSurface(display, eglSurface)
                GlUtil.destroyEglContext(display, context)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            surfaceTexture.release()
        }

        private companion object {
            const val MSG_INIT = 0
            const val MSG_DRAW = 1
            const val MSG_REDRAW = 2
            const val MSG_SIZE = 3
            const val MSG_RELEASE = 4
        }
    }

    /**
     * 在 GL 线程上画一帧 libass 字幕. [lift] 给出此刻下半部分要往上挪的像素.
     */
    private class AssGlRenderer(
        private val assHandler: AssHandler,
        private val lift: () -> Float,
    ) {
        private lateinit var program: GlProgram
        private var vertexBuffer = 0
        private var texCoordBuffer = 0
        private var nativeTexture = false
        private var surfaceSize = Size.ZERO
        private var renderSize = Size.ZERO

        /** 画面上还留着上一帧的字: 这一帧没有字时也要清一次. */
        private var surfaceDirty = false

        /**
         * 最近一帧的小图与它们的纹理. libass 在帧没变时不再给图, 只挪位置的重画照这份画;
         * 纹理留到下一帧的图来了再删.
         */
        private var images: List<SubtitleImage> = emptyList()

        fun onSurfaceCreated() {
            nativeTexture = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().contains("GL_EXT_unpack_subimage")
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            val buffers = IntArray(2)
            GLES20.glGenBuffers(2, buffers, 0)
            vertexBuffer = buffers[0]
            texCoordBuffer = buffers[1]
            upload(vertexBuffer, QUAD)
            upload(texCoordBuffer, TEX_COORDS)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            surfaceSize = Size(width, height)
            renderSize = assHandler.computeRenderSize(width, height)
            assHandler.render?.setFrameSize(renderSize.width, renderSize.height)
            GLES20.glViewport(0, 0, width, height)
        }

        /**
         * @param force 帧没变也重画 (尺寸或挪动距离变了)
         * @return 画了 (要交换缓冲)
         */
        fun onDrawFrame(timeUs: Long, force: Boolean): Boolean {
            val type = if (nativeTexture) AssTexType.TEXTURE else AssTexType.BITMAP_ALPHA
            val frame = assHandler.render?.renderFrame(timeUs / 1000, type)
            val fresh = frame?.images
            if (frame == null || frame.changed != 0 || !fresh.isNullOrEmpty()) replaceImages(fresh)
            val changed = if (frame == null) surfaceDirty else frame.changed != 0
            if (!changed && !force) return false
            GlUtil.clearFocusedBuffers()
            surfaceDirty = images.isNotEmpty() && renderSize.width > 0 && renderSize.height > 0
            if (surfaceDirty) drawImages()
            GLES20.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
            return true
        }

        private fun replaceImages(fresh: Array<AssTex>?) {
            images.forEach { GlUtil.deleteTexture(it.texture) }
            images = fresh.orEmpty().mapNotNull { image ->
                val texture = when {
                    nativeTexture -> image.tex
                    else -> image.bitmap?.let { GlUtil.createTexture(it) } ?: 0
                }
                if (texture <= 0) null
                else SubtitleImage(image.x, image.y, image.w, image.h, image.color, texture)
            }
        }

        private fun drawImages() {
            program.use()
            bindAttribute("a_Position", vertexBuffer)
            bindAttribute("a_TexCoord", texCoordBuffer)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            val colorLocation = program.getUniformLocation("u_Color")
            val scaleX = surfaceSize.width.toFloat() / renderSize.width
            val scaleY = surfaceSize.height.toFloat() / renderSize.height
            val lift = lift()
            val middle = renderSize.height / 2f
            for (image in images) {
                val color = image.color
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, image.texture)
                val shift = if (image.y + image.h / 2f > middle) lift else 0f
                val x = (image.x * scaleX).toInt()
                val y = (image.y * scaleY - shift).toInt()
                val w = (image.w * scaleX).toInt()
                val h = (image.h * scaleY).toInt()
                GLES20.glViewport(x, surfaceSize.height - y - h, w, h)
                GLES20.glUniform4f(
                    colorLocation,
                    (color ushr 24 and 0xFF) / 255f,
                    (color ushr 16 and 0xFF) / 255f,
                    (color ushr 8 and 0xFF) / 255f,
                    (255 - (color and 0xFF)) / 255f,
                )
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        private fun bindAttribute(name: String, buffer: Int) {
            val location = program.getAttributeArrayLocationAndEnable(name)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
            GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, 0)
        }

        fun onSurfaceDestroyed() {
            replaceImages(null)
            GlUtil.deleteBuffer(vertexBuffer)
            GlUtil.deleteBuffer(texCoordBuffer)
            if (::program.isInitialized) program.delete()
        }

        private fun upload(buffer: Int, data: FloatArray) {
            val floats = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            floats.put(data).position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, floats, GLES20.GL_STATIC_DRAW)
        }

        private companion object {
            const val VERTEX_SHADER = """
attribute vec4 a_Position;
attribute vec2 a_TexCoord;
varying vec2 v_TexCoord;
void main() {
    gl_Position = a_Position;
    v_TexCoord = a_TexCoord.xy;
}
"""
            const val FRAGMENT_SHADER = """
precision mediump float;
varying vec2 v_TexCoord;
uniform sampler2D u_Texture;
uniform vec4 u_Color;
void main() {
    float alpha = texture2D(u_Texture, v_TexCoord).a;
    gl_FragColor = vec4(u_Color.rgb, u_Color.a * alpha);
}
"""

            /** 铺满 viewport 的四边形 (三角形带), 左上角对纹理的 (0, 0). */
            val QUAD = floatArrayOf(-1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f)
            val TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        }
    }

    /** 一张字幕小图: 渲染坐标里的矩形、RRGGBBAA 颜色 (AA 为透明度) 与它的 alpha 纹理. */
    private class SubtitleImage(val x: Int, val y: Int, val w: Int, val h: Int, val color: Int, val texture: Int)

    private companion object {
        private val logger = logger<LiftableAssSubtitleView>()
    }
}

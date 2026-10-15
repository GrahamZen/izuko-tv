/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.videoplayer.media.SeekPreview
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 预览浮窗里的小画面: 主播放器的视频输出接到 [SeekPreviewFrameRelay] 的输入面上, 每解出一帧, 在它自己的线程上缩画进这块
 * 小 TextureView; 界面只画这张小图.
 *
 * 不把视频输出直接接到 TextureView 上: 那样应用的渲染线程每重画一次界面 (圆点挪一步、时间变一下) 都要把整张视频帧再读一遍,
 * 4K 帧在慢电视上读一遍要一秒左右, 渲染线程又是整个应用共用的, 界面和按键都跟着停. 缩小只在出新帧时做一次, 而且不在渲染线程上.
 * 也不用 SurfaceView: 有的电视只显示一路视频, 第二路 (小画面) 根本不出来.
 *
 * @param onFrameDrawn 主线程上: 小画面上画出了带这个时间戳的一帧 (见 [SeekPreview.shownFrameTimestampNs])
 * @param onRelayFailed 主线程上: 中转建不起来 (EGL 出错), 视频输出没接到这里; 由调用方改用别的画法 (见 `PlayerSeekPreviewState`)
 */
@Composable
internal fun SeekPreviewRelayFrame(
    preview: SeekPreview,
    onFrameDrawn: (timestampNs: Long) -> Unit,
    onRelayFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                val view = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var relay: SeekPreviewFrameRelay? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        lateinit var created: SeekPreviewFrameRelay
                        created = SeekPreviewFrameRelay(texture, width, height) { timestampNs ->
                            view.post { if (relay === created) onFrameDrawn(timestampNs) }
                        }
                        relay = created
                        created.start(
                            onReady = { input -> view.post { if (relay === created) preview.attachFrameSurface(input) } },
                            onFailed = {
                                view.post {
                                    if (relay !== created) return@post
                                    relay = null
                                    onRelayFailed()
                                }
                            },
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        // 先把视频输出挪走 (返回时播放线程已经不再往这里画), 再拆中转
                        preview.attachFrameSurface(null)
                        val current = relay ?: return true
                        relay = null
                        current.release() // 输出面由中转线程用完再释放
                        return false
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * 在自己的线程上把视频帧缩画到 [output] (浮窗里那块小 TextureView 的画面) 上: 输入是一个 [SurfaceTexture] (交给播放器当视频输出),
 * 每来一帧取进来、按 [output] 的大小画一遍, 画完在中转线程上调 [onFrameDrawn] (那一帧的时间戳).
 * 线程、EGL 上下文都是这一次预览独占的, [release] 后整个丢掉.
 *
 * 4K 缩到小画面是十几倍: 每个点只采一次的话细线和边缘一闪一闪、带锯齿. 每个点在它盖住的那一片里均匀采 [samplesPerAxis] × [samplesPerAxis] 次再平均.
 */
private class SeekPreviewFrameRelay(
    private val output: SurfaceTexture,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val samplesPerAxis: Int = SAMPLES_PER_AXIS,
    private val onFrameDrawn: (timestampNs: Long) -> Unit,
) {
    private val thread = HandlerThread("SeekPreviewRelay").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var input: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private var released = false

    /** 建好后在中转线程上调 [onReady] (交出输入面), 出错调 [onFailed]. */
    fun start(onReady: (Surface) -> Unit, onFailed: () -> Unit) {
        handler.post {
            try {
                setUp()
                onReady(checkNotNull(inputSurface))
            } catch (e: Exception) {
                logger.warn(e) { "Seek preview relay failed to start" }
                tearDown(releaseOutput = false)
                thread.quitSafely()
                onFailed()
            }
        }
    }

    /** 拆掉: 不再画, 释放输入面、EGL 与 [output]. 调之前视频输出要已经挪走. */
    fun release() {
        handler.post {
            tearDown(releaseOutput = true)
            thread.quitSafely()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        output.setDefaultBufferSize(outputWidth, outputHeight)
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
        program = buildProgram()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texture)
        st.setOnFrameAvailableListener({ draw() }, handler)
        input = st
        inputSurface = Surface(st)
        logger.info { "Seek preview relay ready: ${outputWidth}x$outputHeight" }
    }

    private fun draw() {
        if (released) return
        try {
            drawFrame()
        } catch (e: Exception) {
            // 画不出来只是这一帧小画面不更新, 别让中转线程把应用带崩
            logger.warn(e) { "Seek preview relay failed to draw a frame" }
        }
    }

    private fun drawFrame() {
        val st = input ?: return
        val startedAt = SystemClock.elapsedRealtime()
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, VERTICES)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        // 一个输出点在纹理坐标里的宽高 (经过纹理变换: 可能翻转、裁边)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uStepX"), texMatrix[0] / outputWidth, texMatrix[1] / outputWidth)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uStepY"), texMatrix[4] / outputHeight, texMatrix[5] / outputHeight)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, eglSurface)
        logger.info { "Seek preview frame relayed in ${SystemClock.elapsedRealtime() - startedAt}ms" }
        onFrameDrawn(st.timestamp)
    }

    private fun tearDown(releaseOutput: Boolean) {
        released = true
        input?.release()
        input = null
        inputSurface?.release()
        inputSurface = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            // 不 eglTerminate: 默认显示是整个进程共用的 (界面的渲染线程也在用)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        if (releaseOutput) output.release()
    }

    private fun buildProgram(): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader(samplesPerAxis)))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "glLinkProgram: " + GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "glCompileShader: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private companion object {
        private val logger = logger<SeekPreviewFrameRelay>()

        private val VERTICES = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX_COORDS = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        /** 每个输出点每个方向采几次, 见 [SeekPreviewFrameRelay]. */
        private const val SAMPLES_PER_AXIS = 4

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying highp vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        /** 在一个输出点盖住的那一片里均匀采 [n] × [n] 次取平均 (每次本身是双线性的). 纹理坐标尽量用 highp: 4K 上 mediump 差好几个像素. */
        private fun fragmentShader(n: Int) = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            #define N $n
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uStepX;
            uniform vec2 uStepY;
            void main() {
                vec4 sum = vec4(0.0);
                for (int i = 0; i < N; i++) {
                    for (int j = 0; j < N; j++) {
                        vec2 offset = uStepX * ((float(i) + 0.5) / float(N) - 0.5) + uStepY * ((float(j) + 0.5) / float(N) - 0.5);
                        sum += texture2D(sTexture, vTexCoord + offset);
                    }
                }
                gl_FragColor = sum / float(N * N);
            }
        """.trimIndent()

        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}

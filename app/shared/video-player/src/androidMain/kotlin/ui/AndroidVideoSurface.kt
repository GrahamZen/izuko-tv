/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import org.openani.mediamp.MediampPlayer
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private val videoSurfaces = WeakHashMap<MediampPlayer, WeakReference<SurfaceView>>()

internal fun registerAndroidVideoSurface(player: MediampPlayer, surfaceView: SurfaceView) {
    val replaced = synchronized(videoSurfaces) {
        val previous = videoSurfaces[player]?.get()
        videoSurfaces[player] = WeakReference(surfaceView)
        previous !== surfaceView
    }
    // 换了一块 view = 输出面换代, 新的这一代上还没有画面 (见 VideoSurfaceFrameSignal)。
    // 只在**真的换了**的时候清: 本函数会随组合重跑, 每次都清会让标记再也回不到 true
    if (replaced) (player as? LibassExoPlayerMediampPlayer)?.onVideoSurfaceReplaced()
    // Surface 重建时 sticky dataspace 会丢, 由这里补写; 见 SurfaceDataSpace.kt。
    // holder 不会主动放手回调, 所以回调只弱引用播放器 (存活的播放器由其持有者引住),
    // 播放器 close 或被回收后在下一次事件里自我注销 (写入本身另有 closed 门控挡住)
    (player as? LibassExoPlayerMediampPlayer)?.let { libassPlayer ->
        val playerRef = WeakReference(libassPlayer)
        surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                /** 取还活着且没关闭的播放器; 否则自我注销并返回 null。 */
                private fun alivePlayer(holder: SurfaceHolder): LibassExoPlayerMediampPlayer? {
                    val p = playerRef.get()
                    if (p == null || p.isClosed) {
                        holder.removeCallback(this)
                        return null
                    }
                    return p
                }

                override fun surfaceCreated(holder: SurfaceHolder) {
                    alivePlayer(holder)?.applyVideoDataSpace()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    alivePlayer(holder)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // sticky dataspace 随该代次 Surface 消亡; 带上自己的 view, 播放器只释放
                    // 属于它的记账, 避免旧 view 销毁清掉新 Surface 的账。已关闭的播放器也要
                    // 放账 (close 的清理重试以记账非零为继续条件), 所以先放账再注销
                    val p = playerRef.get()
                    p?.onVideoSurfaceDestroyed(surfaceView)
                    if (p == null || p.isClosed) {
                        holder.removeCallback(this)
                    }
                }
            },
        )
    }
}

fun MediampPlayer.findAndroidVideoSurface(): SurfaceView? = synchronized(videoSurfaces) {
    videoSurfaces[this]?.get()
}

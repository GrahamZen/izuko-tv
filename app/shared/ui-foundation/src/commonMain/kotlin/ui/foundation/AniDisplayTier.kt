/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.concurrent.Volatile

/**
 * UI 的渲染分辨率档位 —— 图片该取哪一档的唯一依据.
 *
 * 做成进程级单例而不是形参: 档位是**整个界面**的属性 (一个进程只有一块屏), 而要它的地方散在
 * 很深的调用链里 (hero URL 拼装、邻居预取的排除判断、剧照降档), 逐个透传形参要改一大片签名却
 * 表达不出"这不是某个调用点的选择". 同款先例见 `TvImageNetworkSpeed`.
 *
 * **为什么需要它**: 图片档位原先按 1080p 写死 (backdrop w1280、剧照卡片 w780), 而电视可以跑原生
 * 4K UI (Shield 上 `wm size 3839x2160` 免 root 强制, 见 project-shield-4k-ui-locked). 2026-08-21
 * 在 4K 上实测, 清晰度日志一片 `SOURCE_LIMITED`: 全屏 hero **3.0 倍**放大、详情页 hero 2.0 倍、
 * 竖封面 2.0 倍、头像 3.4 倍 —— 1080p 那一轮同样的浏览量里这些基本都是 1.0~1.5. 档位必须跟着
 * 显示尺寸走, 而不是写死.
 *
 * **但"能升就升"是错的**: 同一天按这个档位把所有 hero 一起升到原图档, 4K 实测是净亏 (解码位图
 * 3.7MB→33MB, 阻塞 GC 20→115 次), 账记在
 * [tmdbBackdropOriginalSizeUrl][me.him188.ani.app.data.network.tmdbBackdropOriginalSizeUrl].
 * 现在只有**详情页那一张全屏 hero** 认这个档位 (`HeroBackdropSharpeningOverlay`) —— 一次一张、
 * 停留久、没有邻居预取的乘数. 加新的消费点前先算"这张图一次会出现几张、多久换一次".
 *
 * 竖封面 (2.0 倍) 与头像 (3.4 倍) 还没跟上: 那两路的图床未必有更大的档 (Bangumi 封面 l 档就是
 * 上传原图; myani 头像同理), 得先确认有更高档可取再谈升档.
 */
object AniDisplayTier {
    /**
     * 界面宽度到达这个像素数就算"高分屏", 图片取更高档.
     *
     * 2560 而不是 3840: 取中间值把 1080p (1920) 与 4K (3840) 分开, 顺带把 1440p 这类中间档
     * 归到高分那边 —— 它的框已经比 w1280 大一截, 也该升档.
     */
    private const val HIGH_RES_WIDTH_PX = 2560

    /**
     * 写在组合 (主线程), 读在各处 (含 `Dispatchers.Default` 上的预取判断) —— 单个 Boolean 的读写
     * 在 JVM 上本就是原子的, `@Volatile` 只为保证跨线程可见性.
     */
    @Volatile
    private var highRes = false

    /** true = 原生 4K/1440p 级 UI, 图片取更高档 (见 [AniDisplayTier] 的实测数据). */
    val isHighRes: Boolean get() = highRes

    internal fun update(widthPx: Int) {
        // 宽度为 0 的那几帧 (窗口还没测量) 不作数: 会把 4K 误判成低分, 而误判的那一瞬正好是
        // 首屏图片发请求的时刻 —— 首屏拿到低档 URL 就等于这次启动全程都是低档 (缓存键按 URL)
        if (widthPx <= 0) return
        highRes = widthPx >= HIGH_RES_WIDTH_PX
    }
}

/**
 * 把当前窗口宽度喂给 [AniDisplayTier]. 挂在应用根部即可, 全局只需一处.
 *
 * 用 `LocalWindowInfo.containerSize` 而不是屏幕物理尺寸: 图片要匹配的是**它实际被画多大**,
 * 而窗口才是布局的上界 —— 桌面端能拖动窗口, 电视上分屏/缩放模式也会让两者不等.
 */
@Composable
fun TrackAniDisplayTier() {
    val size = LocalWindowInfo.current.containerSize
    AniDisplayTier.update(size.width)
}

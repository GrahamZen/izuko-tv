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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.request.Disposable
import com.github.panpf.sketch.request.ImageRequest
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger

/**
 * 同时挂在后台补下的图片数上限.
 *
 * 取 2, 而不是按"聚焦 + 四个邻居"来估: 队列里装的全是**已经离场**的卡片, 邻居数是"还没到的
 * 目标"的量级, 拿它当依据本身就不对. 更关键的是 OkHttp 对同一 host 默认只放 5 个并发, 而
 * 卡片封面与补下走的是同一个图床 —— 补下占得多, 真正可见的新卡片就得排队, 窄带宽下这正是
 * 要避免的事.
 *
 * 尤其要防的是**整页销毁**那一下 (进详情页/播放页): 满屏没下完的卡片同时触发 `onDispose`,
 * 一瞬间就能提交十几个, 而那一刻带宽本该全留给目的页的首图.
 */
private const val MAX_PENDING_COMPLETIONS = 2

private val logger = logger("ImageCompletionGrace")

/**
 * 还在后台补下的请求. 超上限时**丢弃新提交的**, 让已经在跑的那几个跑完.
 *
 * 早先是"挤掉最老的", 实测下来两头都坏 (2026-08-15 日志: 27 次提交 / 19 次被挤掉 / 只有 8 次
 * 真跑完):
 * - **没达成目的**: 被 `dispose()` 的那些, 下到一半的字节照样作废 —— 而这个机制存在的理由
 *   恰恰就是"别让下到一半的字节作废". 上限从 6 降到 2 之后, 挤掉成了常态, 于是它七成时间在
 *   做无用功.
 * - **把取消模式带回来了**: `dispose()` 取消的是正在读的 TLS socket, 而这正是
 *   Android 11 Conscrypt 并发关闭崩溃的引信 (见 `TvHeroImagePrefetch.retain` 的说明).
 *
 * 丢弃新的则两条都不占: 不取消任何在途连接, 而且每次都真能攒下完整的几张. 代价是最新离场的
 * 那张不补 —— 它恰恰是最可能马上被划回来的, 但届时可见请求自己会重下, 与不补下时一样.
 *
 * 不加锁: 提交点是 Compose 的 `onDispose`, 移除点是 Sketch 的 request listener, 两者都在主线程.
 */
private val pendingCompletions = LinkedHashMap<String, Disposable>()

/**
 * 图片加载的"完成宽限": 卡片离开组合时那张还没下完的图, 交给后台跑完并写进磁盘缓存.
 *
 * 图片库把请求绑在组合上, 卡片一离开就取消, 而**已经下到一半的字节全废** —— HTTP 缓存要拿到
 * 完整响应才落盘. 带宽宽裕时这无所谓, 一张图几百毫秒就下完了, 根本碰不到取消; 窄带宽上却是
 * 致命的: 一张 400 KB 的封面要六七秒, 远慢于遥控器导航的节奏, 于是每张图都在"下一半 → 丢弃
 * → 回来重下"里打转, 永远下不完, 卡片就在骨架和图之间反复闪 (issue #7 报告者的日志里
 * 五十多条 CANCELLED, 同一张封面被取消两次才终于下完).
 *
 * 补下的请求**不进内存缓存**: 它已经不在屏上了, 占着只会把真正可见的那些挤出去; 写到磁盘就够
 * —— 焦点转回来时从本地读, 不必再走网络.
 *
 * 用法: 拿到的标志位交给 `onSuccess` 置位, 没置位就说明丢弃时还没下完, 需要补.
 * ```
 * val loaded = rememberImageCompletionGrace(imageUrl)
 * AsyncImage(imageUrl, ..., onSuccess = { loaded.value = true })
 * ```
 *
 * @param url 要补下的图片 URL; 为 null 时什么都不做.
 */
@Composable
fun rememberImageCompletionGrace(
    url: String?,
    sketch: Sketch = LocalSketch.current,
): MutableState<Boolean> {
    val context = LocalPlatformContext.current
    val loaded = remember(url) { mutableStateOf(false) }
    DisposableEffect(url, sketch) {
        onDispose {
            // 在 onDispose 里读, 不是组合期间读 —— 不会给这个卡片建立重组订阅
            if (!loaded.value && url != null) {
                submitImageCompletion(context, sketch, url)
            }
        }
    }
    return loaded
}

private fun submitImageCompletion(context: PlatformContext, sketch: Sketch, url: String) {
    if (pendingCompletions.containsKey(url)) return
    if (pendingCompletions.size >= MAX_PENDING_COMPLETIONS) {
        logger.debug { "Completion queue full, dropping: $url" }
        return
    }

    val request = ImageRequest(context, url) {
        memoryCachePolicy(CachePolicy.DISABLED)
        // 下载缓存存的是网络原始字节, 正是补下要落盘的东西; 结果缓存 (重编码后的位图) 不需要
        downloadCachePolicy(CachePolicy.ENABLED)
        resultCachePolicy(CachePolicy.DISABLED)
        // 1×1: 补下要的只是"把字节写进磁盘缓存", 而磁盘存的是网络原始字节, 在解码之前就写好了.
        // 不给尺寸的话按原尺寸解码, 每张都完整解出一张原尺寸位图 (一张竖版封面约 5MB), 随即因
        // memoryCachePolicy(DISABLED) 当场作废 —— 纯白烧 CPU 与 GC.
        // 与 hero 的磁盘档预热同一处理 (见 TvHeroImagePrefetch)
        size(1, 1)
        addListener(
            onCancel = { pendingCompletions.remove(url) },
            onError = { _, _ -> pendingCompletions.remove(url) },
            onSuccess = { _, _ ->
                pendingCompletions.remove(url)
                logger.debug { "Completed in background: $url" }
            },
        )
    }

    // enqueue 走 Sketch 自己的 scope, 不跟着组合走, 所以能跑完
    pendingCompletions[url] = sketch.enqueue(request)
}

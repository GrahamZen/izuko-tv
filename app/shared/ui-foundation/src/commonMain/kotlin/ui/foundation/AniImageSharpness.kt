/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.request.ImageData
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.Interceptor
import com.github.panpf.sketch.resize.Precision
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeSource
import com.github.panpf.sketch.util.Size as SketchSize

private val logger = logger("ImageSharpness")

/**
 * 每多少次解码打一条累计汇总. 10 差不多是"进一个页面"的量 —— 再大就要滚半天才等到第一条数字,
 * 再小也没意义 (汇总是累计的, 后一条包含前一条).
 */
internal const val SHARPNESS_SUMMARY_INTERVAL = 10

/**
 * 是否每张图都单独打一行 (源图/解码/显示三个尺寸 + URL).
 *
 * 平时关着: 逛一会儿就是几百行, 会把 app.log 里别的线索冲掉, 而且解码线程上的日志 IO 会干扰
 * 流畅度实测. 要定位"具体是哪张图不对"时才打开 —— 平时看汇总那一条就够 (它带最差的那张).
 */
private const val SHARPNESS_LOG_EVERY_DECODE = false

/**
 * 慢解码单独记一行的阈值.
 *
 * 平均是 70~110ms, 所以 150ms 以上算慢. 与 `ImageLoad: Slow image request` (网络那一段) 对着看,
 * 就能回答"这张图等的几秒里, 网络占多少、解码占多少" —— 2026-08-21 就是靠这两行定位到"追番页慢"
 * 其实是跨页缓存没共用 (同一张封面被解两遍) 加冷缓存下载, 与解码本身无关.
 */
private const val SLOW_DECODE_MILLIS = 150L

/**
 * 每个汇总周期 ([SHARPNESS_SUMMARY_INTERVAL] 次解码) 内最多单独记几条慢解码.
 *
 * 这个数字含排队 (见 [ImageSharpnessSample.decodeMillis]), 而 sketch 只有 4 个解码槽 ——
 * 冷缓存进网格页时队尾的十几张**全都**会超阈值, 挨条记就是十几行; Android 的文件 appender
 * 是同步的, 这些写盘又都发生在图片完成路径上, 诊断日志反过来拖慢被诊断的东西. 前几条 +
 * 汇总里的 max/worst 已经足够看出"慢的是排队还是单张", 多出来的只计数不逐条记.
 *
 * **破纪录的那一次不受这个上限约束** —— 2026-08-21 实测踩到: 限频上线后第一轮就出现
 * `max=3488ms`, 而那条 slow decode 行正好排在第 4 个之后被吃掉了, 于是"最慢的是哪张图"
 * 在日志里查无此人. 破纪录天然是单调稀少事件, 放行它不会带来量.
 */
private const val SLOW_LOG_MAX_PER_SUMMARY = 3

/**
 * 判"放大"的容差. 请求尺寸会按 8/16/64 向上取整 ([toAniImageRequestSize]), 解码也有取整,
 * 所以零点几个像素的出入不算放大.
 */
private const val SHARPNESS_TOLERANCE = 1.02f

/** 低于这个倍数算"明显过采样": 解出的边长是显示的 1.33 倍以上, 即多解了约 80% 像素. */
private const val OVERSAMPLE_THRESHOLD = 0.75f

/** 小于这个尺寸的请求当成预取/探测 (hero 预取用 `size(1,1)` 只为填磁盘缓存), 不计入统计. */
private const val MIN_MEASURABLE_BOX = 8

internal enum class ImageSharpnessVerdict {
    /** 1:1 (或只差一点点): 最理想. */
    EXACT,

    /** 解得比显示尺寸大不少: 不影响清晰度, 但白花解码时间与内存. */
    OVERSAMPLED,

    /** 被放大了, 但源图本身就比显示尺寸小 —— 无解, 不是 bug. */
    SOURCE_LIMITED,

    /** **被放大了, 而源图明明够大** —— 解码参数的 bug, 会肉眼发虚. */
    UNDERSAMPLED,
}

internal data class ImageSharpnessSample(
    val uri: String,
    /** 源图 (解码前) 尺寸. */
    val source: SketchSize,
    /** 真正解出来的位图尺寸. */
    val decoded: SketchSize,
    /**
     * 这张图要显示成多大. 布局尺寸按 8/16/64 向上取整过 ([toAniImageRequestSize]), 所以它是个
     * 略偏大的上界 —— 度量因此是**保守**的 (宁可多报一点放大, 不会漏报).
     */
    val box: SketchSize,
    val precision: Precision,
    /** 绘制时的缩放倍数: >1 被放大 (发虚), 1 是 1:1, <1 是过采样. */
    val drawScale: Float,
    /** 源图直接按 1:1 解能达到的倍数: >1 说明源图本身就不够大. */
    val sourceDrawScale: Float,
    val verdict: ImageSharpnessVerdict,
    val decodedPixels: Long,
    /** 最终真正画到屏幕上的像素数. 与 [decodedPixels] 的差就是白解出来的部分. */
    val displayedPixels: Long,
    /**
     * **排队 + 解码**, 不是纯解码时间. sketch 把解码派发到限并发的调度器上 (默认只有 4 个并行,
     * 网络 10), 而拦截器在派发之前就开始计时 —— 一屏几十张封面同时到齐时, 队尾等的那 1.4 秒
     * 全在这个数字里 (2026-08-21 实测: 四张小图同时报 1460ms, 而源图大 4 倍的那张只用 187ms).
     */
    val decodeMillis: Long,
    val dataFrom: String,
) {
    fun detail(): String = "drawScale=x${drawScale.format2()} src=${source.str()} decoded=${decoded.str()} " +
            "box~${box.str()} $precision from=$dataFrom dec+q=${decodeMillis}ms $uri"
}

/**
 * 把"清晰度"算成一个可比较的数字.
 *
 * 换图片库/改解码参数时**看配置看不出清晰度问题** —— 2026-08-21 那次把请求尺寸从 2 倍改回 1 倍,
 * 所有配置断言都是绿的, 实际却在解码时欠采样到 113×169 (见 [AniCropPrecisionDecider] 的说明).
 * 唯一可靠的判据是真解出来的像素与它最终占的显示尺寸之比, 也就是绘制时的缩放倍数 `drawScale`:
 * **>1 就是被放大 = 肉眼发虚**, =1 是 1:1 (最理想), <1 是过采样 (不伤清晰度, 但白花解码时间和内存).
 *
 * 再看源图本身够不够大 ([ImageSharpnessSample.sourceDrawScale]), 把"被放大"分成两类:
 * [ImageSharpnessVerdict.SOURCE_LIMITED] 是源图就那么小, 无解;
 * [ImageSharpnessVerdict.UNDERSAMPLED] 是**源图够大却解小了**, 这才是我们自己的 bug.
 *
 * 纯函数, 便于直接对着尺寸做单测 (见 `AniImageSharpnessTest`).
 *
 * @param box 这张图要显示成多大 —— 由请求自己带着 (见 [aniDisplaySize]), 不从 `size()` 反推:
 *   `size()` 里含过采样倍数, 反推错了整个度量就是错的.
 * @return `null` 表示这次加载不适合度量 (尺寸缺失, 或是预取用的 1×1 请求).
 */
internal fun measureImageSharpness(
    uri: String,
    source: SketchSize,
    decoded: SketchSize,
    box: SketchSize,
    precision: Precision,
    decodeMillis: Long,
    dataFrom: String,
): ImageSharpnessSample? {
    if (source.width <= 0 || source.height <= 0) return null
    if (decoded.width <= 0 || decoded.height <= 0) return null
    if (box.width < MIN_MEASURABLE_BOX || box.height < MIN_MEASURABLE_BOX) return null
    // query/fragment 不进日志: host+path 足够对上是哪张图, 而 query 里可能带签名/token
    // (Jellyfin 一类自建源的图 URL 就有), 汇总里的 worst 与慢解码行都会把 URL 原样打出去.
    @Suppress("NAME_SHADOWING")
    val uri = uri.substringBefore('?').substringBefore('#')

    // 裁剪类 (Crop / FillBounds) 铺满整个显示框, 缩放倍数取两维的**大**者; 其余 (Fit) 是装进框里,
    // 取**小**者 —— Fit 的图有一维小于框是它本来的样子, 不是模糊. 这个映射与
    // [configureAniImageRequest] 里"按 contentScale 配精度"一一对应, 改那里要一起改这里.
    val cropLike = precision == Precision.EXACTLY || precision == Precision.SAME_ASPECT_RATIO
    val drawScale = drawScaleOf(decoded, box, cropLike)
    val sourceDrawScale = drawScaleOf(source, box, cropLike)
    val verdict = when {
        drawScale <= SHARPNESS_TOLERANCE ->
            if (drawScale < OVERSAMPLE_THRESHOLD) ImageSharpnessVerdict.OVERSAMPLED
            else ImageSharpnessVerdict.EXACT

        sourceDrawScale > SHARPNESS_TOLERANCE -> ImageSharpnessVerdict.SOURCE_LIMITED
        else -> ImageSharpnessVerdict.UNDERSAMPLED
    }
    val decodedPixels = decoded.width.toLong() * decoded.height
    val displayedPixels = if (cropLike) {
        box.width.toLong() * box.height
    } else {
        // Fit: 画上去的是解码尺寸按 drawScale 缩放之后的样子
        (decodedPixels * drawScale.toDouble() * drawScale.toDouble()).roundToLong()
    }
    return ImageSharpnessSample(
        uri = uri,
        source = source,
        decoded = decoded,
        box = box,
        precision = precision,
        drawScale = drawScale,
        sourceDrawScale = sourceDrawScale,
        verdict = verdict,
        decodedPixels = decodedPixels,
        displayedPixels = displayedPixels.coerceAtLeast(1L),
        decodeMillis = decodeMillis,
        dataFrom = dataFrom,
    )
}

private fun drawScaleOf(image: SketchSize, box: SketchSize, cropLike: Boolean): Float {
    val horizontal = box.width.toFloat() / image.width
    val vertical = box.height.toFloat() / image.height
    return if (cropLike) max(horizontal, vertical) else min(horizontal, vertical)
}

/**
 * 进程级累计统计, 定期打进日志.
 *
 * 日志出口 (release 包也有, logcat 与 app.log 都能看; 见 `AndroidLoggingConfigurator` 的 root=TRACE):
 * - `adb logcat -s ImageSharpness`, 或 `grep Sharpness app.log`;
 * - 每 [SHARPNESS_SUMMARY_INTERVAL] 次解码一条累计汇总 (平均倍数、四类计数、多解了多少像素、解码耗时);
 * - 欠采样立刻单独一条 warn, 带 URL 与源图/解码/显示三个尺寸.
 *
 * 汇总字符串在锁内拼好、锁外打印 —— 别让日志的文件 IO 卡住解码线程.
 */
internal object ImageSharpnessLog {
    private val lock = SynchronizedObject()

    private var decodes = 0
    private val verdicts = IntArray(ImageSharpnessVerdict.entries.size)
    private var drawScaleSum = 0.0
    private var decodedPixels = 0L
    private var displayedPixels = 0L
    private var decodeMillisSum = 0L
    private var decodeMillisMax = 0L
    private var worst: ImageSharpnessSample? = null
    private var slowLogged = 0
    private var unmeasured = 0

    /** 最近一次度量结果, 只给测试用. */
    var lastSample: ImageSharpnessSample? = null
        private set

    fun record(sample: ImageSharpnessSample) {
        var summary: String? = null
        var logSlow = false
        var slowestSoFar = false
        synchronized(lock) {
            lastSample = sample
            decodes++
            verdicts[sample.verdict.ordinal]++
            drawScaleSum += sample.drawScale
            decodedPixels += sample.decodedPixels
            displayedPixels += sample.displayedPixels
            decodeMillisSum += sample.decodeMillis
            val isSlowest = sample.decodeMillis > decodeMillisMax
            decodeMillisMax = max(decodeMillisMax, sample.decodeMillis)
            val previousWorst = worst
            if (previousWorst == null || sample.drawScale > previousWorst.drawScale) worst = sample
            // 慢解码限频 (见 SLOW_LOG_MAX_PER_SUMMARY): 决定"记不记"在锁内, 真正写日志在锁外.
            // isSlowest 那一支不受配额约束 —— 汇总里的 max 必须能在日志里找到对应的那张图
            if (SHARPNESS_LOG_EVERY_DECODE ||
                (
                    sample.decodeMillis >= SLOW_DECODE_MILLIS &&
                        (isSlowest || slowLogged < SLOW_LOG_MAX_PER_SUMMARY)
                    )
            ) {
                slowLogged++
                logSlow = true
                slowestSoFar = isSlowest
            }
            if ((decodes + unmeasured) % SHARPNESS_SUMMARY_INTERVAL == 0) {
                summary = buildSummary()
                slowLogged = 0
            }
        }
        // 欠采样是唯一"我们能修"的模糊, 立刻单独报, 不用等汇总也不限频 (它本该是零)
        if (sample.verdict == ImageSharpnessVerdict.UNDERSAMPLED) {
            logger.warn { "Sharpness: UNDERSAMPLED (blurry but avoidable) ${sample.detail()}" }
        } else if (logSlow) {
            // "slowest so far" = 这条就是汇总里那个 max 对应的图 (grep 得到)
            val label = if (slowestSoFar) "slowest so far" else "slow decode"
            logger.info { "Sharpness: $label ${sample.verdict} ${sample.detail()}" }
        }
        summary?.let { text -> logger.info { text } }
    }

    fun reset() = synchronized(lock) {
        decodes = 0
        verdicts.fill(0)
        drawScaleSum = 0.0
        decodedPixels = 0L
        displayedPixels = 0L
        decodeMillisSum = 0L
        decodeMillisMax = 0L
        worst = null
        slowLogged = 0
        unmeasured = 0
        lastSample = null
    }

    /** 当前累计汇总, 与日志里那条同源. */
    fun summary(): String = synchronized(lock) { buildSummary() }

    fun verdictCount(verdict: ImageSharpnessVerdict): Int = synchronized(lock) { verdicts[verdict.ordinal] }

    /** 量到的解码次数 (= 真实解码次数, 缓存命中不算). */
    fun decodeCount(): Int = synchronized(lock) { decodes }

    /** 本汇总周期内单独记了几条慢解码, 只给测试用 (见 [SLOW_LOG_MAX_PER_SUMMARY]). */
    fun slowLoggedCount(): Int = synchronized(lock) { slowLogged }

    /** 量不了清晰度的解码次数 (请求没声明显示尺寸), 只给测试用. */
    fun unmeasuredCount(): Int = synchronized(lock) { unmeasured }

    /**
     * 记一次"算不出清晰度但确实发生了"的解码.
     *
     * 只进两处: 累计汇总里的 `unmeasured=N` (回答"这批图到底解没解、解了几次"), 以及慢的那几条
     * 单独记行 (与慢解码共用配额 [SLOW_LOG_MAX_PER_SUMMARY]). **不进** 平均倍数与四类计数 ——
     * 没有显示尺寸就没有 drawScale, 硬凑一个数只会污染那张表.
     */
    fun recordUnmeasured(
        uri: String,
        source: SketchSize,
        decoded: SketchSize,
        decodeMillis: Long,
        dataFrom: String,
    ) {
        var logLine: String? = null
        var summary: String? = null
        synchronized(lock) {
            unmeasured++
            if (decodeMillis >= SLOW_DECODE_MILLIS && slowLogged < SLOW_LOG_MAX_PER_SUMMARY) {
                slowLogged++
                logLine = "Sharpness: slow decode (no display size) src=${source.str()} " +
                        "decoded=${decoded.str()} from=$dataFrom dec+q=${decodeMillis}ms $uri"
            }
            // 与可度量的那些**共用同一个周期计数**: 否则只逛选集条时汇总永远等不到
            if ((decodes + unmeasured) % SHARPNESS_SUMMARY_INTERVAL == 0) {
                summary = buildSummary()
                slowLogged = 0
            }
        }
        logLine?.let { text -> logger.info { text } }
        summary?.let { text -> logger.info { text } }
    }

    private fun buildSummary(): String {
        // 一屏全是"算不出清晰度"的解码时 (比如只逛选集条, 全是剧照) 也要有汇总 —— 否则整段浏览
        // 在日志里一条不留, 又回到那次查不下去的处境
        if (decodes == 0) {
            return if (unmeasured == 0) {
                "Sharpness: no decode measured yet"
            } else {
                "Sharpness: $unmeasured decodes without display size (sharpness not measurable)"
            }
        }
        val avgDrawScale = (drawScaleSum / decodes).toFloat()
        val overDecode = ((decodedPixels.toDouble() / displayedPixels - 1) * 100).roundToInt()
        return buildString {
            append("Sharpness: $decodes decodes, avgDrawScale=x${avgDrawScale.format2()}")
            append(" (x1=${verdicts[ImageSharpnessVerdict.EXACT.ordinal]}")
            append(" over=${verdicts[ImageSharpnessVerdict.OVERSAMPLED.ordinal]}")
            append(" srcSmall=${verdicts[ImageSharpnessVerdict.SOURCE_LIMITED.ordinal]}")
            append(" UNDERSAMPLED=${verdicts[ImageSharpnessVerdict.UNDERSAMPLED.ordinal]})")
            append("; pixels decoded=${decodedPixels.megaPixels()}MP shown=${displayedPixels.megaPixels()}MP")
            append(" (over-decode $overDecode%)")
            append("; decode+queue avg=${decodeMillisSum / decodes}ms max=${decodeMillisMax}ms")
            // 算不出清晰度但确实解了的那些 (剧照那条路, 见 recordUnmeasured): 只报个数 ——
            // "这批图到底解没解、解了几次"就靠它
            if (unmeasured > 0) append("; unmeasured=$unmeasured")
            worst?.let { append("; worst ${it.verdict} ${it.detail()}") }
        }
    }
}

/**
 * 在解码环节量清晰度 (见 [measureImageSharpness] 与 [ImageSharpnessLog]).
 *
 * [sortWeight] 夹在 sketch 自己的 `FetcherInterceptor` (90) 与 `DecoderInterceptor` (100) 之间,
 * 于是: 数据已经拿到、解码还没开始 —— 计时器量的**只有解码**这一段; 而内存缓存 (15) 与结果缓存
 * (45) 命中根本走不到这里, 计数天然等于真实解码次数.
 *
 * 只量**声明了显示尺寸**的请求 ([aniDisplaySize]); 预取那种"只为把字节落进磁盘缓存"的请求
 * (`size(1,1)`) 没有显示尺寸, 直接跳过.
 */
internal data object AniImageSharpnessInterceptor : Interceptor {
    override val key: String? = null // 不参与请求/缓存键: 它只观察, 不改变结果
    override val sortWeight: Int = 95

    override suspend fun intercept(chain: Interceptor.Chain): Result<ImageData> {
        val startedAt = TimeSource.Monotonic.markNow()
        val result = chain.proceed(chain.request)
        val data = result.getOrNull() ?: return result
        val elapsed = startedAt.elapsedNow()
        // 诊断绝不能把图片加载搞挂
        runCatching {
            val box = chain.request.aniDisplaySize()
            if (box == null) {
                // 没声明显示尺寸的解码: 清晰度算不出来 (见 measureImageSharpness 的 box), 但**解码
                // 本身要留痕** —— 剧照那条路 (Size.Origin, 请求与布局无关, 不能挂 extras, 见
                // configureAniImageRequest) 全在这里, 2026-08-21 排查"剧照整屏不显示"时正是因为
                // 这批解码在日志里彻底隐形才查不下去.
                ImageSharpnessLog.recordUnmeasured(
                    uri = chain.request.uri.toString().substringBefore('?').substringBefore('#'),
                    source = SketchSize(data.imageInfo.width, data.imageInfo.height),
                    decoded = SketchSize(data.image.width, data.image.height),
                    decodeMillis = elapsed.inWholeMilliseconds,
                    dataFrom = data.dataFrom.name,
                )
                return@runCatching
            }
            measureImageSharpness(
                uri = chain.request.uri.toString(),
                source = SketchSize(data.imageInfo.width, data.imageInfo.height),
                decoded = SketchSize(data.image.width, data.image.height),
                box = box,
                precision = data.resize.precision,
                decodeMillis = elapsed.inWholeMilliseconds,
                dataFrom = data.dataFrom.name,
            )?.let(ImageSharpnessLog::record)
        }
        return result
    }
}

private const val ANI_DISPLAY_SIZE_EXTRA = "aniDisplaySize"

/**
 * 把"这张图要显示成多大"挂在请求上 (`cacheKey`/`requestKey` 都传 null = **不参与任何键**,
 * 所以既不会让缓存失效, 也不会改变请求身份).
 *
 * 为什么不直接用请求里的 `size()`: 那个含过采样倍数 ([ANI_IMAGE_REQUEST_OVERSAMPLE], 桌面是 2),
 * 解码环节要反推就得知道调用方用的是哪个倍数 —— 反推错一次, 整张统计表就都是错的.
 */
internal fun ImageRequest.Builder.setAniDisplaySize(width: Int, height: Int) {
    setExtra(key = ANI_DISPLAY_SIZE_EXTRA, value = "${width}x$height", cacheKey = null, requestKey = null)
}

internal fun ImageRequest.aniDisplaySize(): SketchSize? {
    val raw = extras?.value<String>(ANI_DISPLAY_SIZE_EXTRA) ?: return null
    val width = raw.substringBefore('x').toIntOrNull() ?: return null
    val height = raw.substringAfter('x').toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return SketchSize(width, height)
}

private fun SketchSize.str(): String = "${width}x$height"

/** 保留两位小数: common 里没有 `String.format`. */
private fun Float.format2(): String {
    val scaled = (this * 100).roundToInt()
    return "${scaled / 100}." + (scaled % 100).toString().padStart(2, '0')
}

private fun Long.megaPixels(): String = ((this / 100_000.0).roundToInt() / 10.0).toString()

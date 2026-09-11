/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * 列表页 hero 背景 → 详情页全屏背景的**放大转场** (Google TV / Prime 点卡片进详情的观感).
 *
 * 列表页 (探索 / 追番 / 搜索) 的 backdrop 层与大标题把"此刻画着谁、哪张图、在屏幕哪个框里、标题写的什么"登记在这.
 * 导航那一刻 (Nav3 的转场规则, 见 AniAppContent 里 SubjectDetail 那条) 用 [willZoom] 判断会不会放大 —— 列表页正画着
 * 目标条目、且详情页会用同一张 URL (内存缓存里的同一张位图) —— 会就建一个 [Session].
 *
 * 放大由详情页的 `TvHeroZoomLayer` 那一层画: 它挂在"占位页 / 真页"切换之外, 从导航后第一帧起就按登记的框画同一张图,
 * 图一上屏就转不透明、列表页其余内容硬切 ([covering]), 同时开始放大; 真页来了在上面接着画大标题与侧边栏, 放大到位且
 * 自己的背景图就位后接手, 会话结束. 占位页换成真页时这一层不重建 —— 重建的新图片实例头一两帧是空的.
 *
 * 为什么不等真页: 真页要等 VM 出状态才组合 (实测 +137~250ms), 由它来画的话图要 +290~360ms 才上屏, 硬切只能拖到那时,
 * 期间要么露空 (整屏黑, 2026-09-10 录屏亮度 0) 要么卡片干等着 (用户: "为什么探索页不能立刻消失").
 *
 * 不用导航参数传: 详情页有多个入口 (卡片 / 搜索意图 / 深链), 逐条改签名不值得; 登记 + 匹配就够 —— 条目 id 与
 * URL 都对得上时, 无论从哪进来, 屏幕上那张图就是它. 匹配不上 (继续观看行的 hero 是单集剧照、详情页是整部 backdrop;
 * 展示层还没跟上焦点; 冷启动) 就走原来的交叉淡入.
 */
object TvHeroZoomHandoff {
    class Source(val subjectId: Int, val url: String, val bounds: Rect)

    private class TitleSource(val subjectId: Int, val bounds: Rect, val text: String)

    @Volatile
    private var source: Source? = null

    @Volatile
    private var titleSource: TitleSource? = null

    /**
     * 详情页背景 URL 的取法 (TmdbImageService.peekBackdropUrl), 由 TV 壳注册: [willZoom] 要在详情页组合之前就知道
     * "目标条目的背景是不是列表页此刻画着的这张".
     */
    @Volatile
    var detailsUrlProvider: ((Int) -> String?)? = null

    /**
     * 一次放大转场. 进度 [t] 由 `TvHeroZoomLayer` 按帧写 (放大那一层与两页的大标题都读它, 绘制里读), 从图上屏那一帧
     * ([start]) 起按时间推: 占位页换成真页时进度不断、不重来.
     */
    @Stable
    class Session internal constructor(
        val subjectId: Int,
        val url: String,
        /** 列表页 hero 图的框 (根坐标), 放大从这里起. */
        val bounds: Rect,
        /** 列表页大标题的框 (根坐标); 没登记到就 null (标题不平移). */
        val titleBounds: Rect?,
        /** 列表页大标题的文字: 占位页组合时连条目信息都还没有, 标题先用它. */
        val title: String?,
    ) {
        private var startNanos by mutableLongStateOf(0L)

        /** 图已上屏、放大已开始 (底色转不透明、列表页硬切都以它为准). */
        val started: Boolean get() = startNanos != 0L

        /** 放大进度 0..1 (已缓动). */
        var t: Float by mutableFloatStateOf(0f)

        /**
         * 详情页大标题的框 (根坐标), 最近一次量到的. 占位页换成真页那一帧, 新标题还没量出自己的框, 先用占位页量的这个
         * (两页标题同一位置), 否则那一帧只能不画标题.
         */
        var titleTarget: Rect? = null

        /** 图上屏那一帧调用 (传 withFrameNanos 的帧时间). 只认第一次. */
        fun start(frameNanos: Long) {
            if (startNanos == 0L) startNanos = frameNanos
        }

        /** 某一帧的进度: 未开始为 0; 按 [TV_HERO_ZOOM_MILLIS] 线性推进再过 [TvHeroZoomEasing]. */
        fun progress(frameNanos: Long): Float {
            if (startNanos == 0L) return 0f
            val linear = ((frameNanos - startNanos) / 1_000_000f / TV_HERO_ZOOM_MILLIS).coerceIn(0f, 1f)
            return TvHeroZoomEasing.transform(linear)
        }
    }

    /** 进行中的放大会话; null = 没有. */
    var session: Session? by mutableStateOf(null)
        private set

    /**
     * 被放大层盖住的导航条目 (列表页) 整页**硬切** (PageForegroundNavEntryDecorator 读它; alpha 0, HWUI 整层跳过,
     * 组合树还在, 返回零成本恢复): 放大那一层的图已经上屏、底色已不透明, 下面那份纯属白画. 用户 2026-09-10 对比过
     * 硬切 / 淡出 / 不处理, 选硬切. 绘制里读的快照状态.
     */
    val covering: Boolean get() = session?.started == true

    /**
     * 导航那一刻判断这一跳会不会放大 (Nav3 的转场规则里调, 会被求值不止一次): 会就建 [Session], 不淡入.
     * 同一条目的会话已经在了直接算会 (第二次求值时列表页的登记可能已被撤销).
     */
    fun willZoom(subjectId: Int): Boolean {
        session?.let { if (it.subjectId == subjectId) return true }
        val s = source
        val yes = TvPolishFlags.heroZoom && s != null &&
            s.subjectId == subjectId && s.url == detailsUrlProvider?.invoke(subjectId)
        session = if (yes && s != null) {
            val ts = titleSource?.takeIf { it.subjectId == subjectId }
            Session(subjectId, s.url, s.bounds, ts?.bounds, ts?.text)
        } else {
            null
        }
        return yes
    }

    /** 结束会话 (真页接手 / 放弃 / 详情页离开). 只结束传进来的那一个, 免得误伤后来新建的. */
    fun endSession(s: Session) {
        if (session === s) {
            session = null
        }
    }

    /** backdrop 层每次定位时登记 (在组合里读不到, 只在布局回调里写). */
    fun publish(subjectId: Int, url: String, bounds: Rect) {
        source = Source(subjectId, url, bounds)
    }

    /**
     * 列表页 hero 大标题每次定位时登记. 详情页标题从这个框平移到自己的位置, 与放大同步 —— 标题"不消失而是变过去"
     * (用户 2026-09-10). 两边都是 headlineLarge, 只差位置, 不缩放.
     */
    fun publishTitle(subjectId: Int, bounds: Rect, text: String) {
        titleSource = TitleSource(subjectId, bounds, text)
    }

    /** backdrop 层离开组合时撤销; 只撤自己登记的那张 (交叉淡出期间新旧两张共存, 旧的不能把新的抹掉). */
    fun retract(url: String) {
        if (source?.url == url) source = null
    }
}

/** 放大的缓动: M3 emphasized decelerate, 起步快、落地缓. */
val TvHeroZoomEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** 放大时长. */
const val TV_HERO_ZOOM_MILLIS = 400

/**
 * 放大那一跳导航转场撑多久 (旧页在组合里留多久): 要盖住"导航 → 放大那一层的图上屏"这一段 (一两帧, 冷的更晚),
 * 放大层透明等待期间底下一直有东西. 富余一些无妨: 硬切之后列表页整层跳过绘制.
 */
const val TV_HERO_ZOOM_NAV_HOLD_MILLIS = 700

/** 放大那一层等图上屏的预算, 从它第一帧画完起算: 超过就放弃放大 (图是后到的, 再放大只会突兀). 内存命中在一两帧内. */
const val TV_HERO_ZOOM_LOAD_BUDGET_MILLIS = 150L

/**
 * 放大进度过了这个值, 剩下的位移已经看不出来 ([TvHeroZoomEasing] 在六成时长就走完 97%, 余下约 160ms 只挪几个像素):
 * 详情页那几份几十毫秒的组合 (占位页换真页、首屏信息带) 挪到这之后做, 放大的快段一帧都不卡. 2026-09-10 追踪:
 * 真页组合原本落在放大开始后 40~170ms, 一帧 30~65ms, 正是位移最快的一段.
 */
const val TV_HERO_ZOOM_TAIL_T = 0.97f

/**
 * hero backdrop 是否按源图解码 (`AsyncImage(decodeAtOriginalSize = true)`): 列表页 hero 框与详情页全屏框尺寸不同,
 * sketch 的内存缓存键含请求尺寸, 同一张 w1280 会各解一份 —— 详情页首帧因此没图 (2026-09-10 录屏: 进页 450ms 后
 * 图才淡入, 放大转场全程空转). TMDB 的 w1280 只有 1280×720, 两个框都比它大, 按源图解码与按框解码得到的位图
 * 本来就一样, 钉成 Origin 只是让键相同. 竖版封面兜底 (Bangumi 原图, 最宽 2700px) 不走这条: Origin 会解出一张
 * 十几 MB 的位图.
 */
fun tvHeroBackdropDecodeAtOriginalSize(url: String): Boolean = "/t/p/w1280/" in url

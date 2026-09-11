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
import androidx.compose.ui.graphics.Color
import kotlin.concurrent.Volatile

/**
 * 列表页 hero 背景 → 详情页全屏背景的**放大转场** (Google TV / Prime 点卡片进详情的观感).
 *
 * 列表页 (探索 / 追番 / 搜索 / 时间表) 的 backdrop 层与大标题把"此刻画着谁、哪张图、在屏幕哪个框里、标题写的什么"登记在这.
 * 导航那一刻 (Nav3 的转场规则, 见 AniAppContent 里 SubjectDetail 那条) 用 [willZoom] 判断会不会放大 —— 列表页正画着
 * 目标条目、且详情页的背景 URL 已知 —— 会就建一个 [Session]. 放大的永远是列表页正画着的那张 (内存里的同一张位图);
 * 详情页的图若是另一张 (继续观看行的单集剧照 → 整部 backdrop), 到位后由详情页在上面淡入换图再接手 (见 [Session.detailsUrl]).
 *
 * 放大由详情页的 `TvHeroZoomLayer` 那一层画: 它挂在"占位页 / 真页"切换之外, 从导航后第一帧起就按登记的框画同一张图,
 * 图一上屏就转不透明、列表页其余内容硬切 ([covering]), 同时开始放大; 真页来了在上面接着画大标题与侧边栏, 放大到位且
 * 自己的背景图就位后接手, 会话结束. 占位页换成真页时这一层不重建 —— 重建的新图片实例头一两帧是空的.
 *
 * 为什么不等真页: 真页要等 VM 出状态才组合 (实测 +137~250ms), 由它来画的话图要 +290~360ms 才上屏, 硬切只能拖到那时,
 * 期间要么露空 (整屏黑, 2026-09-10 录屏亮度 0) 要么卡片干等着 (用户: "为什么探索页不能立刻消失").
 *
 * 不用导航参数传: 详情页有多个入口 (卡片 / 搜索意图 / 深链), 逐条改签名不值得; 登记 + 匹配就够 —— 条目 id 对得上
 * 时, 无论从哪进来, 屏幕上那张图就是它. 匹配不上 (展示层还没跟上焦点; 详情页背景还没解析过, 如冷启动) 就走原来的交叉淡入.
 */
object TvHeroZoomHandoff {
    class Source(val subjectId: Int, val url: String, val bounds: Rect, val dim: Color)

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
        /** 放大画的图: 列表页 hero 正画着的那张. */
        val url: String,
        /**
         * 详情页自己的背景图. 与 [url] 不同时 (继续观看行的单集剧照 → 整部 backdrop), 放大到位后详情页把它淡进来再
         * 接手, 不硬换图; 相同时接手那一帧直接换 (同一张图同一位置, 看不出来).
         */
        val detailsUrl: String,
        /** 列表页 hero 图的框 (根坐标), 放大从这里起. */
        val bounds: Rect,
        /** 列表页大标题的框 (根坐标); 没登记到就 null (标题不平移). */
        val titleBounds: Rect?,
        /** 列表页大标题的文字: 占位页组合时连条目信息都还没有, 标题先用它. */
        val title: String?,
        /**
         * 列表页盖在这张图上的整层压暗 (带不透明度的颜色; 时间表页的全屏背景有, hero 页没有 = [Color.Transparent]).
         * 放大层起跑那一帧照样压暗、随进度退到 0: 起跑那一帧与列表页一样暗, 不会先亮一下.
         */
        val dim: Color,
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

        /** 详情页正在淡入换图 (见 [detailsUrl]): 放大层的收场兜底不许中途结束会话, 否则淡到一半跳图. */
        var handingOver: Boolean = false

        /** 详情页这个导航条目的 contentKey (导航转场规则里记下, 见 [noteEntryKey]); 起跑时交给 [coverEntryKey]. */
        internal var entryKey: Any? = null

        /** 图上屏那一帧调用 (传 withFrameNanos 的帧时间). 只认第一次. */
        fun start(frameNanos: Long) {
            if (startNanos == 0L) {
                startNanos = frameNanos
                entryKey?.let { coverEntryKey = it }
            }
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
     * 放大进来的那个详情页条目 (contentKey). 它还在栈顶时, 下面的列表页**接着不画** —— 会话在真页接手时就结束了
     * ([covering] 变回 false), 而导航转场要撑到 [TV_HERO_ZOOM_NAV_HOLD_MILLIS] 列表页才被移出组合: 中间 ~450~650ms
     * 整张列表页 (背景图 / 卡片行) 在不透明的详情页下面白画, 正好是首屏以下分帧组合、用户"停稳即可按"的那段
     * (2026-09-13 审查). 往前跳 (播放器) 或返回时栈顶一变就不再成立; 条目销毁后留着也无害, 由装饰器按时清掉.
     */
    var coverEntryKey: Any? by mutableStateOf(null)

    /** 导航转场规则里调: 这一跳会放大时记下详情页条目的 contentKey (见 [coverEntryKey]). */
    fun noteEntryKey(subjectId: Int, entryKey: Any?) {
        session?.takeIf { it.subjectId == subjectId }?.entryKey = entryKey
    }

    /**
     * 导航那一刻判断这一跳会不会放大 (Nav3 的转场规则里调, 会被求值不止一次): 会就建 [Session], 不淡入.
     * 同一条目的会话已经在了直接算会 (第二次求值时列表页的登记可能已被撤销).
     */
    fun willZoom(subjectId: Int): Boolean {
        session?.let { if (it.subjectId == subjectId) return true }
        val s = source
        // 详情页背景: "" = 确认无 TMDB 图 (详情页回落竖版封面, 地址这里拿不到), null = 本进程没解析过; 两者都不放大
        val detailsUrl = detailsUrlProvider?.invoke(subjectId)?.takeIf { it.isNotEmpty() }
        val yes = TvPolishFlags.heroZoom && s != null && s.subjectId == subjectId && detailsUrl != null
        session = if (yes && s != null && detailsUrl != null) {
            val ts = titleSource?.takeIf { it.subjectId == subjectId }
            Session(subjectId, s.url, detailsUrl, s.bounds, ts?.bounds, ts?.text, s.dim)
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

    /** backdrop 层每次定位时登记 (在组合里读不到, 只在布局回调里写). [dim] 见 [Session.dim]. */
    fun publish(subjectId: Int, url: String, bounds: Rect, dim: Color = Color.Transparent) {
        source = Source(subjectId, url, bounds, dim)
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

/**
 * 放大的缓动: 照 iOS 打开 app 的窗口展开拟合 (2026-09-13 用户给的录屏逐帧量: 50%@124 / 90%@246 / 97%@305ms, 最贴合三次贝塞尔
 * (0.3, 0.2, 0.3, 1)): 起步两三帧平滑加速 (首帧只走 ~3%), 中段匀速, 落地长而缓. 原来的 M3 emphasized decelerate 从最高速起步,
 * 缩到 200ms 时首帧就走 58% 位移, 看着是"跳过去" (用户: 很快但观感不好).
 */
val TvHeroZoomEasing = CubicBezierEasing(0.3f, 0.2f, 0.3f, 1f)

/**
 * 放大时长. 250 = 用户在 2×2 录屏对比里选的 (iOS 曲线 250 / 300、M3 emphasized 250 / 300). 这条曲线 ~200ms 走完 90%, 余下是
 * 减速落地, UI 在落地途中就出现 (见 [TV_HERO_ZOOM_REVEAL_T]). Shield AOT 实测: 按键后 ~294ms 出 UI, ~350ms 完全停住.
 * (时长本身的下限另测过: 首屏最早按键后 ~200ms 才组合得完, 再短 UI 也不会更早.)
 */
const val TV_HERO_ZOOM_MILLIS = 250

/**
 * 放大那一跳导航转场撑多久 (旧页在组合里留多久): 要盖住"导航 → 放大那一层的图上屏"这一段 (一两帧, 冷的更晚),
 * 放大层透明等待期间底下一直有东西. 富余一些无妨: 硬切之后列表页整层跳过绘制.
 */
const val TV_HERO_ZOOM_NAV_HOLD_MILLIS = 700

/** 放大那一层等图上屏的预算, 从它第一帧画完起算: 超过就放弃放大 (图是后到的, 再放大只会突兀). 内存命中在一两帧内. */
const val TV_HERO_ZOOM_LOAD_BUDGET_MILLIS = 150L

/**
 * 放大进度 (位移) 过了这个值才做详情页那两份几十毫秒的组合 (占位页换真页 ~30ms、首屏信息带预组合 ~35ms), 放大的快段
 * 一帧都不卡. 2026-09-10 追踪: 真页组合原本落在放大开始后 40~170ms, 一帧 30~65ms, 正是位移最快的一段.
 * 取 0.93 而不是更晚: 从这里到 UI 能显示要 ~60ms (换真页一帧 + 预组合一帧 + 显示一帧), 得赶在 [TV_HERO_ZOOM_REVEAL_T]
 * 之前. 代价是换真页那帧落在位移 93~97%, 画面停 ~30ms (跳 ~4% 位移, 慢放看得出, 正常速度不明显; 2026-09-13 实测).
 */
const val TV_HERO_ZOOM_TAIL_T = 0.93f

/**
 * 放大进度 (位移) 到这就显示详情页首屏信息带 (按钮 / 标签 / 评分), 放大层照常画完最后一段: 用户要"停稳就能直接按,
 * 不要等一下按钮" (2026-09-13). 会话仍在到位且真页背景就绪后才结束 (撤放大层、放行首屏以下区块都跟着会话走).
 */
const val TV_HERO_ZOOM_REVEAL_T = 0.97f

/**
 * hero backdrop 是否按源图解码 (`AsyncImage(decodeAtOriginalSize = true)`): 列表页 hero 框与详情页全屏框尺寸不同,
 * sketch 的内存缓存键含请求尺寸, 同一张 w1280 会各解一份 —— 详情页首帧因此没图 (2026-09-10 录屏: 进页 450ms 后
 * 图才淡入, 放大转场全程空转). TMDB 的 w1280 只有 1280×720, 两个框都比它大, 按源图解码与按框解码得到的位图
 * 本来就一样, 钉成 Origin 只是让键相同. 竖版封面兜底 (Bangumi 原图, 最宽 2700px) 不走这条: Origin 会解出一张
 * 十几 MB 的位图.
 */
fun tvHeroBackdropDecodeAtOriginalSize(url: String): Boolean = "/t/p/w1280/" in url

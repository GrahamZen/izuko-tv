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
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.sin

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
    class Source(
        val subjectId: Int,
        val url: String,
        val bounds: Rect,
        val dim: Color,
        val owner: Any?,
        /** 这张图上压着的那三条渐变 (见 [TvBackdropTreatment]); null = 没有 (时间表页那种只有一层均匀压暗). */
        val treatment: TvBackdropTreatment? = null,
    )

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
        /** 列表页那张图上压着的三条渐变 (见 [TvBackdropTreatment]): 起跑那一帧照它复现, 随进度退掉. */
        val treatment: TvBackdropTreatment?,
    ) {
        private var startNanos by mutableLongStateOf(0L)

        /** 图已上屏、放大已开始 (底色转不透明、列表页硬切都以它为准). */
        val started: Boolean get() = startNanos != 0L

        /** 放大进度 0..1 (已缓动). */
        var t: Float by mutableFloatStateOf(0f)

        /** 本帧的**线性**进度 0..1 (未过缓动), 由 [progress] 顺带写. 见 [scrimAlpha]. */
        var linear: Float by mutableFloatStateOf(0f)
            private set

        /**
         * 详情页整屏底色此刻的不透明度: 前 [TvPolishFlags.zoomScrimT] 段**时间**里从 0 涨到 1, 之后恒 1.
         * 没起跑时为 0 —— 按确认到图上屏之间占位页什么都不该露 (侧边栏也跟着它, 见占位页).
         *
         * **按 [linear] 算而不是按 [t]**: t 已经过了放大缓动, 前段很快 (首帧 5.9%、次帧 14.9%), 挂在它上面的话
         * 0.35 折算下来约 57ms (四帧) 就满了, 看着仍旧是"一瞬间整个出现"(用户 2026-09-16 第一次改完后的反馈).
         */
        val scrimAlpha: Float
            get() {
                if (!started) return 0f
                val seg = TvPolishFlags.zoomScrimT
                return if (seg <= 0f) 1f else (linear / seg).coerceAtMost(1f)
            }

        /**
         * 详情页大标题的框 (根坐标), 最近一次量到的. 占位页换成真页那一帧, 新标题还没量出自己的框, 先用占位页量的这个
         * (两页标题同一位置), 否则那一帧只能不画标题.
         */
        var titleTarget: Rect? = null

        /** 详情页正在淡入换图 (见 [detailsUrl]): 放大层的收场兜底不许中途结束会话, 否则淡到一半跳图. */
        var handingOver: Boolean = false

        /** 换图的放大 ([url] ≠ [detailsUrl]) 已在途中压暗换成详情页那张 (见 tvHeroSwapDim): 落地后直接接手, 不再淡入. */
        var swapped: Boolean by mutableStateOf(false)

        /** 详情页这个导航条目的 contentKey (导航转场规则里记下, 见 [noteEntryKey]); 起跑时交给 [coverEntryKey]. */
        var entryKey: Any? = null
            internal set

        /** 图上屏那一帧调用 (传 withFrameNanos 的帧时间). 只认第一次. */
        fun start(frameNanos: Long) {
            if (startNanos == 0L) {
                startNanos = frameNanos
                entryKey?.let {
                    coverEntryKey = it
                    startedEntryKeys = startedEntryKeys + it
                    zoomRecords[it] = this
                }
                lastZoom = this
                standbyUrl = url
                standbyDetailsUrl = detailsUrl
                standbyBounds = bounds
            }
        }

        /** 某一帧的进度: 未开始为 0; 按 [TV_HERO_ZOOM_MILLIS] 线性推进再过 [TvHeroZoomEasing]. */
        fun progress(frameNanos: Long): Float {
            if (startNanos == 0L) return 0f
            val u = ((frameNanos - startNanos) / 1_000_000f / TV_HERO_ZOOM_MILLIS).coerceIn(0f, 1f)
            linear = u // 底色渐入按时间走, 见 [scrimAlpha]
            return TvHeroZoomEasing.transform(u)
        }
    }

    /** 进行中的放大会话; null = 没有. */
    var session: Session? by mutableStateOf(null)
        private set

    /**
     * 被放大层盖住的导航条目 (列表页) 整页**硬切** (PageForegroundNavEntryDecorator 读它; alpha 0, HWUI 整层跳过,
     * 组合树还在, 返回零成本恢复): 放大那一层的图已经上屏、底色已不透明, 下面那份纯属白画. 用户 2026-09-10 对比过
     * 硬切 / 淡出 / 不处理, 选硬切. 绘制里读的快照状态. 返回缩回 ([shrink]) 的图上屏后同理, 直到缩回框里.
     */
    val covering: Boolean
        get() = session?.let { it.started && it.scrimAlpha >= 1f } == true ||
                shrink?.let { it.armed && !it.revealed && it.scrimAlpha >= 1f } == true

    /**
     * 放大的整屏底色已经不透明 (没有会话 / 没起跑时为 true = 沿用原口径).
     *
     * 底色还在渐入的那一段, 下面的列表页必须**接着画** —— 否则渐入的是"底色盖在黑上", 与硬切没区别.
     * 读点: [covering] 与 `TvZoomStackScene` 里藏列表页那一处.
     */
    val scrimOpaque: Boolean get() = session?.takeIf { it.started }?.let { it.scrimAlpha >= 1f } ?: true

    /**
     * 缩回的尾段: 整屏底色正在化开, **下面那页 (列表页) 要提前画出来**, 而栈顶那页照旧不画.
     * 与 [scrimOpaque] 是一对: 放大那头管"还没盖满", 这头管"已经在化开".
     */
    val shrinkRevealing: Boolean
        get() = shrink?.let { it.armed && !it.revealed && it.scrimAlpha < 1f } == true

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
     * 放大进来的详情页条目 (contentKey): 由叠放布局 (AniAppContent 的 TvZoomStackScene) 在它第一次到栈顶时判定一次 ([decideZoomEntry]),
     * 之后一直算, 直到出栈 ([retainEntries]). 叠放布局让来源列表页常驻组合、垫在它下面, 返回缩回落地时列表页现成, 不重建
     * (2026-09-15 用户: 放大完成后返回要跟放大途中返回一样快). 进播放器再回来按返回照样缩回也靠它 (页面组合是新的, 记不住).
     */
    private val zoomEntryKeys = mutableSetOf<Any>()
    private val decidedEntryKeys = mutableSetOf<Any>()

    /** [zoomEntryKeys] 里放大真起跑过的 (图上过屏): 叠放布局据此让下面的列表页不画. 没起跑就放弃的, 列表页照常画在淡入的详情页下面. */
    var startedEntryKeys: Set<Any> by mutableStateOf(emptySet())
        private set

    /**
     * 栈顶变了: 把**不再在栈顶**的条目从 [startedEntryKeys] 里撤掉 (PageForegroundNavEntryDecorator 每次栈变化时调).
     *
     * "放大进来的详情页盖着列表页, 列表页不用画"只在它还在栈顶时成立。出栈之后 Nav3 还会把它留在场景里走完退场
     * 转场 (几百毫秒), 这期间标记若还在, 叠放布局就继续让列表页整层不画 —— 画面上只剩根部那层背景图, 侧边栏、
     * hero 文字、整排卡片全没有, 观感就是"返回之后列表黑了一下" (用户 2026-09-16 报, 逐帧实测黑 1.2 秒).
     */
    fun releaseStartedExcept(top: Any?) {
        if (startedEntryKeys.isEmpty()) return
        if (startedEntryKeys.size == 1 && top != null && top in startedEntryKeys) return
        startedEntryKeys = startedEntryKeys.filterTo(HashSet()) { it == top }
    }

    /** 叠放布局算布局时调: [entryKey] 这个详情页条目是不是放大进来的. 每个条目只真判一次 (布局会被反复计算, 会话结束后 [willZoom] 就不成立了). */
    fun decideZoomEntry(entryKey: Any, subjectId: Int): Boolean {
        if (entryKey in zoomEntryKeys) return true
        if (!decidedEntryKeys.add(entryKey)) return false
        if (!willZoom(subjectId)) return false
        noteEntryKey(subjectId, entryKey)
        zoomEntryKeys += entryKey
        return true
    }

    fun isZoomEntry(entryKey: Any?): Boolean = entryKey != null && entryKey in zoomEntryKeys

    /**
     * 每个放大进来的详情页条目**自己那一次**放大 (起跑时记下): 缩回用它 (框 / 图 / 进度), 不用全局最近一次 [lastZoom] —— 列表页放大进 A → A 进搜索 →
     * 搜索页又放大进 A, 退回第一个 A 时按最近一次会缩向搜索页 hero 的位置 (2026-09-15 审查).
     */
    private val zoomRecords = mutableMapOf<Any, Session>()

    /** 返回栈变化时调 (页面装饰器): 只留还在栈里的条目. */
    fun retainEntries(keys: Set<Any>) {
        zoomEntryKeys.retainAll(keys)
        decidedEntryKeys.retainAll(keys)
        zoomRecords.keys.retainAll(keys)
        if (!keys.containsAll(startedEntryKeys)) startedEntryKeys = startedEntryKeys.filterTo(HashSet()) { it in keys }
    }

    /**
     * 导航那一刻判断这一跳会不会放大 (Nav3 的转场规则里调, 会被求值不止一次): 会就建 [Session], 不淡入.
     * 同一条目的会话已经在了直接算会 (第二次求值时列表页的登记可能已被撤销).
     */
    fun willZoom(subjectId: Int): Boolean {
        session?.let { if (it.subjectId == subjectId) return true }
        // 缩回还没收场就又进了一个详情页 (缩回后马上再按确认): 撤掉缩回, 让新的一次照常放大 —— 否则缩回层还盖着、两页都不画
        shrink?.let { endShrink(it) }
        val s = source
        // 详情页背景: "" = 确认无 TMDB 图 (详情页回落竖版封面, 地址这里拿不到), null = 本进程没解析过; 两者都不放大
        val detailsUrl = detailsUrlProvider?.invoke(subjectId)?.takeIf { it.isNotEmpty() }
        val yes = TvPolishFlags.heroZoom && s != null && s.subjectId == subjectId && detailsUrl != null
        session = if (yes && s != null && detailsUrl != null) {
            val ts = titleSource?.takeIf { it.subjectId == subjectId }
            Session(subjectId, s.url, detailsUrl, s.bounds, ts?.bounds, ts?.text, s.dim, s.treatment)
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

    /** 最近一次起跑的放大 (起跑时记下): 真页接手、会话结束后按返回缩回去, 还要知道是哪张图. 能不能用由 [canShrink] 判断. */
    internal var lastZoom: Session? = null

    /**
     * 放大进来的详情页, 在列表页还没被移出组合时按返回: 反向**缩回**列表页 hero 框 (撤销的观感), 由根部的 `TvHeroShrinkLayer`
     * 画 —— 详情页出栈就移出组合, 放大那一层随它销毁, 缩回去的这一层得在导航之外. 与放大对称: 全屏不透明底 + 缩小的图,
     * 两页都不画 ([covering]), 缩进框里那一帧硬切回列表页. 列表页全程是现成的 (还没被移出组合), 不用重建; 列表页离场后
     * 再返回只能照常淡出 (重建的列表页边缩边露会卡, 2026-09-13 测过).
     */
    @Stable
    class Shrink internal constructor(
        val subjectId: Int,
        /** 缩回去画的图: 列表页 hero 正画着的那张 (与放大时同一张). */
        val url: String,
        /**
         * 缩回开始时屏幕上那张: 一般同 [url]; 换图的情形 (继续观看, 详情页已是整部背景) 是详情页那张, 缩到一半压暗换回 [url]
         * (见 tvHeroSwapDim).
         */
        val startUrl: String,
        /** 列表页 hero 图此刻的框 (根坐标), 缩到这里. */
        val bounds: Rect,
        val dim: Color,
        /** 列表页那张图上压着的三条渐变 (同 [Session.scrim]): 缩回落地那一帧要与列表页对得上. */
        val treatment: TvBackdropTreatment?,
        /** 从哪一次放大缩回 (那一页的放大层据此停住; 别的放大 —— 如马上又进来的新一次 —— 不受影响). */
        val fromSession: Session?,
        /** 发起缩回的那个详情页导航条目: 出栈前核对栈顶仍然是它 (见 [pop]). */
        val entryKey: Any?,
        /**
         * 出栈. 默认 (新顺序) 在缩到位、静止之后才调 —— 页面切换 (详情页移出 / 列表页恢复或重建 / 焦点落位) 全挪出运动阶段,
         * 在不动的画面下做完, 列表页就绪再撤层; [TvPolishFlags.shrinkPopFirst] 时 (旧顺序, A/B 用) 在图上屏那一帧就调.
         */
        val onPop: () -> Unit,
        /**
         * 按返回那一刻**屏幕上** hero 背景的淡出程度 (0 = 全亮, 1 = 淡到 `HERO_BACKDROP_MIN_ALPHA`).
         *
         * 缩回层有自己的一份背景 (常驻 standby), 它的滚动量恒 0 ⇒ 平时算出来的淡出恒 0 = 最亮. 停在首屏按返回时这正好
         * 对得上; 但从第二页翻回首屏的那 450ms 里背景还在往亮里走, 这时按下第二次返回, 缩回层会**先把背景跳到全亮**再
         * 缩 (2026-09-16 审查推导, 代码核实: 缩回层的 `rememberScrollState()` 恒 0 且没接 `scrollFade`).
         * 记下当时的实际值, 缩回途中随进度化到 0 —— 落地时正好等于列表页 hero 的全亮, 两头都连得上.
         */
        val startFade: Float = 0f,
    ) {
        /** 图已上屏: 底色不透明, 一直盖到撤层. */
        var armed: Boolean by mutableStateOf(false)
            internal set

        /** 正在缩 (运动中): 两页都不画、栈顶那页不算前台 —— 运动期间不让任何页面干活. 落地时置 false (列表页随即恢复). */
        var moving: Boolean by mutableStateOf(false)

        /** 换图的缩回 ([startUrl] ≠ [url]) 已在途中压暗换回列表页那张. */
        var swapped: Boolean by mutableStateOf(false)

        /** 从这个进度缩起: 图上屏那一刻的放大进度 (放大还在跑时是它当前的值, 不倒退); 真页已接手 = 1. */
        var fromT: Float = 1f
            internal set

        /** 当前进度, [fromT] → 0 (与放大同一套几何, 0 = 列表页 hero 框). */
        var t: Float by mutableFloatStateOf(1f)

        /** 本帧的**线性**进度 0..1 (0 = 刚上屏, 1 = 缩到位), 缩回层每帧写. 见 [scrimAlpha]. */
        var linear: Float by mutableFloatStateOf(0f)

        /**
         * 整屏底色此刻的不透明度: 前段恒 1, **最后** [TvPolishFlags.zoomScrimT] 段时间里化到 0 —— 与放大那头对称.
         *
         * 原来是从上屏到撤层一直不透明地盖着, 最后一下子消失 (用户 2026-09-16: "缩小的时候也是一直在, 最后突然消失").
         * 化开这一段下面的列表页要**接着画** (见 [shrinkRevealing]), 否则化开的是"底色盖在黑上", 与硬切没区别.
         */
        val scrimAlpha: Float
            get() {
                if (!armed || revealed) return 0f
                val seg = TvPolishFlags.shrinkScrimT
                return if (seg <= 0f) 1f else ((1f - linear) / seg).coerceAtMost(1f)
            }

        /** 快速路径 ([TvPolishFlags.shrinkKeep] = 2) 落地后: 缩回层不再画, 下面的列表页露出来; 出栈与撤层随后. */
        var revealed: Boolean by mutableStateOf(false)

        private var popped = false

        /**
         * 出栈, 只认第一次 (几条收场路径都可能调), 且**只在栈顶仍是发起缩回的那一页时才退**.
         *
         * "只退一次"和"退对了页"是两件事: 缩回这两三百毫秒里导航栈可能被别人改掉 —— 手机控制中心
         * (局域网网页) 能触发搜索 / 播放跳转, 深链同理 —— 那时无条件 `popBackStack()` 退掉的是新页面.
         * 慢速淡出那条路径早就有同一道闸 (`backForeground` 判据), 快速路径缺了, 这里补上.
         *
         * 不记账地跳过: [hasPopped] 仍为 false, [endShrink] 于是会把只藏不销毁的详情页放出来, 不留一个隐身页.
         */
        fun pop() {
            if (popped) return
            val top = topEntryKey
            if (entryKey != null && top != null && top != entryKey) return
            popped = true
            onPop()
        }

        internal val hasPopped: Boolean get() = popped
    }

    /** 进行中的缩回; null = 没有. */
    var shrink: Shrink? by mutableStateOf(null)
        private set

    /** 缩回进行中: 两页都不做导航转场 (详情页立刻移出组合), 栈顶的列表页也不画, 直到缩回框里. */
    val shrinking: Boolean get() = shrink != null

    /**
     * 缩回的图已上屏 ([Shrink.armed]): 页面装饰器只认这个 (两页不画、栈顶列表页先不算前台). **不能用 [shrinking]**: 从按返回到
     * 图上屏还要一两帧 (加载 + 对齐帧头), 那几帧里两页已藏、缩回层还没出来 —— 整屏黑 (2026-09-14 用户: 放大途中按返回闪黑)
     */
    val shrinkArmed: Boolean get() = shrink?.armed == true

    /** 缩回运动中 ([Shrink.moving]): 页面装饰器据此让两页都不画、栈顶那页不算前台. 落地后等列表页就绪的那段不算 (列表页要恢复). */
    val shrinkMoving: Boolean get() = shrink?.moving == true

    /**
     * 条目 [subjectId] 的详情页此刻按返回能不能缩回去: 这次是放大进来、已经起跑的. 放大的是另一张图 (继续观看的单集剧照 → 整部背景)
     * 也缩: 缩到一半压暗换回列表页那张 (见 tvHeroSwapDim).
     */
    /**
     * 缩回时列表页大标题要额外让开的位移 (本元素坐标系): 从**详情页标题的位置**回到自己的位置, 与进入时
     * `tvHeroZoomTitleShift` 的平移正好反过来 —— 进入是"标题不消失而是变过去", 返回同理 (用户 2026-09-16).
     *
     * 由**列表页自己的标题**做这个位移, 而不是在缩回层另画一个: 缩回尾段列表页是画着的 (见 [shrinkRevealing]),
     * 另画一个就会两个标题重影.
     *
     * 自己的框直接取登记过的那份 ([publishTitle]): 登记发生在 `onGloballyPositioned`, 而本位移挂在它**之内**的
     * graphicsLayer 上, 所以登记的框不受位移影响, 不会自激. 没在缩回 / 不是这个条目 / 两边框没齐时给 null.
     */
    fun shrinkTitleOffset(subjectId: Int): Offset? {
        val s = shrink ?: return null
        if (s.subjectId != subjectId || !s.armed || s.revealed) return null
        val from = s.fromSession?.titleTarget ?: return null
        val own = titleSource?.takeIf { it.subjectId == subjectId }?.bounds ?: return null
        val p = (1f - s.t / s.fromT.coerceAtLeast(1e-3f)).coerceIn(0f, 1f) // 0 = 刚起步, 1 = 落位
        return Offset((from.left - own.left) * (1f - p), (from.top - own.top) * (1f - p))
    }

    /**
     * 缩回期间列表页标题要**停掉走马灯**(停掉即回到行首) 再做 [shrinkTitleOffset] 的平移.
     *
     * 否则: 标题正滚到中间时被拉去平移, 落位那一刻走马灯重新开始又跳回行首 —— 看起来闪一下
     * (用户 2026-09-16). 在组合里读, 一次返回只翻两次.
     */
    fun titleSettling(subjectId: Int): Boolean {
        val s = shrink ?: return false
        return s.subjectId == subjectId && s.armed && !s.revealed
    }

    fun canShrink(subjectId: Int, entryKey: Any?): Boolean {
        if (!TvPolishFlags.heroZoom || shrink != null) return false
        val z = zoomRecordFor(subjectId, entryKey) ?: return false
        // 本条目这次还没起跑 (图还在加载): lastZoom 是之前某一次的, 不算
        session?.let { if (it.subjectId == subjectId && it !== z) return false }
        // 旧顺序 (出栈后边缩边恢复) 要求列表页还在组合里; 新顺序落地后才出栈、等列表页就绪再撤层, 列表页离场 (要重建) 也能缩
        if (TvPolishFlags.shrinkPopFirst && source?.let { it.subjectId == subjectId && it.url == z.url } != true) return false
        return true
    }

    /** 开始缩回 (详情页的返回键调): 能缩就建 [Shrink] 返回 true, 由 `TvHeroShrinkLayer` 等图上屏后调 [Shrink.onArmed] 出栈. */
    fun beginShrink(subjectId: Int, entryKey: Any?, startFade: Float = 0f, onPop: () -> Unit): Boolean {
        if (!canShrink(subjectId, entryKey)) return false
        val z = zoomRecordFor(subjectId, entryKey) ?: return false
        // 列表页还在就用它此刻的框; 已离场就用起跑时记下的 (重建后的列表页排版不变)
        val s = source?.takeIf { it.subjectId == subjectId && it.url == z.url }
        // 此刻屏幕上是哪张: 同图就是它; 换图的放大还没换过去 (放大途中、也没在淡入) 是列表页那张; 否则 (已换 / 已接手) 是详情页那张
        val startUrl = when {
            z.url == z.detailsUrl -> z.url
            session === z && !z.swapped && !z.handingOver -> z.url
            else -> z.detailsUrl
        }
        shrink = Shrink(
            subjectId, z.url, startUrl, s?.bounds ?: z.bounds, s?.dim ?: z.dim,
            s?.treatment ?: z.treatment, z, entryKey, onPop, startFade,
        )
        return true
    }

    /**
     * 缩回时出栈的那个详情页条目 (contentKey): 它移出组合之前**一直不画** (PageForegroundNavEntryDecorator 读). 放大途中出栈时
     * 进页转场还没走完, Nav3 会把它倒着走完, 详情页要在组合里再留一阵; 缩回撤层后它若还在, 会以全屏背景图的样子现出来一下
     * (2026-09-14 用户: 缩回过程中横屏大图全屏闪一下). 条目销毁时由装饰器清掉.
     */
    var shrinkHiddenKey: Any? by mutableStateOf(null)

    /**
     * 快速路径 ([TvPolishFlags.shrinkKeep]) 缩回的详情页条目: **只藏不销毁** (装饰器: 不画、不算前台, 组合留着), 出栈时才移出组合 ——
     * 起步前那一帧不再有整页销毁. 与 [shrinkHiddenKey] (立刻移出组合) 分开. 条目销毁时由装饰器清掉.
     */
    var shrinkHideKey: Any? by mutableStateOf(null)

    /**
     * 返回栈栈顶那个条目的 contentKey (PageForegroundNavEntryDecorator 每次栈变化时写).
     *
     * 只给 [Shrink.pop] 的条件出栈用 —— 不能拿 `LocalPageIsForeground` 代替: 缩回期间藏起来的详情页
     * 被刻意算作"不在前台" (装饰器读 shrinkHideKey), 拿它判会永远不出栈.
     */
    var topEntryKey: Any? by mutableStateOf(null)

    /** 这个详情页条目那一次放大 ([zoomRecords]); 不知道条目时退回最近一次. */
    private fun zoomRecordFor(subjectId: Int, entryKey: Any?): Session? =
        (if (entryKey != null) zoomRecords[entryKey] else lastZoom)?.takeIf { it.subjectId == subjectId }

    /** 缩回的图上屏那一帧调 (两页随即不画). [keepDetails] = 快速路径: 详情页只藏不销毁 (见 [shrinkHideKey]). */
    fun armShrink(s: Shrink, keepDetails: Boolean = false) {
        // 用这次缩回绑定的那一次放大, 不再读全局最近一次
        val z = s.fromSession
        s.fromT = if (z != null && session === z) z.t else 1f
        s.t = s.fromT
        if (keepDetails) {
            shrinkHideKey = z?.entryKey
            // 放大接手后 ~1.2s 内它还可能在: 盖着列表页的依据改由缩回自己管, 否则落地露列表页时列表页仍被当作"被盖住"不画
            coverEntryKey = null
        } else {
            shrinkHiddenKey = z?.entryKey
        }
        s.moving = true
        s.armed = true
        // **缩回层上屏这一刻才结束那次放大的会话**: [covering] 的两项是"放大已起跑"与"缩回已上屏", 中间那段
        // (按了返回、缩回层还在等图与帧头, 最多 TV_HERO_ZOOM_LOAD_BUDGET_MILLIS) 若会话已经结束, 两项全假 ——
        // 详情页于是整页正常画出来, 首屏信息带 (圆钮 / 播放按钮) 当场冒出来, 随即又被缩回层盖掉, 观感是
        // "缩小途中按钮闪一下" (用户 2026-09-16). 放到这里结束, 画面已经被盖住, 不会漏出来;
        // 详情页那边则在缩回在途时跳过 endSession (见 SubjectDetailsTvPage 的接手 effect).
        if (z != null) endSession(z)
    }

    fun endShrink(s: Shrink) {
        if (shrink === s) shrink = null
        // 没走到上屏就收场 (图没就绪而放弃): 那次放大的会话还挂着, 不结束的话 [covering] 一直为真, 两页都不画 = 整屏黑
        s.fromSession?.let { if (session === it) endSession(it) }
        // 没走到出栈就收场 (缩回层被销毁、图没就绪而放弃、[Shrink.pop] 因栈顶变了而跳过):
        // 详情页还在栈里, 两个隐身标记都得收回去 —— 只收 shrinkHideKey 的话, 非快速路径
        // (详情页是被移出组合的 shrinkHiddenKey) 会因为永远不被销毁而没人清标记, 整页一直不组合
        if (!s.hasPopped) {
            val k = s.fromSession?.entryKey
            if (k != null && shrinkHideKey == k) shrinkHideKey = null
            if (k != null && shrinkHiddenKey == k) shrinkHiddenKey = null
        }
    }

    /**
     * 最近一次放大的那张图 (列表页 hero 那张) 与起始框: 缩回层平时就把它组合着、不画 (见 `TvHeroShrinkLayer`), 按返回时图已加载好,
     * 当帧上屏 —— 不必等请求状态 / 回调 / 新节点首绘, 也不再要求列表页还在组合里. 同一张内存缓存位图, 不另占一份.
     */
    var standbyUrl: String? by mutableStateOf(null)
        private set
    var standbyBounds: Rect? by mutableStateOf(null)
        private set

    /** 最近一次放大的详情页那张 (与 [standbyUrl] 不同 = 换图的放大, 缩回时从它起、途中换回 [standbyUrl]). */
    var standbyDetailsUrl: String? by mutableStateOf(null)
        private set

    /**
     * 加载过的列表页 hero 图 (条目, URL) —— 撤层前判"列表页就绪"用 (见 [listReady]).
     *
     * **必须是集合而不是一个槽位**: 原来只记最近一张, 而在列表里横移焦点时每张卡的 hero 图都会把它覆盖掉,
     * 于是返回一个**更早看过**的条目时判据永远为假, 每次都白等满 [TV_HERO_SHRINK_READY_TIMEOUT_MILLIS]
     * (2026-09-16 实测日志: 43 次缩回里 6 次超时, 诊断行都是"条目与 URL 都对上、loaded 指向另一个条目").
     * 这是"返回列表后黑一秒"那条修复的残余 —— 当时把兜底从 800ms 降到 250ms, 把一秒的黑压成顿一下, 逻辑错误还在.
     *
     * 记的是"这张图加载过"这个**事实**, 所以只进不出 (同 [retract] 的注释); 只按容量淘汰最旧的,
     * 容量取够一屏卡片横移的量即可. 读写都在主线程 (图的 onSuccess 与缩回层都是 Compose 回调), 不加锁.
     */
    private val sourceLoaded = LinkedHashSet<Pair<Int, String>>()

    /**
     * 列表页 hero 图加载好时调 (TvBackdropImage 的 onSuccess).
     *
     * **不记是谁写的**: 这条记的是"这张图加载过"这个事实, 与哪个组件写的无关 (见 [retract]).
     */
    fun markSourceLoaded(subjectId: Int, url: String) {
        val key = subjectId to url
        sourceLoaded.remove(key) // 重新插到末尾: 淘汰按"最久没再加载过"走
        sourceLoaded.add(key)
        while (sourceLoaded.size > SOURCE_LOADED_CAPACITY) {
            sourceLoaded.remove(sourceLoaded.first())
        }
    }

    /** 列表页此刻还在组合里, 且 hero 画的就是这一次缩回的那张 (同条目同图已登记). */
    fun listAlive(s: Shrink): Boolean = source?.let { it.subjectId == s.subjectId && it.url == s.url } == true

    /** 缩回落地、出栈后, 列表页就绪了没有: hero 已按同一条目同一张图登记, 且这张图已加载好 (重建的列表页也要等到这一步). */
    fun listReady(s: Shrink): Boolean = listAlive(s) && (s.subjectId to s.url) in sourceLoaded

    /**
     * 诊断用: [listReady] 等超时那一刻, 把"缩回要的"与"列表页实际登记的"都打出来.
     *
     * 超时 = 缩回层要多盖住画面 800ms (一整秒的黑, 用户 2026-09-16 实测), 而从外面完全看不出是哪一项对不上 ——
     * 条目对不上 / URL 对不上 / 登记还在但图没报加载好, 三种的修法完全不同. 留这一行, 下次不用再录屏猜.
     */
    fun sourceDebug(): String {
        fun String?.tail() = this?.takeLast(32) ?: "null"
        val loaded = sourceLoaded.toList()
        return "source=${source?.subjectId}:${source?.url.tail()} " +
                "loaded(${loaded.size})=${loaded.takeLast(4).joinToString { "${it.first}:${it.second.tail()}" }}"
    }

    /**
     * backdrop 层每次定位时登记 (在组合里读不到, 只在布局回调里写). [dim] 见 [Session.dim].
     *
     * [owner] = 登记方的身份 (backdrop 那个组件自己 remember 出来的一枚标记), 撤销时按它对认 —— 见 [retract].
     */
    fun publish(
        owner: Any,
        subjectId: Int,
        url: String,
        bounds: Rect,
        dim: Color = Color.Transparent,
        treatment: TvBackdropTreatment? = null,
    ) {
        source = Source(subjectId, url, bounds, dim, owner, treatment)
    }

    /**
     * 列表页 hero 大标题每次定位时登记. 详情页标题从这个框平移到自己的位置, 与放大同步 —— 标题"不消失而是变过去"
     * (用户 2026-09-10). 两边都是 headlineLarge, 只差位置, 不缩放.
     */
    fun publishTitle(subjectId: Int, bounds: Rect, text: String) {
        titleSource = TitleSource(subjectId, bounds, text)
    }

    /**
     * backdrop 层离开组合时撤销; **只撤自己登记的那份**.
     *
     * 原先按 URL 对认: 同一页内交叉淡出 (新旧两张 URL 不同) 是对的, 但**两个页面同时显示同一条目的同一张图**时
     * (换 tab 的那几帧, 探索页与追番页的 hero 可能是同一部), 离场那个会把留下那个的登记抹掉 —— 缩回于是
     * 判不出"列表页就绪", 要么白等到超时, 要么撤层时露出没画好的页面 (2026-09-15 审查). 按登记方身份对认就没有这个洞.
     */
    fun retract(owner: Any) {
        if (source?.owner === owner) source = null
        // **不清 [sourceLoaded]**: 它记的是"这张图加载过", 图还在内存缓存里, 下次显示当帧就能画出来 ——
        // 组件销毁不等于图没了. 清掉的代价是它**再也补不回来**: 补回来只能靠图片组件再报一次 onSuccess,
        // 而一张已经显示在屏幕上的图不会再加载一次 —— 于是这个条目的 [listReady] 永远为假, 每次返回缩回
        // 都等满 [TV_HERO_SHRINK_READY_TIMEOUT_MILLIS] 才撤层, 画面上就是"返回后黑一秒", 而且一旦触发
        // 就稳定复现、换个条目 (= 换 URL = 真的重新加载一次) 又自己好了 (用户 2026-09-16 报, 诊断日志
        // `listReady timeout ... loaded=null:null` 钉死). 留着旧值无害: [listReady] 还要求它与当前登记的
        // 条目和 URL 都对得上.
    }
}

/**
 * 放大的缓动: 照 iOS 打开 app 的窗口展开拟合 (2026-09-13 用户给的录屏逐帧量: 50%@124 / 90%@246 / 97%@305ms, 最贴合三次贝塞尔
 * (0.3, 0.2, 0.3, 1)): 起步两三帧平滑加速 (首帧只走 ~3%), 中段匀速, 落地长而缓. 原来的 M3 emphasized decelerate 从最高速起步,
 * 缩到 200ms 时首帧就走 58% 位移, 看着是"跳过去" (用户: 很快但观感不好).
 */
val TvHeroZoomEasing = CubicBezierEasing(0.3f, 0.2f, 0.3f, 1f)

/**
 * 返回缩回的缓动: 照 iOS 关闭 app (窗口缩回图标) 拟合 (用户 2026-09-15: "缩小动画的曲线接近就行, 其他按性能好的做"), 与放大 (打开)
 * 不是同一条. 录屏 rec/ios.mov 段 2 / 段 4 逐帧按模板匹配量窗口缩放 (ios_close_fit.py): 前 ~60ms 几乎不动 (10%@58), 中段近匀速
 * (50%@121 / 75%@158), ~220ms 化成图标 (97%@224) —— 起步慢、落地不拖. 原来直接用放大的曲线, 起步就快 (50%@78), 末尾长减速.
 */
val TvHeroShrinkEasing = CubicBezierEasing(0.45f, 0f, 0.45f, 0.9f)

/**
 * 起步抬升过的几档 (见 [TvPolishFlags.shrinkCurve]), 落地时刻与原曲线一致 (97% 都在 ~224ms), 只有前 50ms 不同:
 *
 * | 档 | 16.7ms | 33.3ms | 50ms | 50% 到 | 首帧边缘位移 |
 * |---|---|---|---|---|---|
 * | 0 原 | 0.7% | 3.0% | 7.3% | 121ms | 2.3px |
 * | 1 | 1.7% | 5.6% | 12.0% | 111ms | 5.5px |
 * | 2 | 2.6% | 8.0% | 16.0% | 103ms | 8.5px |
 * | 3 | 3.9% | 11.1% | 20.6% | 95ms | 12.8px |
 */
private val TvHeroShrinkEasingLifted = arrayOf(
    CubicBezierEasing(0.36f, 0.035f, 0.45f, 0.9f),
    CubicBezierEasing(0.30f, 0.055f, 0.44f, 0.9f),
    CubicBezierEasing(0.25f, 0.080f, 0.42f, 0.9f),
)

/** 本次缩回该用的缓动: 运动开始前取一次 (循环里每帧读开关没意义, 也不该中途换曲线). */
fun tvHeroShrinkEasing(): Easing =
    TvHeroShrinkEasingLifted.getOrNull(TvPolishFlags.shrinkCurve - 1) ?: TvHeroShrinkEasing

/**
 * 压在 backdrop 上的那套遮罩的**声明**: 一层均匀压暗 + 三条固定角色的有向渐变 (顶缘 / 左缘 / 下缘).
 *
 * **为什么要一份声明而不是各画各的**: 列表页与详情页原来各写各的一套 (列表页 4 层, 详情页 7~8 层), 放大转场靠
 * "旧那套按 1-t 退、新那套按 t 进"的交叉淡入把两边接起来 —— 而交叉淡入在中途是"两套各有一部分同时存在",
 * 这在数学上**不等于**"形状从 A 连续变到 B": 中间既不像列表页也不像详情页, 两端各留一个台阶
 * (2026-09-16 用户逐帧: 起跑那一帧右半区亮度掉 16%, 观感是"背景提前变成详情页的样子"; 连着三轮打补丁都没补住).
 *
 * 换成声明 + 插值之后: 转场画的是 `lerp(列表页那份, 详情页那份, t)` —— t=0 逐像素等于列表页, t=1 逐像素等于详情页,
 * 中间是**渐变带的位置与强度在连续变形**, 结构上不可能有台阶.
 *
 * 角色固定成三个槽 (而不是一个 List) 就是为了能按角色对齐插值; 某一侧没有某个角色时, **沿用另一侧的几何、强度从 0 起**,
 * 于是渐变带的位置不跳, 只是浓淡在变.
 */
@Immutable
data class TvBackdropTreatment(
    /** 整层均匀压暗 (时间表页的全屏背景 / 列表页"按下即压暗"). */
    val dim: Color = Color.Transparent,
    /** 顶缘可读性 scrim: 从上缘起衰减到透明. */
    val top: TvBackdropFade? = null,
    /** 左缘渐隐: 从左缘起衰减到透明. */
    val left: TvBackdropFade? = null,
    /** 下缘渐隐: 从 [TvBackdropFade.start] 起加深到下缘. */
    val bottom: TvBackdropFade? = null,
    /**
     * 下缘用 DstOut **擦掉图自身的透明度**而不是盖一层色: 详情页静止态用它露出下层的动态渐变背景 (盖纯色的话浅色主题
     * 下是一片突兀的纯白). 有纯色垫底时 (放大 / 缩回那一层) 改画同色渐变, 逐像素相同却不必开离屏缓冲, 所以转场途中恒 false.
     */
    val bottomDstOut: Boolean = false,
)

/**
 * 一条有向渐变: 在 [start]..[end] 这一段里由 [maxAlpha] 的 [color] 渐变到透明 (顶缘 / 左缘), 或反过来由透明
 * 渐变到 [maxAlpha] (下缘, 见 [toEdge]). 坐标是整层的 0..1 比例, 与层的实际尺寸无关 —— 放大途中层在缩放,
 * 比例坐标保证渐变带跟着图一起变形, 与列表页那张对得上.
 */
@Immutable
data class TvBackdropFade(
    val start: Float,
    val end: Float,
    val maxAlpha: Float,
    val color: Color,
    /** true = 由透明加深到 [maxAlpha] (下缘那种); false = 由 [maxAlpha] 衰减到透明 (顶缘 / 左缘那种). */
    val toEdge: Boolean = false,
)

/** 按角色对齐插值; 见 [TvBackdropTreatment] 的说明. */
fun lerpTvBackdropTreatment(a: TvBackdropTreatment, b: TvBackdropTreatment, t: Float): TvBackdropTreatment =
    TvBackdropTreatment(
        dim = lerpDimColor(a.dim, b.dim, t),
        top = lerpFade(a.top, b.top, t),
        left = lerpFade(a.left, b.left, t),
        bottom = lerpFade(a.bottom, b.bottom, t),
        bottomDstOut = if (t < 0.5f) a.bottomDstOut else b.bottomDstOut,
    )

/** 色与不透明度都连续插值: 只有一侧有压暗时, 用另一侧的同色 alpha 0 当对端, 免得从"透明黑"插过去中段发灰. */
private fun lerpDimColor(a: Color, b: Color, t: Float): Color {
    if (a.alpha == 0f && b.alpha == 0f) return Color.Transparent
    val from = if (a.alpha == 0f) b.copy(alpha = 0f) else a
    val to = if (b.alpha == 0f) a.copy(alpha = 0f) else b
    val c = androidx.compose.ui.graphics.lerp(from, to, t)
    return if (c.alpha <= 0f) Color.Transparent else c
}

private fun lerpFade(a: TvBackdropFade?, b: TvBackdropFade?, t: Float): TvBackdropFade? {
    // 一侧没有这个角色: 沿用另一侧的几何, 强度从 0 起 —— 渐变带的位置不跳, 只有浓淡在变
    if (a == null && b == null) return null
    if (a == null) return b!!.copy(maxAlpha = b.maxAlpha * t)
    if (b == null) return a.copy(maxAlpha = a.maxAlpha * (1f - t))
    fun f(x: Float, y: Float) = x + (y - x) * t
    return TvBackdropFade(
        start = f(a.start, b.start),
        end = f(a.end, b.end),
        maxAlpha = f(a.maxAlpha, b.maxAlpha),
        // **色也要连续插**: 原来按 t < 0.5 硬切, 于是"声明插值全程连续"这句话在两端色差大时不成立 ——
        // 列表页左缘是页面底色、详情页左缘是黑, 浅色主题下中途会突然变色 (2026-09-16 审查)
        color = androidx.compose.ui.graphics.lerp(a.color, b.color, t),
        // 方向是离散量 (从边缘衰减 / 向边缘加深), 插不了; 两端一致时无歧义, 不一致时取占比大的那侧
        toEdge = if (t < 0.5f) a.toEdge else b.toEdge,
    )
}

/** [TvHeroZoomHandoff.markSourceLoaded] 的记忆容量: 够列表里横移过一屏卡片再返回即可. */
private const val SOURCE_LOADED_CAPACITY = 48

/** 返回缩回时长 (从全屏缩到列表页 hero 框; 放大中途缩回按当时进度等比缩短). 见 [TvHeroShrinkEasing]. */
const val TV_HERO_SHRINK_MILLIS = 250

/**
 * 放大 / 缩回的起止两张图不同时 (继续观看行: 列表页是单集剧照, 详情页是整部背景) 的换图 (用户 2026-09-15 要的): 运动中画面先压暗,
 * 最暗时 ([TV_HERO_SWAP_AT]) 换成另一张, 再亮回来. 每帧只多一层半透明黑 (一次 drawRect); 两张图都预先组合好、只画当前那张.
 * 替代原来"放大落地后再交叉淡入 250ms" (两张图叠画、还拖晚接手) 与"换过图就不能缩回、只能淡出". [progress] = 运动进度 0..1.
 */
fun tvHeroSwapDim(progress: Float): Float = TV_HERO_SWAP_DIM_PEAK * sin(PI.toFloat() * progress.coerceIn(0f, 1f))

/** 换图发生在运动进度的这一点 (压暗最深处). 见 [tvHeroSwapDim]. */
const val TV_HERO_SWAP_AT = 0.5f

/**
 * 换图压暗最深时黑层的不透明度 = 最暗时保留 ~65% 亮度. 照 iOS 打开 / 关闭时桌面被压暗的程度 (用户 2026-09-15: "iOS 的不是特别黑";
 * 录屏 rec/ios.mov 窗口外角落逐帧亮度 `ios_dim.py`: 打开时 ~120ms 内降到 ~60%, 关闭时桌面刚露出 50~70%、~350ms 回满). 原先 0.7 (剩 30%) 太黑.
 */
const val TV_HERO_SWAP_DIM_PEAK = 0.35f

/**
 * 放大时长. 250 = 用户在 2×2 录屏对比里选的 (iOS 曲线 250 / 300、M3 emphasized 250 / 300). 这条曲线 ~200ms 走完 90%, 余下是
 * 减速落地, UI 在落地途中就出现 (见 [TV_HERO_ZOOM_REVEAL_T]). Shield AOT 实测: 按键后 ~294ms 出 UI, ~350ms 完全停住.
 * (时长本身的下限另测过: 首屏最早按键后 ~200ms 才组合得完, 再短 UI 也不会更早.)
 */
const val TV_HERO_ZOOM_MILLIS = 250

/**
 * 放大那一跳导航转场撑多久 (旧页在组合里留多久): 要盖住"导航 → 放大那一层的图上屏"这一段 (一两帧, 冷的更晚),
 * 放大层透明等待期间底下一直有东西. 富余一些无妨: 硬切之后列表页整层跳过绘制.
 * 返回缩回**不靠它保活**列表页 (缩到位、静止后才出栈, 列表页离场就在层下重建, 见 [TvHeroZoomHandoff.Shrink]). 2026-09-15 试过放到
 * 1200 撑长缩回窗口: 索尼上列表页照样 ~0.6s 就被移出 (没用), Shield 上移出那一帧挪到 ~1.2s, 正撞进页后第一次往下翻 (每页多一次
 * 38~44ms 停顿) —— 撤回 700.
 */
const val TV_HERO_ZOOM_NAV_HOLD_MILLIS = 700

/**
 * 缩回落地、出栈后等列表页就绪 (见 [TvHeroZoomHandoff.listReady]) 的上限: 过了就直接撤层, 不让画面冻在落点上.
 *
 * **别再调大**: 这段时间里缩回层拿不透明底色盖着整个画面, 等多久就黑多久. 原来是 800ms, 判据一旦失配
 * 就是实打实的"返回后黑一秒" (2026-09-16 实测 +1101ms). 判据修好之后它只是兜底, 取小值让任何残余失配
 * 表现成"轻轻跳一下"而不是黑屏 —— 也更容易被看见、被报上来.
 */
const val TV_HERO_SHRINK_READY_TIMEOUT_MILLIS = 250L

/** 放大那一层等图上屏的预算, 从它第一帧画完起算: 超过就放弃放大 (图是后到的, 再放大只会突兀). 内存命中在一两帧内. */
const val TV_HERO_ZOOM_LOAD_BUDGET_MILLIS = 150L

/**
 * 放大进度 (位移) 过了这个值才换真页并组合首屏信息带 (占位页换真页 ~30ms、信息带 ~35ms; Sony 翻倍), 此前放大那一段一帧都
 * 不卡. 2026-09-10 追踪: 真页组合原本落在放大开始后 40~170ms, 一帧 30~65ms, 正是位移最快的一段.
 * 取 0.99: 原来的 0.93 让换真页那帧落在位移 ~95%, 停 ~40ms 后一步跳 ~4% (2026-09-14 用户: "放大尾段有点卡"; 追踪还发现
 * 前面二百毫秒的轻帧让 Shield CPU 降到最低频, 这帧更重). 改到 0.99 后剩下的位移不到 1%, 停在那里看不出来; UI 出现时间
 * 与 0.93 时差不多 (那时信息带那一帧本来就落在落地之后, 实际 ~+280ms 才出).
 * **别把重活往前挪进放大途中** (2026-09-14 试过): 真页一旦在放大期间存在, 它订阅的十几个流首次发射、分页器首页、标签墙的
 * 第二遍测量都会跟着进快段 —— 起跑前组合真页 + 信息带逐帧分四份, Shield 停顿挪到 2~15% / 46~79%, Sony 全程停 5~6 次.
 */
const val TV_HERO_ZOOM_TAIL_T = 0.99f

/**
 * 放大进度 (位移) 到这就显示详情页首屏信息带 (按钮 / 标签 / 评分), 放大层照常画完最后一段: 用户要"停稳就能直接按,
 * 不要等一下按钮" (2026-09-13). 会话仍在到位且真页背景就绪后才结束 (撤放大层、放行首屏以下区块都跟着会话走).
 * 比 [TV_HERO_ZOOM_TAIL_T] 小: 真页 / 信息带在它之后才组合时, 组合完那一帧直接可见.
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

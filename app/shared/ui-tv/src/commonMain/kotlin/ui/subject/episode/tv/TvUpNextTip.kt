/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_next_episode
import me.him188.ani.app.ui.lang.video_player_tv_up_next_in
import me.him188.ani.app.ui.subject.details.SubjectDetailsUIState
import me.him188.ani.app.ui.subject.details.sections.episodeStillImageUrl
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem
import org.jetbrains.compose.resources.stringResource

/*
 * 片尾的「接下来播放」提示 (Prime / Kodi 形态: 角落小卡, 不接管整屏).
 *
 * 触发分两档, 与业界一致 (Plex/Netflix 用片尾标记, Kodi/Jellyfin 用固定秒数):
 * - **有 ED 标记**: **片尾放完**就把卡片摆出来 (那才是"这一集的内容播完了"; ED 进行中该给的是
 *   「跳过 ED」按钮, 两者因此天然错开), 但在最后 N 秒之前**不倒计时** —— 还想看次回预告的人
 *   不该被催, 想直接走的人按一下确认就走;
 * - **没有 ED 标记**, 或 ED 结束后剩得太多 (那多半是误判): 距结尾不足 N 秒时出现, 出现即倒计时.
 *
 * **倒计时到 0 不由本组件切集**: 归零那一刻就是视频自然结束, 切集照旧由
 * `SwitchNextEpisodeExtension` 的 MediaEnded 负责 —— 本组件只是把那件本来就会发生的事提前
 * 可视化, 并给一个取消的入口, 不新增第二条切集路径 (两条路径一定会在边界上打架).
 *
 * 关掉自动连播时卡片照常出现, 只是不倒计时也不自动播: 那时它就是一颗"下一集"的快捷按钮
 * (Infuse 社区长年在要的那个东西).
 */

/**
 * 「接下来播放」提示的状态. 位置每 100ms 一跳, 而本状态里只有 [countdownSeconds] 按秒变、
 * [visible] 整段只翻一两次 —— 相同值写回快照状态不触发重组, 所以驱动侧可以放心地按播放位置
 * 全速算, 读侧 (播放器根部) 只会在真正翻转时重组.
 */
@Stable
class TvUpNextState {
    /** 卡片该不该在场. 播放器根部读它决定控制层要不要为它多活一会儿. */
    var visible: Boolean by mutableStateOf(false)
        internal set

    /** 剩余秒数; null = 不倒计时 (片尾刚放完那一段, 或关掉了自动连播). */
    var countdownSeconds: Int? by mutableStateOf(null)
        internal set

    /**
     * 倒计时**已经走了**多少 (0..1), 给卡片底部那条进度条用; 0 = 不倒计时.
     *
     * 与 [countdownSeconds] 分开存: 那个按秒跳 (文字), 这个按位置采样跳 (~100ms, 进度条要顺滑).
     * 卡片在绘制 lambda 里读它, 每次变化只失效绘制不重组.
     */
    var countdownFraction: Float by mutableFloatStateOf(0f)
        internal set

    /** 下一集; null = 没有下一集 (或确定还没播出), 此时卡片不出现. */
    var nextEpisode: EpisodeListItem? by mutableStateOf(null)
        internal set

    /** 下一集的 TMDB 剧照; null = 没有图或设置里关掉了. */
    var stillUrl: String? by mutableStateOf(null)
        internal set

    /**
     * 用户按返回收掉了**这一次**提示.
     *
     * 只压住当前这一趟触发: 播放位置走出触发窗口 (拖回片尾之前) 就由驱动侧自动复位, 再放过来
     * 卡片照样出现; 换集也复位. 做成"这一集再也不提示"的话, 拖回去重看那一段会毫无反应, 与
     * OP/ED 那边"每次拖回段落之前都要重新武装"是同一个道理.
     */
    internal var dismissed: Boolean by mutableStateOf(false)

    fun dismiss() {
        dismissed = true
        visible = false
    }
}

/**
 * 驱动 [TvUpNextState].
 *
 * 三条效应各管一件事, 不合并: 换集重算下一集是低频的; 位置驱动是高频的; 剧照只在卡片真要
 * 显示时才去拿 (那条流每次收集都会重新问一遍 TMDB, 尽管多半命中进程内缓存).
 */
@Composable
internal fun rememberTvUpNextState(vm: EpisodeViewModel): TvUpNextState {
    val state = remember { TvUpNextState() }

    // 下一集: 走 EpisodeViewModel 的单一来源 —— 卡片上写的那一集必须与自动连播真正会播的
    // 那一集是同一集 (它还挡掉了"确定还没播出"的下一集). 每次发射同时也是"换集了"的信号
    LaunchedEffect(state, vm) {
        vm.autoPlayNextEpisodeIdFlow.collect { nextId ->
            state.dismissed = false
            state.visible = false
            state.countdownSeconds = null
            state.stillUrl = null
            state.nextEpisode = if (nextId == null) {
                null
            } else {
                vm.episodeListUiStateFlow.mapNotNull { it?.allEpisodes }
                    .map { list -> list.firstOrNull { it.episodeId == nextId } }
                    .first { it != null }
            }
        }
    }

    // 位置驱动: 决定卡片在不在场、倒不倒计时
    LaunchedEffect(state, vm) {
        combine(
            vm.player.currentPositionMillis,
            vm.player.mediaProperties.map { it?.durationMillis ?: 0L }.distinctUntilChanged(),
            snapshotFlow { vm.playerSkipOpEdState.edChapterEndMillis },
            snapshotFlow { vm.videoScaffoldConfig.upNextTipLeadSeconds to vm.videoScaffoldConfig.autoPlayNext },
            // 一起看: 自动连播被这道闸拦着 (见 SwitchNextEpisodeExtension), 卡片也得跟着不出现 ——
            // 否则它会倒数完然后什么都不发生, 是句假话
            vm.playbackAutomationSuppressed,
        ) { pos, duration, edEnd, (leadSeconds, autoPlayNext), automationSuppressed ->
            val enabled = leadSeconds > 0 && duration > 0L &&
                    state.nextEpisode != null && !automationSuppressed
            val leadMillis = leadSeconds * 1000L
            val remaining = duration - pos
            // 片尾标记只用来"提前把卡片摆出来", 倒计时一律按距结尾算.
            //
            // **认的是 ED 结束, 不是 ED 开始**: 片尾放完才是"这一集的内容播完了"; ED 进行中这一集
            // 还没完, 那会儿该给的也是「跳过 ED」按钮 (按它多半正是为了直奔后面的次回预告),
            // 卡片顶掉它就是功能倒退. 按 ED 结束算, 两者天然错开 —— 按钮只在人**处于** ED 段内时
            // 给, 卡片只在走出 ED 之后出.
            //
            // 还要求 ED 结束点确实靠近片尾 ([ED_TAIL_MAX_REMAINDER_MILLIS]): OP/ED 是按"章节中点
            // 落在时间轴后半段"判的 ED, 万一把中间某段认成 ED, 卡片会从那里一直挂到结尾.
            val edEndsNearTail = edEnd != null && duration - edEnd <= ED_TAIL_MAX_REMAINDER_MILLIS
            val afterEndCredits = edEndsNearTail && pos >= edEnd
            val inLeadWindow = remaining in 0..leadMillis
            val inWindow = enabled && (afterEndCredits || inLeadWindow)
            // **走出触发窗口就重新武装**: 按返回只收掉"这一次", 不是"这一集再也不提示" ——
            // 拖回片尾之前再放过来, 卡片该照样出现 (同 PlayerSkipOpEdState 对 OP/ED 的重新武装:
            // 一锤子买卖会让"拖回去重看"这件事变得没法预期)
            if (!inWindow) state.dismissed = false
            state.visible = inWindow && !state.dismissed
            val countingDown = state.visible && autoPlayNext && inLeadWindow
            state.countdownSeconds = if (countingDown) {
                // 向上取整并且不显示 0: 归零那一刻靠的是播放器自己报 MediaEnded, 时长与真实结尾
                // 差个几百毫秒是常事, "0 秒后播放"挂在那里像卡死了
                ((remaining + 999) / 1000).toInt().coerceAtLeast(1)
            } else {
                null
            }
            state.countdownFraction = if (countingDown && leadMillis > 0) {
                ((leadMillis - remaining).toFloat() / leadMillis).coerceIn(0f, 1f)
            } else {
                0f
            }
        }.collect()
    }

    // 剧照: 跟着"下一集是哪一集"与那条设置走, **不跟着卡片的可见性走**.
    //
    // 一度是"卡片不显示就把 stillUrl 清掉", 结果按返回收卡片时先清了图, 卡片在被移除前的那一两帧
    // 退化成"没有图的两行字", 观感是"先缩成一个小形态再消失" (真机可见). 图本来就是这一集的属性,
    // 留着不占什么, 下次再显示还省一次取.
    //
    // 取图仍是**懒的**: 等到卡片第一次真要显示才去收那条流 (它每次收集都会重新问一遍 TMDB,
    // 尽管多半命中进程内缓存)
    LaunchedEffect(state, vm) {
        snapshotFlow { vm.videoScaffoldConfig.upNextTipShowStill to state.nextEpisode?.episodeId }
            .distinctUntilChanged()
            .collectLatest { (showStill, episodeId) ->
                state.stillUrl = null
                if (!showStill || episodeId == null) return@collectLatest
                snapshotFlow { state.visible }.first { it }
                val details = vm.episodeDetailsState.subjectDetailsStateLoader.state
                    .filterIsInstance<SubjectDetailsUIState.Ok>().first().value
                state.stillUrl = details.tmdbEpisodeStillsFlow
                    .map { it[episodeId] }
                    .first { it != null }
            }
    }

    return state
}

/**
 * 「接下来播放」卡片.
 *
 * **它是底部那一列的一员, 位置由布局给出** —— 与 OP/ED 提示按钮同一个道理: 走"屏幕级悬浮 +
 * 实测坐标跟随"的话, 图标行收起那段逐帧动画里它总慢一拍 (那条路走过两版, 都被否了).
 * 控制层淡出时本卡片不跟着淡 (调用方不给它挂 chrome 那层 alpha), 于是纯视频态下屏上只剩它。
 */
@Composable
internal fun TvUpNextCard(
    state: TvUpNextState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = state.nextEpisode ?: return
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val still = state.stillUrl
    Surface(
        onClick = onClick,
        // 卡片就是那张剧照: 宽度由图定, 文字全部压在图上
        modifier = modifier.width(TV_UP_NEXT_STILL_WIDTH),
        shape = RoundedCornerShape(TV_UP_NEXT_CORNER),
        // 底色只在没有剧照时看得见 (有图时被图盖满). 底色不随焦点翻转 —— 胶囊那套"聚焦即白底
        // 黑字"在这里行不通: 整块反色会把图也一起翻掉. 示焦用白描边, 与播放器其余控件一套
        color = Color.Black.copy(alpha = TV_UP_NEXT_SCRIM_ALPHA),
        contentColor = Color.White,
        interactionSource = interactionSource,
    ) {
        // 卡片外边缘那一圈**既是倒计时又是示焦**: 走满一圈就进下一集.
        //
        // 不做成卡片底缘一条横杠 (原来的做法): 横杠贴着卡底、又是从左往右推进, 与播放进度条
        // 长得太像, 会被当成"这一集还剩多少". 也不缩成右上角一个小圆: 那圈太小, 电视上隔几米
        // 根本看不出它在走.
        //
        // 与示焦白描边合成同一条边: 两条同色白边套在一起只会看着乱 —— 倒计时进行中时,
        // 底圈的深浅表示焦点在不在, 走过的那一段是实白.
        //
        // 进度**读在绘制 lambda 里**: 它每 100ms 变一次, 在组合里读会让整张卡跟着重组
        val ring = Modifier.drawWithCache {
            // 轮廓与测量器**按尺寸缓存**: 它们只跟卡片大小有关, 而这一圈每 100ms 要重画一次 ——
            // 放在绘制里 new 的话就是每帧一个 Path + 一个 PathMeasure (本仓库的重组/分配纪律见
            // TvPlayerControls 的合成策略那一段). 走过的那一段复用同一个 Path, 每次 rewind
            val stroke = TV_UP_NEXT_RING_WIDTH.toPx()
            val inset = stroke / 2
            // 内缩半个线宽: Surface 会按圆角裁剪内容, 不缩的话外侧半条线被裁掉
            val outline = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right = size.width - inset,
                        bottom = size.height - inset,
                        cornerRadius = CornerRadius(
                            (TV_UP_NEXT_CORNER.toPx() - inset).coerceAtLeast(0f),
                        ),
                    ),
                )
            }
            val measure = PathMeasure().apply { setPath(outline, false) }
            val length = measure.length
            val walked = Path()
            val strokeStyle = Stroke(width = stroke)
            val walkedStyle = Stroke(width = stroke, cap = StrokeCap.Round)
            onDrawWithContent {
                drawContent()
                val fraction = state.countdownFraction
                if (fraction <= 0f) {
                    if (focused) drawPath(outline, Color.White, style = strokeStyle)
                    return@onDrawWithContent
                }
                drawPath(
                    outline,
                    Color.White.copy(
                        alpha = if (focused) TV_UP_NEXT_RING_TRACK_FOCUSED_ALPHA
                        else TV_UP_NEXT_RING_TRACK_ALPHA,
                    ),
                    style = strokeStyle,
                )
                walked.rewind()
                measure.getSegment(0f, length * fraction, walked, true)
                drawPath(walked, Color.White, style = walkedStyle)
            }
        }

        if (still == null) {
            // 没有剧照 (设置里关掉了 / TMDB 没图): 退化成两行字, 靠卡片自己的底色
            Column(
                ring.padding(TV_UP_NEXT_PADDING),
                verticalArrangement = Arrangement.spacedBy(TV_UP_NEXT_TEXT_GAP),
            ) {
                UpNextHeader(state)
                UpNextTitle(episode)
            }
        } else {
            Box(ring.fillMaxWidth().aspectRatio(TV_UP_NEXT_STILL_ASPECT_RATIO)) {
                AsyncImage(
                    episodeStillImageUrl(still),
                    contentDescription = null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    // 与选集卡/预取共用同一份解码 (见 episodeStillImageUrl)
                    decodeAtOriginalSize = true,
                )
                // 上下各一条渐变: 上面托住「下一集」那一行, 下面托住集号/集名 (下半那条与选集卡同值).
                // 中间整段透明 —— 图才是这张卡的主角
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = TV_UP_NEXT_TOP_SCRIM_ALPHA),
                            0.35f to Color.Transparent,
                            0.6f to Color.Transparent,
                            1f to Color.Black.copy(alpha = TV_UP_NEXT_STILL_SCRIM_ALPHA),
                        ),
                    ),
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            horizontal = TV_UP_NEXT_TITLE_SIDE_PADDING,
                            vertical = TV_UP_NEXT_TITLE_BOTTOM_PADDING,
                        ),
                ) {
                    UpNextHeader(state)
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = TV_UP_NEXT_TITLE_SIDE_PADDING)
                        .padding(top = 8.dp, bottom = TV_UP_NEXT_TITLE_BOTTOM_PADDING),
                ) {
                    UpNextTitle(episode)
                }
            }
        }
    }
}

/** 「下一集」+ 倒计时秒数. 两者靠**深浅**分主次而不是靠颜色 (播放器控件一律黑白). */
@Composable
private fun UpNextHeader(state: TvUpNextState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Lang.video_player_next_episode),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        // 倒计时读在这里, 不在上层 —— 它每秒变一次, 只该重组这一行文字
        state.countdownSeconds?.let { seconds ->
            Text(
                stringResource(Lang.video_player_tv_up_next_in, seconds),
                color = Color.White.copy(alpha = TV_UP_NEXT_SUBTLE_ALPHA),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 集号 + 集名, 与选集卡上那一行**同一套样式**: 集号 titleSmall 纯白, 集名 bodySmall 白 0.85,
 * 两者字号不同所以按**基线**对齐 (盒子居中会让小字上下飘), 间距 4dp —— 逐条见 FocusEpisodeCard.
 * 图的尺寸日后要是改了, 这两个字号按同一比例缩放, 别只改图.
 */
@Composable
private fun UpNextTitle(episode: EpisodeListItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            episode.sort.toString(),
            Modifier.alignByBaseline(),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        Text(
            episode.nameCn.ifBlank { episode.name },
            Modifier.alignByBaseline(),
            color = Color.White.copy(alpha = TV_UP_NEXT_SUBTLE_ALPHA),
            style = MaterialTheme.typography.bodySmall,
            // 压在图上只给一行: 两行会盖掉大半张图
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * ED 结束之后最多还剩多少, 才认它是"这一集的片尾".
 *
 * 90 秒: 装得下次回预告 (普遍 30 秒上下) 加黑场/台标. 判宽了的坏处是万一某段正片被误判成 ED
 * (判据是"章节中点落在时间轴后半段"), 卡片会从那儿一直挂到结尾; 超出这个数就退回按距结尾
 * N 秒的走法, 与没有 ED 标记的集数一样.
 */
private const val ED_TAIL_MAX_REMAINDER_MILLIS = 90_000L

/** TMDB 分集剧照一律 16:9 (同选集卡与本集详情弹窗). */
private const val TV_UP_NEXT_STILL_ASPECT_RATIO = 16f / 9f

/** 没有剧照那一档里, 第一行与集名之间的间距 (有图时两者分别贴着图的上下缘, 用不到它). */
private val TV_UP_NEXT_TEXT_GAP = 6.dp

/**
 * 剧照宽度 (也就是整张卡的宽度).
 *
 * 200dp: **必须明显宽过第一行那句「下一集 N 秒后播放」** (中文约 110dp), 否则图夹在一行字下面
 * 显得局促; 而这张卡的主角本来就是图 —— 集名压在图上, 文字去将就图, 不是反过来.
 */
private val TV_UP_NEXT_STILL_WIDTH = 200.dp

/**
 * 集号/集名在剧照上的横向内边距与下内边距.
 *
 * 与选集卡同一档观感 (那边是由进度条内缩推出来的, 见 EPISODE_IMAGE_TEXT_SIDE_PADDING);
 * 本卡片底部没有播放进度条, 所以下内边距不必给条让位, 直接贴着底缘留一点点.
 */
private val TV_UP_NEXT_TITLE_SIDE_PADDING = 8.dp
private val TV_UP_NEXT_TITLE_BOTTOM_PADDING = 6.dp

/** 集名压在剧照上时, 图底部那层渐变的最深处 (与选集卡同值). */
private const val TV_UP_NEXT_STILL_SCRIM_ALPHA = 0.85f
private val TV_UP_NEXT_STILL_CORNER = 6.dp
private val TV_UP_NEXT_CORNER = 10.dp
private val TV_UP_NEXT_PADDING = 10.dp

/**
 * 倒计时底圈的不透明度: 看得出整圈有多长, 又不抢主角.
 *
 * 聚焦时亮一档 —— 这条边同时兼着示焦, 没有第二条白边可用了.
 */
private const val TV_UP_NEXT_RING_TRACK_ALPHA = 0.25f
private const val TV_UP_NEXT_RING_TRACK_FOCUSED_ALPHA = 0.5f

/** 图片顶部那条渐变的最深处: 托住「下一集」那一行, 比底部那条浅一档 (那边压着两段文字). */
private const val TV_UP_NEXT_TOP_SCRIM_ALPHA = 0.7f

/**
 * 外边缘那一圈的线宽 (倒计时与示焦共用这一条边).
 *
 * 比常规示焦描边 (1.75dp) 粗一档: 这张卡浮在画面上, 电视上隔着几米要看得出这一圈在走.
 */
private val TV_UP_NEXT_RING_WIDTH = 3.dp

/** 倒计时文字比"下一集"那三个字淡一档: 主次分明, 与参考稿一致. */
private const val TV_UP_NEXT_SUBTLE_ALPHA = 0.85f

private const val TV_UP_NEXT_SCRIM_ALPHA = 0.55f

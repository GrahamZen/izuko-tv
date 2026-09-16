/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.models.subject.RelatedPersonInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.nameCn
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.focus.TV_SCROLL_STIFFNESS
import me.him188.ani.app.ui.foundation.focus.TvAnchoredStrip
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tv.TvImageZoomState
import me.him188.ani.app.ui.foundation.tv.rememberTvImageZoomState
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_characters_with_count
import me.him188.ani.app.ui.lang.subject_details_rating_summary
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_details_staff_with_count
import me.him188.ani.app.ui.lang.subject_details_stat_collected
import me.him188.ani.app.ui.lang.subject_details_stat_watching
import me.him188.ani.app.ui.lang.subject_details_stat_wish
import me.him188.ani.app.ui.lang.subject_details_view_all
import me.him188.ani.app.ui.rating.FiveRatingStars
import me.him188.ani.app.ui.rating.renderScore
import me.him188.ani.app.ui.subject.details.components.PersonCard
import me.him188.ani.app.ui.subject.person.PeoplePreviewTarget
import me.him188.ani.app.ui.subject.person.rememberPeopleClickHandler
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * 收藏统计三格 (对齐 Figma 定稿: `74,553 收藏 / 5,120 在看 / 680 想看`).
 * 收藏 = 五档之和; 在看 = doing; 想看 = wish.
 */
@Composable
fun SubjectCollectionStatsRow(
    stats: SubjectCollectionStats,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCell(stats.collect, stringResource(Lang.subject_details_stat_collected), Modifier.weight(1f))
        StatCell(stats.doing, stringResource(Lang.subject_details_stat_watching), Modifier.weight(1f))
        StatCell(stats.wish, stringResource(Lang.subject_details_stat_wish), Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(count: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            remember(count) { groupThousands(count) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** `39983 -> "39,983"`. 详情页多处数字 (收藏统计/评分人数/评论数) 按定稿千分组显示. */
fun groupThousands(n: Int): String {
    val s = n.toString()
    val neg = s.startsWith("-")
    val digits = if (neg) s.substring(1) else s
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (neg) "-$grouped" else grouped
}

/**
 * 评分摘要块 (对齐定稿: 大分数 `8.4` + 右侧上下两行 [星星 / `#72 · 39,983 人评分`]).
 *
 * 用于双栏/三栏中栏评分行与三栏右栏"评分"卡.
 *
 * @param onClick 非 null 时整块可点击 (打开评分编辑, 见 `EditableRatingState.requestEdit`).
 */
@Composable
fun SubjectRatingSummary(
    ratingInfo: RatingInfo,
    modifier: Modifier = Modifier,
    scoreStyle: TextStyle = MaterialTheme.typography.displaySmall,
    starSize: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(MaterialTheme.shapes.small)
            .ifThen(onClick != null) { clickable(onClick = checkNotNull(onClick)) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            remember(ratingInfo.score) { renderScore(ratingInfo.score) },
            style = scoreStyle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FiveRatingStars(
                remember(ratingInfo.score) { ratingInfo.scoreFloat.roundToInt() },
                starSize = starSize,
            )
            Text(
                stringResource(
                    Lang.subject_details_rating_summary,
                    ratingInfo.rank.toString(),
                    remember(ratingInfo.total) { groupThousands(ratingInfo.total) },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 角色区块: 标题行 (+"查看全部" -> 全量列表 sheet) + 横向头像条 (固定圆形头像 + 角色名 + CV).
 *
 * 头像为固定大小圆形, 图片 crop 顶部对齐 (角色图多为全身立绘, 顶部对齐保证露脸).
 * 尺寸对齐 Figma `CharacterCard`: 桌面 Large (头像 76, 间距 12), 手机 Small (头像 56, 间距 0).
 *
 * @param contentPadding 头像条与标题的水平内边距; 手机端传水平 16dp 可让头像条边到边滚动.
 */
@Composable
fun CharactersSection(
    exposedCharacters: LazyPagingItems<RelatedCharacterInfo>,
    allCharacters: LazyPagingItems<RelatedCharacterInfo>,
    totalCharactersCount: Int?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    itemWidth: Dp = 76.dp,
    avatarSize: Dp = 76.dp,
    itemSpacing: Dp = 12.dp,
    /** TV: 卡片下键的显式落点 (下一区块入口; 跨区块空间焦点搜索不可靠). null 交给空间搜索. */
    downFocus: FocusRequester? = null,
    /**
     * TV: 非 null 时**长按**卡片放大查看大图 (短按仍是人物预览). 大图层与按键拦截由调用方挂在
     * 页面根上 —— 本区块在滚动列里, 全屏层画在这儿会被父布局裁掉. 见 [TvImageZoomState].
     */
    imageZoom: TvImageZoomState? = null,
) {
    if (exposedCharacters.itemCount == 0) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    // TV: "查看全部" 不在标题行, 而是行末的一格 (见 ViewAllMonogramCell)
    val viewAllFocus = remember { FocusRequester() }
    val focusDrivenChars = LocalAniUiBehavior.current.focusDrivenNavigation
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            stringResource(Lang.subject_details_characters),
            actionLabel = if (focusDrivenChars) null else stringResource(Lang.subject_details_view_all),
            onAction = { showAll = true },
            modifier = Modifier.padding(contentPadding),
            actionModifier = if (focusDrivenChars) Modifier else Modifier.focusRequester(viewAllFocus),
        )
        val onClickCharacter = rememberPeopleClickHandler()
        if (focusDrivenChars) {
            // 焦点驱动形态: 卡片形态 (与"查看全部"弹窗同款 PersonCard + 聚焦高亮容器), 锚位横滑条
            // (聚焦卡停在行首, 整行滑动; 入场不动 —— 见 TvAnchoredStrip 的 KDoc).
            // 格宽是**定值** (见 TV_MONOGRAM_SIZE), 不按视口均分: 均分会让"减不减留白""留白算一侧还是两侧"
            // 各处算出不同的宽度 (角色行 136dp / 制作人员行 143dp 就是这么来的), 而 Apple 的圆是恒定 130dp,
            // 一屏放得下几个、第几个被裁是自然结果
            // 长按闸门要读"卡片是否还在滑向锚位", 所以 listState 提到外面自己建
            val stripState = rememberLazyListState()
            run {
                val cardWidth = TV_MONOGRAM_SIZE
                // 还有没露出来的人时, 行末补一格"查看全部" (见 ViewAllMonogramCell)
                val hasMore = (totalCharactersCount ?: 0) > exposedCharacters.itemCount
                TvAnchoredStrip(
                    itemCount = exposedCharacters.itemCount + if (hasMore) 1 else 0,
                    itemSpacing = TV_MONOGRAM_SPACING,
                    contentPadding = contentPadding,
                    state = stripState,
                    horizontalMoveRate = TV_MONOGRAM_MOVE_RATE,
                ) { i, itemModifier ->
                    // 圆几乎撑满格宽 (tvOS 的比例), 而不是小圆浮在宽格子中央
                    val circle = cardWidth
                    if (hasMore && i == exposedCharacters.itemCount) {
                        TvMonogramLockup(
                            onClick = { showAll = true },
                            modifier = Modifier
                                .width(cardWidth)
                                .then(itemModifier)
                                .ifThen(downFocus != null) { focusProperties { down = downFocus!! } },
                        ) { focused, progress ->
                            ViewAllMonogramCell(
                                remaining = totalCharactersCount?.minus(exposedCharacters.itemCount),
                                itemWidth = cardWidth,
                                avatarSize = circle,
                                focused = focused,
                                progress = progress,
                            )
                        }
                        return@TvAnchoredStrip
                    }
                    val item = exposedCharacters[i] ?: return@TvAnchoredStrip
                    TvMonogramLockup(
                        modifier = Modifier
                            .width(cardWidth)
                            .then(itemModifier)
                            .ifThen(downFocus != null) {
                                focusProperties { down = downFocus!! }
                            }
                            // 长按放大: 遥控器上分不出"点头像"与"点名字" (整卡一个焦点目标),
                            // 放大只能挂长按. 确认键被 tvLongPressKey 整个接管, 短按语义因此
                            // 要在这里再派发一次 (下面 onClick 只剩指针设备与可聚焦性在用)
                            .ifThen(imageZoom != null) {
                                tvLongPressKey(
                                    onLongPress = { imageZoom!!.open(item.character.imageLarge) },
                                    onShortPress = {
                                        onClickCharacter(PeoplePreviewTarget.Character(item.character.id))
                                    },
                                    // 卡片还在滑向锚位时先不触发 (同选集轮播的理由)
                                    readyToFire = { !stripState.isScrollInProgress },
                                )
                            },
                        // 点击与焦点都在格容器上 (不在内部的格内容上): 锚位滚动按焦点目标矩形算
                        onClick = { onClickCharacter(PeoplePreviewTarget.Character(item.character.id)) },
                    ) { focused, progress ->
                        PersonMonogramCell(
                            avatarUrl = item.character.imageMedium,
                            name = item.character.displayName,
                            subtitle = remember(item) {
                                item.character.actors.firstOrNull()?.displayName
                            } ?: item.role.nameCn,
                            itemWidth = cardWidth,
                            avatarSize = circle,
                            focused = focused,
                            progress = progress,
                        )
                    }
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                contentPadding = contentPadding,
            ) {
                items(exposedCharacters.itemCount) { i ->
                    val item = exposedCharacters[i] ?: return@items
                    CharacterAvatarCell(
                        item, itemWidth, avatarSize,
                        onClick = { onClickCharacter(PeoplePreviewTarget.Character(item.character.id)) },
                        modifier = if (i == exposedCharacters.itemCount - 1) {
                            Modifier.focusProperties { right = viewAllFocus }
                        } else Modifier,
                    )
                }
            }
        }
    }
    if (showAll) {
        CharactersViewAllDialog(
            allCharacters, totalCharactersCount,
            onDismissRequest = { showAll = false },
        )
    }
}

/**
 * 角色的「查看全部」弹窗: 全量名单, TV 上是居中大网格 (见 [ViewAllSheet]).
 *
 * 抽成独立组合而不是留在 [CharactersSection] 里, 是因为**播放器要弹同一个弹窗而那里没有这个区块**
 * (内嵌详情页是精简版, 角色/制作人员由胶囊面板承担). 胶囊按下确定原先只是"把焦点送进面板",
 * 与直接按上键完全重复 —— 那一下现在给的就是这份大网格.
 *
 * 点卡片: 先关本弹窗再弹人物预览 (**不叠第二层弹窗**, 遥控器上两层弹窗的焦点归属没有好解法).
 */
@Composable
fun CharactersViewAllDialog(
    allCharacters: LazyPagingItems<RelatedCharacterInfo>,
    totalCharactersCount: Int?,
    onDismissRequest: () -> Unit,
    /**
     * 点卡片时在弹人物预览**之前**调用. 默认就是关掉本弹窗;
     * TV 播放器额外要记一笔"预览是从这儿开的"(关掉预览后焦点该还给胶囊而不是面板条目).
     */
    onBeforeOpenPreview: () -> Unit = onDismissRequest,
) {
    val onClickCharacter = rememberPeopleClickHandler()
    // TV: 长按卡片放大. 这一层归本弹窗自己持有 —— 独立窗口, 页面级的大图层在它下面盖不住;
    // 画在窗口内也就不必"先关弹窗再放大" (关掉大图后网格与焦点原样都还在)
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
    val imageZoom = rememberTvImageZoomState()
    ViewAllSheet(
        title = totalCharactersCount?.let { stringResource(Lang.subject_details_characters_with_count, it) }
            ?: stringResource(Lang.subject_details_characters),
        items = allCharacters,
        onDismissRequest = onDismissRequest,
        gridColumns = VIEW_ALL_GRID_COLUMNS,
        imageZoom = imageZoom.takeIf { focusDriven },
    ) {
        PersonCard(
            it,
            Modifier
                .clip(MaterialTheme.shapes.small)
                .ifThen(focusDriven) {
                    tvLongPressKey(
                        onLongPress = { imageZoom.open(it.character.imageLarge) },
                        onShortPress = {
                            onBeforeOpenPreview()
                            onClickCharacter(PeoplePreviewTarget.Character(it.character.id))
                        },
                    )
                }
                .clickable {
                    onBeforeOpenPreview()
                    onClickCharacter(PeoplePreviewTarget.Character(it.character.id))
                },
        )
    }
}

@Composable
private fun CharacterAvatarCell(
    info: RelatedCharacterInfo,
    itemWidth: Dp,
    avatarSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cv = remember(info) { info.character.actors.firstOrNull()?.displayName }
    PersonMonogramCell(
        avatarUrl = info.character.imageMedium,
        name = info.character.displayName,
        subtitle = cv ?: info.role.nameCn,
        itemWidth = itemWidth,
        avatarSize = avatarSize,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
    )
}

/**
 * 演职人员的圆头像格: 圆形头像 + 姓名 + 一行副标题 (角色是声优, 制作人员是职位), 居中竖排.
 *
 * Apple 在 HIG 里给这个形态起了名字叫 **Monogram**, 定义就是"圆形头像 + 姓名, 用于媒体条目的演职人员";
 * tvOS 详情页的 Cast & Crew 行、Plex / Jellyfin / Emby 的演职人员行用的都是它. 手机端本来就是这个形态,
 * 2026-09-15 起 TV 也统一过来 (原先 TV 用的是"左方头像 + 右两行文字"的横向宽卡, 一行只放得下 4 张).
 *
 * **副标题恒占一行**, 空了也留位: 同一排里有副标题 / 没副标题的格高度差一行, 焦点横向移过去页面会跟着动
 * (详情页按焦点元素下缘算页内露出量), 也不符合「状态位恒在、换条目不跳」的排版规则.
 */
@Composable
private fun PersonMonogramCell(
    avatarUrl: String?,
    name: String,
    subtitle: String,
    itemWidth: Dp,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
    /** 聚焦态 (TV): 第二行字提亮. */
    focused: Boolean = false,
    /**
     * 聚焦放大的进度 0..1 (见 [TvMonogramLockup]).
     *
     * ## 为什么只放大圆、文字只是下移
     *
     * 2026-09-15 逐帧量了 Apple TV 的演职人员行 (1920×1080 录屏, 1 像素 = 1 布局像素 = 0.5dp):
     * 未聚焦圆直径 260px, 聚焦 300px (**放大 1.154**), 圆心 x 恒为 210/510/810… —— **邻居纹丝不动**,
     * 所以放大是溢出布局盒画的, 不占布局. 而文字**墨高不变** (聚焦项 24px / 邻项 23px, 差别只是字形),
     * 只是整体下移 20px = 圆下缘的位移量, 圆到姓名的间距恒为 21px.
     *
     * 也就是说: **圆放大, 字不放大, 字跟着圆的下缘走**. 整个 lockup 一起缩放是错的 (字会跟着变大).
     */
    progress: Float = 0f,
) {
    val scale = 1f + (TV_MONOGRAM_FOCUS_SCALE - 1f) * progress
    Column(
        modifier.width(itemWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TV_MONOGRAM_TEXT_GAP),
    ) {
        // 固定圆形, crop, 顶部对齐 (立绘顶部为脸部)
        Box(
            Modifier
                .size(avatarSize)
                // 只改绘制不改布局: 邻居不动, 行高也不变
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                // 聚焦时描一圈细环. Apple TV 不画环 (只放大), Plex 画一圈约 1dp 的白环 —— 两家不一样,
                // 说明这是自选项不是规范; 我们取 Plex 的粗细 + 主题色 (用户 2026-09-15 定)
                .ifThen(progress > 0f) {
                    border(
                        TV_MONOGRAM_FOCUS_RING,
                        MaterialTheme.colorScheme.primary.copy(alpha = progress.coerceIn(0f, 1f)),
                        CircleShape,
                    )
                }
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isMissingAvatar(avatarUrl)) {
                // 没有照片就写姓名首字, 别显示占位图 —— Bangumi 对没照片的人给的是一张粉色的
                // "TEXT ONLY" 图, 48dp 小方头像时不显眼, 放成大圆一排六个就毁了整行.
                // Apple HIG 的 Monogram 条目写的正是这条: "If an image isn't available, the person's
                // initials appear in place of an image."
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Text(
                    remember(name) { monogramInitials(name) },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                AvatarImage(
                    avatarUrl,
                    Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
        }
        // 两行字整体跟着圆的下缘下移 (圆是绕中心放大的, 下缘位移 = 半径 × (scale-1)), 字号不变.
        // 同样走 graphicsLayer: 不占布局, 行高恒定
        Column(
            Modifier.graphicsLayer { translationY = avatarSize.toPx() / 2f * (scale - 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TV_MONOGRAM_LINE_GAP),
        ) {
            // 姓名恒为白、加粗; 职位/声优默认是灰的, **聚焦时提亮成白**
            // (2026-09-15 真机对照: Apple TV 上聚焦项的第二行也是白的, 其余项是灰的)
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = if (focused) 1f else TV_MONOGRAM_SUBTITLE_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 这个头像算不算"没有" —— 只认得出**空 URL**.
 *
 * Bangumi 原始接口对没照片的人给的就是空串, 所以直连取数时这条判据够用.
 *
 * **走 Ani 服务器时判不出来** (2026-09-15 查证): 服务端把图代理成 `api.animeko.org/v2/persons/{id}/image`,
 * 每个人都有 URL; 没照片的人它**返回 200 + 一张 500×500 的占位 PNG**, 而不是 404. 于是 URL、响应头、
 * 接口返回体里都没有任何可判据, 客户端拿不到"这个人没有照片"这个信息. 真正的修法在服务端 (没图就返 404,
 * 客户端 AsyncImage 的 error 兜底自然生效). 这里不做基于图像内容/尺寸的猜测: 那是拿运气当判据.
 */
private fun isMissingAvatar(url: String?): Boolean = url.isNullOrBlank()

/** 姓名首字 (CJK 取第一个字, 拉丁取首字母, 最多两个). */
private fun monogramInitials(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    val first = trimmed.first()
    // CJK 一个字就够认, 取两个反而挤
    if (first.code > 0x2E80) return first.toString()
    return trimmed.split(' ', '·', '・')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
}

/**
 * TV 上可聚焦的圆头像格 (Apple 管这个形态叫 Monogram, 见 [PersonMonogramCell]).
 *
 * **不用 FocusHighlightCard**: 那个画的是半透明圆角底板 + 聚焦时 2dp 方框描边, 圆头像坐在方块里很难看,
 * 也不是 tvOS 的做法 (用户 2026-09-15: "形态太丑, 跟 apple tv 那种不一样"). tvOS 上演职人员行没有任何容器,
 * 圆就直接落在背景上; 聚焦是**整个 lockup 放大抬起 + 圆本身描一圈高光**, 名字同时提亮.
 *
 * 放大走 graphicsLayer (只改绘制不改布局): 行内其它格不会被推开, 也不会因为尺寸变化触发重新测量.
 */
@Composable
private fun TvMonogramLockup(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean, progress: Float) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // 放大进度 0..1. 弹簧而不是缓动: 连按方向键时新动画**从当前值和当前速度接着走**, 缓动会把进度
    // 重置、表现成"走完一段再走下一段". 阻尼略低于临界 —— 实测 Apple 的进入项会冲到 151 再落回 150,
    // 是带一点回弹的弹簧. 刚度取与行滚动同一个 (TV_SCROLL_STIFFNESS), 放大与滚动同相, 看起来是一个动作.
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = TV_SCROLL_STIFFNESS),
        label = "monogramFocus",
    )
    Box(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            // indication = null: 示焦完全由圆的放大承担, 不要再叠一层状态高亮
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        content(focused, progress)
    }
}

/**
 * 横滑行**末尾**的"查看全部"格: 与 [PersonMonogramCell] 同宽同高同形 (圆 + 两行字).
 *
 * 原先它是标题行右上角的文字按钮, 于是左右键在行里走到头要跳到一个**不在同一高度、形状也不同**的东西上,
 * 还得给最右一张卡显式写 `focusProperties { right = ... }` 才够得着 —— 用户 2026-09-15 说这"很不像电视 app".
 * 放进行里当最后一格之后, 左右键一路滑到底自然撞上, 那些显式指路全都不需要了.
 * (Plex 管这个形态叫 end-cap, 且只在"行内放不下"时才给; 这里同样只在还有更多人时才出现.)
 */
@Composable
private fun ViewAllMonogramCell(
    remaining: Int?,
    itemWidth: Dp,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    /** 同 [PersonMonogramCell]: 只放大圆, 文字跟着圆的下缘走. */
    progress: Float = 0f,
) {
    val scale = 1f + (TV_MONOGRAM_FOCUS_SCALE - 1f) * progress
    Column(
        modifier.width(itemWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TV_MONOGRAM_TEXT_GAP),
    ) {
        Box(
            Modifier
                .size(avatarSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .ifThen(progress > 0f) {
                    border(
                        TV_MONOGRAM_FOCUS_RING,
                        MaterialTheme.colorScheme.primary.copy(alpha = progress.coerceIn(0f, 1f)),
                        CircleShape,
                    )
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.graphicsLayer { translationY = avatarSize.toPx() / 2f * (scale - 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TV_MONOGRAM_LINE_GAP),
        ) {
            Text(
                stringResource(Lang.subject_details_view_all),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            // 副标题位恒在 (同 PersonMonogramCell): 数得出剩余人数就写出来, 数不出留空不塌行
            Text(
                if (remaining != null && remaining > 0) "+$remaining" else "",
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = if (focused) 1f else TV_MONOGRAM_SUBTITLE_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 制作人员区块: 标题行 (+"查看全部" -> 全量列表 sheet) + 内容.
 *
 * 内容形态 (对齐定稿):
 * - [gridColumns] 非 null: `职位 上 / 名字 下` 的网格 (双栏中栏单行 6 列, 手机 3 列两行);
 * - [gridColumns] 为 null: `职位 -> 名字` 竖排键值行 (三栏右栏卡, 显示 [maxItems] 个职位).
 */
@Composable
fun StaffSection(
    exposedStaff: LazyPagingItems<RelatedPersonInfo>,
    allStaff: LazyPagingItems<RelatedPersonInfo>,
    totalStaffCount: Int?,
    modifier: Modifier = Modifier,
    gridColumns: Int? = null,
    maxItems: Int = if (gridColumns != null) 6 else 10,
    /** TV: 末行卡片下键的显式落点 (下一区块入口; 跨区块空间焦点搜索不可靠). null 交给空间搜索. */
    downFocus: FocusRequester? = null,
    /** TV: 同 [CharactersSection] 的同名参数 (长按卡片放大). */
    imageZoom: TvImageZoomState? = null,
    /**
     * 行的水平留白. **只作用在起始侧**: 行要往右出血到屏幕边缘 (实测 Apple TV 的演职人员行正是如此 ——
     * 格在 x=80..1880 而屏幕宽 1920, 第 7 格被屏幕边缘裁掉只露一点), 给了 end 留白就露不出"后面还有"的暗示.
     */
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (exposedStaff.itemCount == 0) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val onClickPerson = rememberPeopleClickHandler()
    // 焦点驱动形态: 最右一张卡的右键显式指到标题行"查看全部" (空间搜索够不到)
    val viewAllFocus = remember { FocusRequester() }
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            stringResource(Lang.subject_details_staff),
            actionLabel = if (focusDriven) null else stringResource(Lang.subject_details_view_all),
            onAction = { showAll = true },
            modifier = Modifier.padding(contentPadding),
        )
        when {
            // 焦点驱动形态: 与角色行同一套圆头像横滑条 (见 PersonMonogramCell), 行末补"查看全部".
            // 原先是固定 2 行 x 3 列网格 + 标题行按钮, 于是要给"每行最右"和"末行上方"各写一条显式指路;
            // 摊成一行之后这些全都不需要, 第二页也矮了一截 (2026-09-15 改)
            focusDriven -> {
                val stripState = rememberLazyListState()
                val hasMore = (totalStaffCount ?: 0) > exposedStaff.itemCount
                run {
                    val cardWidth = TV_MONOGRAM_SIZE
                    TvAnchoredStrip(
                        itemCount = exposedStaff.itemCount + if (hasMore) 1 else 0,
                        itemSpacing = TV_MONOGRAM_SPACING,
                        contentPadding = contentPadding,
                        state = stripState,
                        horizontalMoveRate = TV_MONOGRAM_MOVE_RATE,
                    ) { i, itemModifier ->
                        val circle = cardWidth
                        if (hasMore && i == exposedStaff.itemCount) {
                            TvMonogramLockup(
                                onClick = { showAll = true },
                                modifier = Modifier
                                    .width(cardWidth)
                                    .then(itemModifier)
                                    .ifThen(downFocus != null) { focusProperties { down = downFocus!! } },
                            ) { focused, progress ->
                                ViewAllMonogramCell(
                                    remaining = totalStaffCount?.minus(exposedStaff.itemCount),
                                    itemWidth = cardWidth,
                                    avatarSize = circle,
                                    focused = focused,
                                    progress = progress,
                                )
                            }
                            return@TvAnchoredStrip
                        }
                        val item = exposedStaff[i] ?: return@TvAnchoredStrip
                        TvMonogramLockup(
                            modifier = Modifier
                                .width(cardWidth)
                                .then(itemModifier)
                                .ifThen(downFocus != null) { focusProperties { down = downFocus!! } }
                                .ifThen(imageZoom != null) {
                                    tvLongPressKey(
                                        onLongPress = { imageZoom!!.open(item.personInfo.imageLarge) },
                                        onShortPress = {
                                            onClickPerson(PeoplePreviewTarget.Person(item.personInfo.id))
                                        },
                                        readyToFire = { !stripState.isScrollInProgress },
                                    )
                                },
                            onClick = { onClickPerson(PeoplePreviewTarget.Person(item.personInfo.id)) },
                        ) { focused, progress ->
                            PersonMonogramCell(
                                avatarUrl = item.personInfo.imageMedium,
                                name = item.personInfo.displayName,
                                subtitle = item.position.nameCn ?: "",
                                itemWidth = cardWidth,
                                avatarSize = circle,
                                focused = focused,
                                progress = progress,
                            )
                        }
                    }
                }
            }

            gridColumns != null -> StaffGrid(
                exposedStaff, columns = gridColumns, maxItems = maxItems,
                onClick = { onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id)) },
            )

            else -> StaffKeyValueList(
                exposedStaff, maxItems = maxItems,
                onClick = { onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id)) },
            )
        }
    }
    if (showAll) {
        StaffViewAllDialog(
            allStaff, totalStaffCount,
            onDismissRequest = { showAll = false },
        )
    }
}

/** 制作人员的「查看全部」弹窗; 与 [CharactersViewAllDialog] 同一形态与同一批调用方. */
@Composable
fun StaffViewAllDialog(
    allStaff: LazyPagingItems<RelatedPersonInfo>,
    totalStaffCount: Int?,
    onDismissRequest: () -> Unit,
    /** 同 [CharactersViewAllDialog] 的同名参数. */
    onBeforeOpenPreview: () -> Unit = onDismissRequest,
) {
    val onClickPerson = rememberPeopleClickHandler()
    // TV 长按放大, 同 [CharactersViewAllDialog]
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
    val imageZoom = rememberTvImageZoomState()
    ViewAllSheet(
        title = totalStaffCount?.let { stringResource(Lang.subject_details_staff_with_count, it) }
            ?: stringResource(Lang.subject_details_staff),
        items = allStaff,
        onDismissRequest = onDismissRequest,
        gridColumns = VIEW_ALL_GRID_COLUMNS,
        imageZoom = imageZoom.takeIf { focusDriven },
    ) {
        PersonCard(
            it,
            Modifier
                .clip(MaterialTheme.shapes.small)
                .ifThen(focusDriven) {
                    tvLongPressKey(
                        onLongPress = { imageZoom.open(it.personInfo.imageLarge) },
                        onShortPress = {
                            onBeforeOpenPreview()
                            onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id))
                        },
                    )
                }
                .clickable {
                    onBeforeOpenPreview()
                    onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id))
                },
        )
    }
}

/** `职位 上 / 名字 下` 单元格网格, 每行 [columns] 个, 最多 [maxItems] 个. */
@Composable
private fun StaffGrid(
    staff: LazyPagingItems<RelatedPersonInfo>,
    columns: Int,
    maxItems: Int,
    onClick: (RelatedPersonInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        maxItemsInEachRow = columns,
    ) {
        val count = minOf(staff.itemCount, maxItems)
        for (i in 0 until count) {
            val person = staff[i] ?: continue
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onClick(person) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    person.position.nameCn ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    person.personInfo.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 补齐末行空位, 保持列宽一致
        val remainder = count % columns
        if (remainder != 0) {
            repeat(columns - remainder) { Box(Modifier.weight(1f)) }
        }
    }
}

/** `职位 -> 名字` 竖排键值行, 最多 [maxItems] 个. */
@Composable
private fun StaffKeyValueList(
    staff: LazyPagingItems<RelatedPersonInfo>,
    maxItems: Int,
    onClick: (RelatedPersonInfo) -> Unit,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 78.dp,
    rowSpacing: Dp = 12.dp,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        for (i in 0 until minOf(staff.itemCount, maxItems)) {
            val person = staff[i] ?: continue
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onClick(person) },
            ) {
                Text(
                    person.position.nameCn ?: "",
                    Modifier.width(labelWidth),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    person.personInfo.displayName,
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** TV 角色/制作人员"查看全部"弹窗的固定列数. */
private const val VIEW_ALL_GRID_COLUMNS = 3

/**
 * 圆头像格之间的间距.
 *
 * ## 这几个数是从哪来的
 *
 * **不是** HIG 正文 —— HIG 的 Lockups 页对 monogram 只有定性描述, 一个数字都没有. 数字来自 **Apple 官方
 * 设计模板** (Apple Design Resources 的 tvOS 18 Sketch 库) 里的元件本体, 2026-09-15 解包读出来的:
 *
 * ```
 * Views/Lockup/Standard/Dark/x/Oval            260×260   圆直径 260
 * Views/Lockup/Standard/Dark/x/Oval - Focused  260×260   内含 -15,-15,290×290  ← 四边各外扩 15
 * Views/Lockup/Standard/Dark/Oval (整行 1920×391)
 *   Row 80,76,2060×260   item x = 0,300,600,900,1200,1500,1800   每个 260×260
 * ```
 *
 * tvOS 按 1920×1080 **点**布局, 我们是 960×540 **dp**, 所以 **dp = pt ÷ 2**:
 * 圆 260pt = 130dp, 间距 40pt = 20dp, 左右安全边距 80pt = 40dp, 一屏 6 整 + 第 7 个露 20dp.
 * 自洽校验: `40 + 6×130 + 5×20 + 40 = 960` 正好铺满. 且 Apple 自己的六列网格一列正好 260pt ——
 * 圆头像每个恰好占一列, 所以"一行 6 个"不是拍脑袋.
 *
 * 详情页的左右留白后来也改成了 40dp (tvOS 安全区), 所以这一行现在跟 Apple 的几何**完全一致**.
 */
/**
 * 圆头像格的宽度 = 圆的直径. **定值**, 不按视口均分.
 *
 * 实测 Apple TV (同一块屏, 1920×1080 布局像素): 未聚焦圆 260px = 130dp, 圆心步距 300px = 150dp,
 * 行左起 80px = 40dp. 于是 `40 + 6×130 + 5×20 = 960` 正好铺满屏幕宽, 第 7 格从 960+20 起被屏幕裁掉 ——
 * "一屏 6 个 + 露一点"是这几个定值的自然结果, 不是算出来的.
 *
 * 曾一度缩到 112dp —— 那时角色/制作人员这一页还要跟别的内容抢高度, 两排 130 装不下 (用户"还是太大了").
 * 分页重排之后这一页只剩这两排: 24(标题) + 12 + 166(圆 130 + 字 36) 两遍 + 24(排间距) = 428dp,
 * 而一页能给到 540 - 24(页顶) - 24(露出余量) = 492dp, 余 64dp —— 装得下, 于是换回 Apple 的原值.
 */
private val TV_MONOGRAM_SIZE = 130.dp

/**
 * 长按左右键时每秒移动几格 (圆头像行专用, 不动全局上限).
 *
 * 全局默认是 8 格/秒, 那是按原来的宽卡调的; 圆头像格只有 128dp 步距, 8 格/秒只有 1024 dp/秒,
 * 明显比 Apple 慢 (用户 2026-09-15: "角色的左右滚动太慢了"). 逐帧量 Apple TV 的演职人员行:
 * 匀速段 3435 px/秒 = **1717 dp/秒**; 我们 128dp 步距要达到同样速度需 13.4 格/秒, 取 13
 * (13×128 = 1664 dp/秒, 差 3%).
 */
private const val TV_MONOGRAM_MOVE_RATE = 25

/** 格间距: Apple 的 40pt (圆心步距 300pt - 圆 260pt), 与 [TV_MONOGRAM_SIZE] 一起铺满一屏 6 个. */
private val TV_MONOGRAM_SPACING = 20.dp

/**
 * 聚焦时整个 lockup 放大到多少 / 圆上那圈高光多粗.
 *
 * 2026-09-15 在同一块屏上逐帧量 Apple TV 的演职人员行 (录屏 1920×1080, 1 像素 = 1 布局像素):
 * 未聚焦圆直径 260px, 聚焦 300px => **1.154**. Apple 官方 Sketch 模板给的是 290px (四边各 +15), 即 1.115 ——
 * 真机比模板更大一点, **以真机为准**. 社区流传的"tvOS 聚焦缩放 1.1"两者都不是.
 */
/**
 * 聚焦时圆上那圈环的粗细.
 *
 * 实测 Plex (4K 截图, 4 像素 / dp): 沿圆边扫过去是 `-0.5:111  0.0:225  +0.5:221  +1.0:52`,
 * 实心部分约 1dp, 两侧各半格抗锯齿. Apple TV 则**完全不画环**, 只放大 —— 两家不一致, 所以这是
 * 自选项而非规范. 我们取 Plex 的粗细, 颜色用主题色 (用户 2026-09-15 定).
 */
private val TV_MONOGRAM_FOCUS_RING = 1.dp

private const val TV_MONOGRAM_FOCUS_SCALE = 1.115f
/** 圆下缘到姓名的间距: 实测 Apple TV 为 21px = 10.5dp (聚焦与否都一样). */
private val TV_MONOGRAM_TEXT_GAP = 2.dp

/** 姓名与职位两行之间: 实测行距 39px = 19.5dp, 扣掉字号自带的行高后余下这点. */
private val TV_MONOGRAM_LINE_GAP = 2.dp

/**
 * 未聚焦时第二行 (职位 / 声优) 的不透明度.
 *
 * 实测 Apple TV (录屏逐帧取字的墨色): 姓名恒为 255 纯白 —— 聚焦与否都一样; 第二行**聚焦项是 255,
 * 其余是 148~170**, 约白色的 0.63; 扣掉背景本身的亮度反推约 0.6. 主题里的 onSurfaceVariant 在这套
 * 深色配色下是 252, 跟纯白几乎分不出来 (用户 2026-09-15 指出"文字颜色不一致"), 所以这里按透明度给.
 */
private const val TV_MONOGRAM_SUBTITLE_ALPHA = 0.6f

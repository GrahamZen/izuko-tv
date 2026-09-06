/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.data.network.SubjectEntryCandidates
import me.him188.ani.app.data.network.SubjectEntryOption
import me.him188.ani.app.data.network.SubjectFeedbackService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.data.network.TmdbTitleLogoCandidates
import me.him188.ani.app.data.network.TmdbTitleLogoOption
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoFlip
import me.him188.ani.app.ui.foundation.tv.rememberTvTitleLogoLanguage
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.AniFocusRingSurface
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_tv_entry_dialog_description
import me.him188.ani.app.ui.lang.subject_details_tv_entry_dialog_title
import me.him188.ani.app.ui.lang.subject_details_tv_entry_empty
import me.him188.ani.app.ui.lang.subject_details_tv_entry_load_failed
import me.him188.ani.app.ui.lang.subject_details_tv_entry_loading
import me.him188.ani.app.ui.lang.subject_details_tv_entry_none
import me.him188.ani.app.ui.lang.subject_details_tv_entry_submitted
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_cancel
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_confirm_entry
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_confirm_entry_none
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_confirm_logo
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_confirm_logo_not_listed
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_confirm_logo_text
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_confirm_title
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_entry
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_entry_description
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_failed
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_logo
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_logo_description
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_pending_entry
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_pending_logo
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_rate_limited
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_submit
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_title
import me.him188.ani.app.ui.lang.subject_details_tv_feedback_unreachable
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_dialog_description
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_dialog_title
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_done
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_duplicate
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_empty
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_failed
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_load_failed
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_loading
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_no_language
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_qr_hint
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_not_listed
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_not_listed_submitted
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_rate_limited
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_submitted
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_submitting
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_text_option
import me.him188.ani.app.ui.lang.subject_details_tv_title_logo_unreachable
import org.jetbrains.compose.resources.stringResource

/**
 * 详情页「反馈」: 先选反馈哪一类 —— 标题 logo 不对 (设置里关了 logo 就没有这一项, 直接进下一项) / 对应的作品不对 —— 再在那一页选对的那个,
 * 确认之后报告给维护者 ([SubjectFeedbackService], 在对应表仓库开修正请求, 审核通过后写进对应表、所有人换上).
 *
 * - 标题 logo: 列出 TMDB 上各种语言的全部 logo (这种语言 —— 设置里选的, 见 [rememberTvTitleLogoLanguage] —— 的在前, 每张标出语言;
 *   只有英文 logo 的作品中文下也能选英文那张) 与「不用 logo」; 提交时本机立刻换上 ([TmdbImageService.chooseTitleLogo]).
 *   logo 放在深色底上、照详情页的样子翻色 ([TvTitleLogoFlip]). 末格「列表里没有」: TMDB 网页上有而接口不列的 logo (如 SVG 格式的)
 *   在这里选不到, 就只报告「不在列表里」, 不带图 ([SubjectFeedbackService.reportLogoNotListed]), 维护者审核时去 TMDB 上找到补上; 本机不变.
 *   右上角的二维码是 TMDB 网页上这个条目的 logo 页, 用手机看全部 logo (含接口不列的那些), 好判断对的那张在不在列表里.
 *   logo 网格第一行整行、第二行露出一半 (卡片高度按网格的可用高度算), 看得出下面还有.
 * - 对应的作品: 现在对应的与对应表核对页里的备选 ([SubjectFeedbackService.entryCandidates]) 与「TMDB 上没有对应」; 审核通过才生效.
 *
 * 现在用的那格打勾, 进页时焦点在它上面; 选的就是现在这个时直接关. 选了别的先确认 (防误触: 焦点默认在「取消」上), 交完显示结果,
 * 按「完成」关. 提交途中按返回取消提交并关掉 (网络不通时每个中转要等到超时, 不让人干等). 在某一页按返回回到第一步.
 * 连不上 (GitHub、中转、TMDB) 时提示里带一句去设置里配代理.
 * 标题下面一直写着作品名 ([displayName] 与 [originalName]): 详情页标题换成 logo 后, 别处看不到文字名字.
 */
@Composable
internal fun TvSubjectFeedbackDialog(
    subjectId: Int,
    displayName: String,
    originalName: String,
    onDismissRequest: () -> Unit,
) {
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val feedback = remember { GlobalKoin.get<SubjectFeedbackService>() }
    val language = rememberTvTitleLogoLanguage()
    val logoAvailable = LocalThemeSettings.current.tvTitleLogoDisplay != TvTitleLogoDisplay.Off
    var page by remember { mutableStateOf(if (logoAvailable) FeedbackPage.Menu else FeedbackPage.Entry) }
    var pending by remember { mutableStateOf<FeedbackPick?>(null) }
    var submit by remember { mutableStateOf<FeedbackSubmit>(FeedbackSubmit.Idle) }
    var submitJob by remember { mutableStateOf<Job?>(null) }
    // logo 页读到的候选: 标题区要写这一页的说明 (哪种语言)、右上角放二维码 (TMDB 网页上的 logo 页)
    var logoCandidates by remember { mutableStateOf<TmdbTitleLogoCandidates?>(null) }
    val scope = rememberCoroutineScope()

    fun back() {
        when {
            submit == FeedbackSubmit.Submitting -> {
                submitJob?.cancel()
                onDismissRequest()
            }

            submit is FeedbackSubmit.Done -> onDismissRequest()
            pending != null -> pending = null
            page != FeedbackPage.Menu && logoAvailable -> page = FeedbackPage.Menu
            else -> onDismissRequest()
        }
    }

    // 详情页整页关掉了框架的 bring-into-view (按焦点下标自己滚), 弹窗跟着继承; 这里的网格要框架把焦点格滚进视野, 上面让出渐隐那一截
    val density = LocalDensity.current
    val defaultBringIntoView = remember(density) { TopMarginBringIntoViewSpec(with(density) { GRID_TOP_FADE.toPx() }) }
    AniCenteredPanelDialog(
        onDismissRequest = ::back,
        title = {
            val logoHeader = logoCandidates.takeIf { page == FeedbackPage.Logo && pending == null && submit == FeedbackSubmit.Idle }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            when {
                                pending != null && submit == FeedbackSubmit.Idle -> Lang.subject_details_tv_feedback_confirm_title
                                page == FeedbackPage.Logo -> Lang.subject_details_tv_title_logo_dialog_title
                                page == FeedbackPage.Entry -> Lang.subject_details_tv_entry_dialog_title
                                else -> Lang.subject_details_tv_feedback_title
                            },
                        ),
                    )
                    Text(
                        if (originalName.isNotBlank() && originalName != displayName) "$displayName · $originalName" else displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // logo 页的说明写在标题区 (右边是二维码, 这里本来就空着), 下面整块留给网格
                    if (logoHeader != null) {
                        Text(
                            stringResource(
                                if (logoHeader.logos.isEmpty()) {
                                    Lang.subject_details_tv_title_logo_empty
                                } else {
                                    Lang.subject_details_tv_title_logo_dialog_description
                                },
                                languageName(logoHeader.language),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (logoHeader != null) LogoQrCode(tmdbLogosPageUrl(logoHeader))
            }
        },
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoView) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val pick = pending
                when (val s = submit) {
                    FeedbackSubmit.Submitting -> FeedbackStatus(stringResource(Lang.subject_details_tv_title_logo_submitting), progress = true)
                    is FeedbackSubmit.Done -> FeedbackDone(resultText(s.result, s.kind), onDismissRequest)
                    FeedbackSubmit.Idle -> when {
                        pick != null -> FeedbackConfirm(
                            pick,
                            onCancel = { pending = null },
                            onSubmit = {
                                submit = FeedbackSubmit.Submitting
                                submitJob = scope.launch {
                                    submit = when (pick) {
                                        is FeedbackPick.Logo -> {
                                            tmdb.chooseTitleLogo(subjectId, language, pick.candidates, pick.logo)
                                            FeedbackSubmit.Done(
                                                feedback.reportLogo(subjectId, pick.candidates.ref, pick.candidates.language, pick.logo),
                                                FeedbackKind.Logo,
                                            )
                                        }

                                        is FeedbackPick.LogoNotListed -> FeedbackSubmit.Done(
                                            feedback.reportLogoNotListed(subjectId, pick.candidates.ref, pick.candidates.language),
                                            FeedbackKind.LogoNotListed,
                                        )

                                        is FeedbackPick.Entry -> FeedbackSubmit.Done(feedback.reportEntry(subjectId, pick.option), FeedbackKind.Entry)
                                    }
                                }
                            },
                        )

                        page == FeedbackPage.Menu -> FeedbackMenu(onLogo = { page = FeedbackPage.Logo }, onEntry = { page = FeedbackPage.Entry })
                        page == FeedbackPage.Logo -> LogoPage(
                            tmdb,
                            subjectId,
                            originalName,
                            language,
                            onPick = { candidates, logo ->
                                if (logo == candidates.current) onDismissRequest() else pending = FeedbackPick.Logo(candidates, logo)
                            },
                            onNotListed = { candidates -> pending = FeedbackPick.LogoNotListed(candidates) },
                            onLoaded = { candidates -> logoCandidates = candidates },
                        )

                        else -> EntryPage(feedback, subjectId) { candidates, option ->
                            val unchanged = if (option == null) candidates.currentNone else option.ref == candidates.current?.ref
                            if (unchanged) onDismissRequest() else pending = FeedbackPick.Entry(option)
                        }
                    }
                }
            }
        }
    }
}

private enum class FeedbackPage { Menu, Logo, Entry }

/** 选好、等确认的那一项. */
private sealed interface FeedbackPick {
    /** [logo] 为 null = 不用 logo. */
    data class Logo(val candidates: TmdbTitleLogoCandidates, val logo: TmdbTitleLogo?) : FeedbackPick

    /** 该用的 logo 不在列出的候选里. */
    data class LogoNotListed(val candidates: TmdbTitleLogoCandidates) : FeedbackPick

    /** [option] 为 null = TMDB 上没有对应. */
    data class Entry(val option: SubjectEntryOption?) : FeedbackPick
}

private sealed interface FeedbackSubmit {
    data object Idle : FeedbackSubmit
    data object Submitting : FeedbackSubmit
    data class Done(val result: SubjectFeedbackService.Result, val kind: FeedbackKind) : FeedbackSubmit
}

/** 交的是哪一种报告 (结果的说法不同: 选了 logo 的本机已经换上, 另两种本机不变). */
private enum class FeedbackKind { Logo, LogoNotListed, Entry }

/** 交完的结果; 选了 logo 的本机已经换上, 交不出去时也说一句. */
@Composable
private fun resultText(result: SubjectFeedbackService.Result, kind: FeedbackKind): String {
    val logo = kind == FeedbackKind.Logo
    return when (result) {
        is SubjectFeedbackService.Result.Created -> stringResource(
            when (kind) {
                FeedbackKind.Logo -> Lang.subject_details_tv_title_logo_submitted
                FeedbackKind.LogoNotListed -> Lang.subject_details_tv_title_logo_not_listed_submitted
                FeedbackKind.Entry -> Lang.subject_details_tv_entry_submitted
            },
            result.issue,
        )

        is SubjectFeedbackService.Result.Duplicate -> stringResource(Lang.subject_details_tv_title_logo_duplicate, result.issue)
        is SubjectFeedbackService.Result.Pending -> stringResource(
            if (logo) Lang.subject_details_tv_feedback_pending_logo else Lang.subject_details_tv_feedback_pending_entry, result.issue,
        )

        SubjectFeedbackService.Result.RateLimited -> stringResource(
            if (logo) Lang.subject_details_tv_title_logo_rate_limited else Lang.subject_details_tv_feedback_rate_limited,
        )

        SubjectFeedbackService.Result.Unreachable -> stringResource(
            if (logo) Lang.subject_details_tv_title_logo_unreachable else Lang.subject_details_tv_feedback_unreachable,
        )

        SubjectFeedbackService.Result.Failed -> stringResource(
            if (logo) Lang.subject_details_tv_title_logo_failed else Lang.subject_details_tv_feedback_failed,
        )
    }
}

/** 语言码 (如 `ja`) 按界面语言的名字 (「日文」). */
@Composable
private fun languageName(language: String): String {
    val ui = java.util.Locale.forLanguageTag(Locale.current.toLanguageTag())
    return java.util.Locale.forLanguageTag(language).getDisplayLanguage(ui).ifBlank { language }
}

/** 进页 / 换内容时把焦点送到 [requester] 上 (等一帧, 那一格排出来了才送得到). */
@Composable
private fun RequestInitialFocus(requester: FocusRequester, key: Any?) {
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun FeedbackMenu(onLogo: () -> Unit, onEntry: () -> Unit) {
    val requester = remember { FocusRequester() }
    RequestInitialFocus(requester, Unit)
    MenuOption(
        stringResource(Lang.subject_details_tv_feedback_logo),
        stringResource(Lang.subject_details_tv_feedback_logo_description),
        onLogo,
        Modifier.focusRequester(requester),
    )
    MenuOption(
        stringResource(Lang.subject_details_tv_feedback_entry),
        stringResource(Lang.subject_details_tv_feedback_entry_description),
        onEntry,
    )
}

@Composable
private fun MenuOption(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AniFocusRingSurface(onClick = onClick, selected = false, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Failed : Load<Nothing>
    data class Loaded<T>(val value: T) : Load<T>
}

@Composable
private fun LogoPage(
    tmdb: TmdbImageService,
    subjectId: Int,
    originalName: String,
    language: String?,
    onPick: (TmdbTitleLogoCandidates, TmdbTitleLogo?) -> Unit,
    onNotListed: (TmdbTitleLogoCandidates) -> Unit,
    onLoaded: (TmdbTitleLogoCandidates) -> Unit,
) {
    var load by remember { mutableStateOf<Load<TmdbTitleLogoCandidates>>(Load.Loading) }
    LaunchedEffect(tmdb, subjectId, originalName, language) {
        val candidates = tmdb.getTitleLogoCandidates(subjectId, originalName, language)
        candidates?.let(onLoaded)
        load = candidates?.let { Load.Loaded(it) } ?: Load.Failed
    }
    when (val state = load) {
        Load.Loading -> FeedbackStatus(stringResource(Lang.subject_details_tv_title_logo_loading), progress = true)
        Load.Failed -> FeedbackStatus(stringResource(Lang.subject_details_tv_title_logo_load_failed), progress = false)
        is Load.Loaded -> LogoPicker(state.value, onPick = { onPick(state.value, it) }, onNotListed = { onNotListed(state.value) })
    }
}

@Composable
private fun LogoPicker(candidates: TmdbTitleLogoCandidates, onPick: (TmdbTitleLogo?) -> Unit, onNotListed: () -> Unit) {
    // 第一格「不用 logo」, 之后各语言的 logo (这种语言的在前), 末格「列表里没有」; 现在用的那格 (没有 logo = 第一格) 打勾、先拿焦点
    val options: List<TmdbTitleLogoOption?> = listOf(null) + candidates.logos
    val selectedIndex = options.indexOfFirst { it?.logo == candidates.current }.coerceAtLeast(0)
    val noLanguage = stringResource(Lang.subject_details_tv_title_logo_no_language)
    val requester = remember { FocusRequester() }
    RequestInitialFocus(requester, candidates)
    BoxWithConstraints {
        // 第一行整行、第二行露出一半: 看得出下面还有
        val cardHeight = if (maxHeight == Dp.Infinity) {
            LOGO_CARD_HEIGHT
        } else {
            ((maxHeight - LOGO_GRID_PADDING - LOGO_GRID_SPACING) / LOGO_GRID_VISIBLE_ROWS).coerceIn(LOGO_CARD_MIN_HEIGHT, LOGO_CARD_MAX_HEIGHT)
        }
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex)
        LazyVerticalGrid(
            GridCells.Fixed(LOGO_GRID_COLUMNS),
            Modifier.fadeTopWhenScrolled(gridState),
            state = gridState,
            contentPadding = PaddingValues(LOGO_GRID_PADDING),
            horizontalArrangement = Arrangement.spacedBy(LOGO_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(LOGO_GRID_SPACING),
        ) {
            itemsIndexed(options) { index, option ->
                val selected = index == selectedIndex
                OptionCard(
                    selected = selected,
                    onClick = { onPick(option?.logo) },
                    modifier = Modifier.height(cardHeight).then(if (selected) Modifier.focusRequester(requester) else Modifier),
                ) {
                    LogoPreview(option?.logo, label = option?.let { it.language?.let { lang -> languageName(lang) } ?: noLanguage })
                }
            }
            item {
                OptionCard(selected = false, onClick = onNotListed, modifier = Modifier.height(cardHeight)) {
                    LogoTextCard(stringResource(Lang.subject_details_tv_title_logo_not_listed))
                }
            }
        }
    }
}

/** logo 页右上角: TMDB 网页上这部的 logo 页的二维码, 下面一句说明 (不占标题区的宽, 左边的说明能写两行). */
@Composable
private fun LogoQrCode(url: String) {
    Column(Modifier.width(LOGO_QR_SIZE + LOGO_QR_QUIET_ZONE * 2), horizontalAlignment = Alignment.CenterHorizontally) {
        QrCodeImage(url, Modifier.size(LOGO_QR_SIZE), quietZone = LOGO_QR_QUIET_ZONE)
        Text(
            stringResource(Lang.subject_details_tv_title_logo_qr_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 网格往上翻过时 (上面还有内容), 滚到顶边的那一截 ([GRID_TOP_FADE]) 渐隐成透明, 不是被标题区下缘一刀切掉; 停在最上面时不渐隐.
 * 进度读在绘制里, 滚动不重组.
 */
private fun Modifier.fadeTopWhenScrolled(state: LazyGridState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (state.canScrollBackward) {
            val fade = GRID_TOP_FADE.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * 焦点格滚进视野时上边多留 [topMarginPx] (渐隐那一截, 见 [fadeTopWhenScrolled]), 焦点所在的那一行不落在半透明里; 其余同默认 (整格看得见就不滚).
 */
private class TopMarginBringIntoViewSpec(private val topMarginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - topMarginPx
        val end = offset + size
        return when {
            start >= 0f && end <= containerSize -> 0f
            // 比视野还高: 顶边对齐, 不来回抖
            end - start > containerSize -> start
            start < 0f -> start
            else -> end - containerSize
        }
    }
}

/** TMDB 网页上 [candidates] 那个条目、那种语言的 logo 页 (网页上列全部 logo, 接口不列的 SVG 格式的也在; 侧栏能换语言). */
private fun tmdbLogosPageUrl(candidates: TmdbTitleLogoCandidates): String =
    "https://www.themoviedb.org/${candidates.ref}/images/logos?image_language=${candidates.language}"

/** 深色底上的一行字, 占一格 logo 的位置 (如「列表里没有」). */
@Composable
private fun LogoTextCard(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(LOGO_BACKDROP).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        LogoCardText(text)
    }
}

@Composable
private fun LogoCardText(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
}

/** 深色底上的一张 logo (照详情页的样子翻色), [logo] 为 null 时是「不用 logo」; [label] = 标在下面的语言. */
@Composable
private fun LogoPreview(logo: TmdbTitleLogo?, label: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(LOGO_BACKDROP).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (logo == null) {
            LogoCardText(stringResource(Lang.subject_details_tv_title_logo_text_option))
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AsyncImage(
                    logo.url(LOGO_IMAGE_WIDTH_PX),
                    contentDescription = logo.filePath,
                    Modifier.fillMaxWidth().weight(1f),
                    contentScale = ContentScale.Fit,
                    crossfade = false,
                    transformations = listOf(TvTitleLogoFlip(lightText = true)),
                )
                if (label != null) {
                    Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EntryPage(
    feedback: SubjectFeedbackService,
    subjectId: Int,
    onPick: (SubjectEntryCandidates, SubjectEntryOption?) -> Unit,
) {
    var load by remember { mutableStateOf<Load<SubjectEntryCandidates>>(Load.Loading) }
    LaunchedEffect(feedback, subjectId) {
        load = feedback.entryCandidates(subjectId)?.let { Load.Loaded(it) } ?: Load.Failed
    }
    when (val state = load) {
        Load.Loading -> FeedbackStatus(stringResource(Lang.subject_details_tv_entry_loading), progress = true)
        Load.Failed -> FeedbackStatus(stringResource(Lang.subject_details_tv_entry_load_failed), progress = false)
        is Load.Loaded -> EntryPicker(state.value) { onPick(state.value, it) }
    }
}

@Composable
private fun EntryPicker(candidates: SubjectEntryCandidates, onPick: (SubjectEntryOption?) -> Unit) {
    Text(
        stringResource(Lang.subject_details_tv_entry_dialog_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (candidates.options.isEmpty()) {
        Text(stringResource(Lang.subject_details_tv_entry_empty), style = MaterialTheme.typography.bodyMedium)
    }
    // 现在对应的与备选在前,「TMDB 上没有对应」最后; 现在的那格打勾、先拿焦点
    val options: List<SubjectEntryOption?> = candidates.options + null
    val selectedIndex = when {
        candidates.currentNone -> options.lastIndex
        else -> options.indexOfFirst { it != null && it.ref == candidates.current?.ref }.coerceAtLeast(0)
    }
    val requester = remember { FocusRequester() }
    RequestInitialFocus(requester, candidates)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex)
    LazyVerticalGrid(
        GridCells.Fixed(ENTRY_GRID_COLUMNS),
        Modifier.fadeTopWhenScrolled(gridState),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(options) { index, option ->
            val selected = index == selectedIndex
            OptionCard(
                selected = selected,
                onClick = { onPick(option) },
                modifier = if (selected) Modifier.focusRequester(requester) else Modifier,
            ) {
                EntryPreview(option)
            }
        }
    }
}

/** 一部作品: 背景图缩略图 + 名字 + 条目与日期; [option] 为 null 时是「TMDB 上没有对应」. */
@Composable
private fun EntryPreview(option: SubjectEntryOption?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(LOGO_BACKDROP),
            contentAlignment = Alignment.Center,
        ) {
            val url = option?.backdropThumbnailUrl
            if (url != null) {
                AsyncImage(url, contentDescription = option.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, crossfade = false)
            } else if (option == null) {
                Text(
                    stringResource(Lang.subject_details_tv_entry_none),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (option != null) {
            Text(option.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(option.ref, option.date).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 一个选项格: 选中 (现在用的) 右上角打勾. */
@Composable
private fun OptionCard(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AniFocusRingSurface(onClick = onClick, selected = selected, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Box(Modifier.padding(6.dp)) {
            content()
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
            }
        }
    }
}

/** 提交前确认: 选中的那项 + 一句说明 + 「取消」「提交」(防误触: 焦点默认在「取消」). */
@Composable
private fun FeedbackConfirm(pick: FeedbackPick, onCancel: () -> Unit, onSubmit: () -> Unit) {
    val text = when (pick) {
        is FeedbackPick.Logo -> {
            val name = languageName(pick.candidates.language)
            if (pick.logo == null) {
                stringResource(Lang.subject_details_tv_feedback_confirm_logo_text, name)
            } else {
                stringResource(Lang.subject_details_tv_feedback_confirm_logo, name)
            }
        }

        is FeedbackPick.LogoNotListed -> stringResource(
            Lang.subject_details_tv_feedback_confirm_logo_not_listed,
            languageName(pick.candidates.language),
        )

        is FeedbackPick.Entry -> pick.option?.let { stringResource(Lang.subject_details_tv_feedback_confirm_entry, it.name) }
            ?: stringResource(Lang.subject_details_tv_feedback_confirm_entry_none)
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
    Box(Modifier.width(CONFIRM_PREVIEW_WIDTH)) {
        when (pick) {
            is FeedbackPick.Logo -> Box(Modifier.height(LOGO_CARD_HEIGHT)) { LogoPreview(pick.logo, label = null) }
            is FeedbackPick.LogoNotListed -> Box(Modifier.height(LOGO_CARD_HEIGHT)) {
                LogoTextCard(stringResource(Lang.subject_details_tv_title_logo_not_listed))
            }
            is FeedbackPick.Entry -> EntryPreview(pick.option)
        }
    }
    val cancel = remember { FocusRequester() }
    RequestInitialFocus(cancel, pick)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AniFocusActionButton(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) {
            Text(stringResource(Lang.subject_details_tv_feedback_cancel))
        }
        AniFocusActionButton(onClick = onSubmit) {
            Text(stringResource(Lang.subject_details_tv_feedback_submit))
        }
    }
}

@Composable
private fun FeedbackStatus(text: String, progress: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (progress) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 一句结果 + 「完成」(焦点在它上面). */
@Composable
private fun FeedbackDone(text: String, onDismissRequest: () -> Unit) {
    val requester = remember { FocusRequester() }
    RequestInitialFocus(requester, Unit)
    Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    AniFocusActionButton(onClick = onDismissRequest, modifier = Modifier.focusRequester(requester)) {
        Text(stringResource(Lang.subject_details_tv_title_logo_done))
    }
}

private const val LOGO_GRID_COLUMNS = 3
private const val ENTRY_GRID_COLUMNS = 3
/** 确认页上 logo 预览的高; 网格里的卡片高度按可用高度算, 夹在 [LOGO_CARD_MIN_HEIGHT]..[LOGO_CARD_MAX_HEIGHT]. */
private val LOGO_CARD_HEIGHT = 128.dp
private val LOGO_CARD_MIN_HEIGHT = 112.dp
private val LOGO_CARD_MAX_HEIGHT = 220.dp
private val LOGO_GRID_PADDING = 4.dp
private val LOGO_GRID_SPACING = 12.dp

/** logo 网格一屏露出几行 (第二行露一半). */
private const val LOGO_GRID_VISIBLE_ROWS = 1.5f
private val CONFIRM_PREVIEW_WIDTH = 280.dp
/** 网格顶边渐隐的那一截 (见 [fadeTopWhenScrolled]). */
private val GRID_TOP_FADE = 24.dp

private val LOGO_QR_SIZE = 96.dp
private val LOGO_QR_QUIET_ZONE = 8.dp

/** 卡里的图按这个宽取 (w500 档, 一格两三百像素宽够用). */
private const val LOGO_IMAGE_WIDTH_PX = 500

/** logo 与背景图缩略图底下的深色底: 详情页的 logo 压在背景图上, 这里也放深底看 (白字 logo 在面板色上看不清). */
private val LOGO_BACKDROP = Color.Black.copy(alpha = 0.6f)

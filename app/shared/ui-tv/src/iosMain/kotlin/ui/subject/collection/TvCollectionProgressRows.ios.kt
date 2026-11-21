package me.him188.ani.app.ui.subject.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageRows
import me.him188.ani.app.ui.foundation.tv.wall.TvWallGridRowsSpec

/** 「追更」那份内容画 Compose 版的行式海报墙 (网格页的宾客, 见 TvWallGridRows.kt): 只要几何与卡宽. */
@Composable
internal actual fun rememberTvCollectionProgressRows(key: Int): TvNativeGridPageRows {
    val layout = tvCollectionProgressLayout()
    return remember(key, layout.metrics, layout.cardWidth) { TvWallGridRowsSpec(key, layout.metrics, layout.cardWidth) }
}

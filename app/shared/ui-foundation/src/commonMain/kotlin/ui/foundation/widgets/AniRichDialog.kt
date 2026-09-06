package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.CardDefaults
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount

/**
 * @param buttons aligned to the end; `null` 时整行 (含它的上间距) 不渲染 —— 按钮全部由返回键
 * 代替的形态 (遥控器) 下留着会在底部空出一块
 */
@Composable
fun RichDialogLayout(
    title: @Composable RowScope.() -> Unit,
    buttons: (@Composable RowScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: @Composable (RowScope.() -> Unit)? = null,
    description: @Composable (RowScope.() -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // TV: 与别的弹窗同一套 —— 半透明底色 (Card 的默认是不透明的 surfaceContainerLow, 盖在播放画面上就是一块板子)、
    // 圆角与内边距、窗外压暗、对话框按钮画成动作按钮. 手机 / 桌面保持 Card 原默认不动
    val panelStyle = LocalAniUiBehavior.current.focusDrivenNavigation
    if (panelStyle) {
        // 本布局总是某个对话框的内容, 这里调得到它的窗口; 不在对话框里时为空操作
        DialogWindowDimAmount(CENTERED_PANEL_WINDOW_DIM)
    }
    Card(
        modifier,
        shape = if (panelStyle) CENTERED_PANEL_SHAPE else CardDefaults.shape,
        colors = if (panelStyle) {
            // 半透明底在配色表里查不到 "on" 色, 内容色必须显式给 (见 centeredPanelColor)
            CardDefaults.cardColors(containerColor = aniDialogContainerColor(), contentColor = MaterialTheme.colorScheme.onSurface)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        ProvidePopupControlStyle {
            Box {
                Column(Modifier.padding(if (panelStyle) CENTERED_PANEL_CONTENT_PADDING else PaddingValues(16.dp))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                            title()
                        }
                    }

                    subtitle?.let {
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                                subtitle()
                            }
                        }
                    }

                    description?.let {
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                description()
                            }
                        }
                    }

                    Column(Modifier.padding(top = 16.dp)) {
                        content()
                    }

                    buttons?.let {
                        Row(
                            Modifier.padding(top = 16.dp).align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            it()
                        }
                    }
                }

                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    topBarActions()
                }
            }
        }
    }
}


// matches material design
///**
// * @param buttons aligned to the end
// */
//@Composable
//fun RichDialogLayout(
//    title: @Composable RowScope.() -> Unit,
//    buttons: @Composable RowScope.() -> Unit,
//    modifier: Modifier = Modifier,
//    subtitle: @Composable (RowScope.() -> Unit)? = null,
//    description: @Composable (RowScope.() -> Unit)? = null,
//    topBarActions: @Composable RowScope.() -> Unit = {},
//    content: @Composable ColumnScope.() -> Unit,
//) {
//    Surface(
//        modifier.wrapContentSize(),
//        shape = AlertDialogDefaults.shape,
//        tonalElevation = AlertDialogDefaults.TonalElevation,
//        color = AlertDialogDefaults.containerColor,
//    ) {
//        Box {
//            Column(Modifier.padding(16.dp)) {
//                Row(verticalAlignment = Alignment.CenterVertically) {
//                    ProvideTextStyleContentColor(
//                        MaterialTheme.typography.titleLarge,
//                        AlertDialogDefaults.titleContentColor
//                    ) {
//                        title()
//                    }
//                }
//
//                subtitle?.let {
//                    Row(
//                        Modifier.padding(top = 8.dp),
//                        verticalAlignment = Alignment.CenterVertically
//                    ) {
//                        ProvideTextStyleContentColor(
//                            MaterialTheme.typography.bodyLarge,
//                            AlertDialogDefaults.titleContentColor
//                        ) {
//                            subtitle()
//                        }
//                    }
//                }
//
//                description?.let {
//                    Row(
//                        Modifier.padding(top = 8.dp),
//                        verticalAlignment = Alignment.CenterVertically
//                    ) {
//                        ProvideTextStyleContentColor(
//                            MaterialTheme.typography.bodyMedium,
//                            AlertDialogDefaults.textContentColor
//                        ) {
//                            description()
//                        }
//                    }
//                }
//
//                Column(Modifier.padding(top = 16.dp)) {
//                    content()
//                }
//
//                Row(
//                    Modifier.padding(top = 16.dp).align(Alignment.End),
//                    horizontalArrangement = Arrangement.spacedBy(8.dp),
//                    verticalAlignment = Alignment.CenterVertically
//                ) {
//                    buttons()
//                }
//            }
//
//            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
//                topBarActions()
//            }
//        }
//    }
//}

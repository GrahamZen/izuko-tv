/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    id("ani.kmp-compose")
}

/*
 * 平板触屏界面: 照电视版海报墙 (ui-tv) 的样子画, 交互换成触屏.
 *
 * 与 ui-tv 不同, 本模块是普通的多平台模块: iOS 入口与 Android phone 变体的入口都装它 (见 [InstallPadPageVariants]).
 * 依赖方向同 ui-tv: 本模块 -> 各共享界面模块, 共享模块不得反向依赖本模块; 页面经各页的 Local*PageVariant 插槽装进去.
 */
kotlin {
    android {
        namespace = "me.him188.ani.app.ui.pad"
    }
    sourceSets.commonMain.dependencies {
        api(projects.app.shared.uiExploration)
        implementation(projects.app.shared.uiSubject)
        implementation(libs.compose.components.resources)
    }
}

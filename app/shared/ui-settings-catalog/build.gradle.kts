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
 * 设置清单: 电视设置页的全部设置项写成的数据 (分类、开关、单选、动作、动态行), 连同按清单建页面的模型与状态.
 * 各端的设置页按同一份清单画 (现在是电视版 ui-tv 的原生三栏); 平台的系统功能走 expect/actual (TvSettingsPlatformApis),
 * 只有电视才有的东西 (Web 控制台、首次引导、性能诊断等) 经 [TvSettingsPlatform] 由电视版注入.
 */
kotlin {
    android {
        namespace = "me.him188.ani.app.ui.settings.catalog"
    }
    sourceSets.commonMain.dependencies {
        api(projects.app.shared.uiSettings)
        implementation(projects.app.shared.videoPlayer.videoPlayerApi)
        implementation(libs.compose.components.resources)
        implementation(libs.koin.core)
        implementation(libs.kotlinx.datetime)
    }
    sourceSets.commonTest.dependencies {
        implementation(projects.utils.testing)
    }
}

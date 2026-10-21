/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import org.gradle.api.Project

/** `torrent/anitorrent/native/repo` 里重编的 anitorrent 原生库版本, 见 `torrent/anitorrent/native/README.md`. */
const val REBUILT_ANITORRENT_NATIVE_VERSION = "0.2.0-izuko6"

/**
 * 安卓的 anitorrent 原生库与 Java 绑定换成本仓库重编的版本 (上游 0.2.0 加了写盘回调与整季包里单独删一集).
 * 上游的 debug 包也换成它: 两者只差编译选项. 桌面端的原生库仍是上游 0.2.0, 不带这两样 (调用方会跳过).
 */
fun Project.useRebuiltAnitorrentNative() {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            val android = module("org.openani.anitorrent:anitorrent-native-android:$REBUILT_ANITORRENT_NATIVE_VERSION")
            substitute(module("org.openani.anitorrent:anitorrent-native-android")).using(android)
            substitute(module("org.openani.anitorrent:anitorrent-native-android-debug")).using(android)
            substitute(module("org.openani.anitorrent:anitorrent-native-desktop-jni"))
                .using(module("org.openani.anitorrent:anitorrent-native-desktop-jni:$REBUILT_ANITORRENT_NATIVE_VERSION"))
        }
    }
}

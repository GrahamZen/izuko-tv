/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    id("ani.android-application")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.google.gms.google.services)
    idea
}

val archs = getPropertyOrNull("ani.android.abis")
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?.let { abis ->
        if (abis.size == 1 && abis.first() == "all") {
            listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        } else {
            abis
        }
    }
    ?: listOf("arm64-v8a")

android {
    namespace = "me.him188.ani.android"
    compileSdk = getIntProperty("android.compile.sdk")
    defaultConfig {
        applicationId = "me.him188.ani"
        minSdk = androidMinSdk
        targetSdk = getIntProperty("android.compile.sdk")
        versionCode = getIntProperty("android.version.code")
        versionName = project.version.toString()
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters.clear()
            //noinspection ChromeOsAbiSupport
            abiFilters += archs
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include(*archs.toTypedArray())
            isUniversalApk = true // 额外构建一个
        }
    }
    signingConfigs {
        kotlin.runCatching { getProperty("signing_release_storeFileFromRoot") }.getOrNull()?.let {
            create("release") {
                storeFile = rootProject.file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
        kotlin.runCatching { getProperty("signing_release_storeFile") }.getOrNull()?.let {
            create("release") {
                storeFile = file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
    }
    packaging {
        jniLibs {
            // FFmpeg is launched as a process, so the native binary must be extracted to a filesystem path.
            useLegacyPackaging = true
        }
        resources {
            merges.add("META-INF/DEPENDENCIES") // log4j
            pickFirsts.add("META-INF/LICENSE.md")
            pickFirsts.add("META-INF/LICENSE-notice.md")

        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                *sharedAndroidProguardRules(),
            )
        }
        debug {
            applicationIdSuffix = getLocalProperty("ani.android.debug.applicationIdSuffix") ?: ".debug2"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    flavorDimensions += "distribution"
    flavorDimensions += "formFactor"
    productFlavors {
        create("default") {
            dimension = "distribution"
        }
        /*
         * 形态维度: 手机/平板 与 Android TV 出两个独立 APK.
         *
         * TV 变体多出的东西全部按 flavor 隔离, phone 变体一行不受影响:
         * - src/tv/AndroidManifest.xml: LEANBACK 启动器入口 / banner / 屏保服务 / EPG 权限
         * - src/tv/kotlin: 形态接缝实现 + 主屏频道 + 屏保 (见 src/phone 下的同名接缝)
         * - tvImplementation: 只有 TV 变体打包遥控器界面与 tvprovider
         */
        create("phone") {
            dimension = "formFactor"
        }
        create("tv") {
            isDefault = true
            dimension = "formFactor"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        // 只在兼容包上开: minSdk 低于 26 时系统里没有 java.time (kotlinx-datetime 等在用), 需要 D8 回填.
        // 这是模块级设置, 开了就是所有用户一起换成回填实现, 所以正式包坚决不开. 见 buildLegacyAndroidApp.
        isCoreLibraryDesugaringEnabled = buildLegacyAndroidApp
    }
}

dependencies {
    implementation(projects.app.shared)
    implementation(projects.app.shared.application)
    // TV (遥控器) 专属界面与主屏频道 API: 只进 tv 变体, phone 包里一个类都没有
    "tvImplementation"(projects.app.shared.uiTv)
    "tvImplementation"(libs.androidx.tvprovider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)

//    implementation(libs.log4j.core)
//    implementation(libs.log4j.slf4j.impl)

    implementation(libs.ktor.client.core)
    implementation(libs.mediamp.ffmpeg)

    // 必须与 isCoreLibraryDesugaringEnabled 同进同退: 只挂依赖不开开关, AGP 会直接报错.
    if (buildLegacyAndroidApp) {
        coreLibraryDesugaring(libs.android.desugar.jdk.libs)
    }
}

idea {
    module {
        excludeDirs.add(file(".cxx"))
    }
}

googleServices {
    missingGoogleServicesStrategy = (getLocalProperty("ani.enable.firebase") ?: "false").toBooleanStrict()
        .let {
            if (it) MissingGoogleServicesStrategy.ERROR else MissingGoogleServicesStrategy.IGNORE
        }
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

// 换了包名 (org.jsoup -> me.him188.ani.shaded.jsoup) 的 jsoup. 代码里一律用这一份, 不要直接依赖 libs.jsoup.
//
// 有的电视 ROM 往 BOOTCLASSPATH 里塞了自己的 org.jsoup (例如 TCL 的 /system/framework/com.tcl.tclvoicecontrol.jar,
// 还是混淆过的). 类加载双亲优先, APK 里的 org.jsoup 被整个遮蔽: QueryParser 用不了, Element.select(Evaluator) 不存在,
// 所有 selector 在线源静默 0 结果 (issue #12). 换了包名就和 ROM 那份毫无关系.
// R8 不会替我们改名: proguard 里有 -keepnames class **.

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

val bundledJsoup: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundledJsoup(libs.jsoup)
}

tasks.shadowJar {
    configurations.set(listOf(bundledJsoup))
    relocate("org.jsoup", "me.him188.ani.shaded.jsoup")
    archiveClassifier.set("")
    // Android 不认 multi-release; 桌面端也用不到那几个 Java 9+ 的 HTTP 辅助类 (我们不用 jsoup 发请求).
    exclude("META-INF/versions/**", "META-INF/maven/**")
}

tasks.jar {
    enabled = false
}

// 依赖本模块的项目拿到的是换过包名的 jar, 而不是空 jar 加原版 jsoup.
for (name in listOf("apiElements", "runtimeElements")) {
    configurations.named(name) {
        outgoing.artifacts.clear()
        outgoing.variants.clear()
        outgoing.artifact(tasks.shadowJar)
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8) // jsoup 字节码是 Java 8
    }
}

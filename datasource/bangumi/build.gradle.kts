/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("ani.kmp-library")
    alias(libs.plugins.kotlin.plugin.serialization)
    idea
    alias(libs.plugins.openapi.generator)
}

val generatedRoot = "generated/openapi"

kotlin {
    android {
        namespace = "me.him188.ani.datasources.bangumi"
    }
    sourceSets.commonMain.dependencies {
        api(projects.datasource.datasourceApi)
        api(libs.kotlinx.datetime)
        api(libs.kotlinx.coroutines.core)
        api(libs.androidx.collection)

        implementation(projects.utils.coroutines)
        implementation(projects.utils.serialization)
        implementation(libs.ktor.client.logging)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
    }
    sourceSets.commonMain {
        kotlin.srcDirs(file("src/commonMain/gen"))
    }
    sourceSets.getByName("desktopTest").dependencies {
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.ktor.client.cio) // BangumiSpecSmokeTest 打真实网络
    }
}

idea {
    module {
        generatedSourceDirs.add(file("src/commonMain/gen"))
    }
}

// https://github.com/OpenAPITools/openapi-generator/blob/master/modules/openapi-generator-gradle-plugin/README.adoc
// 两个 generate 任务输出到同一个目录, 而生成器不会清理自己的输出.
// 不清的话, spec 里去掉的端点会留下上一轮的文件, 被 copyGeneratedToSrc 同步进仓库.
val cleanGeneratedOpenApi = tasks.register("cleanGeneratedOpenApi", Delete::class) {
    delete(layout.buildDirectory.dir(generatedRoot))
}

val generateApiV0 = tasks.register("generateApiV0", GenerateTask::class) {
    dependsOn(cleanGeneratedOpenApi)
    generatorName.set("kotlin")
    inputSpec.set("$projectDir/v0.yaml")
    outputDir.set(layout.buildDirectory.file(generatedRoot).get().asFile.absolutePath)
    packageName.set("me.him188.ani.datasources.bangumi")
    modelNamePrefix.set("Bangumi")
    apiNameSuffix.set("BangumiApi")
    // https://github.com/OpenAPITools/openapi-generator/blob/master/docs/generators/kotlin.md
    additionalProperties.set(
        mapOf(
            "apiSuffix" to "BangumiApi",
            "library" to "multiplatform",
            "dateLibrary" to "kotlinx-datetime",
//            "serializationLibrary" to "kotlinx_serialization", // 加了这个他会生成两个 `@Serializable`
            "enumPropertyNaming" to "UPPERCASE",
//            "generateOneOfAnyOfWrappers" to "true",
            "omitGradleWrapper" to "true",
        ),
    )
    generateModelTests.set(false)
    generateApiTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)

//    typeMappings.put("BangumiValue", "kotlinx.serialization.json.JsonElement")
//    schemaMappings.put("WikiV0", "kotlinx.serialization.json.JsonElement") // works
//    schemaMappings.put("Item", "kotlinx.serialization.json.JsonElement")
//    schemaMappings.put("Value", "kotlinx.serialization.json.JsonElement")
    typeMappings.put(
        "kotlin.Double",
        "@Serializable(me.him188.ani.utils.serialization.BigNumAsDoubleStringSerializer::class) me.him188.ani.utils.serialization.BigNum",
    )
//    typeMappings.put("BangumiEpisodeCollectionType", "/*- `0`: 未收藏 - `1`: 想看 - `2`: 看过 - `3`: 抛弃*/ Int")
}

val stripeApiP1 = tasks.register("stripeApiP1") {
    val strippedP1File = layout.buildDirectory.file("p1-stripped.yaml")
    val inputFile = file("$projectDir/p1.yaml")
    inputs.file(inputFile)
    outputs.file(strippedP1File)

    /**
     * 我们只需要保留 app 实际会调用的那些 API.
     *
     * schema 不再用白名单列举, 而是从保留的 path 出发做 `$ref` 传递闭包:
     * 白名单漏掉一个 schema 时, 生成器不报错, 只会把该字段静默生成成 `Any`.
     */
    doLast {
        val yaml = org.yaml.snakeyaml.Yaml()
        val p1ApiObject: Map<String, Any> = inputFile.inputStream().use { yaml.load(it) }

        val paths = p1ApiObject["paths"].cast<Map<String, *>>().toMutableMap()
        val keepPaths = listOf(
            // 条目
            "/p1/subjects/{subjectID}", // 详情 (含 interest = 自己的收藏状态)
            "/p1/subjects/{subjectID}/episodes", // 分集列表 (带 auth 时含每集 collection.status)
            "/p1/subjects/{subjectID}/relations", // 关联条目
            "/p1/subjects/{subjectID}/characters", // 角色 (含声优)
            // 制作人员. 用 positions 而不是 persons: 前者按职位分组、一页给全 (52 个职位 vs 256 个人分 3 页)、
            // 且已按职位号排好序 (原作/导演在前), 与 Ani 那个端点的展示顺序一致.
            "/p1/subjects/{subjectID}/staffs/positions",
            "/p1/subjects/{subjectID}/staffs/persons",
            "/p1/subjects/{subjectID}/recs", // 猜你喜欢
            "/p1/subjects/{subjectID}/comments", // 条目吐槽箱, 作为条目评论
            "/p1/subjects/-/comments/{commentID}", // 条目评论的增删改
            // 分集
            "/p1/episodes/{episodeID}", // 单集信息
            "/p1/episodes/{episodeID}/comments", // 剧集吐槽箱, 作为剧集评论
            "/p1/episodes/-/comments/{commentID}", // 剧集评论的增删改
            // 收藏
            "/p1/collections/subjects", // 追番列表
            "/p1/collections/subjects/{subjectID}", // 改收藏状态/评分/短评
            "/p1/collections/episodes/{episodeID}", // 标记看过 (batch=true 表示"看到这一集为止")
            // 人物 / 角色
            "/p1/persons/{personID}",
            "/p1/persons/{personID}/casts",
            "/p1/persons/{personID}/comments",
            "/p1/characters/{characterID}",
            "/p1/characters/{characterID}/casts",
            "/p1/characters/{characterID}/comments",
            // 探索 / 搜索 / 时间表
            "/p1/trending/subjects",
            "/p1/search/subjects",
            // "/p1/calendar" 不在这里: 它的响应是 Map<String, List<CalendarItem>>, 生成器对这个形状
            // 会产出不带类型参数的 `List` 而编译不过. 时间表的第二级兜底需要它时手写一次请求.
            // 用户
            "/p1/me",
        )
        val subjectPaths = paths.filterKeys { it in keepPaths }
        val missing = keepPaths.filter { it !in paths }
        check(missing.isEmpty()) { "These paths are not present in p1.yaml: $missing" }
        println("The following paths are kept: ${subjectPaths.keys}")

        // keep components transitively referred by the kept paths
        val components = p1ApiObject["components"].cast<Map<String, *>>().toMutableMap()
        components.remove("securitySchemes")
        val schemas = components["schemas"].cast<Map<String, *>>().toMutableMap()

        fun collectRefs(node: Any?, into: MutableSet<String>) {
            when (node) {
                is Map<*, *> -> node.forEach { (key, value) ->
                    if (key == "\$ref" && value is String) {
                        value.substringAfterLast("/components/schemas/", "")
                            .takeIf { it.isNotEmpty() }
                            ?.let { into.add(it) }
                    } else {
                        collectRefs(value, into)
                    }
                }

                is List<*> -> node.forEach { collectRefs(it, into) }
            }
        }

        val reachable = mutableSetOf<String>()
        collectRefs(subjectPaths, reachable)
        var frontier = reachable.toSet()
        while (frontier.isNotEmpty()) {
            val next = mutableSetOf<String>()
            frontier.forEach { collectRefs(schemas[it], next) }
            frontier = next - reachable
            reachable.addAll(frontier)
        }
        val unknown = reachable.filter { it !in schemas }
        check(unknown.isEmpty()) { "Dangling \$ref in p1.yaml: $unknown" }
        val keepSchemas = schemas.filterKeys { it in reachable }.toMutableMap()
        println("${keepSchemas.size} of ${schemas.size} schemas are kept")

        // 生成器不会给 additionalProperties.items 里的匿名 object 起名字, 会产出不带类型参数的 `List`.
        // Calendar 是 `{ "1".."7": [{subject, watchers}] }`, 把那个匿名 object 提升成具名 schema.
        keepSchemas["Calendar"]?.cast<MutableMap<String, Any?>>()
            ?.get("additionalProperties")?.cast<MutableMap<String, Any?>>()
            ?.takeIf { it["type"] == "array" && it["items"].cast<Map<String, *>>().containsKey("properties") }
            ?.let { array ->
                keepSchemas["CalendarItem"] = array["items"].cast()
                array["items"] = mapOf("\$ref" to "#/components/schemas/CalendarItem")
            }

        val strippedApiObject = mutableMapOf<String, Any>().apply {
            put("openapi", p1ApiObject["openapi"].cast())
            put("info", p1ApiObject["info"].cast())
            put("paths", subjectPaths)
            put("components", mapOf("schemas" to keepSchemas))
        }

        strippedP1File.get().asFile.writeText(yaml.dump(strippedApiObject))
    }
}

val generateApiP1 = tasks.register("generateApiP1", GenerateTask::class) {
    dependsOn(cleanGeneratedOpenApi)
    generatorName.set("kotlin")
    inputSpec.set(stripeApiP1.get().outputs.files.singleFile.absolutePath)
    outputDir.set(layout.buildDirectory.file(generatedRoot).get().asFile.absolutePath)
    packageName.set("me.him188.ani.datasources.bangumi.next")
    modelNamePrefix.set("BangumiNext")
    apiNameSuffix.set("BangumiNextApi")
    additionalProperties.set(
        mapOf(
            "apiSuffix" to "BangumiNextApi",
            "library" to "multiplatform",
            "dateLibrary" to "kotlinx-datetime",
            "enumPropertyNaming" to "UPPERCASE",
            "omitGradleWrapper" to "true",
            "generateOneOfAnyOfWrappers" to "true",
        ),
    )
    generateModelTests.set(false)
    generateApiTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    validateSpec.set(false)

    typeMappings.put(
        "kotlin.Double",
        "@Serializable(me.him188.ani.utils.serialization.BigNumAsDoubleStringSerializer::class) me.him188.ani.utils.serialization.BigNum",
    )

    dependsOn(stripeApiP1)
}

val fixGeneratedOpenApi = tasks.register("fixGeneratedOpenApi") {
    dependsOn(generateApiV0, generateApiP1)
    val models =
        layout.buildDirectory.file("$generatedRoot/src/commonMain/kotlin/me/him188/ani/datasources/bangumi/models/")
            .get().asFile


//    inputs.file(file)
//    outputs.file(file)
    //    outputs.upToDateWhen {
//        models.resolve("BangumiValue.kt").readText() == expected
//    }
    val generatedSources = layout.buildDirectory.file("$generatedRoot/src/commonMain/kotlin").get().asFile

    doLast {
        models.resolve("BangumiValue.kt").writeText(
            """
                package me.him188.ani.datasources.bangumi.models

                typealias BangumiValue = kotlinx.serialization.json.JsonElement
            """.trimIndent(),
        )
        models.resolve("BangumiEpisodeCollectionType.kt").delete()
        models.resolve("BangumiSubjectCollectionType.kt").delete()
        models.resolve("BangumiSubjectType.kt").delete()

        // 生成器不写 license header, 但 src/commonMain/gen 是提交进仓库的, 每个文件都要有
        val licenseHeader = """
            /*
             * Copyright (C) 2024-2026 OpenAni and contributors.
             *
             * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
             * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
             *
             * https://github.com/open-ani/ani/blob/main/LICENSE
             */

        """.trimIndent()
        generatedSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            if (!text.startsWith("/*")) file.writeText(licenseHeader + "\n" + text)
        }
    }
}

// Sync 而不是 Copy: spec 里改名/删掉的 schema 会留下陈旧文件 (例如 v0 的 CharacterDetail -> Character)
val copyGeneratedToSrc = tasks.register("copyGeneratedToSrc", Sync::class) {
    dependsOn(fixGeneratedOpenApi)
    from(layout.buildDirectory.file("$generatedRoot/src/commonMain/kotlin"))
    into("src/commonMain/gen")
}

//tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
//    dependsOn(fixGeneratedOpenApi)
//}

tasks.register("generateOpenApi") {
    dependsOn(copyGeneratedToSrc)
}

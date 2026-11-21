/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * 加密的应用配置: 交流群、弹弹 play 的应用凭据、TMDB 的 token 这类可能要换的东西, 明文不进仓库, 也不进 CI.
 * - 配置以密文放在本仓库 `lists` 分支的 `app-config.txt` (`scripts/app-config.py` 写), 构建时经属性 `ani.config.sealed` 传进来
 *   (CI 从那个分支取; 本机由脚本写进 local.properties);
 * - 解密钥匙是属性 `ani.config.key` (本机 local.properties, CI 的 secret `APP_CONFIG_KEY`), 也编进应用:
 *   应用运行时再去那个分支取最新的一份 (app-data 的 `RemoteAppConfigRepository`), 换掉不用发版.
 */

/** 配置里的一项 [name] (如 `dandanplay.app.id`): 密文里有就用它, 没有就看属性 `ani.<name>`, 都没有是空串. */
fun Project.appConfigValue(name: String): String =
    sealedAppConfig()[name]?.takeIf { it.isNotBlank() } ?: getPropertyOrNull("ani.$name").orEmpty()

/** 编进应用的解密钥匙 (base64url); 没配时为空串, 应用不去取. */
fun Project.appConfigKey(): String = getPropertyOrNull("ani.config.key").orEmpty().trim()

/** 解开的配置; 没配钥匙或密文时为空. 配了却解不开直接报错: 宁可编不出来, 也别发一个缺凭据的包. */
fun Project.sealedAppConfig(): Map<String, String> {
    val key = appConfigKey()
    val sealed = getPropertyOrNull("ani.config.sealed").orEmpty().trim()
    if (key.isEmpty() || sealed.isEmpty()) return emptyMap()
    return SealedAppConfig.open(sealed, key)
        ?: throw GradleException("ani.config.sealed 解不开: ani.config.key 不对, 或密文坏了 (用 scripts/app-config.py show 核对)")
}

/**
 * 密文格式: `v1.<nonce>.<密文>`, 两段都是 base64url (不补 `=`); AES-256-GCM, 关联数据 [ASSOCIATED_DATA];
 * 明文是逐行的 `名字=值`. 与应用里的 `SealedText`、`scripts/sealed_text.py` 同一个格式, 改了三处一起改.
 */
internal object SealedAppConfig {
    private const val VERSION = "v1"
    private const val ASSOCIATED_DATA = "izuko-app-config/v1"
    private const val TAG_BITS = 128

    fun open(token: String, key: String): Map<String, String>? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != VERSION) return null
        val decoder = Base64.getUrlDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decoder.decode(key), "AES"), GCMParameterSpec(TAG_BITS, decoder.decode(parts[1])))
        cipher.updateAAD(ASSOCIATED_DATA.toByteArray())
        cipher.doFinal(decoder.decode(parts[2])).decodeToString().lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
    }.getOrNull()
}

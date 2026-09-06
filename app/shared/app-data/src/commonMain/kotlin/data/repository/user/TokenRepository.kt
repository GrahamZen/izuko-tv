/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.user

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.session.AccessTokenPair
import me.him188.ani.app.domain.session.isExpired

/**
 * Do not access directly. Use [SessionManager] instead.
 */
/** 当前登录流程的代号: 1 = 直连 bangumi 的 OAuth. 见 [TokenSave.loginFlowVersion]. */
const val CURRENT_LOGIN_FLOW_VERSION = 1

class TokenRepository(
    private val dataStore: DataStore<TokenSave>
) {
    val refreshToken: Flow<String?> = dataStore.data.map { it.refreshToken }
    suspend fun setRefreshToken(value: String) {
        dataStore.updateData {
            it.copy(refreshToken = value)
        }
    }

    /**
     * 当前的登录会话, 为 `null` 表示未登录.
     */
    val session: Flow<Session> = dataStore.data.map { save ->
        when {
            save.accessTokens != null -> {
                AccessTokenSession(
                    AccessTokenPair(
                        aniAccessToken = save.accessTokens.aniAccessToken,
                        expiresAtMillis = save.accessTokens.expiresAtMillis,
                        bangumiAccessToken = save.accessTokens.bangumiAccessToken,
                    ),
                )
            }

            else -> GuestSession
        }
    }

    /**
     * Updates [TokenSave.accessTokens].
     *
     * For [GuestSession], this also removes [TokenSave.refreshToken].
     */
    suspend fun setSession(session: Session) {
        when (session) {
            is AccessTokenSession -> {
                dataStore.updateData {
                    it.copy(
                        accessTokens = TokenSave.AccessTokens(
                            bangumiAccessToken = session.tokens.bangumiAccessToken,
                            aniAccessToken = session.tokens.aniAccessToken,
                            expiresAtMillis = session.tokens.expiresAtMillis,
                        ),
                        loginFlowVersion = CURRENT_LOGIN_FLOW_VERSION,
                    )
                }
            }

            GuestSession -> {
                dataStore.updateData {
                    it.copy(
                        refreshToken = null,
                        accessTokens = null,
                    )
                }
            }
        }
    }

    suspend fun clear() {
        dataStore.updateData {
            it.copy(
                refreshToken = null,
                accessTokens = null,
            )
        }
    }

    /**
     * 老流程 (Ani 服务器) 写下的会话作废, 强制重登一次. 返回是否真的清了.
     *
     * 见 [TokenSave.loginFlowVersion]. 只在启动时调一次.
     */
    suspend fun clearLegacySession(): Boolean {
        var cleared = false
        dataStore.updateData { save ->
            if (save.accessTokens == null || save.loginFlowVersion >= CURRENT_LOGIN_FLOW_VERSION) {
                save
            } else {
                cleared = true
                save.copy(
                    refreshToken = null,
                    accessTokens = null,
                    loginFlowVersion = CURRENT_LOGIN_FLOW_VERSION,
                )
            }
        }
        return cleared
    }

    /**
     * for settings backup only
     */
    suspend fun getTokenSaveSnapshot(): TokenSave {
        return dataStore.data.map { it }.first()
    }

    /**
     * for settings restore only
     */
    suspend fun restoreFromTokenSave(save: TokenSave) {
        dataStore.updateData { save }
    }
}

@ConsistentCopyVisibility
@Serializable
data class TokenSave internal constructor(
    val refreshToken: String? = null,
    val accessTokens: AccessTokens? = null,
    /**
     * 这份存档是哪一代登录流程写的.
     *
     * `0` (老存档的默认值) = Ani 服务器那套: refreshToken 是 Ani 的 JWT, 拿去 bangumi 刷新必然
     * 失败; 而存档里的 `expiresAtMillis` 是 Ani 给的一年后, **完全不反映 bangumi token 的 7 天**,
     * 于是那一周过完之后, app 会在"自以为登录着"的状态下对所有 bangumi 请求收 401。
     * 只能强制重登一次, 见 [TokenRepository.clearLegacySession].
     */
    val loginFlowVersion: Int = 0,
) {
    @Serializable
    data class AccessTokens(
        val bangumiAccessToken: String?,
        val aniAccessToken: String,
        val expiresAtMillis: Long,
    )

    companion object {
        val Initial = TokenSave()
    }
}

sealed interface Session

/**
 * 以游客登录
 */
data object GuestSession : Session

/**
 * 以 Bangumi access token 登录
 */
// don't remove `data`. required for equals
data class AccessTokenSession(
    val tokens: AccessTokenPair,
) : Session {
    @Deprecated("Use this.tokens.expiresAtMillis instead.", replaceWith = ReplaceWith("this.tokens.expiresAtMillis"))
    val expiresAtMillis: Long get() = tokens.expiresAtMillis
}

@Deprecated(
    "",
    replaceWith = ReplaceWith(
        "!tokens.isExpired()",
        "me.him188.ani.app.domain.session.isExpired",
    ),
)
fun AccessTokenSession.isValid() = !tokens.isExpired()

@Deprecated(
    "",
    replaceWith = ReplaceWith(
        "tokens.isExpired()",
        "me.him188.ani.app.domain.session.isExpired",
    ),
)
fun AccessTokenSession.isExpired() = tokens.isExpired()


/**
 * Used before 4.9.
 *
 * Only for migration
 */
class LegacyTokenRepository(
    store: DataStore<Preferences>,
) {
    private companion object Keys {
        val USER_ID = longPreferencesKey("user_id")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token") // bangumi

        // Note: we added this because we cannot change ACCESS_TOKEN anymore because old users are using them.
        val IS_GUEST = stringPreferencesKey("is_guest")
        val ACCESS_TOKEN = stringPreferencesKey("access_token") // bangumi
        val ACCESS_TOKEN_EXPIRE_AT = longPreferencesKey("access_token_expire_at")
    }

    private val tokenStore = store

    val refreshToken: Flow<String?> = tokenStore.data.map { it[REFRESH_TOKEN] }

    suspend fun setRefreshToken(value: String) {
        tokenStore.edit { it[REFRESH_TOKEN] = value }
    }

    val session: Flow<Session?> = tokenStore.data.map { preferences ->
        val accessToken = preferences[ACCESS_TOKEN]
        val expireAt = preferences[ACCESS_TOKEN_EXPIRE_AT]
        val isGuest = preferences[IS_GUEST]?.toBooleanStrict()
        if (isGuest == true) {
            GuestSession
        } else {
            if (accessToken == null || expireAt == null) {
                return@map null
            }
            AccessTokenSession(
                AccessTokenPair(
                    "",
                    expiresAtMillis = expireAt,
                    accessToken,
                ),
            )
        }
    }

    suspend fun clear() {
        tokenStore.edit {
            it.remove(USER_ID)
            it.remove(ACCESS_TOKEN)
            it.remove(ACCESS_TOKEN_EXPIRE_AT)
            it.remove(REFRESH_TOKEN)
            it.remove(IS_GUEST)
        }
    }
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.settings

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * "刚才有请求连不上/等超时了" 的**进程级信标**: 只记最近一次的时刻, 供连通性检查决定要不要
 * 自己再跑一轮 (见 TV 动作面板里那一条服务连通).
 *
 * ## 为什么需要它
 *
 * 那一条只在本进程第一次出面板时自动测一次, 之后过期只提示不代劳 —— 依据是"用户不该为每次
 * 长按返回付五个请求". 但有一种情形值得破例: **用户正常浏览时已经撞上失败了** (背景图迟迟不
 * 出、hero 等满超时), 这时他打开面板多半就是想知道"是不是网络的问题", 而手上那批结果是坏事
 * 发生**之前**测的, 照旧显示等于答非所问.
 *
 * 于是: 上次检测之后又亮过信标, 下次出面板就再自动跑一轮 —— 相当于把"是不是第一次"那个判定
 * 重新打开.
 *
 * ## 为什么是显式几处上报, 而不是在 HTTP 客户端上挂一个全局钩子
 *
 * 全局钩子会把**在线数据源与 BT** 的失败也算进来, 而那些失败是家常便饭 (tracker 连不上、
 * selector 源站改版), 信标就会长亮, 自动重测退化成"每次开面板都测" —— 正是被撤掉的那档行为.
 * 这里只认**应用自己那几个服务**的失败, 所以宁可漏报也不误报: 目前上报点是 TMDB 那条链
 * (背景图/剧照解析) 与 TV hero 的加载超时; 主服务与 bgm 直连暂未接入 (它们的失败散在各
 * repository 的错误态里, 没有单一入口), 这是已知的缺口.
 */
object NetworkTroubleBeacon {
    private val logger = logger("NetworkTroubleBeacon")

    @OptIn(ExperimentalAtomicApi::class)
    private val lastAt = AtomicLong(0L)

    /** 最近一次上报的时刻 (毫秒); 0 = 本进程还没亮过. */
    @OptIn(ExperimentalAtomicApi::class)
    val lastTroubleAt: Long
        get() = lastAt.load()

    /**
     * 报告一次"连不上/等超时".
     *
     * @param reason 只进日志, 便于事后对着连通性结果看是哪条链先出事的.
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun report(reason: String) {
        lastAt.store(currentTimeMillis())
        logger.info { "Network trouble reported: $reason" }
    }
}

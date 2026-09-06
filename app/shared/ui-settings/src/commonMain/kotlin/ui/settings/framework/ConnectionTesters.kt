package me.him188.ani.app.ui.settings.framework

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.him188.ani.app.tools.MonoTasker
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

private inline fun <T> propertyDelegateProvider(
    crossinline createProperty: (property: KProperty<*>) -> T,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, T>> {
    return PropertyDelegateProvider { _, property ->
        val value = createProperty(property)
        ReadOnlyProperty { _, _ ->
            value
        }
    }
}

/**
 * 创建一个单个的测试器, 需要使用 `val tester by connectionTester {}`
 */
@Suppress("FunctionName")
fun ConnectionTester(
    testConnection: suspend () -> ConnectionTestResult,
    backgroundScope: CoroutineScope,
) = propertyDelegateProvider {
    SingleTester(ConnectionTester(it.name, testConnection), backgroundScope)
}

interface ConnectionTesterRunner<T : Tester<*>> {
    val testers: List<T>

    fun testAll()
    fun cancel()
    fun toggleTest()
    val anyTesting: Boolean
}

@Stable
class SingleTester<T>(
    tester: Tester<T>,
    backgroundScope: CoroutineScope,
) : ConnectionTesterRunner<Tester<T>> by DefaultConnectionTesterRunner(listOf(tester), backgroundScope) {
    val tester get() = testers.single()
}

/**
 * 一轮测试的进度: 已出结果 [completed] 个, 其中失败 [failed] 个, 共 [total] 个.
 */
@Immutable
data class ConnectionTestProgress(
    val completed: Int,
    val failed: Int,
    val total: Int,
)

/**
 * 出了结果且不在测的算完成, 结果是 [ConnectionTestResult.FAILED] 的算失败. 一个都没在测、也一个结果都没有时
 * 返回 `null` (还没测过).
 */
internal fun List<Tester<*>>.connectionTestProgress(): ConnectionTestProgress? {
    if (none { it.isTesting || it.result != null }) return null
    val done = filter { !it.isTesting && it.result != null }
    return ConnectionTestProgress(
        completed = done.size,
        failed = done.count { it.result == ConnectionTestResult.FAILED },
        total = size,
    )
}

// 堆屎咯
@Stable
open class DefaultConnectionTesterRunner<T : Tester<*>>(
    override val testers: List<T>,
    backgroundScope: CoroutineScope,
) : ConnectionTesterRunner<T> {
    private val testScope = MonoTasker(backgroundScope)
    override fun testAll() {
        // 先清掉上一轮的结果, [progress] 只数这一轮 (各项在测时本来就只显示转圈)
        testers.forEach { it.reset() }
        testScope.launch {
            supervisorScope {
                testers.forEach {
                    launch {
                        it.test()
                    }
                }
            }
        }
    }

    override fun cancel() {
        testScope.cancel()
    }

    override fun toggleTest() {
        if (testers.any { it.isTesting }) {
            cancel()
        } else {
            testAll()
        }
    }

    override val anyTesting by derivedStateOf {
        testers.any { it.isTesting }
    }

    /**
     * 这一轮测试的进度, 各项并发测, 谁先出结果先算谁. 还没测过时为 `null`; 被终止的项不算完成.
     */
    val progress: ConnectionTestProgress? by derivedStateOf {
        testers.connectionTestProgress()
    }
}

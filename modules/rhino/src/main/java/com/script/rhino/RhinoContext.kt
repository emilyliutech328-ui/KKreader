package com.script.rhino

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import kotlin.coroutines.CoroutineContext

class RhinoContext(factory: ContextFactory) : Context(factory) {

    var coroutineContext: CoroutineContext? = null
    var allowScriptRun = false
    var recursiveCount = 0
    // [自建版修复 P1] 顶层脚本的解释执行 deadline(毫秒)，0 表示未设置
    var scriptDeadline: Long = 0L

    @Throws(RhinoInterruptError::class)
    fun ensureActive() {
        try {
            coroutineContext?.ensureActive()
        } catch (e: CancellationException) {
            throw RhinoInterruptError(e)
        }
        // [自建版修复 P1] 看门狗：调用方未传入可取消的协程上下文时
        // （AnalyzeUrl 默认就是 EmptyCoroutineContext，ensureActive() 恒为 no-op），
        // 死循环的书源 JS 依然会在超时后被强制中断。
        if (scriptDeadline > 0L && System.currentTimeMillis() > scriptDeadline) {
            throw RhinoInterruptError(CancellationException("JS 脚本执行超时"))
        }
    }

    @Throws(RhinoRecursionError::class)
    fun checkRecursive() {
        if (recursiveCount >= 10) {
            throw RhinoRecursionError()
        }
    }

}

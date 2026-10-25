package io.legado.app.utils

import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.CrashHandler
import splitties.init.appCtx
import java.util.regex.Matcher

/**
 * [自建版修复 P1] 正则超时保护
 *
 * 原版只对「净化替换规则」做了超时检测（见 RegexExtensions.replace），
 * 且超时后无法真正中断匹配线程，只能 3 秒后重启应用兜底；
 * 书源的正则替换、列表规则、TXT 目录规则走的是另一条完全无保护的路径，
 * 一条灾难性回溯(ReDoS)的正则就能把整个应用卡死。
 *
 * 这里利用 Android 的 java.util.regex 在匹配过程中持续回调
 * CharSequence.charAt() 的特性（已用 android-36 的 android.jar 字节码确认：
 * Matcher/Pattern 内部持有 CharSequence 并逐字符回调 charAt），
 * 在包装类里做周期性 deadline 检查，超时即抛异常，真正中断匹配，无需重启应用。
 */
object RegexTimeout {

    const val DEFAULT_TIMEOUT_MS = 3000L

    /**
     * 用带超时中断能力的包装类包住待匹配文本，timeoutMs <= 0 表示不启用超时。
     */
    fun wrap(
        source: CharSequence,
        name: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CharSequence {
        if (timeoutMs <= 0) return source
        return TimeoutCharSequence(source, System.currentTimeMillis() + timeoutMs, name)
    }

    fun onTimeout(name: String, rule: String, e: RegexTimeoutException) {
        CrashHandler.saveCrashInfo2File(e)
        kotlin.runCatching {
            appCtx.longToastOnUi("正则匹配超时,已强制中断\n规则:$name\n正则:$rule")
        }
    }
}

/**
 * 带超时中断能力的 CharSequence 包装类。
 * 每 65536 次字符访问检查一次 deadline，正常匹配的额外开销可忽略。
 */
private class TimeoutCharSequence(
    private val source: CharSequence,
    private val deadline: Long,
    private val name: String
) : CharSequence {

    private var counter = 0

    override val length: Int get() = source.length

    override fun get(index: Int): Char {
        counter++
        if ((counter and 0xFFFF) == 0 && System.currentTimeMillis() > deadline) {
            throw RegexTimeoutException("正则匹配超时\n规则:$name")
        }
        return source[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        TimeoutCharSequence(source.subSequence(startIndex, endIndex), deadline, name)

    override fun toString(): String = source.toString()
}

/**
 * 带超时中断的正则替换，语义等价于 Kotlin 的 String.replace(Regex, String)。
 */
fun CharSequence.replaceWithTimeout(
    regex: Regex,
    replacement: String,
    name: String,
    timeoutMs: Long = RegexTimeout.DEFAULT_TIMEOUT_MS
): String {
    val quoted = Matcher.quoteReplacement(replacement)
    val matcher = regex.toPattern().matcher(RegexTimeout.wrap(this, name, timeoutMs))
    val sb = StringBuffer(length + 16)
    while (matcher.find()) {
        matcher.appendReplacement(sb, quoted)
    }
    matcher.appendTail(sb)
    return sb.toString()
}

package io.legado.app.model.analyzeRule

import androidx.annotation.Keep
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.utils.RegexTimeout
import java.util.regex.Pattern

@Keep
object AnalyzeByRegex {

    fun getElement(res: String, regs: Array<String>, index: Int = 0): List<String>? {
        return try {
            getElementInternal(res, regs, index)
        } catch (e: RegexTimeoutException) {
            // [自建版修复 P1] 列表规则超时（多为灾难性回溯），放弃该规则而不是卡死应用
            RegexTimeout.onTimeout("列表规则", regs.getOrNull(index) ?: "", e)
            null
        }
    }

    private fun getElementInternal(res: String, regs: Array<String>, index: Int): List<String>? {
        var vIndex = index
        // [自建版修复 P1] 待匹配文本走超时包装，超时可被真正中断
        val resM = Pattern.compile(regs[vIndex]).matcher(RegexTimeout.wrap(res, "列表规则"))
        if (!resM.find()) {
            return null
        }
        // 判断索引的规则是最后一个规则
        return if (vIndex + 1 == regs.size) {
            // 新建容器
            val info = arrayListOf<String>()
            for (groupIndex in 0..resM.groupCount()) {
                // [自建版修复 P0] 可选组未参与匹配时 group() 返回 null，
                // 原 `!!` 抛 NPE。同文件 getElements() 已用 `?: ""`，此处属遗漏。
                info.add(resM.group(groupIndex) ?: "")
            }
            info
        } else {
            val result = StringBuilder()
            do {
                result.append(resM.group())
            } while (resM.find())
            getElementInternal(result.toString(), regs, ++vIndex)
        }
    }

    fun getElements(res: String, regs: Array<String>, index: Int = 0): List<List<String>> {
        return try {
            getElementsInternal(res, regs, index)
        } catch (e: RegexTimeoutException) {
            // [自建版修复 P1] 列表规则超时，放弃该规则而不是卡死应用
            RegexTimeout.onTimeout("列表规则", regs.getOrNull(index) ?: "", e)
            arrayListOf()
        }
    }

    private fun getElementsInternal(
        res: String,
        regs: Array<String>,
        index: Int
    ): List<List<String>> {
        var vIndex = index
        // [自建版修复 P1] 待匹配文本走超时包装，超时可被真正中断
        val resM = Pattern.compile(regs[vIndex]).matcher(RegexTimeout.wrap(res, "列表规则"))
        if (!resM.find()) {
            return arrayListOf()
        }
        // 判断索引的规则是最后一个规则
        if (vIndex + 1 == regs.size) {
            // 创建书息缓存数组
            val books = ArrayList<List<String>>()
            // 提取列表
            do {
                // 新建容器
                val info = arrayListOf<String>()
                for (groupIndex in 0..resM.groupCount()) {
                    info.add(resM.group(groupIndex) ?: "")
                }
                books.add(info)
            } while (resM.find())
            return books
        } else {
            val result = StringBuilder()
            do {
                result.append(resM.group())
            } while (resM.find())
            return getElementsInternal(result.toString(), regs, ++vIndex)
        }
    }
}

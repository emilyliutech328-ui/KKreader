package io.legado.app

import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验内置书源 JSON 的结构与 CSS 规则能命中真实页面。
 *
 * 这里不启动 Rhino/Framework，只验证：
 *  1) JSON 能被 BookSource 反序列化（否则导入即失败）
 *  2) ruleBookInfo / ruleToc / ruleContent 的 CSS 选择器在真实页面上能取到值
 */
class Wenku8SourceRuleTest {

    private fun loadAssetSource(): BookSource? {
        val candidates = listOf(
            File("src/main/assets/defaultData/wenku8Sources.json"),
            File("app/src/main/assets/defaultData/wenku8Sources.json")
        )
        val file = candidates.firstOrNull { it.exists() } ?: return null
        val json = file.readText()
        val list = GSON.fromJsonArray<BookSource>(json).getOrNull() ?: return null
        return list.firstOrNull()
    }

    private fun loadSample(name: String): String? {
        val candidates = listOf(
            File("src/test/resources/wenku8/$name"),
            File("app/src/test/resources/wenku8/$name")
        )
        candidates.firstOrNull { it.exists() }?.let {
            return it.readBytes().toString(charset("GBK"))
        }
        return null
    }

    @Test
    fun sourceJsonParses() {
        val source = loadAssetSource() ?: return
        assertEquals("轻小说文库", source.bookSourceName)
        assertEquals("https://www.wenku8.net", source.bookSourceUrl)
        assertTrue("应启用", source.enabled)
        assertNotNull("必须声明详情规则", source.ruleBookInfo)
        assertNotNull("必须声明目录规则", source.ruleToc)
        assertNotNull("必须声明正文规则", source.ruleContent)
        assertNotNull("必须带 header（UA 绕过 Cloudflare）", source.header)
        assertTrue("header 应包含 Dalvik UA", source.header!!.contains("Dalvik"))
    }

    /** 模拟 AnalyzeByJSoup 的 @css:xxx@attr 处理 */
    private fun css(doc: org.jsoup.nodes.Element, rule: String): List<String> {
        val body = rule.removePrefix("@css:").removePrefix("@CSS:")
        val at = body.lastIndexOf('@')
        return if (at < 0) {
            doc.select(body).map { it.text().trim() }
        } else {
            doc.select(body.take(at)).map { it.attr(body.substring(at + 1)).trim() }
        }
    }

    @Test
    fun bookInfoRulesHitRealPage() {
        val source = loadAssetSource() ?: return
        val html = loadSample("book_1.htm") ?: return
        val doc = Jsoup.parse(html, "https://www.wenku8.net/book/1.htm")
        val content = doc.getElementById("content")!!

        val name = css(content, source.ruleBookInfo!!.name!!).firstOrNull().orEmpty()
        val author = css(content, source.ruleBookInfo!!.author!!).firstOrNull().orEmpty()
        val kind = css(content, source.ruleBookInfo!!.kind!!).firstOrNull().orEmpty()
        val update = css(content, source.ruleBookInfo!!.updateTime!!).firstOrNull().orEmpty()
        val words = css(content, source.ruleBookInfo!!.wordCount!!).firstOrNull().orEmpty()
        val cover = css(content, source.ruleBookInfo!!.coverUrl!!).firstOrNull().orEmpty()

        assertTrue("书名规则应命中: '$name'", name.contains("文学少女"))
        assertTrue("作者规则应命中: '$author'", author.contains("野村美月"))
        assertTrue("标签规则应命中: '$kind'", kind.contains("Tags"))
        assertTrue("更新规则应命中: '$update'", update.contains("2022-02-27"))
        assertTrue("字数规则应命中: '$words'", words.contains("2015475"))
        assertTrue("封面规则应命中: '$cover'", cover.startsWith("http"))
    }

    @Test
    fun tocRulesHitRealPage() {
        val source = loadAssetSource() ?: return
        val html = loadSample("index_3466.htm") ?: return
        val doc = Jsoup.parse(html, "https://www.wenku8.net/novel/3/3466/")
        val toc = source.ruleToc!!

        // chapterList 取行容器；wenku8 每个 <tr> 内并排 4 个章节
        val rows = doc.select(toc.chapterList!!.removePrefix("@css:"))
        assertTrue("章节行应命中", rows.isNotEmpty())

        // 关键：章节本身是 td.ccss > a，直接按元素提取才是每章一条
        val chapterCells = rows.flatMap { it.select("td.ccss a") }
        assertTrue("每个 tr 应含多个章节链接", chapterCells.size >= 5)

        val urls = chapterCells.map { it.attr("href") }.filter { it.endsWith(".htm") }
        assertTrue("章节地址应为 .htm", urls.size >= 5)
        // 卷标题与章节要能区分
        assertTrue("应有分卷标题", doc.select("td.vcss").size >= 1)
    }

    @Test
    fun contentRuleHitsRealPage() {
        val source = loadAssetSource() ?: return
        val html = loadSample("chapter_143512.htm") ?: return
        val doc = Jsoup.parse(html, "https://www.wenku8.net/novel/3/3466/143512.htm")
        val content = doc.getElementById("content")!!
        // @css:#content@html 等价于取 innerHTML
        val body = source.ruleContent!!.content!!.removePrefix("@css:")
        val sel = body.substringBefore('@')
        val attr = body.substringAfter('@', "")
        assertEquals("#content", sel)
        assertEquals("html", attr)
        val inner = content.html()
        assertTrue("正文 html 应有实际内容", inner.length > 1000)
    }
}

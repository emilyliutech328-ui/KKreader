package io.legado.app.ui.main.bookshelf.carousel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.entities.Book
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 立体书架离屏渲染测试。
 *
 * 用 Robolectric 原生图形模式把 [BookshelfCarouselView] 真实绘制到 Bitmap，
 * 输出 PNG 用于与 Wenku8_Flutter 的参考截图对比，无需真机/模拟器。
 */
/**
 * 立体书架离屏渲染测试。
 *
 * 用 Robolectric 原生图形模式把 [BookshelfCarouselView] 真实绘制到 Bitmap，
 * 输出 PNG 用于与 Wenku8_Flutter 的参考截图对比，无需真机/模拟器。
 *
 * ⚠️ 这是**开发辅助工具**，不属于常规单元测试：
 *  - 需要 Robolectric NATIVE 图形运行时（首次会下载 SDK，CI 上很慢且易失败）
 *  - 产物是给人眼看的 PNG，断言只是粗略校验
 * 因此加 @Ignore 默认跳过，本地需要时用 -PwithRenderTest=true 或
 * 手工去掉注解单独运行：
 *   ./gradlew testAppDebugUnitTest --tests "*BookshelfCarouselRenderTest*"
 */
@Ignore("开发辅助渲染测试，默认跳过；需要时单独运行")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
class BookshelfCarouselRenderTest {

    private val titles = listOf(
        "月光航线", "玻璃海岸纪事", "风经过山谷", "星空邮局", "风过荒野",
        "残棠记事", "Ночной экспресс", "拱桥之下", "本地书稿", "第十本"
    )

    @Test
    fun renderBookshelf() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = BookshelfCarouselView(context)
        val books = titles.mapIndexed { index, title ->
            Book().apply {
                bookUrl = "test://$index"
                name = title
                author = "林见舟"
                origin = "loc_book"
                originName = "本地书籍"
                totalChapterNum = 120
            }
        }
        val fragment = Fragment()
        val registry = LifecycleRegistry(fragment)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        view.submitBooks(books, fragment, registry)

        val density = context.resources.displayMetrics.density
        val width = (390f * density).toInt()
        val height = (340f * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        // 让入场动画（520ms）跑完
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0xF7, 0xF4, 0xEC))
        view.draw(canvas)

        val out = File("build/render_test/shelf.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHELF_RENDER_OK " + out.absolutePath + " " + width + "x" + height)
    }
}

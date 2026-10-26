package io.legado.app.lib.theme

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import io.legado.app.help.PerfHelper
import io.legado.app.help.config.AppConfig

/**
 * 玻璃拟态（Glassmorphism）辅助。
 *
 * 说明：真正的实时背景模糊在 Android 上只有两条路——
 *  1. Android 12+ 的 RenderEffect（View.setRenderEffect），可以模糊「自身内容」，
 *     要模糊「背后的内容」需要配 Window 级模糊或截图；
 *  2. 截图后模糊整屏再叠加（成本高，滚动时会掉帧）。
 *
 * 这里采取折中且稳妥的方案：
 *  - 支持 RenderEffect 的设备（Android 12+ 且不是低配）给面板加真实模糊；
 *  - 其余设备退化为「半透明 + 渐变描边」的视觉近似，观感接近玻璃，
 *    没有额外绘制开销，也不会有兼容性问题。
 */
object GlassUi {

    /** 是否启用玻璃效果（用户开关 + 设备能力） */
    fun enabled(): Boolean = AppConfig.glassUiEnabled && PerfHelper.glassUiEnabled()

    /**
     * 给面板应用玻璃外观。
     *
     * @param view 要应用的面板
     * @param tint 底色（通常是主题背景色）
     * @param alpha 底色不透明度，玻璃感越强可以越低
     */
    fun apply(view: View?, tint: Int, alpha: Float = 0.86f, radiusPx: Float = 16f) {
        view ?: return
        val useBlur = enabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // 底色是半透明渐变，边缘略亮，模拟玻璃的折射高光
        val base = adjustAlpha(tint, alpha)
        val edge = adjustAlpha(Color.WHITE, if (isNight(tint)) 0.06f else 0.28f)
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(edge, base, base)
        ).apply {
            cornerRadius = radiusPx
        }
        view.background = drawable
        if (useBlur) {
            // 12+ 才有 setRenderEffect；模糊半径按性能档位收敛
            runCatching {
                val radius = if (PerfHelper.isHighTier()) 18f else 10f
                view.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, android.graphics.Shader.TileMode.CLAMP
                    )
                )
            }
        }
    }

    /**
     * 去掉玻璃效果，恢复普通背景
     */
    fun clear(view: View?) {
        view ?: return
        view.background = ColorDrawable(Color.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { view.setRenderEffect(null) }
        }
    }

    /**
     * 给一个容器加一层玻璃卡片（用于对话框、底部面板）
     */
    fun card(container: FrameLayout?, tint: Int) {
        container ?: return
        apply(container, tint, alpha = 0.9f, radiusPx = 24f)
    }

    private fun isNight(@androidx.annotation.ColorInt tint: Int): Boolean =
        io.legado.app.utils.ColorUtils.isColorLight(tint).not()

    private fun adjustAlpha(@androidx.annotation.ColorInt color: Int, factor: Float): Int =
        Color.argb(
            (factor.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
}

package io.legado.app.lib.theme

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.getPrefString
import io.legado.app.utils.printOnDebug
import java.io.File

/**
 * 莫奈（Monet）动态取色。
 *
 * 思路对齐 Material You：不直接取「出现最多的颜色」（那通常发灰），
 * 而是把图片降采样后做量化统计，挑一个饱和度和明度都落在可用区间的
 * 主色，再按主题明暗做 HSL 校正，生成主色/深色/强调色/背景色四件套。
 *
 * 实现上不依赖 androidx.palette，避免为一个功能额外加库；
 * 用的是缩小采样 + 桶统计，几十万像素的图也只要几毫秒。
 */
object Monet {

    @ColorInt
    private val FALLBACK_PRIMARY = Color.parseColor("#455A64")

    data class Scheme(
        @ColorInt val primary: Int,
        @ColorInt val primaryDark: Int,
        @ColorInt val accent: Int,
        @ColorInt val background: Int
    )

    /** 取色统计的采样边长（采样 96x96 ≈ 9000 像素，足够稳定） */
    private const val SAMPLE_SIZE = 96

    /** 颜色桶的量化位数：每通道保留 4 位，共 4096 桶 */
    private const val QUANT_SHIFT = 4

    fun fromWallpaper(context: Context): Scheme? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return runCatching {
            val manager = WallpaperManager.getInstance(context)
            val drawable = manager.drawable ?: return null
            val bitmap = drawable.toBitmap() ?: return null
            val scheme = fromBitmap(bitmap)
            bitmap.recycleSafely()
            scheme
        }.onFailure {
            AppLog.put("壁纸取色失败\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    fun fromImage(path: String): Scheme? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = false }
        val bitmap = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null
        val scheme = fromBitmap(bitmap)
        bitmap.recycleSafely()
        scheme
    }.onFailure { it.printOnDebug() }.getOrNull()

    /**
     * 从 Bitmap 提炼配色
     */
    fun fromBitmap(source: Bitmap): Scheme? {
        if (source.width <= 0 || source.height <= 0) return null
        val sampled = scaleDown(source, SAMPLE_SIZE)
        val seed = runCatching { dominantSeed(sampled) }.getOrNull() ?: FALLBACK_PRIMARY
        if (sampled !== source) sampled.recycleSafely()

        val dark = isDarkTheme()
        val primary = tune(seed, dark, darker = false)
        val primaryDark = tune(seed, dark, darker = true)
        val accent = adjustHue(seed, 24f, if (dark) 0.85f else 0.75f)
        val background = if (dark) mix(primary, Color.BLACK, 0.82f)
        else mix(primary, Color.WHITE, 0.88f)
        return Scheme(primary, primaryDark, accent, background)
    }

    /**
     * 主色种子：按「饱和度加权 + 明度可用」打分，取最高分的桶平均色。
     * 近黑/近白/低饱和的像素基本不参与，避免选出灰底。
     */
    private fun dominantSeed(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val counts = IntArray(1 shl (3 * QUANT_SHIFT))
        val sumR = IntArray(counts.size)
        val sumG = IntArray(counts.size)
        val sumB = IntArray(counts.size)

        for (pixel in pixels) {
            val a = Color.alpha(pixel)
            if (a < 128) continue
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val hsv = FloatArray(3)
            Color.RGBToHSV(r, g, b, hsv)
            // 排除极端明度与低饱和
            if (hsv[2] < 0.12f || hsv[2] > 0.94f) continue
            if (hsv[1] < 0.12f) continue
            val key = ((r shr QUANT_SHIFT) shl (2 * QUANT_SHIFT)) or
                    ((g shr QUANT_SHIFT) shl QUANT_SHIFT) or
                    (b shr QUANT_SHIFT)
            counts[key]++
            sumR[key] += r
            sumG[key] += g
            sumB[key] += b
        }

        var bestKey = -1
        var bestScore = 0.0
        for (key in counts.indices) {
            val count = counts[key]
            if (count <= 0) continue
            val r = sumR[key] / count
            val g = sumG[key] / count
            val b = sumB[key] / count
            val hsv = FloatArray(3)
            Color.RGBToHSV(r, g, b, hsv)
            // 分数：像素数 x 饱和度权重 x 明度居中性
            val midness = 1f - kotlin.math.abs(hsv[2] - 0.55f) / 0.55f
            val score = count.toDouble() * (0.35 + hsv[1]) * (0.5 + midness.coerceAtLeast(0f))
            if (score > bestScore) {
                bestScore = score
                bestKey = key
            }
        }
        if (bestKey < 0) return FALLBACK_PRIMARY
        val count = counts[bestKey]
        return Color.rgb(sumR[bestKey] / count, sumG[bestKey] / count, sumB[bestKey] / count)
    }

    /**
     * 应用配色到全局主题（写入后立即提交）
     */
    fun apply(context: Context, scheme: Scheme) {
        ThemeStore.editTheme(context)
            .primaryColor(scheme.primary)
            .primaryColorDark(scheme.primaryDark)
            .accentColor(scheme.accent)
            .backgroundColor(scheme.background)
            .apply()
    }

    /**
     * 按当前设置刷新；返回 false 表示没取到颜色（主题保持原样）。
     */
    fun refresh(context: Context): Boolean {
        if (!AppConfig.monetEnabled) return false
        val scheme = when (AppConfig.monetSource) {
            "1" -> {
                val bg = backgroundImagePath(context)
                bg?.takeIf { it.isNotBlank() }?.let { fromImage(it) }
            }

            else -> fromWallpaper(context)
        } ?: return false
        apply(context, scheme)
        return true
    }

    /**
     * 当前主题背景图路径（主题里选的那张），没有则返回 null
     */
    private fun backgroundImagePath(context: Context): String? {
        val key = if (ThemeConfig.isDarkTheme()) PreferKey.bgImageN else PreferKey.bgImage
        return context.getPrefString(key)?.takeIf { it.isNotBlank() }
    }

    private fun isDarkTheme(): Boolean {
        val mode = AppConfig.themeMode
        return when (mode) {
            "1" -> true
            "2" -> io.legado.app.utils.ColorUtils.isColorLight(
                ThemeStore.primaryColor(splitties.init.appCtx)
            ).not()
            else -> false
        }
    }

    private fun Bitmap.recycleSafely() {
        if (!this.isRecycled) runCatching { this.recycle() }
    }

    private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
        val w = source.width
        val h = source.height
        val max = maxOf(w, h)
        if (max <= maxSide) return source
        val ratio = maxSide.toFloat() / max
        val scaled = Bitmap.createScaledBitmap(
            source,
            (w * ratio).toInt().coerceAtLeast(1),
            (h * ratio).toInt().coerceAtLeast(1),
            true
        )
        return if (scaled === source) source else scaled
    }

    /**
     * 校正种子色：主色要可读（不太亮也不糊），暗色主题压暗、亮色主题提亮。
     */
    @ColorInt
    private fun tune(@ColorInt color: Int, dark: Boolean, darker: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
        hsv[2] = when {
            darker -> if (dark) 0.32f else 0.45f
            dark -> 0.55f
            else -> 0.72f
        }
        return Color.HSVToColor(hsv)
    }

    /** 色相偏移 + 调饱和，得到协调的强调色 */
    @ColorInt
    private fun adjustHue(@ColorInt color: Int, delta: Float, saturate: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + delta).mod(360f)
        hsv[1] = (hsv[1] * saturate).coerceIn(0.3f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.85f)
        return Color.HSVToColor(hsv)
    }

    private fun mix(@ColorInt a: Int, @ColorInt b: Int, ratio: Float): Int {
        val r = (Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt()
        val g = (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt()
        val bl = (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun android.graphics.drawable.Drawable.toBitmap(): Bitmap? {
        if (this is android.graphics.drawable.BitmapDrawable) {
            return this.bitmap
        }
        val w = intrinsicWidth.coerceAtLeast(1)
        val h = intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, w, h)
        draw(canvas)
        return bitmap
    }
}

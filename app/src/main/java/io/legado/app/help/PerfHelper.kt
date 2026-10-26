package io.legado.app.help

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.legado.app.help.config.AppConfig
import splitties.systemservices.activityManager

/**
 * 设备性能档位判定。
 *
 * 用于给「立体书架 / 玻璃拟态 / 高刷动效」这类较重的内容做自动降级，
 * 让低端机默认只看列表，不至于一进书架就掉帧。
 *
 * 判定依据（都由系统给出，不需要额外权限）：
 *  - ActivityManager.isLowRamDevice
 *  - 总内存大小
 *  - CPU 核心数
 *  - Android 版本（过低版本对 RenderEffect/Blur 支持差）
 */
enum class PerfTier {
    /** 低端：关闭 3D 轮播、玻璃模糊，仅保留基础动效 */
    Low,

    /** 中端：保留轮播但降采样、缩短动画，模糊可开 */
    Medium,

    /** 高端：全效果 */
    High
}

object PerfHelper {

    @Volatile
    private var cached: PerfTier? = null

    @Volatile
    private var cachedTotalMemMb: Long = -1

    fun tier(context: Context = splitties.init.appCtx): PerfTier {
        cached?.let { return it }
        val tier = compute(context)
        cached = tier
        return tier
    }

    /** 总内存（MB） */
    fun totalMemMb(context: Context = splitties.init.appCtx): Long {
        if (cachedTotalMemMb > 0) return cachedTotalMemMb
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val mb = info.totalMem / (1024 * 1024)
        cachedTotalMemMb = mb
        return mb
    }

    private fun compute(context: Context): PerfTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) {
            return PerfTier.Low
        }
        val mem = totalMemMb(context)
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            // 低于 3GB 或 4 核以下：低端
            mem < 3000 || cores <= 4 -> PerfTier.Low
            // 3~6GB：中端
            mem < 6000 -> PerfTier.Medium
            else -> PerfTier.High
        }
    }

    /**
     * 立体书架是否应该显示。
     *
     * 用户在设置里关闭 -> 一律不显示；开启且允许自动降级时，
     * 低端机默认不显示（用户仍可在设置里关掉自动降级强行打开）。
     */
    fun shouldShowCarousel(): Boolean {
        if (!AppConfig.bookshelfCarousel) return false
        if (!AppConfig.bookshelfCarouselAutoPerf) return true
        return tier() != PerfTier.Low
    }

    /** 3D 轮播的动效强度：低端收敛，避免掉帧 */
    fun carouselReduceMotion(): Boolean {
        if (!AppConfig.bookshelfCarouselAutoPerf) return false
        return tier() != PerfTier.High
    }

    /** 玻璃拟态是否可用（低端机关闭模糊，只保留半透明） */
    fun glassBlurSupported(): Boolean {
        if (!AppConfig.glassUiEnabled) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return tier() != PerfTier.Low
    }

    /** 玻璃效果总开关（用户开关 + 设备支持），供 GlassUi 使用 */
    fun glassUiEnabled(): Boolean = glassBlurSupported()

    /** 是否高端设备（决定模糊半径、动效时长等） */
    fun isHighTier(): Boolean = tier() == PerfTier.High

    /** 供设置页展示的档位描述 */
    fun tierLabel(): String = when (tier()) {
        PerfTier.Low -> "低配（已自动简化动效）"
        PerfTier.Medium -> "中配"
        PerfTier.High -> "高配（全效果）"
    }
}

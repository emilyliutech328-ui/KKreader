package io.legado.app.ui.main.bookshelf.carousel

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 立体书架（书脊墙 + 当前书上浮）
 *
 * 参考「书脊墙」排版：书架上立着一排书脊，当前书从书架里被抽出来、封面浮在前面，
 * 下方是书名、来源与页码。所有元素一次性绘制在 Canvas 上，因此：
 * - 上浮/回落是连续的：相邻两本书的封面透明度、位移、缩放随选择进度平滑插值，
 *   书脊同步淡出淡入、整排书随手指横移，不会出现两本书「跳」的硬切
 * - 惯性吸附：松手后按甩动速度决定落到哪一本，再以减速动画归位
 * - 触觉反馈：每跨过一本书触发一次轻触觉
 *
 * 数据更新走 [submitBooks]，点击/长按回调 [onBookClick] / [onBookLongClick]。
 */
class BookshelfCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onBookClick: ((Book) -> Unit)? = null
    var onBookLongClick: ((Book) -> Unit)? = null

    private var books: List<Book> = emptyList()
    private var fragment: Fragment? = null
    private var lifecycle: Lifecycle? = null

    /** 当前选择位置（可以是小数，拖动/动画中间态都写在这里） */
    private var selection = 0f
    private var animator: ValueAnimator? = null

    // 入场动画
    private var entranceProgress = 1f  // 0=未开始，1=完成
    private var entranceAnimator: ValueAnimator? = null
    private var lastEntranceTime = 0L

    // 骨架屏状态
    private var isLoading = false
    private var skeletonPhase = 0f  // 0~1 循环，用于波浪动画

    // 触觉防抖
    private var lastHapticTime = 0L
    private val HAPTIC_DEBOUNCE_MS = 65L

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // 尺寸（onSizeChanged 计算）
    private var textBlockHeight = 0f
    private var shelfBottom = 0f
    private var baseline = 0f
    private var spineWidth = 0f
    private var pitch = 0f
    private var coverWidth = 0f
    private var coverHeight = 0f
    private var liftHeight = 0f
    private var spineMaxHeight = 0f
    private var arcAmplitude = 0f
    private var halfVisibleCount = 6

    // 触摸状态
    private var downX = 0f
    private var downY = 0f
    private var downSelection = 0f
    private var lastX = 0f
    private var lastMoveTime = 0L
    private var velocityX = 0f
    private var isDragging = false
    private var longPressTriggered = false
    private var lastTickIndex = Int.MIN_VALUE

    private val longPressRunnable = Runnable {
        val index = hitIndex(downX)
        val book = bookAt(index)
        if (book != null && !isDragging) {
            longPressTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onBookLongClick?.invoke(book)
        }
    }

    // 画笔
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val spineTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val indexTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // 底部"全部图书 · N 本"虚线区域
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val captionMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // 封面缓存
    private val coverCache = object : LruCache<String, Bitmap>(coverCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()

    // 三级懒加载状态
    private var nearRadius = 1   // |focus-index| <= 1: 挂载真实图片
    private var warmRadius = 2   // |focus-index| <= 2: 预热解码
    private var focusedIndex = 0 // == index: 参与 Hero 动画

    private val nightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // 减少动画模式
    private var reduceMotion = false

    fun setReduceMotion(enabled: Boolean) {
        if (reduceMotion != enabled) {
            reduceMotion = enabled
            if (enabled) {
                // 直接跳到完成状态
                entranceProgress = 1f
                entranceAnimator?.cancel()
            }
            invalidate()
        }
    }

    // ---------------------------------------------------------------- 语义化

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "立体书架"
        info.isScrollable = true
        
        val currentIndex = selection.roundToInt()
        val book = bookAt(currentIndex)
        if (book != null) {
            info.text = "${book.name}，第 ${currentIndex + 1} 本，共 ${books.size} 本"
            info.contentDescription = "立体书架，当前选中${book.name}，第 ${currentIndex + 1} 本，共 ${books.size} 本，左右滑动浏览"
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    // ---------------------------------------------------------------- 对外接口

    fun submitBooks(books: List<Book>, fragment: Fragment, lifecycle: Lifecycle) {
        this.fragment = fragment
        this.lifecycle = lifecycle
        val previousUrl = bookAt(selection.roundToInt())?.bookUrl
        this.books = books
        if (books.isEmpty()) {
            selection = 0f
            animator?.cancel()
        } else {
            val index = books.indexOfFirst { it.bookUrl == previousUrl }
            if (index >= 0) {
                selection = index.toFloat()
            } else {
                selection = selection.coerceIn(0f, books.size - 1f)
            }
        }
        lastTickIndex = selection.roundToInt()
        focusedIndex = selection.roundToInt()
        // 启动入场波浪动画
        startEntranceAnimation()
        invalidate()
    }

    private fun startEntranceAnimation() {
        entranceAnimator?.cancel()
        entranceProgress = 0f
        lastEntranceTime = System.currentTimeMillis()
        entranceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                entranceProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- 布局参数

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 底部只保留一条"全部图书 · N 本"虚线区域的高度
        textBlockHeight = dp(46f)
        shelfBottom = h - textBlockHeight
        // 书基线上移 7dp，让最后一排书底与虚线框之间留出约半个字（14sp）的空隙
        // 原为 shelfBottom - 6dp，与框顶（shelfBottom + 8dp）仅隔 14dp，显得挤
        baseline = shelfBottom - dp(13f)
        // 取自 Wenku8_Flutter archive_shelf_geometry.dart，按 390dp 屏宽等比收窄：
        // spineFor 约 13dp、pitch 约 19dp、coverFor 约 125dp
        spineWidth = (w * 0.034f).coerceIn(dp(12f), dp(15f))
        pitch = spineWidth + dp(6f)
        coverWidth = (w * 0.30f).coerceIn(dp(96f), dp(126f))
        // 书脊 / 封面高度：按书架区高度收窄，留出上浮空间，保证书顶不溢出控件。
        // 取 0.70 而非 0.78：书脊略矮一点，给「选中的书浮出书架」腾出更多上浮余量。
        val bodyHeight = (coverWidth - dp(20f)) * 166f / 118f + dp(54f)
        spineMaxHeight = bodyHeight.coerceAtMost(shelfBottom * 0.70f)
        coverHeight = spineMaxHeight
        // 上浮量：选中的书要「从书架抽出、悬浮在书架外」，
        // 目标浮起约半个书脊高度；同时保证书顶不超出控件（留 2dp 余量）。
        // 原实现用 min(书脊*0.42, baseline - 书脊*1.14 - 4dp)，实际只有 ~27dp，
        // 书脊却有 ~190dp，只浮了 14%，看起来像缩在书架深处。
        val idealLift = spineMaxHeight * 0.50f
        val maxLiftAllowed = baseline - spineMaxHeight - dp(2f)
        liftHeight = minOf(idealLift, maxLiftAllowed).coerceAtLeast(0f)
        ensureCovers(selection.roundToInt())
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (books.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downX = event.x
                downY = event.y
                downSelection = selection
                lastX = event.x
                lastMoveTime = System.currentTimeMillis()
                velocityX = 0f
                isDragging = false
                longPressTriggered = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!isDragging) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        isDragging = true
                        removeCallbacks(longPressRunnable)
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (isDragging) {
                    val now = System.currentTimeMillis()
                    val dt = now - lastMoveTime
                    if (dt in 1..120) {
                        velocityX = velocityX * 0.35f + ((event.x - lastX) / dt * 1000f) * 0.65f
                    }
                    lastX = event.x
                    lastMoveTime = now
                    selection = downSelection - dx / pitch
                    checkTick()
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
                if (isDragging) {
                    settleWithVelocity()
                } else {
                    handleTap(event.x)
                }
                isDragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (isDragging) settleWithVelocity()
                isDragging = false
            }
        }
        return true
    }

    private fun handleTap(x: Float) {
        val currentIndex = selection.roundToInt()
        // 底部虚线标签区（"全部图书 · N 本"）：点一下打开当前书。
        // 不设隐形翻页热区：右下角没有绘制「‹ ›」箭头，热区却与虚线框
        // 重叠约 8dp，点「N 本」附近会莫名翻页（KNOWN_ISSUES P2-1）。
        // 翻书一律用横向拖动或点其它书脊。
        if (downY > shelfBottom + dp(6f)) {
            onBookClick?.invoke(bookAt(currentIndex) ?: return)
            return
        }
        val target = hitIndex(x)
        if (target == currentIndex) {
            // 点当前书：打开
            onBookClick?.invoke(bookAt(target) ?: return)
        } else {
            animateTo(target.toFloat())
        }
    }

    private fun hitIndex(x: Float): Int {
        val d = (x - width / 2f) / pitch
        return (selection + d).roundToInt()
    }

    private fun settleWithVelocity() {
        val slotsPerSecond = -velocityX / pitch
        val projected = selection + slotsPerSecond.coerceIn(-6f, 6f) * 0.22f
        val target: Float = if (abs(velocityX) > dp(900f)) {
            if (slotsPerSecond > 0) ceil(projected) else floor(projected)
        } else {
            projected.roundToInt().toFloat()
        }
        animateTo(target)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        val start = selection
        val distance = abs(target - start)
        if (distance < 0.001f) {
            selection = target
            checkTick()
            invalidate()
            return
        }
        val valueAnimator = ValueAnimator.ofFloat(start, target)
        valueAnimator.interpolator = DecelerateInterpolator(1.5f)
        valueAnimator.duration = (170 + 130 * distance).toLong().coerceIn(170L, 520L)
        valueAnimator.addUpdateListener {
            selection = it.animatedValue as Float
            checkTick()
            invalidate()
        }
        valueAnimator.start()
        animator = valueAnimator
    }

    private fun checkTick() {
        val index = selection.roundToInt()
        if (index != lastTickIndex) {
            lastTickIndex = index
            focusedIndex = index
            // 触觉防抖：65ms 内不重复触发
            val now = System.currentTimeMillis()
            if (now - lastHapticTime >= HAPTIC_DEBOUNCE_MS) {
                lastHapticTime = now
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            ensureCovers(index)
        }
    }

    private fun bookAt(index: Int): Book? {
        if (books.isEmpty()) return null
        val wrapped = ((index % books.size) + books.size) % books.size
        return books[wrapped]
    }

    // ---------------------------------------------------------------- 封面加载

    /**
     * 三级懒加载策略（参考 Wenku8_Flutter _LazyArchiveCover）
     * - near: |focus-index| <= 1，挂载真实图片
     * - warm: |focus-index| <= 2，预热解码
     * - focused: == index，参与 Hero 动画
     */
    private fun ensureCovers(centerIndex: Int) {
        if (books.isEmpty() || coverWidth <= 0f) return
        val targetWidth = max(dp(64f), coverWidth).toInt()
        val targetHeight = max(dp(88f), coverHeight).toInt()
        
        // near 范围：立即加载
        for (offset in -nearRadius..nearRadius) {
            val book = bookAt(centerIndex + offset) ?: continue
            requestCover(book, targetWidth, targetHeight, isNear = true)
        }
        
        // warm 范围：预热解码
        for (offset in -warmRadius..warmRadius) {
            if (abs(offset) <= nearRadius) continue  // 已处理
            val book = bookAt(centerIndex + offset) ?: continue
            requestCover(book, targetWidth, targetHeight, isNear = false)
        }
    }

    private fun requestCover(book: Book, targetWidth: Int, targetHeight: Int, isNear: Boolean = true) {
        val path = book.getDisplayCover()
        if (path.isNullOrEmpty() || coverCache.get(path) != null) return
        if (path in failed || !loading.add(path)) return
        
        var options = RequestOptions()
            .set(OkHttpModelLoader.loadOnlyWifiOption, false)
            .override(targetWidth, targetHeight)
            .centerCrop()
        val origin = book.origin
        if (origin.isNotEmpty()) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, origin)
        }
        
        val target = object : CustomTarget<Bitmap>(targetWidth, targetHeight) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                coverCache.put(path, resource)
                loading.remove(path)
                postInvalidateOnAnimation()
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                loading.remove(path)
                failed.add(path)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                loading.remove(path)
            }
        }
        
        if (isNear) {
            // 立即加载
            ImageLoader.loadBitmap(context, path).apply(options).into(target)
        } else {
            // 预热：延迟加载，避免阻塞滚动
            postDelayed({
                if (path !in failed && path !in coverCache.snapshot().keys) {
                    ImageLoader.loadBitmap(context, path).apply(options).into(target)
                }
            }, 100)
        }
    }

    private fun coverCacheSizeKb(): Int {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return maxMemory / 8
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val night = nightMode
        val ink = if (night) 0xFFE9E4D8.toInt() else 0xFF20242A.toInt()
        val paper = if (night) 0xFF23262B.toInt() else 0xFFF4F0E7.toInt()

        // 更新骨架屏波浪相位
        if (isLoading) {
            skeletonPhase = (System.currentTimeMillis() % 1700) / 1700f
        }

        drawShelfBackdrop(canvas, ink)

        val centerIndex = selection.roundToInt()
        val halfVisible = ceil(width / pitch / 2f).toInt() + 2
        val first = floor(selection).toInt() - halfVisible
        val last = ceil(selection).toInt() + halfVisible

        // 绘制顺序：默认从左到右（右边压左边，符合真实书架透视）。
        // 但**被抽出的那本要最后画** —— 它浮在书架外，应当遮挡左右两侧的邻居。
        // 否则它会被右邻盖住，视觉上像「缩回书架深处」。
        val visibleSlots = ArrayList<Int>(last - first + 1)
        for (slot in first..last) {
            if (abs(slot - selection) > halfVisible + 1) continue
            visibleSlots.add(slot)
        }
        val focusSlot = visibleSlots.maxByOrNull { coverWeight(it - selection) }
        val drawOrder = if (focusSlot == null) visibleSlots
        else visibleSlots.filter { it != focusSlot } + focusSlot

        for (slot in drawOrder) {
            val offset = slot - selection
            val book = bookAt(slot) ?: continue
            val weight = coverWeight(offset)
            val spineAlpha = (1f - weight * 1.05f).coerceIn(0f, 1f)
            if (spineAlpha <= 0.02f && weight <= 0.02f) continue

            // 入场波浪动画：每本书按位置错开入场
            val inkProgress = calculateInkProgress(slot, centerIndex)

            if (isLoading) {
                // 骨架屏模式：画占位符
                drawSkeletonBook(canvas, slot, offset, spineAlpha, ink, paper, night)
            } else {
                drawBookVolume(canvas, book, slot, offset, weight, spineAlpha, inkProgress, ink, paper, night)
            }
        }

        drawCaptions(canvas, ink)
    }

    /**
     * 计算每本书的入场进度（波浪效果）
     * 参考 Wenku8_Flutter: inkStart(index) = ((index - focus + 8) / 24).clamp(0, 0.65)
     */
    private fun calculateInkProgress(slot: Int, centerIndex: Int): Float {
        if (entranceProgress >= 1f) return 1f
        val inkStart = ((slot - centerIndex + 8) / 24f).coerceIn(0f, 0.65f)
        val inkEnd = inkStart + 0.35f
        val progress = ((entranceProgress - inkStart) / (inkEnd - inkStart)).coerceIn(0f, 1f)
        // easeOutCubic
        return 1f - (1f - progress) * (1f - progress) * (1f - progress)
    }

    /**
     * 骨架屏：画占位符，带波浪抬升效果
     * 参考 Wenku8_Flutter: archiveLoadingLift(lane, frame, remaining)
     *   phase = frame.inMicroseconds/1700000 - lane*0.075
     *   crest = (1+sin(phase*2π))/2
     *   return -9*crest²*remaining
     */
    private fun drawSkeletonBook(
        canvas: Canvas,
        slot: Int,
        offset: Float,
        alpha: Float,
        ink: Int,
        paper: Int,
        night: Boolean
    ) {
        val push = if (abs(offset) < 2.2f) {
            coverWidth * 0.42f * (1f - abs(offset) / 2.2f) * coverWeight(offset)
        } else {
            0f
        }
        val x = width / 2f + offset * pitch + (if (offset >= 0) push else -push) - spineWidth / 2f
        val variance = 0.74f + 0.26f * hash01(bookAt(slot) ?: return, 11)
        val spineHeight = spineMaxHeight * variance
        
        // 波浪抬升：沿书脊行进的正弦波
        val lane = slot - selection.roundToInt()
        val phase = skeletonPhase * 2f * PI.toFloat() - lane * 0.075f
        val crest = (1f + sin(phase)) / 2f
        val remaining = 1f - entranceProgress
        val lift = -9f * crest * crest * remaining
        
        val bottom = baseline + arcShift(offset)
        val top = bottom - spineHeight + lift
        val rect = RectF(x, top, x + spineWidth, bottom)

        // 画骨架膃
        bodyPaint.color = if (night) 0xFF2D2D2D.toInt() else 0xFFF2F0E9.toInt()
        bodyPaint.alpha = (255 * alpha * 0.3f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), bodyPaint)

        // 骨架线条（书脊：两条横线）
        linePaint.color = ink
        linePaint.alpha = (255 * alpha * 0.075f * (1f - entranceProgress)).toInt().coerceIn(0, 255)
        canvas.drawRect(rect.left + dp(4f), rect.top + dp(13f), rect.right - dp(4f), rect.top + dp(18f), linePaint)
        canvas.drawRect(rect.left + dp(5f), rect.top + spineHeight * 0.32f, rect.right - dp(5f), rect.top + spineHeight * 0.66f, linePaint)
    }

    private fun coverWeight(offset: Float): Float {
        val t = (1f - abs(offset) / 1.15f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 书架轨道（Wenku8 railY）：整排书沿极浅的弧线排列，边缘略低 */
    private fun arcShift(offset: Float): Float {
        val d = offset.coerceIn(-7f, 7f)
        return dp(1.1f) * d + dp(0.16f) * d * d
    }

    private fun drawShelfBackdrop(canvas: Canvas, ink: Int) {
        // Wenku8 是干净纸面：不画竖向网格，只留书架线和落影
        linePaint.color = ink
        linePaint.alpha = 26
        val shelf = Path()
        var px = 0f
        shelf.moveTo(px, baseline + arcShift((px - width / 2f) / pitch))
        while (px < width) {
            px += dp(12f)
            shelf.lineTo(px, baseline + arcShift((px - width / 2f) / pitch))
        }
        canvas.drawPath(shelf, linePaint)

        // 书架落影
        shadowPaint.shader = LinearGradient(
            0f, baseline, 0f, baseline + dp(14f),
            intArrayOf(Color.argb(22, 0, 0, 0), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, baseline - dp(24f), width.toFloat(), baseline + dp(14f), shadowPaint)
        shadowPaint.shader = null
    }

    private fun drawBookVolume(
        canvas: Canvas,
        book: Book,
        slot: Int,
        offset: Float,
        weight: Float,
        spineAlpha: Float,
        inkProgress: Float,
        ink: Int,
        paper: Int,
        night: Boolean
    ) {
        val push = if (abs(offset) < 2.2f) {
            coverWidth * 0.42f * (1f - abs(offset) / 2.2f) * weight
        } else {
            0f
        }
        val x = width / 2f + offset * pitch + (if (offset >= 0) push else -push) - spineWidth / 2f
        val variance = 0.86f + 0.14f * hash01(book, 11)
        val spineHeight = spineMaxHeight * variance
        val bottom = baseline + arcShift(offset)
        val spineTop = bottom - spineHeight
        // 被选中的书整体上提（抽离书架），并向左让出封面厚度
        val lift = liftHeight * weight * (1.24f - 0.24f * weight)
        // 上提只受一个约束：书顶不能超出控件顶部（留 2dp）。
        // 注意：不能再夹「不高于书架线」——那等于禁止它浮出书架，
        // 正是之前看起来「缩回书架深处」的直接原因。
        val maxLiftTop = (spineTop - dp(2f)).coerceAtLeast(0f)
        val pull = lift.coerceAtMost(maxLiftTop)
        // 横移与上提解耦：横移只占上提量的一小部分，避免破坏整排书的弧形排列
        val shiftX = pull * 0.30f
        val spineRect = RectF(
            x - shiftX,
            spineTop - pull,
            x - shiftX + spineWidth,
            bottom - pull
        )

        // 1. 书脊
        if (spineAlpha > 0.02f) {
            drawSpineFace(canvas, book, slot, spineRect, spineAlpha * inkProgress, ink, paper)
        }

        // 2. 顶面（连接书脊和封面的厚度面）
        drawTopFace(canvas, book, slot, spineRect, weight, inkProgress, ink, paper, night)

        // 3. 封面：整排书都画，右侧被下一本书脊遮住，只露出书页般的侧边
        drawCoverFace(canvas, book, slot, spineRect, weight * inkProgress, ink, paper, night)
    }

    private fun drawSpineFace(
        canvas: Canvas,
        book: Book,
        slot: Int,
        rect: RectF,
        alpha: Float,
        ink: Int,
        paper: Int
    ) {
        bodyPaint.shader = null
        bodyPaint.color = spineFillColor(book, ink, paper)
        bodyPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), bodyPaint)

        // 柱面光影：中间亮、两侧暗，做出圆柱形书脊的立体感
        bodyPaint.shader = LinearGradient(
            rect.left, 0f, rect.right, 0f,
            intArrayOf(
                Color.argb((58 * alpha).toInt().coerceIn(0, 255), 0, 0, 0),
                Color.argb(0, 0, 0, 0),
                Color.argb((18 * alpha).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.argb(0, 0, 0, 0),
                Color.argb((86 * alpha).toInt().coerceIn(0, 255), 0, 0, 0)
            ),
            floatArrayOf(0f, 0.24f, 0.5f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        bodyPaint.alpha = 255
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), bodyPaint)
        bodyPaint.shader = null

        // 书脊右侧一道暗边，左上一道亮边，做出厚度
        linePaint.color = ink
        linePaint.alpha = (36 * alpha).toInt().coerceIn(0, 255)
        canvas.drawLine(rect.right - dp(1f), rect.top, rect.right - dp(1f), rect.bottom, linePaint)
        linePaint.color = Color.WHITE
        linePaint.alpha = (30 * alpha).toInt().coerceIn(0, 255)
        canvas.drawLine(rect.left + dp(1f), rect.top, rect.left + dp(1f), rect.bottom, linePaint)

        // 竖排书名：贴顶部、逐字向下排，字号按书脊宽度收紧
        val textAlpha = (215 * alpha).toInt().coerceIn(0, 255)
        val step = dp(10.8f)
        spineTextPaint.textSize = min(dp(9.5f), spineWidth * 0.8f)
        spineTextPaint.textAlign = Paint.Align.CENTER
        spineTextPaint.color = blendWith(ink, textAlpha)
        val label = verticalLabel(book, rect.height(), step)
        var ty = rect.top + dp(23f)
        for (c in label) {
            if (ty > rect.bottom - dp(4f)) break
            canvas.drawText(c.toString(), rect.centerX(), ty, spineTextPaint)
            ty += step
        }

        // 顶部编号，字号更小，同样向下排
        indexTextPaint.textSize = dp(6.5f)
        indexTextPaint.textAlign = Paint.Align.CENTER
        indexTextPaint.color = blendWith(ink, (120 * alpha).toInt().coerceIn(0, 255))
        val idx = "No.${((slot % 1000 + 1000) % 1000).toString().padStart(3, '0')}"
        var iy = rect.top + dp(8.5f)
        for (c in idx) {
            canvas.drawText(c.toString(), rect.centerX(), iy, indexTextPaint)
            iy += dp(7.3f)
        }
    }

    private fun drawTopFace(
        canvas: Canvas,
        book: Book,
        slot: Int,
        spineRect: RectF,
        weight: Float,
        inkProgress: Float,
        ink: Int,
        paper: Int,
        night: Boolean
    ) {
        // 顶面是连接书脊和封面的四边形，与封面同样的等距方向后退
        val depthX = 0.66f
        val depthY = -0.30f
        val fullW = coverWidth * (0.86f + 0.14f * weight)
        val shearW = fullW * depthX * (0.22f + 0.78f * weight)
        val shearH = shearW * (depthY / depthX)
        val spineW = spineWidth

        // 四个顶点
        val p1x = spineRect.left
        val p1y = spineRect.top
        val p2x = spineRect.right
        val p2y = spineRect.top + spineW * 0.10f
        val p3x = p2x + shearW
        val p3y = p2y + shearH
        val p4x = p1x + shearW
        val p4y = p1y + shearH

        val topPath = Path()
        topPath.moveTo(p1x, p1y)
        topPath.lineTo(p2x, p2y)
        topPath.lineTo(p3x, p3y)
        topPath.lineTo(p4x, p4y)
        topPath.close()

        // 填充：从浅到深的渐变，做出光照感
        val topColors = if (night) {
            intArrayOf(0xFF3A3A3A.toInt(), 0xFF2D2D2D.toInt())
        } else {
            intArrayOf(0xFFE3DFD3.toInt(), 0xFFFEFDF8.toInt())
        }
        bodyPaint.shader = LinearGradient(
            p1x, p1y, p3x, p3y,
            topColors,
            null,
            Shader.TileMode.CLAMP
        )
        bodyPaint.alpha = (255 * inkProgress).toInt().coerceIn(0, 255)
        canvas.drawPath(topPath, bodyPaint)
        bodyPaint.shader = null

        // 描边：细线勾勒轮廓
        linePaint.color = if (night) 0xFF3C3C3C.toInt() else 0xFF9A927F.toInt()
        linePaint.alpha = (60 * inkProgress).toInt().coerceIn(0, 255)
        linePaint.strokeWidth = dp(0.4f)
        linePaint.style = Paint.Style.STROKE
        canvas.drawPath(topPath, linePaint)
        linePaint.style = Paint.Style.FILL
    }

    private fun drawCoverFace(
        canvas: Canvas,
        book: Book,
        slot: Int,
        spineRect: RectF,
        weight: Float,
        ink: Int,
        paper: Int,
        night: Boolean
    ) {
        // Wenku8 archive_shelf.dart：封面是从书脊右侧沿等距方向后退的平行四边形。
        // 邻书只露出窄窄一条侧边，当前书整面展开并整体上提。
        val depthX = 0.66f
        val depthY = -0.30f
        val fullW = coverWidth * (0.86f + 0.14f * weight)
        val coverH = spineRect.height() * (1f + 0.10f * weight)
        val shearW = fullW * depthX * (0.22f + 0.78f * weight)
        val shearH = shearW * (depthY / depthX)
        val leftX = spineRect.right
        val bottomY = spineRect.bottom

        // 四角：C0 左上、C1 右上（向里后退并抬高）、C3 左下、C2 右下
        val c0x = leftX
        val c0y = bottomY - coverH
        val c1x = c0x + shearW
        val c1y = c0y + shearH
        val c3x = leftX
        val c3y = bottomY
        val c2x = c1x
        val c2y = c1y + coverH

        val alpha = (0.62f + 0.38f * weight).coerceAtMost(1f)

        // 投影：贴着封面底边
        shadowPaint.shader = LinearGradient(
            0f, c3y, 0f, c3y + dp(18f),
            intArrayOf(Color.argb((80 * alpha).toInt(), 0, 0, 0), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        val shadowPath = Path().apply {
            moveTo(c3x, c3y)
            lineTo(c2x, c2y)
            lineTo(c2x, c2y + dp(18f))
            lineTo(c3x, c3y + dp(18f))
            close()
        }
        canvas.drawPath(shadowPath, shadowPaint)
        shadowPaint.shader = null

        // 书页侧面：封面底边下方的一条浅色厚度
        val pagePath = Path().apply {
            moveTo(c3x, c3y)
            lineTo(c2x, c2y)
            lineTo(c2x, c2y + dp(4f))
            lineTo(c3x, c3y + dp(4f))
            close()
        }
        bodyPaint.color = if (night) 0xFF3A3E44.toInt() else 0xFFE7DFCC.toInt()
        bodyPaint.alpha = (235 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(pagePath, bodyPaint)

        // 封面本体：把位图映射到平行四边形
        val coverPath = Path().apply {
            moveTo(c0x, c0y)
            lineTo(c1x, c1y)
            lineTo(c2x, c2y)
            lineTo(c3x, c3y)
            close()
        }
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    (c1x - c0x) / fullW, (c3x - c0x) / coverH, c0x,
                    (c1y - c0y) / fullW, (c3y - c0y) / coverH, c0y,
                    0f, 0f, 1f
                )
            )
        }
        val path = book.getDisplayCover()
        val bitmap = path?.let { coverCache.get(it) }
        canvas.save()
        canvas.concat(matrix)
        if (bitmap != null) {
            coverPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, fullW, coverH), coverPaint)
            coverPaint.alpha = 255
        } else {
            drawGeneratedCover(canvas, book, RectF(0f, 0f, fullW, coverH), alpha, night)
            if (path != null) requestCover(book, fullW.toInt(), coverH.toInt())
        }
        canvas.restore()

        // 封面边框
        linePaint.color = Color.BLACK
        linePaint.alpha = (46 * alpha).toInt().coerceIn(0, 255)
        linePaint.strokeWidth = dp(1f)
        linePaint.style = Paint.Style.STROKE
        canvas.drawPath(coverPath, linePaint)
        linePaint.style = Paint.Style.FILL
    }

    private fun drawFloatingCover(
        canvas: Canvas,
        book: Book,
        offset: Float,
        weight: Float,
        night: Boolean
    ) {
        val scale = 0.76f + 0.24f * weight
        val width = coverWidth * scale
        val height = coverHeight * scale
        val centerX = width / 2f + offset * pitch * 0.55f * (1f - weight)
        val bottom = baseline + dp(2f)
        val rect = RectF(centerX - width / 2f, bottom - height, centerX + width / 2f, bottom)
        val alpha = min(1f, weight * 1.35f)

        // 投影
        shadowPaint.shader = LinearGradient(
            rect.left, rect.bottom, rect.left, rect.bottom + dp(16f),
            intArrayOf(Color.argb((70 * alpha).toInt(), 0, 0, 0), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            rect.left - dp(6f), rect.bottom, rect.right + dp(6f), rect.bottom + dp(16f),
            dp(4f), dp(4f), shadowPaint
        )
        shadowPaint.shader = null

        // 书页侧面
        bodyPaint.color = if (night) 0xFF3A3E44.toInt() else 0xFFE7DFCC.toInt()
        bodyPaint.alpha = (235 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(
            rect.left + dp(3f), rect.top + dp(2f), rect.right + dp(4f), rect.bottom,
            dp(2f), dp(2f), bodyPaint
        )

        val path = book.getDisplayCover()
        val bitmap = path?.let { coverCache.get(it) }
        if (bitmap != null) {
            coverPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, rect, coverPaint)
            coverPaint.alpha = 255
        } else {
            drawGeneratedCover(canvas, book, rect, alpha, night)
            if (path != null) requestCover(book, rect.width().toInt(), rect.height().toInt())
        }

        linePaint.color = Color.BLACK
        linePaint.alpha = (46 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, dp(1f), dp(1f), linePaint)
    }

    private fun drawGeneratedCover(
        canvas: Canvas,
        book: Book,
        rect: RectF,
        alpha: Float,
        night: Boolean
    ) {
        val color = generatedColor(book, night)
        bodyPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            color, adjustColor(color, -18),
            Shader.TileMode.CLAMP
        )
        bodyPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, dp(1f), dp(1f), bodyPaint)
        bodyPaint.shader = null

        // 内衬细框，做出封面版式（不显示书名，书名只在书脊显示一次）
        linePaint.color = Color.WHITE
        linePaint.alpha = (70 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(
            rect.left + dp(4.5f), rect.top + dp(4.5f),
            rect.right - dp(4.5f), rect.bottom - dp(4.5f), linePaint
        )
    }

    /**
     * 底部区域：一条虚线框住的"全部图书 · N 本"
     *
     * 立体书架和下方列表之间不再显示单本书的书名/作者/来源，
     * 改为一段概括性的区域标签，虚实线框提示这里"往下是全部书目"。
     */
    private fun drawCaptions(canvas: Canvas, ink: Int) {
        if (books.isEmpty()) return
        val top = shelfBottom + dp(8f)
        val bottom = height - dp(8f)
        val left = dp(18f)
        val right = width - dp(18f)
        val rect = RectF(left, top, right, bottom)

        // 虚线圆角框
        dashPaint.color = ink
        dashPaint.alpha = 110
        dashPaint.style = Paint.Style.STROKE
        dashPaint.strokeWidth = dp(1f)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), dashPaint)
        dashPaint.pathEffect = null
        dashPaint.style = Paint.Style.FILL

        // 文案："全部图书" + "N 本"
        val labelPaint = captionTitlePaint
        labelPaint.textSize = sp(14f)
        labelPaint.color = ink
        labelPaint.textAlign = Paint.Align.LEFT
        val labelBaseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText("全部图书", rect.left + dp(12f), labelBaseline, labelPaint)

        val countPaint = captionMetaPaint
        countPaint.textSize = sp(13f)
        countPaint.color = blendWith(ink, 140)
        countPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${books.size} 本", rect.right - dp(12f), labelBaseline, countPaint)
        countPaint.textAlign = Paint.Align.LEFT
    }

    private fun wrapTitle(text: String, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = ArrayList<String>(maxLines)
        val ellipsis = "\u2026"
        var index = 0
        while (index < text.length && lines.size < maxLines) {
            var end = index
            var widthSum = 0f
            while (end < text.length) {
                val charWidth = titlePaint.measureText(text, end, end + 1)
                if (widthSum + charWidth > maxWidth) break
                widthSum += charWidth
                end++
            }
            if (end == index) end = index + 1
            val isLastLine = lines.size == maxLines - 1
            if (isLastLine && end < text.length) {
                var trim = end
                var widthWithEllipsis = titlePaint.measureText(text, index, trim) +
                    titlePaint.measureText(ellipsis)
                while (trim > index + 1 && widthWithEllipsis > maxWidth) {
                    trim--
                    widthWithEllipsis = titlePaint.measureText(text, index, trim) +
                        titlePaint.measureText(ellipsis)
                }
                lines.add(text.substring(index, trim) + ellipsis)
                index = text.length
            } else {
                lines.add(text.substring(index, end))
                index = end
            }
        }
        return lines
    }

    private fun verticalLabel(book: Book, spineHeight: Float, step: Float): String {
        val maxChars = floor((spineHeight - dp(27f)) / step).toInt().coerceAtLeast(1)
        val name = book.name
        return if (name.length > maxChars) name.take(maxChars - 1) + "…" else name
    }

    // ---------------------------------------------------------------- 颜色/工具

    private fun spineFillColor(book: Book, ink: Int, paper: Int): Int {
        val variant = (hash01(book, 3) * 100).toInt()
        return when {
            variant < 12 -> blendWith(0xFFB34F44.toInt(), 210)
            variant < 20 -> blendWith(0xFF2E3237.toInt(), 220)
            variant < 34 -> blendWith(0xFFB7AC99.toInt(), 205)
            else -> blendWith(paper, 255)
        }
    }

    private fun generatedColor(book: Book, night: Boolean): Int {
        val palette = if (night) {
            intArrayOf(0xFF2F3A47.toInt(), 0xFF463A34.toInt(), 0xFF3B4741.toInt(), 0xFF453945.toInt())
        } else {
            intArrayOf(0xFF5F7488.toInt(), 0xFF8A6B4F.toInt(), 0xFF5E7B62.toInt(), 0xFF7A5A6E.toInt())
        }
        return palette[(book.name.hashCode() and 0x7FFFFFFF) % palette.size]
    }

    private fun adjustColor(color: Int, delta: Int): Int {
        return Color.rgb(
            (Color.red(color) + delta).coerceIn(0, 255),
            (Color.green(color) + delta).coerceIn(0, 255),
            (Color.blue(color) + delta).coerceIn(0, 255)
        )
    }

    private fun blendWith(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun hash01(book: Book, salt: Int): Float {
        val hash = (book.bookUrl.hashCode() * 31 + salt) and 0x7FFFFFFF
        return (hash % 1000) / 1000f
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * scaledDensity

    override fun onDetachedFromWindow() {
        animator?.cancel()
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}

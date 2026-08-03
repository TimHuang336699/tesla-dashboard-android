package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.tesla.dashboard.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * 苹果风格速度表圆环自定义 View
 *
 * 绘制 270° 扇形圆弧速度表，底部开口，支持平滑动画过渡。
 *
 * ## 设计风格
 * - 细线条圆弧，简约无发光
 * - 亮色模式: 进度色 Apple System Blue (#007AFF)，背景 (#E5E5EA)
 * - 暗色模式: 进度色 Apple System Blue (#0A84FF)，背景 (#38383A)
 * - 速度数字使用 sans-serif-thin，大号纯白/纯黑
 *
 * ## 弧形角度
 * 圆弧从 -225° 到 45°(共 270°)，底部留 90° 开口。
 * 内部统一使用 0° = 正右方、逆时针为负的标准数学坐标系。
 *
 * ## 动画
 * 调用 [setSpeed] 时使用 [ValueAnimator] 在 300ms 内平滑过渡，
 * 配合 [DecelerateInterpolator] 实现自然减速效果。
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 最大速度刻度 km/h */
    var maxSpeed: Float = 240f
        set(value) {
            field = value.coerceAtLeast(1f)
            invalidate()
        }

    /** 当前显示速度(动画中间值) */
    private var displaySpeed: Float = 0f

    /** 目标速度(动画终点) */
    private var targetSpeed: Float = 0f

    /** 圆弧线条宽度 */
    private var arcWidth: Float = dpToPx(6f)

    /** 背景弧线宽度 */
    private var bgArcWidth: Float = dpToPx(4f)

    /** 是否暗色模式 */
    var isDarkMode: Boolean = true
        set(value) {
            field = value
            updateColors()
            invalidate()
        }

    // ===== 颜色 =====

    /** 进度弧颜色 */
    private var progressColor: Int = 0x0A84FF.toInt()

    /** 背景弧颜色 */
    private var bgArcColor: Int = 0x38383A.toInt()

    /** 速度文字颜色 */
    private var speedTextColor: Int = 0xFFFFFF

    /** 单位文字颜色 */
    private var unitTextColor: Int = 0x8E8E93

    // ===== Paint =====

    private val bgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    /** 动画实例 */
    private var speedAnimator: ValueAnimator? = null

    /** 弧形绘制区域 */
    private val arcRect = RectF()

    init {
        // 读取 XML 自定义属性
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.SpeedometerView, 0, 0)
            maxSpeed = typedArray.getFloat(R.styleable.SpeedometerView_maxSpeed, 240f)
            displaySpeed = typedArray.getFloat(R.styleable.SpeedometerView_currentSpeed, 0f)
            targetSpeed = displaySpeed
            arcWidth = typedArray.getDimension(R.styleable.SpeedometerView_arcWidth, dpToPx(6f))
            isDarkMode = typedArray.getBoolean(R.styleable.SpeedometerView_isDarkMode, true)
            typedArray.recycle()
        }
        updateColors()
    }

    /**
     * 根据暗色/亮色模式更新颜色配置
     */
    private fun updateColors() {
        if (isDarkMode) {
            progressColor = 0xFF0A84FF.toInt()
            bgArcColor = 0xFF38383A.toInt()
            speedTextColor = 0xFFFFFFFF.toInt()
            unitTextColor = 0xFF8E8E93.toInt()
        } else {
            progressColor = 0xFF007AFF.toInt()
            bgArcColor = 0xFFE5E5EA.toInt()
            speedTextColor = 0xFF000000.toInt()
            unitTextColor = 0xFF8E8E93.toInt()
        }
    }

    /**
     * 设置当前速度(带动画)
     *
     * @param speed 速度值 km/h
     * @param animate 是否启用平滑动画
     */
    fun setSpeed(speed: Float, animate: Boolean = true) {
        val clamped = speed.coerceIn(0f, maxSpeed)
        targetSpeed = clamped

        if (!animate) {
            displaySpeed = clamped
            invalidate()
            return
        }

        speedAnimator?.cancel()
        speedAnimator = ValueAnimator.ofFloat(displaySpeed, clamped).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                displaySpeed = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f + dpToPx(10f)
        val radius = (minOf(width, height) / 2f - arcWidth - dpToPx(8f)).coerceAtLeast(dpToPx(20f))

        // 设置弧形绘制区域
        arcRect.set(
            cx - radius, cy - radius,
            cx + radius, cy + radius,
        )

        // 绘制背景弧 (-225° 到 45°, 共 270°)
        bgArcPaint.color = bgArcColor
        bgArcPaint.strokeWidth = bgArcWidth
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, bgArcPaint)

        // 绘制进度弧
        val progressRatio = displaySpeed / maxSpeed
        val progressSweep = SWEEP_ANGLE * progressRatio
        progressArcPaint.color = progressColor
        progressArcPaint.strokeWidth = arcWidth
        if (progressSweep > 0.5f) {
            canvas.drawArc(arcRect, START_ANGLE, progressSweep, false, progressArcPaint)
        }

        // 绘制速度数字
        speedTextPaint.color = speedTextColor
        speedTextPaint.textSize = radius * 0.55f
        speedTextPaint.typeface = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
        val speedText = displaySpeed.toInt().toString()
        val textY = cy - (speedTextPaint.descent() + speedTextPaint.ascent()) / 2f - radius * 0.1f
        canvas.drawText(speedText, cx, textY, speedTextPaint)

        // 绘制 km/h 单位
        unitTextPaint.color = unitTextColor
        unitTextPaint.textSize = radius * 0.13f
        unitTextPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        val unitY = textY + radius * 0.35f
        canvas.drawText("km/h", cx, unitY, unitTextPaint)
    }

    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        /** 弧起始角度(正右方为 0°, 顺时针为正) */
        private const val START_ANGLE = 135f

        /** 弧扫过角度(270°, 底部留 90° 开口) */
        private const val SWEEP_ANGLE = 270f
    }
}

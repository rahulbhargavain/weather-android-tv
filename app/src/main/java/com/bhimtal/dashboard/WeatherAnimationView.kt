package com.bhimtal.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * A lightweight, dependency-free animated weather icon.
 * Call setCondition() whenever new forecast data comes in.
 */
class WeatherAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Condition { SUNNY, CLOUDY, RAINY }

    private var condition = Condition.SUNNY
    private var phase = 0f

    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8A33D")
        style = Paint.Style.FILL
    }
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA39C")
        style = Paint.Style.FILL
    }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6FA8DC")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setCondition(newCondition: Condition) {
        if (condition != newCondition) {
            condition = newCondition
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 4f

        when (condition) {
            Condition.SUNNY -> drawSun(canvas, cx, cy, radius)
            Condition.CLOUDY -> drawCloud(canvas, cx, cy, radius, 0f)
            Condition.RAINY -> drawRain(canvas, cx, cy, radius, w, h)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, sunPaint)
        val rayLength = radius * 0.6f
        val rotationDeg = phase * 360f
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45f + rotationDeg).toDouble())
            val innerR = radius * 1.2f
            val outerR = innerR + rayLength
            val startX = cx + innerR * cos(angle).toFloat()
            val startY = cy + innerR * sin(angle).toFloat()
            val endX = cx + outerR * cos(angle).toFloat()
            val endY = cy + outerR * sin(angle).toFloat()
            canvas.drawLine(startX, startY, endX, endY, sunPaint)
        }
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, radius: Float, yOffset: Float) {
        val drift = sin(phase * 2 * Math.PI).toFloat() * radius * 0.25f
        val y = cy + yOffset
        canvas.drawCircle(cx - radius * 0.6f + drift, y, radius * 0.55f, cloudPaint)
        canvas.drawCircle(cx + radius * 0.2f + drift, y - radius * 0.2f, radius * 0.65f, cloudPaint)
        canvas.drawCircle(cx + radius * 0.9f + drift, y, radius * 0.45f, cloudPaint)
    }

    private fun drawRain(canvas: Canvas, cx: Float, cy: Float, radius: Float, w: Float, h: Float) {
        drawCloud(canvas, cx, cy, radius, -radius * 0.3f)
        val dropCount = 5
        val topY = cy
        val bottomY = h
        for (i in 0 until dropCount) {
            val baseX = w * (i + 0.5f) / dropCount
            val dropPhase = (phase + i * 0.17f) % 1f
            val startY = topY + dropPhase * (bottomY - topY)
            val endY = minOf(startY + radius * 0.35f, bottomY)
            canvas.drawLine(baseX, startY, baseX, endY, rainPaint)
        }
    }
}

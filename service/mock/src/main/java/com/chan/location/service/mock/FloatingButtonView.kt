package com.chan.location.service.mock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot

/**
 * 悬浮控制按钮：全局可拖动；按住 2 秒（带进度环）触发启停。
 * 移动超过 touchSlop 判定为拖动并取消长按。
 */
class FloatingButtonView(
    context: Context,
) : View(context) {
    var windowManager: WindowManager? = null
    var params: WindowManager.LayoutParams? = null
    var onToggle: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val sizePx = (BUTTON_SIZE_DP * density).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bgPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE263238.toInt()
            style = Paint.Style.FILL
        }
    private val iconPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4CAF50.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
            strokeCap = Paint.Cap.ROUND
        }

    private val handler = Handler(Looper.getMainLooper())
    private var progress = 0f
    private var pressStartAt = 0L
    private var longPressFired = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLpX = 0
    private var startLpY = 0

    private val progressTicker =
        object : Runnable {
            override fun run() {
                progress =
                    ((SystemClock.elapsedRealtime() - pressStartAt).toFloat() / LONG_PRESS_MS)
                        .coerceIn(0f, 1f)
                invalidate()
                if (progress < 1f) handler.postDelayed(this, TICK_MS)
            }
        }

    private val longPressRunnable =
        Runnable {
            longPressFired = true
            progress = 0f
            invalidate()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onToggle?.invoke()
        }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                params?.let {
                    startLpX = it.x
                    startLpY = it.y
                }
                longPressFired = false
                dragging = false
                progress = 0f
                pressStartAt = SystemClock.elapsedRealtime()
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                handler.post(progressTicker)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    cancelPress()
                }
                if (dragging) {
                    params?.let { lp ->
                        lp.x = (startLpX + dx).toInt().coerceAtLeast(0)
                        lp.y = (startLpY + dy).toInt().coerceAtLeast(0)
                        windowManager?.updateViewLayout(this, lp)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelPress()
        }
        return true
    }

    private fun cancelPress() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(progressTicker)
        progress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = width / 2f
        canvas.drawCircle(c, c, c - 2f * density, bgPaint)
        // 十字准星图标
        val gap = 5f * density
        val len = 5f * density
        canvas.drawLine(c - gap - len, c, c - gap, c, iconPaint)
        canvas.drawLine(c + gap, c, c + gap + len, c, iconPaint)
        canvas.drawLine(c, c - gap - len, c, c - gap, iconPaint)
        canvas.drawLine(c, c + gap, c, c + gap + len, iconPaint)
        canvas.drawCircle(c, c, 2.5f * density, iconPaint)
        if (progress > 0f) {
            val r = c - 2f * density
            canvas.drawArc(
                RectF(c - r, c - r, c + r, c + r),
                -90f,
                360f * progress,
                false,
                ringPaint,
            )
        }
    }

    companion object {
        private const val BUTTON_SIZE_DP = 56f
        private const val LONG_PRESS_MS = 2000L
        private const val TICK_MS = 33L
    }
}

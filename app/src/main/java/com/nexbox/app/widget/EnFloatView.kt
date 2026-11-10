package com.nexbox.app.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class EnFloatView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointer = -1
    private var dx = 0f
    private var dy = 0f
    private var listener: LocationListener? = null
    init { isClickable = true; contentDescription = com.nexbox.core.NexBoxCore.getContext().getString(com.nexbox.app.R.string.location_joystick) }
    fun setListener(value: LocationListener) { listener = value }
    fun cancelInput() {
        pointer = -1; dx = 0f; dy = 0f
        listener?.invoke(0f, 0f)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) * .45f
        paint.color = Color.argb(165, 45, 45, 45)
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.color = Color.argb(230, 245, 245, 245)
        canvas.drawCircle(width / 2f + dx * radius * .65f,
            height / 2f + dy * radius * .65f, radius * .28f, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pointer = event.getPointerId(0); parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelInput(); parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) {
                cancelInput(); return true
            }
        }
        val index = event.findPointerIndex(pointer)
        if (index < 0) return true
        val radius = (min(width, height) * .3f).coerceAtLeast(1f)
        val x = (event.getX(index) - width / 2f) / radius
        val y = (event.getY(index) - height / 2f) / radius
        val magnitude = hypot(x, y)
        dx = x / magnitude.coerceAtLeast(1f); dy = y / magnitude.coerceAtLeast(1f)
        val strength = ((magnitude.coerceAtMost(1f) - .12f) / .88f).coerceAtLeast(0f)
        listener?.invoke(Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat(), strength)
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { cancelInput(); super.onDetachedFromWindow() }
}

typealias LocationListener = (angle: Float, strength: Float) -> Unit

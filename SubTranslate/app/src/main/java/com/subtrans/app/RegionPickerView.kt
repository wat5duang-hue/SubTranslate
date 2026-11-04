package com.subtrans.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * หน้าจอโปร่งแสงสำหรับลากกรอบเลือกบริเวณซับ
 * - ลากในกรอบ = ย้ายกรอบ
 * - ลากที่มุม/ขอบ = ปรับขนาด
 * - ลากนอกกรอบ = วาดกรอบใหม่
 */
class RegionPickerView(context: Context) : View(context) {

    /** กรอบในพิกัดของ View นี้ (px) */
    val rect = RectF()

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF43A047.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val touchSlop = dp(28f)
    private val minSize = dp(40f)

    private var mode = 0 // 0 none, 1 move, 2 resize, 3 new
    private var edgeL = false
    private var edgeT = false
    private var edgeR = false
    private var edgeB = false
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRect(rect, Path.Direction.CW)
        canvas.drawPath(path, dim)
        canvas.drawRect(rect, border)
        val r = dp(7f)
        for (x in floatArrayOf(rect.left, rect.right)) for (y in floatArrayOf(rect.top, rect.bottom)) {
            canvas.drawCircle(x, y, r, handle)
            canvas.drawCircle(x, y, r, border)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x; lastY = y; startX = x; startY = y
                val inX = x > rect.left - touchSlop && x < rect.right + touchSlop
                val inY = y > rect.top - touchSlop && y < rect.bottom + touchSlop
                edgeL = inY && abs(x - rect.left) < touchSlop
                edgeR = inY && abs(x - rect.right) < touchSlop
                edgeT = inX && abs(y - rect.top) < touchSlop
                edgeB = inX && abs(y - rect.bottom) < touchSlop
                mode = when {
                    edgeL || edgeR || edgeT || edgeB -> 2
                    rect.contains(x, y) -> 1
                    else -> 3
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                when (mode) {
                    1 -> {
                        val ox = dx.coerceIn(-rect.left, width - rect.right)
                        val oy = dy.coerceIn(-rect.top, height - rect.bottom)
                        rect.offset(ox, oy)
                    }
                    2 -> {
                        if (edgeL) rect.left = (rect.left + dx).coerceIn(0f, rect.right - minSize)
                        if (edgeR) rect.right = (rect.right + dx).coerceIn(rect.left + minSize, width.toFloat())
                        if (edgeT) rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - minSize)
                        if (edgeB) rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, height.toFloat())
                    }
                    3 -> {
                        val l = min(startX, x).coerceAtLeast(0f)
                        val t = min(startY, y).coerceAtLeast(0f)
                        val r = max(startX, x).coerceAtMost(width.toFloat())
                        val b = max(startY, y).coerceAtMost(height.toFloat())
                        if (r - l >= minSize && b - t >= minSize) rect.set(l, t, r, b)
                    }
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }
}

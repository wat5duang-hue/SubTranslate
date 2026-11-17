package com.subtrans.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.min

/**
 * ข้อความแบบซับไตเติล (ตัวอักษรสี + ขอบดำ) รองรับพื้นหลังกึ่งโปร่งใส
 * วาด 3 ขั้น: พื้นหลังกึ่งโปร่งใส (ถ้าเปิด) -> เส้นขอบสีดำ -> ตัวอักษรทับ
 */
class OutlineTextView(context: Context) : View(context) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bgRect = RectF()
    private var strokePx = 0f
    private var pad = 0
    private var bgPadX = 0
    private var bgPadY = 0
    private var cornerRadiusPx = 0f
    private var layout: StaticLayout? = null

    var textColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** ความทึบพื้นหลัง 0..220 (0 = ไม่มีพื้นหลัง) */
    var bgAlpha: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, 220)
            if (field == clamped) return
            field = clamped
            updateBgPadding()
            requestLayout()
            invalidate()
        }

    var text: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** ความกว้างสูงสุดก่อนตัดขึ้นบรรทัดใหม่ */
    var maxWidthPx: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    init {
        val dm = resources.displayMetrics
        cornerRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, dm)
        setStyle(20, 4)
    }

    private fun updateBgPadding() {
        val dm = resources.displayMetrics
        if (bgAlpha > 0) {
            bgPadX = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, dm).toInt()
            bgPadY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, dm).toInt()
        } else {
            bgPadX = 0
            bgPadY = 0
        }
    }

    /** ตั้งขนาดตัวอักษร (sp) และความหนาขอบ (dp) */
    fun setStyle(textSizeSp: Int, strokeDp: Int) {
        val dm = resources.displayMetrics
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat(), dm)
        strokePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, strokeDp.toFloat(), dm)
        pad = ceil(strokePx).toInt() + 2
        updateBgPadding()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalPadX = pad + bgPadX
        val totalPadY = pad + bgPadY
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val outer = if (mode == MeasureSpec.UNSPECIFIED) maxWidthPx else min(size, maxWidthPx)
        val limit = (outer - totalPadX * 2).coerceAtLeast(1)
        paint.style = Paint.Style.FILL
        val desired = ceil(Layout.getDesiredWidth(text, paint)).toInt()
        val lineWidth = min(desired, limit).coerceAtLeast(1)
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, lineWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(true)
            .build()
        layout = l
        setMeasuredDimension(lineWidth + totalPadX * 2, l.height + totalPadY * 2)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        if (bgAlpha > 0) {
            bgPaint.color = Color.argb(bgAlpha, 0, 0, 0)
            bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(bgRect, cornerRadiusPx, cornerRadiusPx, bgPaint)
        }
        canvas.save()
        canvas.translate((pad + bgPadX).toFloat(), (pad + bgPadY).toFloat())
        if (strokePx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokePx
            paint.color = Color.BLACK
            l.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.color = textColor
        l.draw(canvas)
        canvas.restore()
    }
}

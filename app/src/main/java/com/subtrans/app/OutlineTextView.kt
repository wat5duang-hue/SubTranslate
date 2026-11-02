package com.subtrans.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.min

/**
 * ข้อความแบบซับไตเติล (ตัวอักษรสี + ขอบดำ) ไม่มีพื้นหลัง
 * วาด 2 รอบ: รอบแรกเป็นเส้นขอบสีดำ รอบสองเป็นตัวอักษรทับ
 */
class OutlineTextView(context: Context) : View(context) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private var strokePx = 0f
    private var pad = 0
    private var layout: StaticLayout? = null

    var textColor: Int = Color.WHITE
        set(value) {
            field = value
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
        setStyle(20, 4)
    }

    /** ตั้งขนาดตัวอักษร (sp) และความหนาขอบ (dp) */
    fun setStyle(textSizeSp: Int, strokeDp: Int) {
        val dm = resources.displayMetrics
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat(), dm)
        strokePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, strokeDp.toFloat(), dm)
        pad = ceil(strokePx).toInt() + 2
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val outer = if (mode == MeasureSpec.UNSPECIFIED) maxWidthPx else min(size, maxWidthPx)
        val limit = (outer - pad * 2).coerceAtLeast(1)
        paint.style = Paint.Style.FILL
        val desired = ceil(Layout.getDesiredWidth(text, paint)).toInt()
        val lineWidth = min(desired, limit).coerceAtLeast(1)
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, lineWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(true)
            .build()
        layout = l
        setMeasuredDimension(lineWidth + pad * 2, l.height + pad * 2)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        canvas.save()
        canvas.translate(pad.toFloat(), pad.toFloat())
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

package com.kazumaproject.tenkey.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 透過の数字テンキーで、画像のキー（括弧キー・← → の位置）に文字を出すための絵。
 * 真ん中に大きく [center]、上・左・右にフリックの文字を小さく描く。
 */
class ClearLabelDrawable(
    private val center: String,
    private val top: String? = null,
    private val left: String? = null,
    private val right: String? = null,
    private val color: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@ClearLabelDrawable.color
        textAlign = Paint.Align.CENTER
    }
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.alpha = drawableAlpha
        val big = minOf(b.height() * 0.38f, b.width() * 0.32f)
        val small = big * 0.6f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        fun text(s: String, x: Float, y: Float, size: Float) {
            paint.textSize = size
            val fm = paint.fontMetrics
            canvas.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, paint)
        }
        text(center, cx, cy, big)
        top?.let { text(it, cx, cy - big * 0.95f, small) }
        left?.let { text(it, cx - big * 1.0f, cy, small) }
        right?.let { text(it, cx + big * 1.0f, cy, small) }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

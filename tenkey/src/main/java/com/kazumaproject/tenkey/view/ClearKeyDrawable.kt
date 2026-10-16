package com.kazumaproject.tenkey.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 透過モード用のキー背景。普段は何も描かず、押している間だけ薄く光らせる。
 * 格子の線はキーではなく TenKey 側でまとめて描く（隙間の真ん中に1本だけ引くため）。
 */
class ClearKeyDrawable(
    private val pressedColor: Int = 0x40FFFFFF,
    private val onPressedChanged: (() -> Unit)? = null,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pressedColor }
    private var pressed = false
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        if (!pressed) return
        paint.color = pressedColor
        paint.alpha = (android.graphics.Color.alpha(pressedColor) * drawableAlpha) / 255
        canvas.drawRect(bounds, paint)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = state.contains(android.R.attr.state_pressed) ||
            state.contains(android.R.attr.state_selected)
        if (nowPressed == pressed) return false
        pressed = nowPressed
        invalidateSelf()
        onPressedChanged?.invoke()
        return true
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

    override fun getConstantState(): ConstantState = State(pressedColor, onPressedChanged)

    private class State(
        private val pressedColor: Int,
        private val onPressedChanged: (() -> Unit)?,
    ) : ConstantState() {
        override fun newDrawable(): Drawable = ClearKeyDrawable(pressedColor, onPressedChanged)
        override fun getChangingConfigurations(): Int = 0
    }
}

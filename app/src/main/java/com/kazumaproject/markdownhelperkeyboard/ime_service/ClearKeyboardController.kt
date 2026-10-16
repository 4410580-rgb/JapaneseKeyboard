package com.kazumaproject.markdownhelperkeyboard.ime_service

import android.graphics.Color
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService.Insets
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.kazumaproject.markdownhelperkeyboard.databinding.MainLayoutBinding

/**
 * 透過キーボード（日本語テンキーを格子だけで描くモード）の見た目と insets をまとめて扱う。
 *
 * - 有効な間はアプリの画面を縮めない（Samsung のフローティングと同じ insets）。
 *   キーボードの裏にアプリの文章がそのまま見える。
 * - アプリの入力欄はキーの裏に回るので、キーボードの上に「書いている文章の末尾」を1行出す。
 * - 候補の段は半透明の暗い帯にする（候補が読めるように）。
 */
class ClearKeyboardController(
    private val isAllowed: () -> Boolean,
    private val onActiveChanged: (active: Boolean, mainView: MainLayoutBinding) -> Unit,
) {
    private var container: FrameLayout? = null
    private var mainView: MainLayoutBinding? = null
    private var preview: TextView? = null
    private var lastActive: Boolean? = null
    private var previewText: CharSequence = ""

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        sync()
        true
    }

    private val rootLayoutListener =
        View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) positionPreview()
        }

    fun attach(container: FrameLayout, mainView: MainLayoutBinding) {
        detach()
        this.container = container
        this.mainView = mainView
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val padH = (12 * density).toInt()
            val padV = (4 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setShadowLayer(3f * density, 0f, 0f, 0xE6000000.toInt())
            background = GradientDrawable().apply { setColor(0x99000000.toInt()) }
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            text = previewText
        }
        container.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        preview = tv
        mainView.root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        mainView.root.addOnLayoutChangeListener(rootLayoutListener)
        lastActive = null
    }

    fun detach() {
        mainView?.root?.let { root ->
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
            root.removeOnLayoutChangeListener(rootLayoutListener)
        }
        preview?.let { (it.parent as? ViewGroup)?.removeView(it) }
        preview = null
        mainView = null
        container = null
        lastActive = null
    }

    /** いま透過モードとして振る舞うべきか。 */
    fun isActive(): Boolean {
        val view = mainView ?: return false
        return isAllowed() &&
            view.keyboardView.isVisible &&
            view.keyboardView.isClearGridApplied &&
            !view.keyboardSymbolView.isVisible
    }

    /** onComputeInsets から呼ぶ。透過中なら true を返し、アプリを縮めない insets を入れる。 */
    fun computeInsets(outInsets: Insets, windowHeight: Int): Boolean {
        if (!isActive()) return false
        val root = mainView?.root ?: return false
        if (windowHeight <= 0 || root.height == 0) return false
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        outInsets.contentTopInsets = windowHeight
        outInsets.visibleTopInsets = windowHeight
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        // 触れるのはキーボード本体と候補の段だけ。上の1行プレビューは素通しにする。
        val region = Region(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
        outInsets.touchableRegion.set(region)
        return true
    }

    /** 書いている文章の末尾をプレビューに出す。 */
    fun updatePreview(connection: InputConnection?) {
        val tv = preview ?: return
        if (!isActive()) return
        val before = runCatching { connection?.getTextBeforeCursor(PREVIEW_CHARS, 0) }
            .getOrNull() ?: ""
        // 改行より前は表示しない（1行で最後の行だけ見せる）
        val lastLine = before.toString().substringAfterLast('\n')
        if (lastLine == previewText.toString()) return
        previewText = lastLine
        tv.text = lastLine
        val shouldShow = lastLine.isNotEmpty()
        if (tv.isVisible != shouldShow) tv.isVisible = shouldShow
    }

    private fun positionPreview() {
        val tv = preview ?: return
        val root = mainView?.root ?: return
        val lp = tv.layoutParams as? FrameLayout.LayoutParams ?: return
        val rootMargin = (root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val bottom = root.height + rootMargin
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom
            tv.post { tv.layoutParams = lp }
        }
    }

    private fun sync() {
        val view = mainView ?: return
        val active = isActive()
        if (active != lastActive) {
            lastActive = active
            if (!active) {
                preview?.isVisible = false
                restoreChrome(view)
            } else {
                preview?.isVisible = previewText.isNotEmpty()
                positionPreview()
            }
            onActiveChanged(active, view)
            // insets を取り直してもらう
            container?.post { container?.requestLayout() }
        }
        if (active) enforceChrome(view)
    }

    private val savedVisibility = HashMap<View, Int>()

    private fun enforceChrome(view: MainLayoutBinding) {
        view.root.background?.let { if (it.alpha != 0) it.alpha = 0 }
        view.suggestionViewParent.background?.let { if (it.alpha != SCRIM_ALPHA) it.alpha = SCRIM_ALPHA }
        view.candidateTabLayout.background?.let { if (it.alpha != SCRIM_ALPHA) it.alpha = SCRIM_ALPHA }
        hide(view.keyboardBackgroundContainer)
        hide(view.shortcutToolbarRecyclerview)
    }

    private fun hide(v: View) {
        if (v.visibility == View.GONE) return
        savedVisibility.putIfAbsent(v, v.visibility)
        v.visibility = View.GONE
    }

    private fun restoreChrome(view: MainLayoutBinding) {
        view.root.background?.alpha = 255
        view.suggestionViewParent.background?.alpha = 255
        view.candidateTabLayout.background?.alpha = 255
        savedVisibility.forEach { (v, vis) -> v.visibility = vis }
        savedVisibility.clear()
    }

    companion object {
        private const val PREVIEW_CHARS = 200
        private const val SCRIM_ALPHA = 150
    }
}

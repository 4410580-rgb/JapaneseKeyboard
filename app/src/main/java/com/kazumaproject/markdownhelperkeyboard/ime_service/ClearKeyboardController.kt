package com.kazumaproject.markdownhelperkeyboard.ime_service

import android.graphics.Region
import android.inputmethodservice.InputMethodService.Insets
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.kazumaproject.markdownhelperkeyboard.databinding.FloatingKeyboardLayoutBinding
import com.kazumaproject.markdownhelperkeyboard.databinding.MainLayoutBinding

/**
 * 透過キーボード（日本語テンキーを格子だけで描くモード）の見た目と insets をまとめて扱う。
 *
 * - 有効な間はアプリの画面を縮めない（Samsung のフローティングと同じ insets）。
 *   キーボードの裏にアプリの文章がそのまま見える。
 * - 候補の段は半透明の暗い帯にする（候補が読めるように）。
 */
class ClearKeyboardController(
    private val isAllowed: () -> Boolean,
    private val onActiveChanged: (active: Boolean, mainView: MainLayoutBinding) -> Unit,
) {
    private var container: FrameLayout? = null
    private var mainView: MainLayoutBinding? = null
    private var lastActive: Boolean? = null
    private val chrome = ClearChrome()

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        sync()
        true
    }

    // 長押しで持ち上げた高さ（px）。透過中だけ効く。
    private var liftPx = -1f

    private fun prefs(ctx: android.content.Context) =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)

    fun moveBy(dyPx: Float) {
        val c = container ?: return
        val root = mainView?.root ?: return
        if (liftPx < 0f) liftPx = prefs(c.context).getFloat(PREF_LIFT, 0f)
        val maxLift = (c.resources.displayMetrics.heightPixels - root.height -
            48 * c.resources.displayMetrics.density).coerceAtLeast(0f)
        liftPx = (liftPx - dyPx).coerceIn(0f, maxLift)
        applyLift(true)
    }

    private var floatingOn: Boolean? = null

    private fun isFloatingOn(ctx: android.content.Context): Boolean =
        floatingOn ?: prefs(ctx).getBoolean(PREF_FLOATING, false).also { floatingOn = it }

    /** フローティング中か（長押しで上下に動かせる状態か） */
    fun canMove(): Boolean {
        val c = container ?: return false
        return isActive() && isFloatingOn(c.context)
    }

    /** 記号キーの長押し: 画面下に固定 ⇔ 浮かせる */
    fun toggleFloating() {
        val c = container ?: return
        val next = !isFloatingOn(c.context)
        floatingOn = next
        if (liftPx < 0f) liftPx = prefs(c.context).getFloat(PREF_LIFT, 0f)
        if (next && liftPx < 1f) {
            // 初めて浮かせるときは画面の 1/3 くらいの高さに出す
            liftPx = c.resources.displayMetrics.heightPixels / 3f
        }
        prefs(c.context).edit()
            .putBoolean(PREF_FLOATING, next)
            .putFloat(PREF_LIFT, liftPx)
            .apply()
        lastFloatingActive = null
        applyLift(isFloatingActive())
        // insets（アプリを押し上げるかどうか）を取り直してもらう
        c.post { c.requestLayout() }
    }

    fun saveLift() {
        val c = container ?: return
        prefs(c.context).edit().putFloat(PREF_LIFT, liftPx.coerceAtLeast(0f)).apply()
    }

    private fun applyLift(active: Boolean) {
        val c = container ?: return
        if (liftPx < 0f) liftPx = prefs(c.context).getFloat(PREF_LIFT, 0f)
        val bottom = if (active) liftPx.toInt() else 0
        if (c.paddingBottom != bottom) c.setPadding(0, 0, 0, bottom)
    }

    fun attach(container: FrameLayout, mainView: MainLayoutBinding) {
        detach()
        this.container = container
        this.mainView = mainView
        mainView.root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        lastActive = null
    }

    fun detach() {
        container?.setPadding(0, 0, 0, 0)
        mainView?.root?.let { root ->
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
        }
        mainView = null
        container = null
        lastActive = null
        lastFloatingActive = null
    }

    /** いま透過モードとして振る舞うべきか。 */
    fun isActive(): Boolean {
        val view = mainView ?: return false
        return isAllowed() &&
            view.keyboardView.isVisible &&
            view.keyboardView.isClearGridApplied &&
            !view.keyboardSymbolView.isVisible
    }

    /**
     * 浮かせている状態か。テンキー・QWERTY・候補の全画面表示・記号など、
     * どのキーボードでも同じ高さに浮かせる。
     */
    fun isFloatingActive(): Boolean {
        val c = container ?: return false
        val view = mainView ?: return false
        return isAllowed() && isFloatingOn(c.context) && view.root.isShown
    }

    private var lastFloatingActive: Boolean? = null

    /**
     * onComputeInsets から呼ぶ。浮かせている間は true を返し、アプリを縮めない insets を入れる。
     * 画面下に固定しているときは普通のキーボードと同じくアプリを押し上げる。
     */
    fun computeInsets(outInsets: Insets, windowHeight: Int): Boolean {
        if (!isFloatingActive()) return false
        val root = mainView?.root ?: return false
        if (windowHeight <= 0 || root.height == 0) return false
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        outInsets.contentTopInsets = windowHeight
        outInsets.visibleTopInsets = windowHeight
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(
            Region(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
        )
        return true
    }

    private fun sync() {
        val view = mainView ?: return
        val active = isActive()
        if (active != lastActive) {
            lastActive = active
            if (!active) chrome.restore(view.root, view.suggestionViewParent, view.candidateTabLayout)
            onActiveChanged(active, view)
            // insets を取り直してもらう
            container?.post { container?.requestLayout() }
        }
        val floating = isFloatingActive()
        if (floating != lastFloatingActive) {
            lastFloatingActive = floating
            applyLift(floating)
            container?.post { container?.requestLayout() }
        }
        if (active) {
            chrome.enforce(
                transparent = listOf(view.root),
                scrim = listOf(view.suggestionViewParent, view.candidateTabLayout),
                hidden = listOf(view.keyboardBackgroundContainer, view.shortcutToolbarRecyclerview),
            )
        }
    }
}

private const val PREF_LIFT = "clear_keyboard_lift_px"
private const val PREF_FLOATING = "clear_keyboard_floating"

/** フローティング表示のときも、日本語テンキー中は格子だけにする。 */
class FloatingClearChrome(
    private val binding: FloatingKeyboardLayoutBinding,
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private var lastActive: Boolean? = null
    private val chrome = ClearChrome()

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        sync()
        true
    }

    init {
        binding.root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    private fun sync() {
        val active = binding.keyboardViewFloating.isVisible &&
            binding.keyboardViewFloating.isClearGridApplied &&
            !binding.floatingSymbolKeyboard.isVisible
        if (active != lastActive) {
            lastActive = active
            if (!active) chrome.restore(binding.root, binding.suggestionViewParent)
            onActiveChanged(active)
        }
        if (active) {
            chrome.enforce(
                transparent = listOf(binding.root),
                scrim = listOf(binding.suggestionViewParent),
                hidden = listOf(binding.floatingKeyboardBackgroundContainer),
            )
        }
    }
}

private class ClearChrome {
    private val savedVisibility = HashMap<View, Int>()

    fun enforce(transparent: List<View>, scrim: List<View>, hidden: List<View>) {
        transparent.forEach { v -> v.background?.let { if (it.alpha != 0) it.alpha = 0 } }
        scrim.forEach { v -> v.background?.let { if (it.alpha != SCRIM_ALPHA) it.alpha = SCRIM_ALPHA } }
        hidden.forEach { v ->
            if (v.visibility != View.GONE) {
                savedVisibility.putIfAbsent(v, v.visibility)
                v.visibility = View.GONE
            }
        }
    }

    fun restore(vararg views: View) {
        views.forEach { it.background?.alpha = 255 }
        savedVisibility.forEach { (v, vis) -> v.visibility = vis }
        savedVisibility.clear()
    }

    companion object {
        private const val SCRIM_ALPHA = 150
    }
}

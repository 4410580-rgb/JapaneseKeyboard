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
    private val hasCandidates: () -> Boolean,
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
        // 掴んでいる間は指に合わせて動かす（下まで下ろしたかどうかは離した後に判定）
        dragging = true
        val bottom = liftPx.toInt()
        if (c.paddingBottom != bottom) c.setPadding(0, 0, 0, bottom)
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
        if (next && liftPx < 24 * c.resources.displayMetrics.density) {
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

    private var dragging = false

    fun saveLift() {
        dragging = false
        val c = container ?: return
        prefs(c.context).edit().putFloat(PREF_LIFT, liftPx.coerceAtLeast(0f)).apply()
        lastFloatingActive = null
        applyLift(isFloatingActive())
        c.post { c.requestLayout() }
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
        if (liftPx < 0f) liftPx = prefs(c.context).getFloat(PREF_LIFT, 0f)
        // 一番下まで下ろしたら、浮かせていても普通のキーボードと同じ扱いにする
        val dockSnap = 24 * c.resources.displayMetrics.density
        return isAllowed() && isFloatingOn(c.context) && liftPx >= dockSnap && view.root.isShown
    }

    private var lastFloatingActive: Boolean? = null

    /**
     * onComputeInsets から呼ぶ。浮かせている間は true を返し、アプリを縮めない insets を入れる。
     * 画面下に固定しているときは普通のキーボードと同じくアプリを押し上げる。
     */
    fun computeInsets(outInsets: Insets, windowHeight: Int): Boolean {
        val root = mainView?.root ?: return false
        if (!isAllowed() || !root.isShown) return false
        if (windowHeight <= 0 || root.height == 0) return false
        val floating = isFloatingActive()
        val region = Region()
        var top = Int.MAX_VALUE
        val loc = IntArray(2)
        contentViews(floating).forEach { v ->
            v.getLocationInWindow(loc)
            region.op(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height, Region.Op.UNION)
            top = minOf(top, loc[1])
        }
        if (top == Int.MAX_VALUE) return false
        // 浮かせているときはアプリを縮めない。画面下のときは中身の上端までアプリを押し上げる
        // （候補の段の上にある透明な余白はアプリ側に返す）。
        val insetTop = if (floating) windowHeight else top
        outInsets.contentTopInsets = insetTop
        outInsets.visibleTopInsets = insetTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(region)
        return true
    }

    /**
     * 実際に中身がある子ビュー。背景や効果用のレイヤー、隠れた段は含めない。
     * 浮かせていて候補が無いときは候補の段も外し、その分は裏のアプリを触れるようにする。
     */
    private fun contentViews(floating: Boolean): List<View> {
        val view = mainView ?: return emptyList()
        val showStrip = !floating || hasCandidates()
        val strip = listOf(view.suggestionViewParent, view.candidateTabLayout, view.shortcutToolbarRecyclerview)
        return listOf(
            view.suggestionViewParent,
            view.candidateTabLayout,
            view.shortcutToolbarRecyclerview,
            view.keyboardView,
            view.gojuonView,
            view.qwertyView,
            view.customLayoutDefault,
            view.gemmaHandwritingKeyboard,
            view.candidatesRowView,
            view.keyboardSymbolView,
        ).filter { v ->
            v.isShown && v.width > 0 && v.height > 0 && (showStrip || v !in strip)
        }
    }

    /**
     * 候補一覧（全画面の候補）を開いている状態。テンキーが透過モードだったときだけ、
     * 一覧もテンキーと同じ大きさに収める。
     */
    private fun isListMode(view: MainLayoutBinding): Boolean =
        isAllowed() &&
            !view.keyboardView.isVisible &&
            view.candidatesRowView.isVisible &&
            view.keyboardView.isClearGridApplied &&
            !view.keyboardSymbolView.isVisible

    /** 候補の段の高さを格子1段に合わせるか（テンキーか、その候補一覧を出しているとき） */
    fun stripMatchesGridRow(): Boolean {
        val view = mainView ?: return false
        return isActive() || isListMode(view)
    }

    private var lastContext: Boolean? = null
    private var listBackgroundSaved = false
    private var savedListBackground: android.graphics.drawable.Drawable? = null

    private fun sync() {
        val view = mainView ?: return
        val active = isActive()
        val listMode = !active && isListMode(view)
        val context = active || listMode
        if (active != lastActive) {
            lastActive = active
            onActiveChanged(active, view)
            // insets を取り直してもらう
            container?.post { container?.requestLayout() }
        }
        if (context != lastContext) {
            lastContext = context
            if (!context) {
                chrome.restore(view.root, view.suggestionViewParent, view.candidateTabLayout)
                view.suggestionViewParent.translationY = 0f
            }
        }
        if (!listMode) leaveListMode(view)
        val floating = isFloatingActive()
        if (!dragging && floating != lastFloatingActive) {
            lastFloatingActive = floating
            applyLift(floating)
            container?.post { container?.requestLayout() }
        }
        if (context) {
            chrome.enforce(
                transparent = listOf(view.root),
                scrim = listOf(view.suggestionViewParent, view.candidateTabLayout),
                hidden = buildList {
                    add(view.keyboardBackgroundContainer)
                    add(view.shortcutToolbarRecyclerview)
                    // 候補一覧を開く矢印は左上のマスの長押しが代わりをするので出さない（候補の幅を優先）
                    if (active) add(view.suggestionVisibility)
                },
            )
        }
        if (active) {
            overlayStripOnTopRow(view, view.keyboardView)
        } else if (listMode) {
            enterListMode(view)
            overlayStripOnTopRow(view, view.candidatesRowView)
        }
        view.keyboardView.setCandidateOverlay(active && hasCandidates())
        // 候補が無いときは候補の段ごと見えなくする（浮かせているときは触れる範囲からも外している）
        val stripAlpha = if ((active || floating) && !hasCandidates()) 0f else 1f
        if (view.suggestionViewParent.alpha != stripAlpha) view.suggestionViewParent.alpha = stripAlpha
    }

    /**
     * 候補一覧をテンキーと同じ大きさに収める: 候補の段を一覧の一番上の段に重ね、
     * 一覧の中身はその下から並べる。
     */
    private fun enterListMode(view: MainLayoutBinding) {
        val list = view.candidatesRowView
        val strip = view.suggestionViewParent
        if (!listBackgroundSaved) {
            listBackgroundSaved = true
            savedListBackground = list.background
            list.background = strip.background?.constantState?.newDrawable()?.mutate()
            list.clipToPadding = true
        }
        list.background?.let { if (it.alpha != 255) it.alpha = 255 }
        val rowHeight = (list.height / 4f).toInt()
        if (rowHeight > 0 && list.paddingTop != rowHeight) {
            list.setPadding(list.paddingLeft, rowHeight, list.paddingRight, list.paddingBottom)
        }
        // 一覧の上に候補の段を描く（触っても一覧ではなく候補の段に届く）
        if (strip.translationZ != 1f) strip.translationZ = 1f
    }

    private fun leaveListMode(view: MainLayoutBinding) {
        if (!listBackgroundSaved) return
        listBackgroundSaved = false
        val list = view.candidatesRowView
        list.background = savedListBackground
        savedListBackground = null
        list.setPadding(list.paddingLeft, 0, list.paddingRight, list.paddingBottom)
        view.suggestionViewParent.translationZ = 0f
    }

    /**
     * 候補の段を一番上の段に重ねる。テンキーのときはキーが上にあるので、触れるのは左上のマスだけ
     * （TenKey が受けて候補のタップ・スライド・長押しに変える）。上に空いた分はアプリ側に返る。
     */
    private fun overlayStripOnTopRow(view: MainLayoutBinding, base: View) {
        val strip = view.suggestionViewParent
        if (base.height == 0 || strip.height == 0) return
        val rowHeight = base.height / 4f
        val targetTop = base.top + ((rowHeight - strip.height) / 2f).coerceAtLeast(0f)
        val dy = targetTop - strip.top
        if (kotlin.math.abs(strip.translationY - dy) > 0.5f) strip.translationY = dy
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
        private const val SCRIM_ALPHA = 255 // 候補の段は不透明
    }
}

package com.spizganed.quickbuds.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.widget.WidgetStateStore
import kotlin.math.abs
import kotlin.math.max

/**
 * The connect popup: a card that slides up from the bottom of the screen when the buds connect. It shows
 * the model name and the left bud, case and right bud in battery rings ([BudsStatusView]). The rings sweep up as the battery report arrives.
 *
 * It is a system overlay (Display over other apps), so it needs [Settings.canDrawOverlays]. It is off until the
 * user turns it on in Settings.
 * Tap opens the app, a swipe down or the close button dismisses it, and it goes by itself after [SHOW_MS].
 * All calls must come on the main thread.
 */
class ConnectPopup(context: Context) {

    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var status: BudsStatusView? = null
    private var subtitle: TextView? = null
    /** While the card slides in, levels wait here; the rings sweep once it has landed. */
    private var entering = false
    private var latest: WidgetStateStore.State? = null
    private var leaving = false

    private val autoHide = Runnable { dismiss() }

    val showing get() = root != null

    private fun dp(v: Float) = ThemeRes.dp(ctx, v)

    /** Show the card, or refresh it when it is already up. The levels are -1 when not known yet. */
    fun show(st: WidgetStateStore.State, connecting: Boolean = false) {
        if (!Settings.canDrawOverlays(ctx)) return
        if (root != null && !leaving) { update(st, connecting); restartTimer(); return }
        if (root != null) removeNow()

        val p = ThemeRes.palette(ctx)
        val bg = when {
            p.glass && p.glassLight -> 0xF7F4F5FB.toInt()
            p.glass -> 0xF71B1C26.toInt()
            else -> p.card
        }
        val text = if (p.glass) p.ink else p.text

        val statusView = BudsStatusView(ctx).apply {
            connected = true
            // Start empty, so the rings sweep up to the levels like a reveal.
            setState(-1, -1, -1, st.leftStatus, st.rightStatus)
        }

        val title = TextView(ctx).apply {
            this.text = ModelCatalog.current(ctx)?.name ?: ctx.getString(R.string.popup_title_fallback)
            textSize = 19f
            typeface = ThemeRes.bold(ctx)
            setTextColor(text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            this.text = ctx.getString(if (connecting) R.string.popup_connecting else R.string.popup_connected)
            textSize = 13f
            typeface = ThemeRes.regular(ctx)
            setTextColor(if (p.glass) Palette.withAlpha(p.ink, 0.65f) else p.textSecondary)
        }
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(sub)
        }
        val close = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (p.glass) Palette.withAlpha(p.ink, 0.65f) else p.textSecondary)
            val pad = dp(8f)
            setPadding(pad, pad, pad, pad)
            contentDescription = ctx.getString(R.string.popup_close)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.withAlpha(text, 0.08f))
            }
            setOnClickListener { dismiss() }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        }
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2f).toFloat()
                setColor(Palette.withAlpha(text, 0.22f))
            }
        }

        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28f).toFloat()
                setColor(bg)
                setStroke(dp(1f), Palette.withAlpha(text, 0.08f))
            }
            elevation = dp(12f).toFloat()
            setPadding(dp(20f), dp(10f), dp(20f), dp(12f))
            addView(handle, LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10f)
            })
            addView(header)
            addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14f)
            })
        }
        attachGestures(c)

        val r = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12f), dp(16f), dp(12f), dp(12f))
            addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // Keep the card above the navigation bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val nav = if (android.os.Build.VERSION.SDK_INT >= 30)
                    insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                v.setPadding(dp(12f), dp(16f), dp(12f), dp(12f) + nav)
                insets
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            setTitle("QuickBuds popup")
        }

        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            // The overlay permission went away between the check and now.
            return
        }
        root = r; card = c; status = statusView; subtitle = sub; leaving = false
        entering = true; latest = st

        // Enter: from below the screen, with a little overshoot.
        c.alpha = 0f
        c.translationY = dp(320f).toFloat()
        c.post {
            c.translationY = (c.height + dp(40f)).toFloat()
            c.animate().translationY(0f).alpha(1f)
                .setDuration(520).setInterpolator(OvershootInterpolator(0.9f))
                .withEndAction { entering = false; latest?.let { update(it) } }
                .start()
        }
        Haptics.tick(ctx)
        restartTimer()
    }

    /** New levels or wear for a card that is up; the rings animate to them. */
    fun update(st: WidgetStateStore.State, connecting: Boolean? = null) {
        if (leaving) return
        if (connecting != null) subtitle?.setText(if (connecting) R.string.popup_connecting else R.string.popup_connected)
        latest = st
        if (entering) return
        status?.setState(st.leftBattery, st.caseBattery, st.rightBattery, st.leftStatus, st.rightStatus)
    }

    fun dismiss() {
        val c = card ?: return
        if (leaving) return
        leaving = true
        handler.removeCallbacks(autoHide)
        c.animate().cancel()
        c.animate().translationY((c.height + dp(60f)).toFloat()).alpha(0f)
            .setDuration(260).setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { removeNow() }
            })
            .start()
    }

    private fun removeNow() {
        handler.removeCallbacks(autoHide)
        root?.let { runCatching { wm.removeView(it) } }
        root = null; card = null; status = null; subtitle = null; leaving = false
        entering = false; latest = null
    }

    private fun restartTimer() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, SHOW_MS)
    }

    /** Drag down to dismiss; a tap opens the app. Up-drags resist, so the card feels attached to the bottom. */
    private fun attachGestures(c: View) {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downY = 0f
        var dragging = false
        c.setOnClickListener { openApp(); dismiss() }
        c.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; dragging = false
                    handler.removeCallbacks(autoHide)
                    v.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && abs(dy) > slop) dragging = true
                    if (dragging) v.translationY = if (dy > 0) dy else dy / 6f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - downY
                    when {
                        dragging && dy > max(dp(60f).toFloat(), v.height / 4f) -> dismiss()
                        dragging -> { v.animate().translationY(0f).setDuration(200).start(); restartTimer() }
                        e.actionMasked == MotionEvent.ACTION_UP -> v.performClick()
                        else -> restartTimer()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openApp() {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { ctx.startActivity(i) }
    }

    companion object {
        const val SHOW_MS = 7_000L
        const val PREF_POPUP = "connectPopup"

        /** The user's switch. Off by default. */
        fun enabled(c: Context) =
            c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_POPUP, false)

        /** The switch is on and the app may draw over other apps: only then is the popup (and the lid scan) worth running. */
        fun ready(c: Context) = enabled(c) && Settings.canDrawOverlays(c)
    }
}

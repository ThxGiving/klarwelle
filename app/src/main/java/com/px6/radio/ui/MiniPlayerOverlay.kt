package com.px6.radio.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A small floating "now playing" window that hovers over other apps while the radio plays in the
 * background — like a music-app mini player, but with **no play/pause** (radio is live, there is
 * nothing to pause). Shows the station logo, the name and the metadata line, carries **‹ ›** to
 * step stations without opening the app, is **draggable**, and a tap opens the app; a small ×
 * closes the overlay (the radio keeps playing).
 *
 * Plain Android Views on a [WindowManager] system overlay (not Compose) — simplest and most robust
 * for a window that must live outside the Activity. Requires the "draw over other apps"
 * (SYSTEM_ALERT_WINDOW) permission; the caller checks `Settings.canDrawOverlays` first and only
 * calls [show] when it is granted, so this class never crashes on a missing permission.
 *
 * The content is **not** a snapshot: [update] is fed from the ViewModel's state while the window
 * is up, so a station change from the steering wheel or the knob is reflected here too.
 */
class MiniPlayerOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var nameView: TextView? = null
    private var lineView: TextView? = null
    private var logoView: ImageView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(24); y = dp(24) }

    val isShowing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        stationName: String,
        line: String?,
        logo: Bitmap?,
        onOpen: () -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onClose: () -> Unit,
    ) {
        if (root != null) { update(stationName, line, logo); return }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E6101418"))
                setStroke(dp(1), Color.parseColor("#3AFFFFFF"))
            }
        }
        val logoIv = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            scaleType = ImageView.ScaleType.FIT_CENTER
            logo?.let { setImageBitmap(it) }
        }
        // Name over metadata line — the second line is what changes while one station plays, and
        // without it the window says nothing new for an hour.
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(10); marginEnd = dp(4) }
        }
        val name = TextView(context).apply {
            text = stationName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(context).apply {
            text = line.orEmpty()
            visibility = if (line.isNullOrBlank()) View.GONE else View.VISIBLE
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(name); texts.addView(sub)

        // Stepping buttons. They carry their own click listener, so the drag handler on the
        // container never sees these touches — same mechanism the × already used.
        fun stepButton(glyph: String, onTap: () -> Unit) = TextView(context).apply {
            text = glyph
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(2) }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
            }
            setOnClickListener { onTap() }
        }
        val prev = stepButton("‹", onPrev)
        val next = stepButton("›", onNext)
        val close = TextView(context).apply {
            text = "×"
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { onClose() }
        }
        container.addView(logoIv); container.addView(texts)
        container.addView(prev); container.addView(next); container.addView(close)

        // Drag to move; a tap (no real movement) opens the app. Buttons have their own listeners.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    runCatching { wm.updateViewLayout(container, params) }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onOpen(); true }
                else -> false
            }
        }

        root = container; nameView = name; lineView = sub; logoView = logoIv
        runCatching { wm.addView(container, params) }.onFailure { root = null }
    }

    /** Live content update while the window is up — station change, new title, new logo. */
    fun update(stationName: String, line: String?, logo: Bitmap?) {
        nameView?.text = stationName
        lineView?.let {
            it.text = line.orEmpty()
            it.visibility = if (line.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        logoView?.let { iv ->
            if (logo != null) iv.setImageBitmap(logo) else iv.setImageDrawable(null)
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; nameView = null; lineView = null; logoView = null
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}

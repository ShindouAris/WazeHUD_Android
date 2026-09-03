package com.chisadin.hudwz.bubble

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.chisadin.hudwz.HudApplication
import com.chisadin.hudwz.MainActivity
import com.chisadin.hudwz.domain.HudAlert
import com.chisadin.hudwz.domain.HudState
import com.chisadin.hudwz.domain.TurnType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Bubble {
    const val PREFS = "waze_hud_gw"
    private const val COLOR_BUBBLE_GLASS = 0xBF0E0E14.toInt() // 75% opacity
    private const val COLOR_BRAND_GLASS = COLOR_BUBBLE_GLASS
    private const val COLOR_BUBBLE_BORDER = 0xFF364258.toInt()
    private const val MAX_ALERT_M = 2000

    private var view: View? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    private var spd: TextView? = null
    private var limitDash: TextView? = null
    private var gpsSignal: GpsSignalView? = null
    private var limitImg: ImageView? = null
    private var limitStack: FrameLayout? = null
    private var avgImg: ImageView? = null
    private var parkingImg: ImageView? = null
    private var navWrap: LinearLayout? = null
    private var navTurn: ImageView? = null
    private var navDist: TextView? = null
    private var navStreet: TextView? = null
    private var navExit: TextView? = null
    private var laneStrip: LaneGuidanceStrip? = null
    private var lanePanel: View? = null
    private var alertsCol: LinearLayout? = null

    private var lastTurn: TurnType = TurnType.NONE
    private var navShown: Boolean = false
    private var navStreetShown: Boolean = false
    private var avgShown: Boolean = false
    private var alertsSig: String? = ""

    private var dens: Float = 1f
    private var scale: Float = 1f
    private var bubbleLayout: Int = 0 // 0 = Horizontal, 1 = Vertical, 2 = Basic
    private var verticalLayout: Boolean = false
    private var basicLayout: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    private val resizeR = Runnable {
        try {
            val v = view ?: return@Runnable
            v.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            lp?.let { params ->
                params.width = v.measuredWidth
                params.height = v.measuredHeight
                val corrected = clampPosition()
                wm?.updateViewLayout(v, params)
                if (corrected) savePosition(v.context)
            }
        } catch (_: Throwable) { }
    }

    fun canShow(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun requestPermission(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun show(ctx: Context) {
        try {
            if (view != null) return
            val c = ctx.applicationContext
            if (!canShow(c)) return

            wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            dens = c.resources.displayMetrics.density

            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val bs = p.getInt("bub_size", 100)
            scale = max(0.8f, min(2.0f, bs / 100f))
            bubbleLayout = p.getInt("bubble_layout", 0)
            verticalLayout = bubbleLayout == 1
            basicLayout = bubbleLayout == 2

            view = build(c)
            alertsSig = null

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = p.getInt("bub_x", (10 * dens).toInt())
                y = p.getInt("bub_y", (150 * dens).toInt())
            }

            wm?.addView(view, lp)
            setupTouch(c)
            tick.run()
        } catch (_: Throwable) {
            view = null
        }
    }

    @Synchronized
    fun hide() {
        try { handler.removeCallbacks(tick) } catch (_: Throwable) { }
        try {
            if (wm != null && view != null) {
                wm?.removeView(view)
            }
        } catch (_: Throwable) { }
        view = null
    }

    @Synchronized
    fun isShowing(): Boolean = view != null

    @Synchronized
    fun refresh(ctx: Context? = null) {
        val targetCtx = ctx ?: view?.context ?: return
        if (view == null) return
        hide()
        show(targetCtx)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            try {
                val v = view ?: return
                val c = v.context
                val app = c.applicationContext as? HudApplication
                val state = app?.container?.hudRepository?.hudState?.value ?: HudState()

                val s = state.speed ?: 0
                val l = state.speedLimit

                spd?.let { speedTv ->
                    speedTv.text = s.toString()
                    val isOver = l != null && l >= 10 && s > l
                    speedTv.setTextColor(if (isOver) 0xFFFF4D4D.toInt() else 0xFFFFFFFF.toInt())
                }

                gpsSignal?.setLevel(if (state.connected && state.gpsAvailable) 3 else if (state.connected) 1 else 0)

                // Speed limit sign or dash
                if (l != null && l >= 10) {
                    val sign = Signs.limit(c, l)
                    if (sign != null) {
                        limitImg?.setImageBitmap(sign)
                        limitImg?.visibility = View.VISIBLE
                        limitDash?.visibility = View.GONE
                    } else {
                        limitDash?.text = l.toString()
                        limitDash?.visibility = View.VISIBLE
                        limitImg?.visibility = View.GONE
                    }
                } else {
                    limitDash?.text = "–"
                    limitDash?.visibility = View.VISIBLE
                    limitImg?.visibility = View.GONE
                }

                // Navigation guidance
                val turn = state.turn
                val showNav = state.navigating && turn != TurnType.NONE
                var laneChanged = false
                laneStrip?.let { strip ->
                    laneChanged = strip.setLanes(if (state.navigating) state.lanes else emptyList())
                }
                lanePanel?.let { panel ->
                    val vis = if (state.navigating && state.lanes.isNotEmpty()) View.VISIBLE else View.GONE
                    if (panel.visibility != vis) {
                        panel.visibility = vis
                        laneChanged = true
                    }
                }
                if (laneChanged) v.post(resizeR)

                if (showNav) {
                    if (turn != lastTurn) {
                        lastTurn = turn
                        val turnPath = Signs.turnDrawablePath(turn)
                        if (turnPath != null) {
                            val bm = Signs.loadAsset(c, turnPath)
                            navTurn?.setImageBitmap(bm)
                        } else {
                            navTurn?.setImageDrawable(null)
                        }
                    }
                    navDist?.text = fmtTurnDist(state.distanceMeters ?: 0)
                    val street = (state.nextStreet ?: state.street).orEmpty().trim()
                    navStreet?.text = street
                    val hasStreet = street.isNotEmpty()
                    navStreet?.visibility = if (hasStreet) View.VISIBLE else View.GONE
                    if (hasStreet != navStreetShown) {
                        navStreetShown = hasStreet
                        v.post(resizeR)
                    }

                    val ex = state.roundaboutExit
                    val isRoundabout = turn in setOf(
                        TurnType.ROUNDABOUT,
                        TurnType.ROUNDABOUT_LEFT,
                        TurnType.ROUNDABOUT_RIGHT,
                        TurnType.ROUNDABOUT_STRAIGHT,
                        TurnType.ROUNDABOUT_U_TURN,
                    ) && ex != null && ex > 0
                    navExit?.text = ex?.toString().orEmpty()
                    navExit?.visibility = if (isRoundabout) View.VISIBLE else View.GONE
                }
                navWrap?.visibility = if (showNav) View.VISIBLE else View.GONE
                if (showNav != navShown) {
                    navShown = showNav
                    v.post(resizeR)
                }

                // No passing zone sign
                val az = state.noPassingZone
                if (az && avgImg?.drawable == null) {
                    val nb = Signs.pin(c, "no_passing_in")
                    if (nb != null) avgImg?.setImageBitmap(nb)
                }
                avgImg?.visibility = if (az) View.VISIBLE else View.GONE
                if (az != avgShown) {
                    avgShown = az
                    v.post(resizeR)
                }

                // Alerts list
                val al = alertsList(state.alerts)
                val sb = StringBuilder()
                for (it in al) sb.append(it.type).append('@').append(it.distanceMeters).append(';')
                val sig = sb.toString()
                if (sig != alertsSig) {
                    alertsSig = sig
                    alertsCol?.let { fillAlerts(c, it, al) }
                    v.post(resizeR)
                }
            } catch (_: Throwable) { }
            handler.postDelayed(this, 500)
        }
    }

    private fun alertsList(alerts: List<HudAlert>): List<HudAlert> {
        val out = mutableListOf<HudAlert>()
        val seen = mutableSetOf<Int>()
        for (a in alerts) {
            if (out.size >= 4) break
            if (a.distanceMeters < 0 || a.distanceMeters > MAX_ALERT_M) continue
            if (seen.add(a.type)) {
                out.add(a)
            }
        }
        return out
    }

    private fun fillAlerts(c: Context, container: LinearLayout, items: List<HudAlert>) {
        container.removeAllViews()
        var any = false
        for (it in items) {
            val bm = Signs.alert(c, it) ?: continue
            val dist = fmtDist(it.distanceMeters)
            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            if (any) {
                if (verticalLayout) p.topMargin = px(8f)
                else p.leftMargin = px(10f)
            }
            container.addView(alertItem(c, bm, dist), p)
            any = true
        }
        if (!any) {
            container.addView(alertItem(c, null, "–"))
        }
        container.visibility = View.VISIBLE
    }

    private fun alertItem(c: Context, bm: Bitmap?, dist: String): View {
        val item = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val isz = px(if (verticalLayout) 42f else 38f)
        if (bm != null) {
            val iv = ImageView(c).apply {
                setImageBitmap(bm)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            item.addView(iv, LinearLayout.LayoutParams(isz, isz))
        } else {
            val ph = TextView(c).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFEDEDED.toInt())
                    setStroke(px(3f), 0xFFB0B0B0.toInt())
                }
            }
            item.addView(ph, LinearLayout.LayoutParams(isz, isz))
        }
        val dt = TextView(c).apply {
            text = dist
            setTextColor(0xFFDDE2E6.toInt())
            textSize = 10f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val dtp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = px(if (verticalLayout) 1f else 2f)
        }
        item.addView(dt, dtp)
        return item
    }

    private fun build(c: Context): View {
        lastTurn = TurnType.NONE
        navShown = false
        navStreetShown = false
        lanePanel = null
        gpsSignal = null
        return if (basicLayout) buildBasic(c)
        else if (verticalLayout) buildVertical(c)
        else buildHorizontal(c)
    }

    private fun buildBasic(c: Context): View {
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBorder(COLOR_BUBBLE_GLASS, px(30f))
            setPadding(px(4f), px(4f), px(4f), px(4f))
        }

        val logoBadge = FrameLayout(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_BUBBLE_BORDER)
            }
        }
        val logo = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(circularAppIcon(c, px(40f)))
        }
        val iconSize = px(40f)
        logoBadge.addView(logo, FrameLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER
        })
        val badgeSize = px(44f)
        body.addView(logoBadge, LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
            rightMargin = px(4f)
        })

        val speedBlock = speedBlockHorizontal(c)
        gpsSignal = GpsSignalView(c)
        val gpsLp = LinearLayout.LayoutParams(px(15f), px(15f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = px(2f)
        }
        speedBlock.addView(gpsSignal, gpsLp)
        body.addView(speedBlock)

        body.addView(limitBlockBasic(c))

        // Inactive placeholders to avoid null pointer exceptions in tick
        navWrap = LinearLayout(c).apply { visibility = View.GONE }
        navTurn = ImageView(c)
        navDist = TextView(c)
        navStreet = TextView(c)
        navExit = TextView(c)
        laneStrip = LaneGuidanceStrip(c)
        alertsCol = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        avgImg = ImageView(c).apply { visibility = View.GONE }
        parkingImg = ImageView(c).apply { visibility = View.GONE }

        return body
    }

    private fun limitBlockBasic(c: Context): View {
        val frame = FrameLayout(c)
        val size = px(46f)
        limitStack = frame
        frame.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            leftMargin = px(4f)
        }

        limitDash = TextView(c).apply {
            text = "–"
            setTextColor(0xFF6E7479.toInt())
            textSize = 22f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEDEDED.toInt())
                setStroke(px(2f), 0xFFB0B0B0.toInt())
            }
        }
        frame.addView(limitDash, FrameLayout.LayoutParams(size, size))

        limitImg = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        frame.addView(limitImg, FrameLayout.LayoutParams(size, size))
        return frame
    }

    private fun buildHorizontal(c: Context): View {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val body = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBorder(COLOR_BUBBLE_GLASS, px(22f))
            setPadding(px(14f), px(14f), px(14f), px(10f))
        }

        val tab = brandTabHorizontal(c)
        root.addView(tab, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(turnBlockHorizontal(c))
        body.addView(speedBlockHorizontal(c))
        body.addView(limitBlockHorizontal(c))

        val sep = View(c).apply {
            setBackgroundColor(0x80FFFFFF.toInt())
        }
        val sepP = LinearLayout.LayoutParams(
            max(1, (1.5f * scale).toInt()), px(34f)).apply {
            leftMargin = px(12f)
            rightMargin = px(12f)
        }
        body.addView(sep, sepP)

        alertsCol = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        body.addView(alertsCol)

        avgImg = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        body.addView(avgImg, LinearLayout.LayoutParams(px(40f), px(40f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = px(10f)
        })

        parkingImg = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        body.addView(parkingImg, LinearLayout.LayoutParams(px(40f), px(40f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = px(6f)
        })

        return root
    }

    private fun brandTabHorizontal(c: Context): View {
        val tab = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = brandTopRoundBorder(COLOR_BRAND_GLASS, brandPx(10f))
            setPadding(brandPx(11f), brandPx(4f), brandPx(11f), brandPx(4f))
        }

        gpsSignal = GpsSignalView(c)
        tab.addView(gpsSignal, LinearLayout.LayoutParams(brandPx(15f), brandPx(15f)).apply {
            rightMargin = brandPx(6f)
        })

        val t = TextView(c).apply {
            text = "WAZE HUD"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        tab.addView(t)

        val logo = ImageView(c).apply {
            try {
                setImageDrawable(c.packageManager.getApplicationIcon(c.packageName))
            } catch (_: Throwable) { }
        }
        val ls = brandPx(16f)
        tab.addView(logo, LinearLayout.LayoutParams(ls, ls).apply {
            leftMargin = brandPx(6f)
        })
        return tab
    }

    private fun speedBlockHorizontal(c: Context): LinearLayout {
        val b = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = px(45f)
        }

        spd = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f * scale
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
        }
        b.addView(spd)

        val unit = TextView(c).apply {
            text = "km/h"
            setTextColor(0xFFAEB4BA.toInt())
            textSize = 11f * scale
            gravity = Gravity.CENTER
        }
        b.addView(unit)
        return b
    }

    private fun limitBlockHorizontal(c: Context): View {
        val f = FrameLayout(c)
        val sz = px(44f)
        limitStack = f
        f.layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
            leftMargin = px(12f)
        }

        limitDash = TextView(c).apply {
            text = "–"
            setTextColor(0xFF6E7479.toInt())
            textSize = 22f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEDEDED.toInt())
                setStroke(px(2f), 0xFFB0B0B0.toInt())
            }
        }
        f.addView(limitDash, FrameLayout.LayoutParams(sz, sz))

        limitImg = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        f.addView(limitImg, FrameLayout.LayoutParams(sz, sz))
        return f
    }

    private fun turnBlockHorizontal(c: Context): View {
        navWrap = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        val block = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = px(102f)
        }

        val arrowSize = px(38f)
        val arrow = FrameLayout(c)
        navTurn = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        }
        arrow.addView(navTurn, FrameLayout.LayoutParams(arrowSize, arrowSize))

        navExit = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        arrow.addView(navExit, FrameLayout.LayoutParams(arrowSize, arrowSize))
        block.addView(arrow, LinearLayout.LayoutParams(arrowSize, arrowSize))

        val text = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textLp = LinearLayout.LayoutParams(px(68f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = px(5f)
        }
        navDist = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f * scale
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }
        text.addView(navDist)

        navStreet = TextView(c).apply {
            setTextColor(0xFF33CCFF.toInt())
            textSize = 9f * scale
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        text.addView(navStreet)
        block.addView(text, textLp)

        val stack = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        laneStrip = LaneGuidanceStrip(c)
        val laneLp = LinearLayout.LayoutParams(px(104f), px(34f)).apply {
            bottomMargin = px(2f)
        }
        stack.addView(laneStrip, laneLp)
        stack.addView(block)
        navWrap?.addView(stack)

        val sep = View(c).apply {
            setBackgroundColor(0x80FFFFFF.toInt())
        }
        val sepP = LinearLayout.LayoutParams(
            max(1, (1.5f * scale).toInt()), px(34f)).apply {
            leftMargin = px(8f)
            rightMargin = px(10f)
        }
        navWrap?.addView(sep, sepP)
        return navWrap!!
    }

    private fun buildVertical(c: Context): View {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        root.addView(brandTabVertical(c))

        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundBorder(COLOR_BUBBLE_GLASS, px(22f))
            val pad = px(7f)
            setPadding(pad, px(9f), pad, px(9f))
        }

        col.addView(turnBlockVertical(c))
        col.addView(limitBlockVertical(c))
        col.addView(speedBlockVertical(c), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(6f)
        })

        val sep = View(c).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val sepP = LinearLayout.LayoutParams(
            px(44f), max(1, (2 * scale).toInt())).apply {
            topMargin = (15 * scale).toInt()
            bottomMargin = (15 * scale).toInt()
        }
        col.addView(sep, sepP)

        alertsCol = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        col.addView(alertsCol)
        root.addView(col)

        val panel = FrameLayout(c).apply {
            background = roundBorder(COLOR_BUBBLE_GLASS, px(17f))
            setPadding(px(7f), px(5f), px(7f), px(5f))
            visibility = View.GONE
        }
        laneStrip = LaneGuidanceStrip(c)
        panel.addView(laneStrip, FrameLayout.LayoutParams(px(116f), px(46f)))
        val lanePanelLp = LinearLayout.LayoutParams(px(130f), px(56f)).apply {
            leftMargin = px(8f)
            topMargin = px(7f)
        }
        root.addView(panel, lanePanelLp)
        lanePanel = panel

        avgImg = ImageView(c).apply {
            visibility = View.GONE
        }
        root.addView(avgImg, LinearLayout.LayoutParams(px(46f), px(46f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = px(6f)
        })

        parkingImg = ImageView(c).apply {
            visibility = View.GONE
        }
        root.addView(parkingImg, LinearLayout.LayoutParams(px(46f), px(46f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = px(4f)
        })

        return root
    }

    private fun brandTabVertical(c: Context): View {
        val wrapperWidth = brandPx(112f)
        val visibleWidth = brandPx(30f)
        val tabHeight = brandPx(132f)

        val wrapper = FrameLayout(c)
        val tab = FrameLayout(c).apply {
            background = brandLeftRoundBorder(COLOR_BRAND_GLASS, brandPx(10f))
        }
        val panelLp = FrameLayout.LayoutParams(visibleWidth, tabHeight).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        wrapper.addView(tab, panelLp)

        val logo = ImageView(c).apply {
            try {
                setImageDrawable(c.packageManager.getApplicationIcon(c.packageName))
            } catch (_: Throwable) { }
        }
        val ls = brandPx(16f)
        tab.addView(logo, FrameLayout.LayoutParams(ls, ls).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = brandPx(8f)
        })

        val t = TextView(c).apply {
            text = "WAZE HUD"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setSingleLine(true)
            rotation = -90f
        }
        tab.addView(t, FrameLayout.LayoutParams(brandPx(80f), brandPx(22f)).apply {
            gravity = Gravity.CENTER
            topMargin = brandPx(9f)
        })

        gpsSignal = GpsSignalView(c)
        tab.addView(gpsSignal, FrameLayout.LayoutParams(brandPx(14f), brandPx(14f)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = brandPx(6f)
        })

        wrapper.layoutParams = LinearLayout.LayoutParams(wrapperWidth, tabHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = -(wrapperWidth - visibleWidth)
        }
        return wrapper
    }

    private fun speedBlockVertical(c: Context): View {
        val b = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        spd = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 23f * scale
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        b.addView(spd)

        val unit = TextView(c).apply {
            text = "km/h"
            setTextColor(0xFFAEB4BA.toInt())
            textSize = 10f * scale
            gravity = Gravity.CENTER
        }
        b.addView(unit)
        return b
    }

    private fun limitBlockVertical(c: Context): View {
        val f = FrameLayout(c)
        val sz = px(42f)
        limitStack = f
        f.layoutParams = LinearLayout.LayoutParams(sz, sz)

        limitDash = TextView(c).apply {
            text = "–"
            setTextColor(0xFF6E7479.toInt())
            textSize = 22f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEDEDED.toInt())
                setStroke(px(3f), 0xFFB0B0B0.toInt())
            }
        }
        f.addView(limitDash, FrameLayout.LayoutParams(sz, sz))

        limitImg = ImageView(c).apply {
            visibility = View.GONE
        }
        f.addView(limitImg, FrameLayout.LayoutParams(sz, sz))
        return f
    }

    private fun turnBlockVertical(c: Context): View {
        navWrap = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        val arrowSize = px(42f)
        val arrow = FrameLayout(c)
        navTurn = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        }
        arrow.addView(navTurn, FrameLayout.LayoutParams(arrowSize, arrowSize))

        navExit = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        arrow.addView(navExit, FrameLayout.LayoutParams(arrowSize, arrowSize))
        navWrap?.addView(arrow, LinearLayout.LayoutParams(arrowSize, arrowSize))

        navDist = TextView(c).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setSingleLine(true)
            minWidth = px(60f)
        }
        navWrap?.addView(navDist)

        navStreet = TextView(c).apply {
            setTextColor(0xFF33CCFF.toInt())
            textSize = 8f * scale
            gravity = Gravity.CENTER
            setSingleLine(true)
            maxWidth = px(56f)
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        navWrap?.addView(navStreet)

        val sep = View(c).apply {
            setBackgroundColor(0x80FFFFFF.toInt())
        }
        val sepP = LinearLayout.LayoutParams(
            px(44f), max(1, (1.5f * scale).toInt())).apply {
            topMargin = px(7f)
            bottomMargin = px(8f)
        }
        navWrap?.addView(sep, sepP)
        return navWrap!!
    }

    private fun setupTouch(c: Context) {
        val slop = ViewConfiguration.get(c).scaledTouchSlop
        view?.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0f
            var dy = 0f
            var downX = 0f
            var downY = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val params = lp ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        moved = false
                        dx = e.rawX - params.x
                        dy = e.rawY - params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                            moved = true
                        }
                        if (moved) {
                            params.x = (e.rawX - dx).toInt()
                            params.y = (e.rawY - dy).toInt()
                            clampPosition()
                            try { wm?.updateViewLayout(view, params) } catch (_: Throwable) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved) {
                            openApp(c)
                        } else {
                            savePosition(c)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun clampPosition(): Boolean {
        return try {
            val windowManager = wm ?: return false
            val params = lp ?: return false
            val currentView = view ?: return false

            val display = Point()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                display.set(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getSize(display)
            }

            val width = if (params.width > 0) params.width else max(currentView.width, currentView.measuredWidth)
            val height = if (params.height > 0) params.height else max(currentView.height, currentView.measuredHeight)

            val maxX = max(0, display.x - max(0, width))
            val maxY = max(0, display.y - max(0, height))
            val x = max(0, min(params.x, maxX))
            val y = max(0, min(params.y, maxY))

            if (x == params.x && y == params.y) return false
            params.x = x
            params.y = y
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun savePosition(c: Context?) {
        val context = c ?: return
        val params = lp ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("bub_x", params.x)
            .putInt("bub_y", params.y)
            .apply()
    }

    private fun openApp(c: Context) {
        try {
            val intent = Intent(c, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            c.startActivity(intent)
        } catch (_: Throwable) { }
    }

    private fun fmtTurnDist(m: Int): String {
        if (m < 0) return ""
        if (m < 1000) return "$m m"
        return String.format(Locale.US, "%.1f km", m / 1000.0)
    }

    private fun fmtDist(m: Int): String {
        if (m < 1000) return "$m m"
        return String.format(Locale.US, "%.1f km", m / 1000.0)
    }

    private fun circularAppIcon(c: Context, size: Int): Bitmap? {
        if (size <= 0) return null
        return try {
            val icon: Drawable = c.packageManager.getApplicationIcon(c.packageName)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val circle = Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(circle)
            val bleed = max(1, size / 10)
            icon.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            icon.draw(canvas)
            canvas.restore()
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun px(dp: Float): Int = (dp * dens * scale + 0.5f).toInt()
    private fun brandPx(dp: Float): Int = (dp * dens + 0.5f).toInt()

    private fun round(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    private fun roundBorder(color: Int, radius: Float): GradientDrawable = round(color, radius).apply {
        setStroke(max(1, px(1f)), COLOR_BUBBLE_BORDER)
    }

    private fun roundBorder(color: Int, radius: Int): GradientDrawable = roundBorder(color, radius.toFloat())

    private fun brandTopRoundBorder(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setStroke(max(1, brandPx(1f)), COLOR_BUBBLE_BORDER)
    }

    private fun brandTopRoundBorder(color: Int, radius: Int): GradientDrawable = brandTopRoundBorder(color, radius.toFloat())

    private fun brandLeftRoundBorder(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        setStroke(max(1, brandPx(1f)), COLOR_BUBBLE_BORDER)
    }

    private fun brandLeftRoundBorder(color: Int, radius: Int): GradientDrawable = brandLeftRoundBorder(color, radius.toFloat())
}

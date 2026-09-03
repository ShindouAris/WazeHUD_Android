package com.chisadin.hudwz.bubble

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper

object BubbleManager {
    private var lifecycleReady = false
    private var startedActivities = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingShow: Runnable? = null

    fun isBubbleRequested(context: Context): Boolean {
        val sp = context.getSharedPreferences(Bubble.PREFS, Context.MODE_PRIVATE)
        val enabled = sp.getBoolean("bubble", false)
        val overlay = Bubble.canShow(context)
        return enabled && overlay
    }

    fun sync(
        context: Context,
        enabled: Boolean? = null,
        layout: Int? = null,
        size: Int? = null,
    ) {
        val sp = context.getSharedPreferences(Bubble.PREFS, Context.MODE_PRIVATE)
        val editor = sp.edit()
        enabled?.let { editor.putBoolean("bubble", it) }
        layout?.let { editor.putInt("bubble_layout", it) }
        size?.let { editor.putInt("bub_size", it) }
        editor.apply()

        BubbleKeepAliveService.sync(context)

        val isEnabled = enabled ?: sp.getBoolean("bubble", false)
        if (!isEnabled || !Bubble.canShow(context)) {
            Bubble.hide()
        } else if (Bubble.isShowing()) {
            Bubble.refresh(context)
        }
    }

    fun installAutoHide(app: Application) {
        if (lifecycleReady) return
        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    cancelPendingShow()
                    startedActivities++
                    Bubble.hide()
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                    if (startedActivities == 0) {
                        cancelPendingShow()
                        val runnable = Runnable {
                            val enabled = isBubbleRequested(app)
                            val foreground = isProcessForeground(app)
                            if (enabled && !foreground) {
                                Bubble.show(app)
                            } else if (foreground) {
                                Bubble.hide()
                            }
                            pendingShow = null
                        }
                        pendingShow = runnable
                        mainHandler.postDelayed(runnable, 700L)
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    cancelPendingShow()
                    Bubble.hide()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { }
                override fun onActivityPaused(activity: Activity) { }
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
                override fun onActivityDestroyed(activity: Activity) { }
            })
            lifecycleReady = true
        } catch (_: Throwable) { }
    }

    private fun cancelPendingShow() {
        pendingShow?.let {
            mainHandler.removeCallbacks(it)
            pendingShow = null
        }
    }

    private fun isProcessForeground(context: Context): Boolean {
        return try {
            val appProcessInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(appProcessInfo)
            appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (_: Throwable) {
            startedActivities > 0
        }
    }
}

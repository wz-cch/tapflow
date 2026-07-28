package com.tapflow.android.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.tapflow.android.engine.EngineState

/**
 * Owns the floating windows.
 *
 * Windows are attached as TYPE_ACCESSIBILITY_OVERLAY, which needs no permission at all and sits
 * above the system UI. Some ROMs (Xiaomi, Huawei, OPPO have all been reported) refuse it, so on
 * failure we fall back to TYPE_APPLICATION_OVERLAY, which does need SYSTEM_ALERT_WINDOW. The
 * fallback is the only reason that permission is declared, and it is never requested up front.
 */
class OverlayHost(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val attached = mutableSetOf<View>()

    /**
     * Full display size including system decor.
     *
     * Resources.displayMetrics can exclude insets, but our windows use FLAG_LAYOUT_NO_LIMITS and
     * cover the whole display, so coordinates need the real size or clamping would cut gestures
     * short near the navigation bar.
     */
    fun displaySize(): Point = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        }
    }.onFailure { Log.w(TAG, "Could not read the display size", it) }.getOrElse {
        // Falling back to app metrics loses the system decor area, which is worth far less than
        // taking down the service: an exception here would happen inside onServiceConnected, and
        // Android switches off an accessibility service that crashes.
        val metrics = service.resources.displayMetrics
        Point(metrics.widthPixels, metrics.heightPixels)
    }

    fun rotation(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.rotation ?: 0
        }
    }.onFailure { Log.w(TAG, "Could not read the display rotation", it) }.getOrDefault(0)

    /**
     * Base layout params for an overlay window.
     *
     * FLAG_NOT_FOCUSABLE is essential rather than cosmetic: without it our window takes input
     * focus, and the app underneath can no longer raise its keyboard — which would break the whole
     * point of a pause point.
     */
    fun params(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    fun isAttached(view: View): Boolean = view in attached

    fun add(view: View, params: WindowManager.LayoutParams): Boolean {
        if (view in attached) return true

        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        if (tryAdd(view, params)) {
            attached += view
            return true
        }

        if (!Settings.canDrawOverlays(service)) {
            EngineState.needsOverlayPermission.value = true
            Log.w(TAG, "Accessibility overlay refused and no overlay permission granted")
            return false
        }

        params.type = fallbackWindowType
        if (tryAdd(view, params)) {
            attached += view
            Log.i(TAG, "Fell back to overlay window type ${params.type}")
            return true
        }
        return false
    }

    /**
     * TYPE_APPLICATION_OVERLAY only exists from API 26. Before that the equivalent is TYPE_PHONE,
     * which is deprecated but is what SYSTEM_ALERT_WINDOW governed on those releases.
     */
    private val fallbackWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun update(view: View, params: WindowManager.LayoutParams) {
        if (view !in attached) return
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
    }

    fun remove(view: View) {
        if (view !in attached) return
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        attached -= view
    }

    /**
     * Re-attaches a window so it sits on top of the others.
     *
     * Same-type overlay windows are stacked in the order they were added, so the toolbar has to be
     * re-added after the full-screen canvas or the canvas would swallow every toolbar press.
     */
    fun bringToFront(view: View, params: WindowManager.LayoutParams) {
        if (view !in attached) return
        remove(view)
        add(view, params)
    }

    fun removeAll() {
        attached.toList().forEach { remove(it) }
    }

    private fun tryAdd(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching { windowManager.addView(view, params); true }
            .onFailure { Log.w(TAG, "addView failed for type ${params.type}", it) }
            .getOrDefault(false)

    private companion object {
        const val TAG = "OverlayHost"
    }
}

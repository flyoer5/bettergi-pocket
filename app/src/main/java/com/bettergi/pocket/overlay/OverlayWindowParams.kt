package com.bettergi.pocket.overlay

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/** 悬浮窗 LayoutParams 共享工厂（收敛 LogWindowPanel / SkipPositionPicker / OverlayWindowController 的重复实现）。 */
object OverlayWindowParams {
    fun create(
        width: Int,
        height: Int,
        touchable: Boolean,
        x: Int,
        y: Int,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }
}

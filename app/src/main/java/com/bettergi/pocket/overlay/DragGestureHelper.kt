package com.bettergi.pocket.overlay

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 拖拽手势状态机（收敛 OverlayWindowController 的 setupDrag / setupDragAndClick 重复实现）。
 *
 * [initialPosition] 按下时返回窗口初始位置 (x, y)，移动基于它计算新位置
 * [onDown] 按下附加回调（取消贴边动画/唤醒/缩放动画）
 * [onMove] 移动回调 (x, y) — 绝对坐标, 调用方赋给 lp.x/lp.y
 * [onClick] 点击回调（未移动时触发）
 * [onDragEnd] 拖动结束回调（移动时触发, 持久化/贴边）
 * [onCancelExtra] 取消时的附加回调（默认复用 onDragEnd）
 */
class DragGestureHelper(
    private val touchSlop: Int,
    private val initialPosition: () -> Pair<Int, Int>,
    private val onDown: () -> Unit = {},
    private val onMove: (x: Int, y: Int) -> Unit = { _, _ -> },
    private val onClick: (() -> Unit)? = null,
    private val onDragEnd: (() -> Unit)? = null,
) {
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var moved = false

    /** 绑定到 View；listener 内部消费全部事件（防注入触摸的 POINTER_DOWN 触发 CANCEL 断触）。 */
    fun attach(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val (ix, iy) = initialPosition()
                    startX = ix
                    startY = iy
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    onDown()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) onMove(startX + dx, startY + dy)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        onDragEnd?.invoke()
                    } else {
                        v.performClick()
                        onClick?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) onDragEnd?.invoke()
                    true
                }
                else -> true // 消费未处理事件（如注入触摸的 POINTER_DOWN），避免系统对手势发 CANCEL 致拖动断触
            }
        }
    }
}
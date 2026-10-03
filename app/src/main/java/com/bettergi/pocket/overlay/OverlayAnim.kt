package com.bettergi.pocket.overlay

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/** 悬浮窗动画共享常量（收敛 OverlayWindowController 各处散落的字面量）。 */
object OverlayAnim {
    /** 金丝曲线插值（退出/展开/收起共用）。 */
    val EASE: Interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** 退出动画时长 (ms)。 */
    const val DUR_EXIT = 160L
    /** 气泡移出动画时长 (ms)。 */
    const val DUR_BUBBLE_OUT = 160L
    /** 面板展开动画时长 (ms)。 */
    const val DUR_EXPAND_PANEL = 280L
    /** 面板收起动画时长 (ms)。 */
    const val DUR_COLLAPSE_PANEL = 200L
    /** 气泡移入动画时长 (ms)。 */
    const val DUR_BUBBLE_IN = 240L
    /** 拖动按下缩放动画时长 (ms)。 */
    const val DUR_PRESS = 80L
    /** 拖动抬起缩放动画时长 (ms)。 */
    const val DUR_RELEASE = 120L
    /** 指示徽章/chevron 动画时长 (ms)。 */
    const val DUR_INDICATOR = 160L

    /** 气泡收起缩放。 */
    const val SCALE_BUBBLE = 0.72f
    /** 面板展开起始缩放。 */
    const val SCALE_PANEL_IN = 0.88f
    /** 面板收起结束缩放。 */
    const val SCALE_PANEL_OUT = 0.9f
    /** 拖动按下缩放。 */
    const val SCALE_PRESS = 0.92f
}
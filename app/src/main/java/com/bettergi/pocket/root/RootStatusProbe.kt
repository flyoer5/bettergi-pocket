package com.bettergi.pocket.root

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/**
 * root 状态感知：经 [RootBridge] 查询系统状态。
 * 替代无障碍版里基于 AccessibilityEvent 的前台包名判断。
 *
 * [foregroundPackage] 带短 TTL 缓存：每次查询都要跨进程 + helper 内 fork dumpsys
 * （50~150ms），而前台状态在短时间窗口内极少变化，避免高频查询（节流）卡顿。
 * EVENT_THROTTLE_MS 内直接返回上次结果。
 */
class RootStatusProbe(
    private val bridge: RootBridge = RootBridge,
) {

    private val cache = AtomicReference<ProbeCache>()
    private data class ProbeCache(
        val pkg: String?,
        val queriedAtElapsedMs: Long,
    )

    /** 当前前台包名；查询失败或未知返回 null。节流窗口内返回上次结果。 */
    fun foregroundPackage(): String? {
        val now = SystemClock.elapsedRealtime()
        val cached = cache.get()
        if (cached != null && now - cached.queriedAtElapsedMs < EVENT_THROTTLE_MS) {
            return cached.pkg
        }
        val pkg = bridge.foreground()
        cache.set(ProbeCache(pkg, now))
        return pkg
    }

    /** 失效缓存（root 重连/服务重启后调用，避免旧包名残留）。 */
    fun invalidate() {
        cache.set(null)
    }

    private companion object {
        /** 前台包名查询节流窗口。dumpsys 查询 50~150ms，1.5s 内复用合理。 */
        const val EVENT_THROTTLE_MS = 1500L
    }
}
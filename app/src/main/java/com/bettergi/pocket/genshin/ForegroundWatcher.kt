package com.bettergi.pocket.genshin

/**
 * 前台状态观察器：后台线程定期查询前台包名并缓存结果。
 *
 * 为什么需要：识别引擎每秒要跑 2~5 拍，而 `dumpsys` 查询一次要 100~300ms，
 * 不能放在引擎线程里同步查。这里用独立线程按 TTL 刷新，引擎只读一个 volatile。
 *
 * 语义：
 * - `true`  ：确认原神（含各渠道服/云原神）在前台
 * - `false` ：确认前台不是原神（桌面/其他 App/锁屏）
 * - `null`  ：查询失败或无法解析（如系统弹窗抢焦点），调用方应保守处理
 */
class ForegroundWatcher(
    private val probe: () -> String?,
    private val intervalMs: Long = 1000L,
) {
    @Volatile
    private var gameForeground: Boolean? = null

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                val pkg = try {
                    probe()
                } catch (_: Throwable) {
                    null
                }
                gameForeground = pkg?.let { GenshinPackages.isGenshinPackage(it) }
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "bg-fg-watch").apply { isDaemon = true }
        thread?.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        gameForeground = null
    }

    /** 当前前台是否原神；未知返回 null */
    fun isGameForeground(): Boolean? = gameForeground
}

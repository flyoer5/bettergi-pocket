package com.bettergi.pocket.root

import com.bettergi.pocket.log.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 用户手动触摸监测（root 版）：
 * 监听真实触摸屏输入，实现自动点击与手动操作互不影响——用户正在操作时
 * 自动注入统一让路，松手后自动恢复。
 * 只收真实触摸屏事件，排除本 app 的 uinput 虚拟设备（BetterGI Virtual Touch）。
 */
object UserTouchMonitor {

    private const val TAG = "BetterGI.TouchMonitor"

    @Volatile
    private var lastUserTouchAtMs: Long = 0L

    @Volatile
    private var active = false

    private val threads = mutableListOf<Thread>()
    private val processes = mutableListOf<Process>()

    /** 用户在最近 windowMs 内是否触摸过屏幕（默认 800ms 窗口，含松手恢复缓冲）。 */
    fun isUserActive(windowMs: Long = 800L): Boolean {
        if (!active) return false
        val last = lastUserTouchAtMs
        return last > 0 && System.currentTimeMillis() - last <= windowMs
    }

    @Synchronized
    fun start() {
        if (active) return
        val devices = detectTouchDevices()
        if (devices.isEmpty()) {
            AppLog.w(TAG, "未探测到触摸屏设备，手动操作保护不可用")
            return
        }
        active = true
        lastUserTouchAtMs = 0L
        for (dev in devices) {
            val t = Thread({ listen(dev) }, "TouchMonitor")
            t.isDaemon = true
            threads.add(t)
            t.start()
        }
        AppLog.i(TAG, "触摸监测启动: ${devices.joinToString()}")
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        processes.forEach { runCatching { it.destroy() } }
        processes.clear()
        threads.forEach { runCatching { it.interrupt() } }
        threads.clear()
        lastUserTouchAtMs = 0L
        AppLog.i(TAG, "触摸监测停止")
    }

    private fun listen(device: String) {
        try {
            while (active) {
                // /dev/input/eventX 属 root:input 组，app 无权直读，必须经 su
                val p = ProcessBuilder(
                    RootBridge.currentSu(),
                    "-c",
                    "export PATH=/system/bin:/system/xbin:\$PATH; getevent $device",
                )
                    .redirectErrorStream(true)
                    .start()
                synchronized(this) { processes.add(p) }
                val reader = BufferedReader(InputStreamReader(p.inputStream), 4096)
                var line: String?
                while (active && p.isAlive && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    // toybox getevent 单设备输出: "0003 0039 00000001"；部分实现带 "/dev/input/eventX:" 前缀
                    val idx = l.indexOf(':')
                    val body = if (idx >= 0) l.substring(idx + 1) else l
                    val typeHex = body.trim().take(4)
                    if (typeHex.length == 4 && typeHex != "0000") {
                        lastUserTouchAtMs = System.currentTimeMillis()
                    }
                }
                runCatching { reader.close() }
                runCatching { p.destroy() }
                synchronized(this) { processes.remove(p) }
                if (active) Thread.sleep(100) // 意外退出后重连
            }
        } catch (e: Exception) {
            if (active) AppLog.w(TAG, "监听 $device 异常: ${e.message}")
        }
    }

    /** 从 /proc/bus/input/devices 探测支持多点触摸的真实触摸屏（排除 BetterGI 虚拟设备）。 */
    private fun detectTouchDevices(): List<String> {
        val result = mutableListOf<String>()
        try {
            val lines = java.io.File("/proc/bus/input/devices").readLines()
            var name = ""
            var handlers = ""
            var absBits = ""
            for (line in lines) {
                when {
                    line.startsWith("N:") -> {
                        name = Regex("Name=\"(.*)\"").find(line)?.groupValues?.get(1) ?: ""
                    }
                    line.startsWith("H:") -> handlers = line
                    line.startsWith("B: ABS=") -> absBits = line.removePrefix("B: ABS=").replace(" ", "")
                    line.isBlank() -> {
                        if (name.isNotEmpty() && !name.contains("BetterGI") && hasMtPositionX(absBits)) {
                            Regex("event([0-9]+)").find(handlers)?.let {
                                result.add("/dev/input/${it.value}")
                            }
                        }
                        name = ""
                        handlers = ""
                        absBits = ""
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "探测触摸设备失败", e)
        }
        return result.distinct()
    }

    /** ABS 位图是否含 ABS_MT_POSITION_X (bit 53)；hex 可能多段 64 位，取低 64 位。 */
    private fun hasMtPositionX(bits: String): Boolean {
        if (bits.isEmpty()) return false
        return try {
            val v = java.lang.Long.parseUnsignedLong(bits.takeLast(16), 16)
            ((v ushr 53) and 1L) == 1L
        } catch (_: Exception) {
            false
        }
    }
}
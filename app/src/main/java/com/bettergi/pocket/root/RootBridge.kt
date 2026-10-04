package com.bettergi.pocket.root

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.bettergi.pocket.log.AppLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * root 注入桥：探测 su、释放并启动 bgroot helper、经 LocalSocket 通信。
 *
 * 设计要点：
 * - 不绑定任何 root 方案（Magisk / KernelSU / APatch / 原生 su 均走通用 su 探测）
 * - 不做授权引导，失败由上层如实报告状态
 * - helper 与 app 同生命周期：stop() 时发 QUIT 并销毁进程
 * - 连接可靠性：单次请求超时不误判断线；意外断开自动重连（有限次数）
 */
object RootBridge {

    private const val TAG = "BetterGI.Root"
    private const val HELPER_ASSET = "root/arm64-v8a/bgroot"
    private const val CONNECT_TIMEOUT_MS = 4000L
    private const val PING_TIMEOUT_MS = 3000L
    private const val RECONNECT_DELAY_MS = 2000L
    private const val MAX_RECONNECT_ATTEMPTS = 5

    @Volatile
    private var running = false
    /** 连接对象：socket + 输出流 + 读缓冲，整体替换避免撕裂读 */
    private class Connection(
        val socket: LocalSocket,
        val output: OutputStream,
        val reader: BufferedReader,
    )

    @Volatile
    private var connection: Connection? = null
    @Volatile
    private var helperProcess: Process? = null

    private var appContext: Context? = null
    @Volatile
    private var suPath: String? = null
    @Volatile
    private var uinputUnavailable = false

    @Volatile
    private var userStopped = false
    private val reconnectPending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var consecutiveFailures = 0
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bg-root-reconnect").apply { isDaemon = true }
    }

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun isRunning(): Boolean = running

    /** 启动 helper 并完成握手；成功返回 true。失败路径统一清理资源（不残留 helper）。 */
    @Synchronized
    fun start(): Boolean {
        val ctx = appContext ?: return false
        cleanupResources()

        val dir = File(ctx.filesDir, "root")
        dir.mkdirs()
        val helper = File(dir, "bgroot")
        if (!releaseHelper(helper)) return false
        helper.setExecutable(true, false)

        val sockPath = File(dir, "sock").absolutePath
        File(sockPath).delete()

        val su = resolveSu()
        // 清理可能残留的旧 helper：避免文件被占用（ETXTBSY）与多实例争用 socket。
        // 注意不能用 pkill -f bgroot：执行它的 shell 命令行本身含 "bgroot" 会被自杀误杀，导致清理从未真正生效。
        // 改为按 /proc/*/comm 精确匹配进程名 bgroot 逐个 kill。
        // 异步执行 + 短暂等待：全 /proc 扫描可能耗时数秒, 不阻塞连接握手（加快"打开即连"）
        val killJob = Thread({
            runCommand(
                ctx,
                "$su -c 'for p in /proc/[0-9]*; do [ \"\$(cat \$p/comm 2>/dev/null)\" = bgroot ] && kill -9 \${p#/proc/} 2>/dev/null; done; sleep 0.2'",
                3000L,
            )
        }, "bg-root-kill-stale").apply { isDaemon = true }
        killJob.start()
        killJob.join(300L)  // 最多等 300ms, 没清完也不阻塞连接

        val cmd = "$su -c \"$helper --server --sock $sockPath --uid ${android.os.Process.myUid()} --app-pid ${android.os.Process.myPid()}\""
        val process = try {
            ProcessBuilder("sh", "-c", cmd).redirectErrorStream(false).start()
        } catch (e: Exception) {
            AppLog.e(TAG, "启动 helper 失败", e)
            return false
        }
        helperProcess = process
        drainHelperOutput(process, "helper")

        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var firstFail = true
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(60)
            if (!process.isAlive) {
                AppLog.e(TAG, "helper 提前退出，code=${process.exitValue()}")
                cleanupResources()
                return false
            }
            val s = tryConnect(sockPath, logFailure = firstFail).also { if (it == null) firstFail = false } ?: continue
            connection = Connection(s, s.outputStream, BufferedReader(InputStreamReader(s.inputStream), 1024))
            val pong = request("PING", PING_TIMEOUT_MS)?.trim()
            if (pong == "OK pong" || pong == "pong") {
                running = true
                userStopped = false
                consecutiveFailures = 0
                uinputUnavailable = false
                AppLog.i(TAG, "root 已连接")
                applyKeepAlive()
                return true
            }
            AppLog.w(TAG, "PING 握手失败: $pong")
            cleanupResources()
            return false
        }
        AppLog.e(TAG, "连接 helper 超时")
        cleanupResources()
        return false
    }

    /** 解析可用的 su 绝对路径（多路径探测，不依赖 app 的 PATH） */
    private fun resolveSu(): String {
        suPath?.let { return it }
        val ctx = appContext ?: return "su"
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
        )
        for (p in candidates) {
            if (runCommand(ctx, "test -x $p && echo yes", 500L)?.trim() == "yes") {
                suPath = p
                return p
            }
        }
        val found = runCommand(ctx, "command -v su", 500L)?.trim()
        return if (!found.isNullOrEmpty()) found else "su"
    }

    /** 释放 helper：先写临时文件再 rename 替换（旧进程持有的 inode 换新，避免 ETXTBSY） */
    private fun releaseHelper(dest: File): Boolean {
        val ctx = appContext ?: return false
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        return try {
            ctx.assets.open(HELPER_ASSET).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    AppLog.e(TAG, "替换 helper 文件失败")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "release helper failed", e)
            try {
                tmp.delete()
            } catch (_: Throwable) {
            }
            false
        }
    }

    private fun tryConnect(path: String, logFailure: Boolean = false): LocalSocket? {
        var s: LocalSocket? = null
        return try {
            s = LocalSocket()
            s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            s
        } catch (e: Exception) {
            // 连接失败必须 close，避免 LocalSocket fd 泄漏
            runCatching { s?.close() }
            if (logFailure) AppLog.w(TAG, "连接 $path 失败: ${e.message}")
            null
        }
    }

    /**
     * 发送一行指令并读取一行回复。
     * - 请求超时（SocketTimeoutException）：单次请求失败，不影响连接状态，返回 null
     * - 对端关闭/IO 异常：连接级失败，标记断开并触发自动重连，返回 null
     */
    @Synchronized
    fun request(cmd: String, timeoutMs: Long = 5000L): String? {
        val conn = connection ?: return null
        return try {
            conn.socket.soTimeout = timeoutMs.toInt()
            conn.output.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            conn.output.flush()
            val line = conn.reader.readLine() ?: run {
                markDisconnected("对端关闭")
                null
            }
            line
        } catch (e: SocketTimeoutException) {
            AppLog.w(TAG, "request 超时: $cmd")
            drainResidual(conn)
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "request failed: $cmd -> ${e.message}")
            markDisconnected("request 异常: ${e.message}")
            null
        }
    }

    /**
     * 丢弃超时后 socket 里残留的回复行：helper 可能在超时后仍写完响应，
     * 不清空会让下次 request 读到旧数据造成协议错位。
     * 仅清空当前已缓冲字节，不阻塞（读不到就放弃）。
     */
    private fun drainResidual(conn: Connection) {
        try {
            val avail = conn.socket.inputStream.available()
            if (avail > 0) {
                val buf = ByteArray(avail)
                val read = conn.socket.inputStream.read(buf, 0, avail)
                if (read > 0) AppLog.d(TAG, "drained ${read}B 残留回复")
            }
        } catch (_: Throwable) {
        }
    }

    fun foreground(): String? {
        val resp = request("FG", 3000L) ?: return null
        if (resp.startsWith("OK ")) {
            val fg = resp.removePrefix("OK ").trim()
            return if (fg == "unknown") null else fg
        }
        return null
    }

    /**
     * 点击注入：input 命令优先（InputManager 正规管线，复用真实触摸屏 deviceId，
     * 原神等米哈游引擎认可）；uinput 虚拟设备会被游戏过滤（实测点击光圈出现但无响应），仅作回退。
     */
    fun inputTap(x: Int, y: Int): Boolean {
        val ctx = appContext ?: return false
        val cmd = "${resolveSu()} -c \"input tap $x $y\""
        if (runCommand(ctx, cmd, 2500L) != null) return true
        AppLog.w(TAG, "input 注入失败 ($x,$y)，尝试 uinput 回退")
        if (!uinputUnavailable) {
            val resp = request("TAP $x $y 60", 1500L)
            if (resp != null) {
                if (resp.startsWith("OK")) return true
                if (resp.startsWith("ERR uinput")) uinputUnavailable = true
            }
        }
        return false
    }

    /** 返回键注入：input 命令优先，socket BACK 仅作回退。 */
    fun inputBack(): Boolean {
        val ctx = appContext ?: return false
        val cmd = "${resolveSu()} -c \"input keyevent 4\""
        if (runCommand(ctx, cmd, 2500L) != null) return true
        AppLog.w(TAG, "input 返回键失败，尝试 socket BACK 回退")
        if (!uinputUnavailable) {
            val resp = request("BACK", 1500L)
            if (resp != null) {
                if (resp.startsWith("OK")) return true
                if (resp.startsWith("ERR uinput")) uinputUnavailable = true
            }
        }
        return false
    }

    /** 加入电池白名单 + 提升为 active 待机桶；每次握手成功都续期（命令幂等，防 OEM 重置） */
    private fun applyKeepAlive() {
        val pkg = appContext?.packageName ?: return
        if (request("KEEPALIVE $pkg", 3000L)?.startsWith("OK") == true) {
            AppLog.i(TAG, "已申请保活: $pkg")
        }
    }

    /** 主动停止：不触发自动重连。 */
    @Synchronized
    fun stop() {
        if (running) AppLog.i(TAG, "root 后端停止")
        userStopped = true
        reconnectPending.set(false)
        cleanupResources()
    }

    /** 清理连接与 helper 进程（幂等）。不改变 userStopped。 */
    private fun cleanupResources() {
        running = false
        try {
            connection?.output?.write("QUIT\n".toByteArray(Charsets.UTF_8))
            connection?.output?.flush()
        } catch (_: Exception) {
        }
        try {
            connection?.socket?.close()
        } catch (_: Exception) {
        }
        try {
            // helperProcess 是 su 包装进程（bgroot 是其子进程），强杀进程树避免 bgroot 残留
            helperProcess?.destroyForcibly()
        } catch (_: Exception) {
        }
        // 兜底：QUIT 未送达（socket 已断）时强杀残留 helper，保证退出后系统干净、覆盖安装不被拖慢。
        // 进程名 bgroot 仅本 app 使用，按 /proc/*/comm 精确匹配安全。
        // 异步执行：全 /proc 扫描 + su 启动可能耗时数秒, 不阻塞调用方（stop/start 主线程调用时避免卡 UI/悬浮窗残留）
        val su = suPath
        val ctx = appContext
        if (su != null && ctx != null) {
            Thread({
                runCommand(
                    ctx,
                    "$su -c 'for p in /proc/[0-9]*; do [ \"\$(cat \$p/comm 2>/dev/null)\" = bgroot ] && kill -9 \${p#/proc/} 2>/dev/null; done'",
                    2000L,
                )
            }, "bg-root-kill-stale").apply { isDaemon = true }.start()
        }
        connection = null
        helperProcess = null
    }

    /** 连接意外断开：标记并调度自动重连（有限次数，主动 stop 不重连）。 */
    private fun markDisconnected(reason: String) {
        if (!running) return
        running = false
        AppLog.w(TAG, "root 连接断开: $reason")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        // 原子 check+set: 防多线程同时调度重连
        if (!reconnectPending.compareAndSet(false, true)) return
        reconnectExecutor.schedule({
            reconnectPending.set(false)
            if (userStopped || appContext == null) return@schedule
            AppLog.i(TAG, "root 自动重连（第 ${consecutiveFailures + 1} 次）")
            if (start()) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures < MAX_RECONNECT_ATTEMPTS) {
                    scheduleReconnect()
                } else {
                    AppLog.e(TAG, "root 自动重连失败 $consecutiveFailures 次，停止自动重试（可点击状态栏手动重连）")
                }
            }
        }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** 逐行消费 helper 的 stdout/stderr：写满管道会阻塞 helper 主循环，必须读 */
    private fun drainHelperOutput(process: Process, prefix: String) {
        for (stream in listOf(process.inputStream, process.errorStream)) {
            val thread = Thread {
                try {
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) AppLog.d(TAG, "[$prefix] $line")
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun runCommand(ctx: Context, command: String, timeoutMs: Long): String? {
        val proc = try {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return null
        }
        return try {
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroy()
            }
            out
        } catch (e: Exception) {
            null
        }
    }
}
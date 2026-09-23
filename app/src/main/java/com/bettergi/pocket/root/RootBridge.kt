package com.bettergi.pocket.root

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.settings.TriggerSettingsRepository
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
    private const val HOT_CONNECT_VERIFY_MS = 1200L
    private const val PREFS_NAME = "root_bridge"
    private const val KEY_SU_PATH = "su_path"
    private const val MAX_RECONNECT_ATTEMPTS = 5

    @Volatile
    private var running = false
    @Volatile
    private var socket: LocalSocket? = null
    @Volatile
    private var output: OutputStream? = null
    @Volatile
    private var reader: BufferedReader? = null
    @Volatile
    private var helperProcess: Process? = null

    private var appContext: Context? = null
    private var settingsRepository: TriggerSettingsRepository? = null
    @Volatile
    private var suPath: String? = null

    @Volatile
    private var connecting = false
    @Volatile
    private var userStopped = false
    @Volatile
    private var reconnectPending = false
    @Volatile
    private var consecutiveFailures = 0
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bg-root-reconnect").apply { isDaemon = true }
    }

    fun attach(context: Context) {
        appContext = context.applicationContext
        settingsRepository = TriggerSettingsRepository(context.applicationContext)
    }

    fun isRunning(): Boolean = running

    /** 是否正在连接 helper（用于 UI 显示「连接中…」而非误导性的「未连接」）。 */
    fun isConnecting(): Boolean = connecting

    /** 启动 helper 并完成握手；成功返回 true。失败路径统一清理资源（不残留 helper）。 */
    @Synchronized
    fun start(): Boolean {
        val ctx = appContext ?: return false
        connecting = true
        try {
            return startLocked(ctx)
        } finally {
            connecting = false
        }
    }

    private fun startLocked(ctx: Context): Boolean {
        val dir = File(ctx.filesDir, "root")
        val sockPath = File(dir, "sock").absolutePath

        // 快路径：helper 仍存活时直接复用 socket（app 重开秒连，跳过 su 探测/清理/重启）
        if (tryHotConnect(sockPath)) return true

        cleanupResources()

        dir.mkdirs()
        val helper = File(dir, "bgroot")
        if (!releaseHelper(helper)) return false
        helper.setExecutable(true, false)

        File(sockPath).delete()

        val su = resolveSu()
        // 清理可能残留的旧 helper：避免文件被占用（ETXTBSY）与多实例争用 socket。
        // 注意不能用 pkill -f bgroot：执行它的 shell 命令行本身含 "bgroot" 会被自杀误杀，导致清理从未真正生效。
        // 改为按 /proc/*/comm 精确匹配进程名 bgroot 逐个 kill。
        runCommand(
            ctx,
            "$su -c 'for p in /proc/[0-9]*; do [ \"\$(cat \$p/comm 2>/dev/null)\" = bgroot ] && kill -9 \${p#/proc/} 2>/dev/null; done; sleep 0.2'",
            3000L,
        )

        val cmd = "$su -c \"$helper --server --sock $sockPath --uid ${android.os.Process.myUid()} --app-pid ${android.os.Process.myPid()}\""
        val process = try {
            ProcessBuilder("sh", "-c", cmd).redirectErrorStream(false).start()
        } catch (e: Exception) {
            AppLog.e(TAG, "启动 helper 失败", e)
            return false
        }
        helperProcess = process
        drainHelperOutput(process, "helper")

        val connectTimeout = settingsRepository?.get()?.connectTimeoutMs ?: CONNECT_TIMEOUT_MS
        val deadline = System.currentTimeMillis() + connectTimeout
        var firstFail = true
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(60)
            if (!process.isAlive) {
                AppLog.e(TAG, "helper 提前退出，code=${process.exitValue()}")
                cleanupResources()
                return false
            }
            val s = tryConnect(sockPath, logFailure = firstFail).also { if (it == null) firstFail = false } ?: continue
            socket = s
            output = s.outputStream
            reader = BufferedReader(InputStreamReader(s.inputStream), 1024)
            val pong = request("PING", 3000L)?.trim()
            if (pong == "OK pong" || pong == "pong") {
                running = true
                userStopped = false
                consecutiveFailures = 0
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
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 先用上次成功解析的路径（一次校验，通常 100~300ms，避免逐个候选探测）
        prefs.getString(KEY_SU_PATH, null)?.let { cached ->
            if (runCommand(ctx, "test -x $cached && echo yes", 1200L)?.trim() == "yes") {
                suPath = cached
                return cached
            }
        }

        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
        )
        for (p in candidates) {
            if (runCommand(ctx, "test -x $p && echo yes", 1500L)?.trim() == "yes") {
                suPath = p
                prefs.edit().putString(KEY_SU_PATH, p).apply()
                return p
            }
        }
        val found = runCommand(ctx, "command -v su", 1500L)?.trim()
        val result = if (!found.isNullOrEmpty()) found else "su"
        prefs.edit().putString(KEY_SU_PATH, result).apply()
        return result
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
     * 热连接：helper 已存活时复用其 socket，避免重建（显著加快 app 重开后的连接）。
     * 二次确认（1.2s 后再次 PING）以排除「旧 app 实例遗留、即将自退」的 helper。
     */
    private fun tryHotConnect(sockPath: String): Boolean {
        val s = tryConnect(sockPath) ?: return false
        return try {
            socket = s
            output = s.outputStream
            reader = BufferedReader(InputStreamReader(s.inputStream), 1024)
            val pong = request("PING", 1000L)?.trim()
            if (pong != "OK pong" && pong != "pong") {
                cleanupResources()
                return false
            }
            Thread.sleep(HOT_CONNECT_VERIFY_MS)
            val again = request("PING", 1000L)?.trim()
            if (again != "OK pong" && again != "pong") {
                // helper 属于旧实例、正在自退：转慢路径重建
                cleanupResources()
                return false
            }
            running = true
            userStopped = false
            consecutiveFailures = 0
            AppLog.i(TAG, "root 热连接成功（复用存活 helper）")
            applyKeepAlive()
            true
        } catch (_: Exception) {
            cleanupResources()
            false
        }
    }

    /**
     * 发送一行指令并读取一行回复。
     * - 请求超时（SocketTimeoutException）：单次请求失败，不影响连接状态，返回 null
     * - 对端关闭/IO 异常：连接级失败，标记断开并触发自动重连，返回 null
     */
    @Synchronized
    fun request(cmd: String, timeoutMs: Long = 5000L): String? {
        val sock = socket ?: return null
        val out = output ?: return null
        val rd = reader ?: return null
        return try {
            sock.soTimeout = timeoutMs.toInt()
            out.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            val line = rd.readLine() ?: run {
                markDisconnected("对端关闭")
                null
            }
            line
        } catch (e: SocketTimeoutException) {
            AppLog.w(TAG, "request 超时: $cmd")
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "request failed: $cmd -> ${e.message}")
            markDisconnected("request 异常: ${e.message}")
            null
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

    /** input 命令模式点击（屏幕坐标，经 su 执行系统命令注入，全 root 方案兼容）。 */
    fun inputTap(x: Int, y: Int): Boolean {
        val ctx = appContext ?: return false
        val cmd = "${resolveSu()} -c \"input tap $x $y\""
        if (runCommand(ctx, cmd, 2500L) == null) {
            AppLog.w(TAG, "input 注入失败 ($x,$y)")
            return false
        }
        return true
    }

    /** input 命令模式返回键。 */
    fun inputBack(): Boolean {
        val ctx = appContext ?: return false
        val cmd = "${resolveSu()} -c \"input keyevent 4\""
        if (runCommand(ctx, cmd, 2500L) == null) {
            AppLog.w(TAG, "input 返回键失败")
            return false
        }
        return true
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
        connecting = false
        cleanupResources()
    }

    /** 清理连接与 helper 进程（幂等）。不改变 userStopped。 */
    private fun cleanupResources() {
        running = false
        try {
            output?.write("QUIT\n".toByteArray(Charsets.UTF_8))
            output?.flush()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            helperProcess?.destroy()
        } catch (_: Exception) {
        }
        // 兜底：QUIT 未送达（socket 已断）时强杀残留 helper，保证退出后系统干净、覆盖安装不被拖慢。
        // 进程名 bgroot 仅本 app 使用，按 /proc/*/comm 精确匹配安全。
        val su = suPath
        val ctx = appContext
        if (su != null && ctx != null) {
            runCommand(
                ctx,
                "$su -c 'for p in /proc/[0-9]*; do [ \"\$(cat \$p/comm 2>/dev/null)\" = bgroot ] && kill -9 \${p#/proc/} 2>/dev/null; done'",
                2000L,
            )
        }
        socket = null
        output = null
        reader = null
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
        if (reconnectPending) return
        reconnectPending = true
        val settings = settingsRepository?.get()
        val delayMs = settings?.reconnectDelayMs ?: RECONNECT_DELAY_MS
        val maxAttempts = settings?.maxReconnectAttempts ?: MAX_RECONNECT_ATTEMPTS
        reconnectExecutor.schedule({
            reconnectPending = false
            if (userStopped || appContext == null) return@schedule
            AppLog.i(TAG, "root 自动重连（第 ${consecutiveFailures + 1} 次）")
            if (start()) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures < maxAttempts) {
                    scheduleReconnect()
                } else {
                    AppLog.e(TAG, "root 自动重连失败 $consecutiveFailures 次，停止自动重试（可点击状态栏手动重连）")
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
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
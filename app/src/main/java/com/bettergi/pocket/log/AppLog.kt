package com.bettergi.pocket.log

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 统一日志门面：logcat + 文件（按天切分，保留最近 [KEEP_DAYS] 天）+ 悬浮球面板。
 *
 * 线程安全设计：
 * - DateTimeFormatter 线程安全（替代原 SimpleDateFormat 跨线程误用）
 * - 文件写入在单线程 drain 循环；队列有界（满则丢弃新日志），避免日志洪水 OOM
 * - 调用线程只做格式化与入队，不阻塞主线程/注入线程
 */
object AppLog {

    interface Sink {
        fun onLog(line: String)
    }

    private const val KEEP_DAYS = 3
    private const val QUEUE_CAPACITY = 4096
    private val sinks = CopyOnWriteArrayList<Sink>()
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val writerThread = Thread(::drainLoop, "bg-log-writer").apply {
        isDaemon = true
        start()
    }

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists() && !dir.mkdirs()) return
        logDir = dir
        try {
            cleanupOldLogs(dir)
        } catch (_: Throwable) {
        }
    }

    fun addSink(sink: Sink) {
        if (!sinks.contains(sink)) sinks.add(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    fun i(tag: String, message: String) = write(Log.INFO, tag, message)

    fun d(tag: String, message: String) = write(Log.DEBUG, tag, message)

    fun w(tag: String, message: String) = write(Log.WARN, tag, message)

    fun e(tag: String, message: String) = write(Log.ERROR, tag, message)

    fun e(tag: String, message: String, error: Throwable) =
        write(Log.ERROR, tag, "$message: ${error::class.java.simpleName}: ${error.message}")

    private fun write(priority: Int, tag: String, message: String) {
        Log.println(priority, tag, message)
        val line = "${timeFormat.format(LocalDateTime.now())} ${levelTag(priority)} [$tag] $message"
        if (priority != Log.DEBUG) {
            for (sink in sinks) {
                try {
                    sink.onLog(line)
                } catch (_: Throwable) {
                }
            }
        }
        if (logDir == null) return
        queue.offer(line) // 队列满则丢弃，防止内存无界增长
    }

    private fun drainLoop() {
        var day = ""
        var writer: BufferedWriter? = null
        try {
            while (true) {
                val line = queue.poll(2, TimeUnit.SECONDS) ?: continue
                val dir = logDir ?: continue
                val today = dayFormat.format(LocalDateTime.now())
                if (today != day) {
                    runCatching { writer?.close() }
                    writer = null
                    writer = BufferedWriter(FileWriter(File(dir, "bettergi-$today.log"), true))
                    day = today
                }
                val bw = writer ?: continue
                try {
                    bw.write(line)
                    bw.write("\n")
                    bw.flush()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun levelTag(priority: Int): String = when (priority) {
        Log.ERROR -> "E"
        Log.WARN -> "W"
        Log.INFO -> "I"
        Log.DEBUG -> "D"
        else -> "V"
    }

    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.name.startsWith("bettergi-") || !f.name.endsWith(".log")) continue
            if (f.lastModified() < cutoff) {
                try {
                    f.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
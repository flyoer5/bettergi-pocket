package com.bettergi.pocket

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.bettergi.pocket.log.AppLog
import com.bettergi.pocket.root.RootBridge
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import com.bettergi.pocket.rust.RustNative
import java.io.File

class PocketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RootBridge.attach(this)
        AppLog.init(this)
        if (currentProcessName() != packageName) return
        if (!OpenCvRuntime.ensureLoaded()) {
            AppLog.e(TAG, "OpenCV initialization failed")
        }
        OcrFactory.init(this)
        // 启动时就确认 Rust 引擎是否可用：version() 是 native 调用，
        // 能同时验证 .so 被加载和 JNI 符号解析成功，不必等自动对话跑起来。
        if (RustNative.isAvailable) {
            val version = runCatching { RustNative.version() }.getOrNull()
            AppLog.i(TAG, "Rust 引擎已加载: $version")
        } else {
            AppLog.w(TAG, "Rust 引擎不可用: ${RustNative.loadFailure()?.message}")
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val processes = (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
        processes?.firstOrNull { it.pid == pid }?.processName?.let { return it }
        return try {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .ifBlank { packageName }
        } catch (_: Exception) {
            packageName
        }
    }

    private companion object {
        private const val TAG = "BetterGI.App"
    }
}

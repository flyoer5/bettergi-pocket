# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ===== OpenCV Java 绑定：native 方法通过 JNI 按类名/方法名查找，必须完整保留 =====
-keep class org.opencv.** { *; }
-keep class org.opencv.android.** { *; }
-keepclassmembers class org.opencv.** { *; }

# ===== ML Kit OCR：模型加载与管线依赖类名反射，保留 =====
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }

# ===== 保留异常栈行号，便于日志定位 =====
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===== app 自身类保留类名：R8 混淆后异常栈可读 =====
-keep class com.bettergi.pocket.** { *; }

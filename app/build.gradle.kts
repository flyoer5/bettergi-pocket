plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.bettergi.pocket"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bettergi.pocket"
        minSdk = 24
        targetSdk = 36
        // root 分支独立 versionCode，避免与 main 分支发布互相覆盖
        versionCode = 2001
        versionName = "1.1-root"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }


    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            // libc++_shared 无任何 so 依赖（ML Kit/裁剪 OpenCV 均用系统库），排除省 ~1MB
            excludes += "lib/arm64-v8a/libc++_shared.so"
        }
    }

    signingConfigs {
        create("fixed") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("fixed")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.opencv)
    implementation(libs.mlkit.text.recognition.chinese)
    testImplementation(libs.junit)
    // 单测用 openpnp 桌面版（OpenCV 4.9，仅 JVM 单测加载；运行时用 org.opencv 4.12 AAR，API 兼容）
    testImplementation(libs.opencv.desktop)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

configurations.matching {
    val n = name.lowercase()
    n.contains("test") && n.contains("classpath") && !n.contains("androidtest")
}.configureEach {
    exclude(group = "org.opencv", module = "opencv")
}

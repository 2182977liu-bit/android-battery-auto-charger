plugins {
    id("com.android.application")
}

android {
    namespace = "com.autophonez.batterymonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autophonez.batterymonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    // 固定 debug 签名：仓库内自带的 keystore，保证每次 CI 产出的 APK
    // 签名一致，可以直接 adb install -r 覆盖安装，不会出现
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    // AGP 8 起 buildConfig 默认关闭，自动更新要读 BuildConfig.VERSION_NAME，
    // 不开这个开关会直接编译失败。
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

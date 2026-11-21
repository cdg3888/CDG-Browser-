plugins {
    id("com.android.application")
}

android {
    namespace = "com.cdg.browser"
    compileSdk = 37
    compileSdkMinor = 2

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.zip.so"
        }
    }

    defaultConfig {
        applicationId = "com.cdg.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":downloads"))
    implementation(project(":ytdlp"))
    implementation("org.mozilla.geckoview:geckoview:160.0.20261116130455")
}

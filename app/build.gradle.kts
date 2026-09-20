plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.acsmanager.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acsmanager.pro"
        // 适配 Android 5.0（API 21）~ 最新系统：最低 API 21，所有新 API 调用均带版本守卫
        minSdk = 21
        targetSdk = 34
        versionCode = 39
        versionName = "3.1.8"
        // 资源瘦身：仅保留中/英文，其余语言资源不打包
        resConfigs("zh", "en")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "acsmanager2026"
            keyAlias = "acsmanager"
            keyPassword = "acsmanager2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        // 兼容性优先：release 构建不被 lint 阻断（代码中已对低版本 API 做运行时守卫）
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
}

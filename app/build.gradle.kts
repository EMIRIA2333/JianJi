plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jianji.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.jianji.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 63
        versionName = "3.16.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    }
}

/**
 * 可选：把构建目录切到别处，例如 `gradlew assembleDebug -Pjianji.buildDir=build-alt`。
 * 用途：默认 `app/build/outputs/.../app-debug.apk` 偶尔会被其它进程占用（预览/杀软扫描），
 * 导致打包时无法删除旧包，这时换个目录即可正常出包。
 */
providers.gradleProperty("jianji.buildDir").orNull?.takeIf { it.isNotBlank() }?.let {
    layout.buildDirectory.set(file(it))
}

/** 打包后复制一份带版本号的产物到 `dist/`，方便分发与区分版本 */
tasks.register<Copy>("distApk") {
    group = "build"
    description = "把 debug APK 复制为 dist/jianji-<版本号>.apk"
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "jianji-${android.defaultConfig.versionName}.apk" }
}

/**
 * 产物文件名带版本号（如 jianji-1.7.2-debug.apk）：
 * 便于区分版本，也避免与正在被系统/其它进程占用的旧包冲突导致打包失败。
 */


dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
    // Xposed/LSPosed API：仅编译期需要（运行时由框架提供），不会打进 APK
    compileOnly(project(":xposed-stubs"))
}

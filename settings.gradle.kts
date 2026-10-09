pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "JianJi"
include(":app")
// Xposed API 编译期桩（compileOnly，仅用于编译 hook 代码，不打包进 APK）
include(":xposed-stubs")

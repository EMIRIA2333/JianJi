// Xposed / LSPosed API 的**编译期桩**（compileOnly，不会打进 APK）。
//
// 为什么要自己写桩：真机上的 Xposed 框架会在运行时提供这些类，
// 编译时只需要「同名同签名」即可通过；自己写桩就不用依赖网络上的 Xposed 仓库，
// 也避免把框架类打进 APK 造成类冲突。
//
// 只声明本项目真正用到的少量成员，字段与方法签名与 Xposed API 82+ 保持一致。
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

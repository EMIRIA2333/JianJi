package de.robv.android.xposed;

/**
 * Xposed API 桩：模块入口（每个应用进程加载时回调）。
 * 真机上由 Xposed/LSPosed 框架提供实现。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam)
            throws Throwable;
}

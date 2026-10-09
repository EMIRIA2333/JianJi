package io.github.libxposed.api;

/**
 * libxposed API 桩（新版 LSPosed 2.x 使用的模块 API），编译期用，不打包进 APK。
 * 真机上由框架提供同名同签名的实现。
 */
public abstract class XposedModule {

    public XposedModule() {
    }

    public void onModuleLoaded(ModuleLoadedParam param) {
    }

    public void onSystemServerStarting(SystemServerStartingParam param) {
    }

    public void onPackageLoaded(PackageLoadedParam param) {
    }

    public void onPackageReady(PackageReadyParam param) {
    }

    public void onPackageUnloaded(PackageUnloadedParam param) {
    }

    protected final HookHandle hook(java.lang.reflect.Executable origin, Hooker hooker) {
        return null;
    }

    protected final void log(String message) {
    }

    protected final void log(Throwable throwable) {
    }

    protected final void log(int priority, String tag, String message) {
    }
}

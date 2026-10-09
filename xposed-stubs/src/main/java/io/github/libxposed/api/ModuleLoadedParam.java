package io.github.libxposed.api;

/** libxposed API 桩：各类生命周期回调参数。 */
public interface ModuleLoadedParam {
    boolean isSystemServer();

    String getProcessName();
}

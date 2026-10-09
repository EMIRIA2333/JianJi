package io.github.libxposed.api;

/** libxposed API 桩：应用进程加载参数。 */
public interface PackageLoadedParam {
    String getPackageName();

    ClassLoader getClassLoader();

    boolean isFirstPackage();

    boolean isSystemServer();
}

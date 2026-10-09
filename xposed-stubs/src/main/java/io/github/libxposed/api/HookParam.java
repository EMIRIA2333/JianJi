package io.github.libxposed.api;

/**
 * libxposed API 桩：钩子参数。
 * 本项目只通过反射读取 `getArgs()`，因此这里只需保证类型存在。
 */
public abstract class HookParam {

    public abstract Object[] getArgs();
}

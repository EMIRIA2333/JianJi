package io.github.libxposed.api;

/** libxposed API 桩：方法钩子接口。 */
public interface Hooker {

    void before(HookParam param) throws Throwable;

    default void after(HookParam param) throws Throwable {
    }
}

package de.robv.android.xposed;

/**
 * Xposed API 桩：模块入口（Zygote 启动时回调，本项目暂不使用，仅声明以便扩展）。
 */
public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam) throws Throwable;

    class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}

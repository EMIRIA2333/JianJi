package de.robv.android.xposed.callbacks;

/** Xposed API 桩：加载包回调参数。 */
public abstract class XC_LoadPackage {

    public static class LoadPackageParam {
        /** 当前进程的包名 */
        public String packageName;
        /** 当前进程的 ClassLoader（hook 目标类必须用它加载） */
        public ClassLoader classLoader;
        /** 是否是该应用的第一个进程 */
        public boolean isFirstApplication;
        public Object appInfo;
        public String processName;
    }
}

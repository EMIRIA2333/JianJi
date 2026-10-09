package de.robv.android.xposed;

/** Xposed API 桩：日志与按方法对象挂钩子。 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable throwable) {
    }

    /** 直接对 Method/Constructor 挂钩子（比按签名查找更稳，不依赖参数形态） */
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) {
        return null;
    }
}

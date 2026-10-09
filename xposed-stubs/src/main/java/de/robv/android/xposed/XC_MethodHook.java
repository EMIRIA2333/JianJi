package de.robv.android.xposed;

/** Xposed API 桩：方法钩子基类。 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        /** 原方法参数（args[args.length - 1] 通常是 Notification） */
        public Object[] args;
        public Object thisObject;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }
    }

    /** 取消钩子（本项目不使用，仅保证签名完整） */
    public class Unhook {
        public void unhook() {
        }
    }
}

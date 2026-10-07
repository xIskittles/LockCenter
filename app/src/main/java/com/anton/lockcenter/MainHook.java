package com.anton.lockcenter;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.concurrent.atomic.AtomicInteger;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[LockCenter-Scout] ";
    private static final AtomicInteger logCount = new AtomicInteger(0);
    private static final int MAX_LOGS = 2500;
    private static long lastDumpTime = 0;

    private static void log(String msg) {
        if (logCount.incrementAndGet() <= MAX_LOGS) {
            XposedBridge.log(TAG + msg);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        log("SystemUI Android 16 hooked! Настройка слежения за KeyguardRootView...");
        ClassLoader cl = lpparam.classLoader;

        // Хукаем непосредственно KeyguardRootView — центр всей разметки на Android 16
        try {
            Class<?> rootClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);
            if (rootClass != null) {
                XposedBridge.hookAllMethods(rootClass, "onLayout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        long now = System.currentTimeMillis();
                        // Дампим не чаще 1 раза в 2 секунды, чтобы не забивать память
                        if (now - lastDumpTime > 2000) {
                            lastDumpTime = now;
                            View root = (View) param.thisObject;
                            log(">>> KEYGUARD LAYOUT DUMP (w=" + root.getWidth() + " h=" + root.getHeight() + ") <<<");
                            dumpSafe(root, 0);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            log("Ошибка хука KeyguardRootView: " + t);
        }

        // Отслеживание FlexClockViewGroup
        try {
            Class<?> flexClockClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.clocks.view.FlexClockViewGroup", cl);
            if (flexClockClass != null) {
                XposedBridge.hookAllMethods(flexClockClass, "onLayout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        log("FlexClock layout: pos=[" + v.getLeft() + "," + v.getTop() + ".." + v.getRight() + "," + v.getBottom() + "] vis=" + v.getVisibility());
                    }
                });
            }
        } catch (Throwable t) {
            log("Ошибка хука FlexClock: " + t);
        }
    }

    private static void dumpSafe(View v, int depth) {
        if (v == null || logCount.get() >= MAX_LOGS || depth > 8) return;

        String cls = v.getClass().getSimpleName();
        String idName = getResourceEntryName(v);
        int vis = v.getVisibility();
        if (vis != View.VISIBLE) return; // Нас интересуют только видимые элементы

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(cls).append(" [").append(idName).append("] ")
          .append("xy=(").append(v.getLeft()).append(",").append(v.getTop()).append(") ")
          .append("wh=(").append(v.getWidth()).append("x").append(v.getHeight()).append(")");

        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            if (text != null && text.length() > 0) {
                sb.append(" text='").append(text).append("'");
            }
        }
        log(sb.toString());

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                dumpSafe(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private static String getResourceEntryName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "-";
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "0x" + Integer.toHexString(id);
        }
    }
}

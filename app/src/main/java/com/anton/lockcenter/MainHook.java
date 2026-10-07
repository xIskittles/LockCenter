package com.anton.lockcenter;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SCOUT build: moves nothing. Every 3 seconds it checks what is visible
 * inside SystemUI windows and writes the structure to the LSPosed log
 * (only when it changed). No notification text is logged, only text length.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final List<WeakReference<View>> ROOTS = new ArrayList<>();
    private static String last = "";
    private static int logged = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        XposedBridge.log("[LockCenter] scout started");

        try {
            Class<?> vri = XposedHelpers.findClass("android.view.ViewRootImpl", lpparam.classLoader);
            XposedBridge.hookAllMethods(vri, "setView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof View) {
                        ROOTS.add(new WeakReference<>((View) param.args[0]));
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dump();
                handler.postDelayed(this, 3000);
            }
        }, 10000);
    }

    private static void dump() {
        try {
            if (logged > 4000) return;
            StringBuilder sb = new StringBuilder();
            for (WeakReference<View> ref : ROOTS) {
                View v = ref.get();
                if (v != null && v.isAttachedToWindow()) {
                    sb.append("ROOT ").append(v.getClass().getName()).append('\n');
                    walk(v, 0, sb);
                }
            }
            String now = sb.toString();
            if (now.equals(last)) return;
            last = now;
            String[] lines = now.split("\n");
            XposedBridge.log("[LockCenter] ===== DUMP " + System.currentTimeMillis() + " =====");
            for (String s : lines) {
                XposedBridge.log("[LockCenter] " + s);
            }
            logged += lines.length;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void walk(View v, int depth, StringBuilder sb) {
        if (depth > 40 || v.getVisibility() != View.VISIBLE
                || v.getWidth() == 0 || v.getHeight() == 0) return;

        String id = "-";
        try {
            if (v.getId() != View.NO_ID) {
                id = v.getResources().getResourceEntryName(v.getId());
            }
        } catch (Throwable ignored) {
        }

        int[] xy = new int[2];
        v.getLocationOnScreen(xy);

        sb.append(depth).append(' ')
                .append(v.getClass().getName())
                .append(" id=").append(id)
                .append(" x=").append(xy[0])
                .append(" y=").append(xy[1])
                .append(" w=").append(v.getWidth())
                .append(" h=").append(v.getHeight());
        if (v instanceof TextView) {
            sb.append(" textLen=").append(((TextView) v).getText().length());
        }
        sb.append('\n');

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, sb);
            }
        }
    }
}

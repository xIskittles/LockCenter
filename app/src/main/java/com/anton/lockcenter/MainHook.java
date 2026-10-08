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
 * SCOUT v2: moves NOTHING. Every 3 seconds it looks at everything visible in
 * SystemUI windows and writes the structure to the LSPosed log, but only when
 * the structure changed. In addition to v1 it logs, for every view:
 * layout params (size, gravity) and, inside ConstraintLayout, the exact
 * constraints (what each view is attached to). No notification text is
 * logged, only text length.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String[] CONSTRAINT_FIELDS = {
            "leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
            "topToTop", "topToBottom", "bottomToTop", "bottomToBottom",
            "baselineToBaseline",
            "startToEnd", "startToStart", "endToStart", "endToEnd"
    };

    private static final List<WeakReference<View>> ROOTS = new ArrayList<>();
    private static String lastKey = "";
    private static int logged = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        XposedBridge.log("[LockCenter] scout v2 started");

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
            if (logged > 9000) return;
            StringBuilder full = new StringBuilder();
            StringBuilder key = new StringBuilder();
            for (WeakReference<View> ref : ROOTS) {
                View v = ref.get();
                if (v != null && v.isAttachedToWindow()) {
                    String head = "ROOT " + v.getClass().getName() + "\n";
                    full.append(head);
                    key.append(head);
                    walk(v, 0, full, key);
                }
            }
            String k = key.toString();
            if (k.equals(lastKey)) return;
            lastKey = k;
            String[] lines = full.toString().split("\n");
            XposedBridge.log("[LockCenter] ===== DUMP " + System.currentTimeMillis() + " =====");
            for (String s : lines) {
                XposedBridge.log("[LockCenter] " + s);
            }
            logged += lines.length;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static String idName(View v, int id) {
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "#" + id;
        }
    }

    private static void walk(View v, int depth, StringBuilder full, StringBuilder key) {
        if (depth > 40 || v.getVisibility() != View.VISIBLE
                || v.getWidth() == 0 || v.getHeight() == 0) return;

        StringBuilder k = new StringBuilder();
        k.append(depth).append(' ').append(v.getClass().getName());
        k.append(" id=").append(v.getId() != View.NO_ID ? idName(v, v.getId()) : "-");
        k.append(" w=").append(v.getWidth()).append(" h=").append(v.getHeight());
        if (v.getAlpha() != 1f) k.append(" alpha=").append(v.getAlpha());
        if (v instanceof TextView) k.append(" textLen=").append(((TextView) v).getText().length());
        appendParams(v, k);

        int[] xy = new int[2];
        v.getLocationOnScreen(xy);

        key.append(k).append('\n');
        full.append(k)
                .append(" | x=").append(xy[0])
                .append(" y=").append(xy[1])
                .append(" tx=").append(v.getTranslationX())
                .append('\n');

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, full, key);
            }
        }
    }

    private static void appendParams(View v, StringBuilder sb) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        Class<?> c = lp.getClass();
        sb.append(" lp=").append(c.getName().replace("androidx.constraintlayout.widget.", "CL."));
        sb.append(" size=").append(lp.width).append('x').append(lp.height);

        if (c.getName().contains("ConstraintLayout")) {
            for (String f : CONSTRAINT_FIELDS) {
                try {
                    int val = c.getField(f).getInt(lp);
                    if (val != -1) {
                        sb.append(' ').append(f).append("->")
                                .append(val == 0 ? "PARENT" : idName(v, val));
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                sb.append(" hBias=").append(c.getField("horizontalBias").getFloat(lp));
            } catch (Throwable ignored) {
            }
        } else {
            try {
                sb.append(" gravity=").append(c.getField("gravity").getInt(lp));
            } catch (Throwable ignored) {
            }
        }
    }
}

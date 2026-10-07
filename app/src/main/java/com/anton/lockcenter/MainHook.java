package com.anton.lockcenter;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[LockCenter] ";

    // Константы ConstraintSet из AndroidX (чтобы не тянуть библиотеку)
    private static final int PARENT_ID = 0;
    private static final int START = 6;
    private static final int END = 7;
    private static final int TOP = 3;
    private static final int BOTTOM = 4;

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        log("Запуск центрирования на Android 16 (через рефлексию)...");
        ClassLoader cl = lpparam.classLoader;

        // 1. Хук на применение ConstraintSet к KeyguardRootView
        try {
            Class<?> constraintSetClass = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintSet", cl);
            Class<?> keyguardRootViewClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);

            if (constraintSetClass != null && keyguardRootViewClass != null) {
                XposedBridge.hookAllMethods(constraintSetClass, "applyTo", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] != null &&
                            keyguardRootViewClass.isInstance(param.args[0])) {
                            
                            Object constraintSet = param.thisObject;
                            View rootView = (View) param.args[0];
                            modifyConstraints(constraintSet, rootView);
                        }
                    }
                });
                log("Хук ConstraintSet.applyTo успешно установлен!");
            }
        } catch (Throwable t) {
            log("Ошибка хука ConstraintSet: " + t);
        }

        // 2. Хук на изменение положения View (часы, дата, иконки) для надежности
        try {
            Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
            XposedBridge.hookAllMethods(viewClass, "onSizeChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    alignCenter(v);
                }
            });
        } catch (Throwable t) {
            log("Ошибка хука View: " + t);
        }
    }

    private static void modifyConstraints(Object cs, View root) {
        Context context = root.getContext();
        Resources res = context.getResources();

        String[] targetIds = {
            "lockscreen_clock_view",
            "small_clock_view",
            "bc_smartspace_view",
            "date_smartspace_view",
            "weather_smartspace_view",
            "aod_notification_icon_container",
            "notification_shelf"
        };

        for (String idName : targetIds) {
            int id = res.getIdentifier(idName, "id", "com.android.systemui");
            if (id > 0) {
                try {
                    // connect(int startID, int startSide, int endID, int endSide)
                    XposedHelpers.callMethod(cs, "connect", id, START, PARENT_ID, START);
                    XposedHelpers.callMethod(cs, "connect", id, END, PARENT_ID, END);
                    // setHorizontalBias(int targetID, float bias) -> 0.5f = ровно центр
                    XposedHelpers.callMethod(cs, "setHorizontalBias", id, 0.5f);
                } catch (Throwable ignored) {}
            }
        }

        // Ставим дату (smartspace) строго под часы
        int clockId = res.getIdentifier("lockscreen_clock_view", "id", "com.android.systemui");
        int smartspaceId = res.getIdentifier("bc_smartspace_view", "id", "com.android.systemui");
        if (clockId > 0 && smartspaceId > 0) {
            try {
                XposedHelpers.callMethod(cs, "connect", smartspaceId, TOP, clockId, BOTTOM);
                XposedHelpers.callMethod(cs, "connect", smartspaceId, START, PARENT_ID, START);
                XposedHelpers.callMethod(cs, "connect", smartspaceId, END, PARENT_ID, END);
                XposedHelpers.callMethod(cs, "setHorizontalBias", smartspaceId, 0.5f);
            } catch (Throwable ignored) {}
        }
    }

    private static void alignCenter(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;

        String cls = view.getClass().getName();
        boolean isClock = cls.contains("FlexClockViewGroup");
        boolean isSmartspace = cls.contains("BcSmartspaceView");
        boolean isIconShelf = cls.contains("NotificationIconContainer") || cls.contains("NotificationShelf");

        if (!isClock && !isSmartspace && !isIconShelf) return;

        View parent = (View) view.getParent();
        if (parent == null || !parent.getClass().getName().contains("KeyguardRootView")) {
            return;
        }

        int screenWidth = view.getResources().getDisplayMetrics().widthPixels;
        int viewWidth = view.getWidth();

        if (viewWidth > 0 && viewWidth < screenWidth - 100) {
            view.post(() -> {
                int currentLeft = view.getLeft();
                int targetLeft = (screenWidth - view.getWidth()) / 2;
                int deltaX = targetLeft - currentLeft;
                if (Math.abs(view.getTranslationX() - deltaX) > 2) {
                    view.setTranslationX(deltaX);
                }
            });
        }
    }
}

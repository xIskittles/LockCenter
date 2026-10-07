package com.anton.lockcenter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[LockCenter] ";

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        log("Инициализация центрирования Lockscreen для Android 16...");
        ClassLoader cl = lpparam.classLoader;

        // 1. Хук на применение ConstraintSet к KeyguardRootView (главный движок разметки)
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
                            
                            ConstraintSet cs = (ConstraintSet) param.thisObject;
                            ConstraintLayout root = (ConstraintLayout) param.args[0];
                            applyCenterConstraints(cs, root);
                        }
                    }
                });
                log("Хук ConstraintSet.applyTo успешно установлен!");
            }
        } catch (Throwable t) {
            log("Ошибка хука ConstraintSet: " + t);
        }

        // 2. Дополнительный хук на изменение положения самих View (FlexClock и иконки)
        try {
            Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
            XposedBridge.hookAllMethods(viewClass, "onSizeChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    enforceCenterTranslation(v);
                }
            });
        } catch (Throwable t) {
            log("Ошибка хука onSizeChanged: " + t);
        }
    }

    private static void applyCenterConstraints(ConstraintSet cs, ConstraintLayout root) {
        Context context = root.getContext();
        int parentId = ConstraintSet.PARENT_ID;

        // Идентификаторы элементов на экране блокировки Pixel Android 15/16
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
            int id = context.getResources().getIdentifier(idName, "id", "com.android.systemui");
            if (id > 0) {
                try {
                    // Центрируем элемент по горизонтали между краями экрана
                    cs.connect(id, ConstraintSet.START, parentId, ConstraintSet.START);
                    cs.connect(id, ConstraintSet.END, parentId, ConstraintSet.END);
                    cs.setHorizontalBias(id, 0.5f);
                } catch (Throwable ignored) {}
            }
        }

        // Если есть виджет даты (smartspace), отвязываем его от правого края часов и ставим СТРОГО ПОД ЧАСАМИ
        int clockId = context.getResources().getIdentifier("lockscreen_clock_view", "id", "com.android.systemui");
        int smartspaceId = context.getResources().getIdentifier("bc_smartspace_view", "id", "com.android.systemui");
        if (clockId > 0 && smartspaceId > 0) {
            try {
                cs.connect(smartspaceId, ConstraintSet.TOP, clockId, ConstraintSet.BOTTOM);
                cs.connect(smartspaceId, ConstraintSet.START, parentId, ConstraintSet.START);
                cs.connect(smartspaceId, ConstraintSet.END, parentId, ConstraintSet.END);
                cs.setHorizontalBias(smartspaceId, 0.5f);
            } catch (Throwable ignored) {}
        }
    }

    private static void enforceCenterTranslation(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;

        String cls = view.getClass().getName();
        boolean isClock = cls.contains("FlexClockViewGroup");
        boolean isSmartspace = cls.contains("BcSmartspaceView");
        boolean isIconShelf = cls.contains("NotificationIconContainer") || cls.contains("NotificationShelf");

        if (!isClock && !isSmartspace && !isIconShelf) return;

        // Проверяем, что View действительно лежит на экране блокировки
        View parent = (View) view.getParent();
        if (parent == null || !parent.getClass().getName().contains("KeyguardRootView")) {
            return;
        }

        int screenWidth = view.getResources().getDisplayMetrics().widthPixels;
        int viewWidth = view.getWidth();

        // Если элемент не на всю ширину (то есть в компактном режиме сбоку)
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

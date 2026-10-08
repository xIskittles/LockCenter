package com.anton.lockcenter;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[LockCenter] ";

    // Константы androidx.constraintlayout.widget.ConstraintSet
    private static final int PARENT_ID = 0;
    private static final int TOP = 3;
    private static final int BOTTOM = 4;
    private static final int START = 6;
    private static final int END = 7;

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        log("LockCenter v3: перехват разметки часов, даты и уведомлений...");
        ClassLoader cl = lpparam.classLoader;

        // 1. Хукаем ConstraintSet.applyTo в KeyguardRootView
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

                            Object cs = param.thisObject;
                            View root = (View) param.args[0];
                            rebuildKeyguardConstraints(cs, root);
                        }
                    }
                });
                log("Хук ConstraintSet.applyTo активен.");
            }
        } catch (Throwable t) {
            log("Ошибка ConstraintSet: " + t);
        }

        // 2. Блокируем сдвиг часов влево на уровне FlexClockViewGroup
        try {
            Class<?> flexClockClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.clocks.view.FlexClockViewGroup", cl);
            if (flexClockClass != null) {
                XposedBridge.hookAllMethods(flexClockClass, "onMeasure", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        applyCenterPosition(v);
                    }
                });
            }
        } catch (Throwable ignored) {}

        // 3. Отслеживание перемещения полки иконок уведомлений и Smartspace
        try {
            Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
            XposedBridge.hookAllMethods(viewClass, "layout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    String cls = v.getClass().getName();
                    if (cls.contains("BcSmartspaceView") || 
                        cls.contains("FlexClockViewGroup") || 
                        cls.contains("NotificationIconContainer") ||
                        cls.contains("NotificationShelf")) {
                        applyCenterPosition(v);
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void rebuildKeyguardConstraints(Object cs, View root) {
        Context context = root.getContext();
        Resources res = context.getResources();

        int smallClockId = getResId(res, "small_clock_view");
        int lockscreenClockId = getResId(res, "lockscreen_clock_view");
        int clockId = smallClockId > 0 ? smallClockId : lockscreenClockId;

        int smartspaceId = getResId(res, "bc_smartspace_view");
        if (smartspaceId == 0) smartspaceId = getResId(res, "date_smartspace_view");

        int aodIconsId = getResId(res, "aod_notification_icon_container");
        int shelfId = getResId(res, "notification_shelf");

        // --- 1. ЦЕНТРИРУЕМ ЧАСЫ ---
        if (clockId > 0) {
            try {
                clearHorizontal(cs, clockId);
                // Привязываем строго между левым и правым краем экрана
                XposedHelpers.callMethod(cs, "connect", clockId, START, PARENT_ID, START);
                XposedHelpers.callMethod(cs, "connect", clockId, END, PARENT_ID, END);
                XposedHelpers.callMethod(cs, "setHorizontalBias", clockId, 0.5f);
            } catch (Throwable t) {
                log("Ошибка центрирования часов: " + t);
            }
        }

        // --- 2. ЦЕНТРИРУЕМ ДАТУ (SMARTSPACE) СТРОГО ПОД ЧАСАМИ ---
        if (smartspaceId > 0) {
            try {
                clearHorizontal(cs, smartspaceId);
                // Отвязываем дату от правого края часов и центрируем
                XposedHelpers.callMethod(cs, "connect", smartspaceId, START, PARENT_ID, START);
                XposedHelpers.callMethod(cs, "connect", smartspaceId, END, PARENT_ID, END);
                XposedHelpers.callMethod(cs, "setHorizontalBias", smartspaceId, 0.5f);

                // Если есть часы — ставим дату ПОД ними
                if (clockId > 0) {
                    XposedHelpers.callMethod(cs, "connect", smartspaceId, TOP, clockId, BOTTOM);
                }
            } catch (Throwable t) {
                log("Ошибка центрирования даты: " + t);
            }
        }

        // --- 3. ЦЕНТРИРУЕМ СВЕРНУТЫЕ ИКОНКИ УВЕДОМЛЕНИЙ ---
        int[] iconShelfIds = {aodIconsId, shelfId};
        for (int iconId : iconShelfIds) {
            if (iconId > 0) {
                try {
                    clearHorizontal(cs, iconId);
                    XposedHelpers.callMethod(cs, "connect", iconId, START, PARENT_ID, START);
                    XposedHelpers.callMethod(cs, "connect", iconId, END, PARENT_ID, END);
                    XposedHelpers.callMethod(cs, "setHorizontalBias", iconId, 0.5f);

                    // Ставим иконки ПОД датой
                    if (smartspaceId > 0) {
                        XposedHelpers.callMethod(cs, "connect", iconId, TOP, smartspaceId, BOTTOM);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void clearHorizontal(Object cs, int viewId) {
        try {
            XposedHelpers.callMethod(cs, "clear", viewId, START);
            XposedHelpers.callMethod(cs, "clear", viewId, END);
        } catch (Throwable ignored) {}
    }

    private static void applyCenterPosition(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;

        // Проверяем, что вьюшка на Keyguard
        View parent = (View) view.getParent();
        if (parent == null) return;
        String pName = parent.getClass().getName();
        if (!pName.contains("KeyguardRootView") && !pName.contains("NotificationShadeWindowView")) {
            return;
        }

        int screenWidth = view.getResources().getDisplayMetrics().widthPixels;
        int viewWidth = view.getWidth();

        // Не трогаем полноэкранные карточки раскрытых уведомлений
        if (viewWidth <= 0 || viewWidth >= screenWidth - 80) {
            return;
        }

        view.post(() -> {
            int currentLeft = view.getLeft();
            int targetLeft = (screenWidth - view.getWidth()) / 2;
            int deltaX = targetLeft - currentLeft;

            if (Math.abs(view.getTranslationX() - deltaX) > 1) {
                view.setTranslationX(deltaX);
            }
        });
    }

    private static int getResId(Resources res, String name) {
        try {
            return res.getIdentifier(name, "id", "com.android.systemui");
        } catch (Exception e) {
            return 0;
        }
    }
}
